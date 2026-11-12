package io.embodit.tangramwood.ui.board

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.tangramwood.R
import io.embodit.tangramwood.domain.GameFlow
import io.embodit.tangramwood.domain.Kind
import io.embodit.tangramwood.domain.Layout
import io.embodit.tangramwood.domain.MotionKind
import io.embodit.tangramwood.domain.Notice
import io.embodit.tangramwood.domain.Phase
import io.embodit.tangramwood.domain.PieceState
import io.embodit.tangramwood.domain.Pieces
import io.embodit.tangramwood.domain.PlayState
import io.embodit.tangramwood.domain.Poly
import io.embodit.tangramwood.domain.Settings
import io.embodit.tangramwood.domain.V2
import io.embodit.tangramwood.domain.V3
import io.embodit.tangramwood.platform.TAG
import io.embodit.tangramwood.ui.board.input.GazeTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Where a piece is drawn: scene position (its centre) and orientation. Pure, so the D35 tests use the same math. */
object PiecePoses {
    private val TILT = Q4.rotX(Layout.THETA)

    fun orientation(p: PieceState): Q4 {
        val turn = Q4.rotY((Math.floorMod(p.pose.rot8, 8) * PI / 4).toFloat())
        val flip = if (p.pose.flipped) Q4.rotZ(PI.toFloat()) else Q4.ID
        return TILT * turn * flip // tray and board both lean back THETA (v0.1.1)
    }

    fun normal(p: PieceState): V3 = if (p.onBoard) Layout.BOARD_NORMAL else Layout.TRAY_NORMAL

    /** Centre of piece [i] resting where [p] says, lifted by [lift] along the surface normal. */
    fun position(i: Int, p: PieceState, figure: io.embodit.tangramwood.domain.Figure, lift: Float = 0f): V3 =
        if (p.onBoard) Layout.boardToScene(Layout.puzzleToBoard(p.pose.centre, figure), Layout.T / 2 + lift - if (p.snapped) Layout.SINK else 0f)
        else Layout.homeScene(i, lift)
}

/**
 * Every entity of the tangram volume. Compose hands over [PlayState] + [Settings] through [apply]; everything that moves
 * is placed in [onFrame] (driven by BoardFrameSystem), never through Compose (spatial-sdk-guideline).
 *
 * ```
 * root (volume centre; +X right, +Y up, +Z toward the user)
 * ├ tray "tray" (holder: tap target + system hover) → tray base, four rims
 * ├ wedge, tiltRoot (the board's near-bottom edge, +25° about X)
 * │ └ board slab, boardRoot (board top centre): slot shadows, figure outline, seams (hint), slot rings,
 * │    25 pads "pad:i:j" (8.2 cm tap targets, a 5 mm marker each), the contact shadow (solved)
 * ├ 7 pieces "piece:i" (holder: convex collider + Interactable + HoverEffect) → maple top half, cherry bottom half, edge ring
 * ├ 3 tabs "tab:k" (holder: box collider) → wooden body, face (icon + word)
 * ├ title plate (back + face), key light
 * ```
 */
internal class BoardScene(private val context: Context, private val scope: CoroutineScope) {
    val root: Entity = Entity().apply { setName("root") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boardRoot = Entity().apply { setName("boardRoot") }

    private var res: SceneResources? = null
    private var built = false
    private var state = PlayState()
    private var settings = Settings()
    private var panelOpen = true
    private var plateText = ""
    private var plateLine2: String? = null
    private var tabLabels: List<String> = listOf("", "", "")
    private var orientationTest = false

    private val pieces = ArrayList<Entity>()
    private val pieceEdges = ArrayList<ModelEntity>()
    private val pieceTops = ArrayList<ModelEntity>()
    private val pieceColliderOn = BooleanArray(Pieces.COUNT) { true }
    private val tabs = ArrayList<Entity>()
    private val tabFaces = ArrayList<ModelEntity>()
    private val tabMats = ArrayList<UnlitMaterial>()
    private var tabTex: List<TextureResource?> = listOf(null, null, null)
    private var tabsShownFor: String? = null
    private var tabsBusy = false
    private var plateMat: UnlitMaterial? = null
    private var plateTex: TextureResource? = null
    private var plateShown: String? = null
    private var plateBusy = false
    private var figureMeshes: FigureMeshes? = null
    private var figureBusy = false
    private val figureEntities = ArrayList<Entity>()
    private val slotEntities = ArrayList<ModelEntity>()
    private val ringEntities = ArrayList<ModelEntity>()
    private var seamEntity: ModelEntity? = null
    private var contact: ModelEntity? = null
    private var tintedFor: Long = -1
    /** Rendered piece centres this frame (the tabs follow the drawn piece). */
    private val drawn = Array(Pieces.COUNT) { V3.ZERO }

    @Volatile private var hover: String? = null
    @Volatile private var hoverAt = 0L

    /** D50 hover from the board-input template: a hint only, never a state change. */
    fun setHover(name: String?) {
        hoverAt = SystemClock.uptimeMillis()
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
    }

    init {
        root.addChild(tiltRoot)
        tiltRoot.addChild(boardRoot)
        place(tiltRoot, Layout.PIVOT)
        tiltRoot.transform().setQuaternion(Q4.rotX(Layout.THETA).sdk())
        place(boardRoot, V3(0f, Layout.BOARD_T, -Layout.BOARD / 2))
    }

    val ready: Boolean get() = built

    private fun place(e: Entity, v: V3) = e.transform().setPosition(Vector3(v.x, v.y, v.z))

    fun install(r: SceneResources) {
        if (built) { r.close(); return }
        res = r
        val t0 = SystemClock.elapsedRealtime()
        // ---- tray: the holder is the tap target (top of the tray floor); its meshes hang below it
        val tray = Entity().apply { setName(BoardNames.TRAY) }
        place(tray, Layout.TRAY_CENTRE + Layout.TRAY_NORMAL * 0.003f)
        tray.transform().setQuaternion(Q4.rotX(Layout.THETA).sdk())
        GazeTargets.install(tray, r.trayShape, r.physics)
        tray.addChild(ModelEntity(r.trayBase, r.trayWood).apply { setName("trayBase"); place(this, V3(0f, -Layout.TRAY_BASE - 0.003f, 0f)) })
        val rimY = -0.003f
        val hx = Layout.TRAY_OUTER_W / 2 - Layout.TRAY_WALL / 2
        val hz = Layout.TRAY_OUTER_D / 2 - Layout.TRAY_WALL / 2
        tray.addChild(ModelEntity(r.trayRimX, r.trayWood).apply { setName("rimL"); place(this, V3(-hx, rimY, 0f)) })
        tray.addChild(ModelEntity(r.trayRimX, r.trayWood).apply { setName("rimR"); place(this, V3(hx, rimY, 0f)) })
        tray.addChild(ModelEntity(r.trayRimZ, r.trayWood).apply { setName("rimF"); place(this, V3(0f, rimY, hz)) })
        tray.addChild(ModelEntity(r.trayRimZ, r.trayWood).apply { setName("rimB"); place(this, V3(0f, rimY, -hz)) })
        root.addChild(tray)
        // ---- wedge + board
        // plinth both columns stand on, and an easel-back wedge behind each slanted surface
        root.addChild(ModelEntity(r.plinth, r.wedgeWood).apply { setName("plinth"); place(this, V3(0f, -Layout.HALF.y, Layout.PIVOT.z - r.plinthDepth / 2)) })
        root.addChild(ModelEntity(r.wedge, r.wedgeWood).apply { setName("boardWedge"); place(this, V3(Layout.BOARD_X, Layout.PLINTH_TOP, Layout.PIVOT.z)) })
        root.addChild(ModelEntity(r.trayWedge, r.wedgeWood).apply { setName("trayWedge"); place(this, V3(Layout.TRAY_X, Layout.PLINTH_TOP, Layout.TRAY_PIVOT.z)) })
        tiltRoot.addChild(ModelEntity(r.boardSlab, r.walnut).apply { setName("boardSlab"); place(this, V3(0f, 0f, -Layout.BOARD / 2)) })
        GazeTargets.cells(
            boardRoot,
            Layout.PADS.associate { (i, j) -> BoardNames.pad(i, j) to Layout.padCentreM(i, j).let { Vector3(it.x, 0f, -it.y) } },
            r.padShape, r.physics, r.markerParts, r.marker,
        )
        contact = ModelEntity(r.shadowPlane, r.contact).apply {
            setName("contactShadow"); enabled = false
            transform().setQuaternion(Q4.rotX((-PI / 2).toFloat()).sdk())
            boardRoot.addChild(this)
        }
        // ---- pieces
        for (i in 0 until Pieces.COUNT) {
            val kind = Pieces.kind(i)
            val holder = Entity().apply { setName(BoardNames.piece(i)) }
            GazeTargets.install(holder, r.pieceShape.getValue(kind), r.physics)
            val top = ModelEntity(r.pieceTop.getValue(kind), r.maple[i]).apply { setName("pieceTop:$i") }
            holder.addChild(top)
            holder.addChild(ModelEntity(r.pieceBottom.getValue(kind), r.cherry).apply { setName("pieceBottom:$i") })
            val edge = ModelEntity(r.pieceEdge.getValue(kind), r.doneEdge).apply { setName("edge:$i"); enabled = false }
            holder.addChild(edge)
            root.addChild(holder)
            pieces += holder; pieceTops += top; pieceEdges += edge
        }
        // ---- tabs (upright, facing +Z)
        for (k in 0 until 3) {
            val holder = Entity().apply { setName(BoardNames.tab(k)); enabled = false }
            GazeTargets.install(holder, r.tabShape, r.physics)
            holder.addChild(ModelEntity(r.tabBody, r.tabWood).apply { setName("tabBody:$k"); transform().setQuaternion(Q4.rotX((PI / 2).toFloat()).sdk()) })
            val mat = UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setCullingMode(MaterialCullingMode.BACK); runCatching { setApplyToneMapping(false) } }.also { r.keep(it) }
            val face = ModelEntity(r.tabFace, mat).apply { setName("tabFace:$k"); place(this, V3(0f, 0f, Layout.TAB_T / 2 + 0.0008f)) }
            holder.addChild(face)
            root.addChild(holder)
            tabs += holder; tabFaces += face; tabMats += mat
        }
        // ---- title plate
        val plateBack = ModelEntity(r.plateBack, r.walnut).apply { setName("plateBack") }
        val pc = Layout.plateCentre()
        place(plateBack, V3(pc.x, pc.y - Layout.PLATE_H / 2 - 0.005f, pc.z - 0.006f))
        root.addChild(plateBack)
        plateMat = UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setCullingMode(MaterialCullingMode.BACK); runCatching { setApplyToneMapping(false) } }.also { r.keep(it) }
        root.addChild(ModelEntity(r.platePlane, plateMat!!).apply { setName("titlePlate"); place(this, V3(pc.x, pc.y, pc.z + 0.0008f)) })
        buildLight()
        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms volume=${Layout.VOLUME} pivot=${Layout.PIVOT} tray=${Layout.TRAY_CENTRE} plate=$pc")
        onFrame(0f)
    }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            // B24: a moderate warm key light from the front right, above (not over-exposed)
            light.components.set(DirectionalLightComponent(Color4(1f, 0.95f, 0.88f, 1f), 0.9f, true))
            light.transform().setQuaternion((Q4.rotY((30 * PI / 180).toFloat()) * Q4.rotX((-55 * PI / 180).toFloat())).sdk())
            root.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    fun apply(next: PlayState, s: Settings, panelOpen: Boolean, plate: String, plate2: String?, tabWords: List<String>, fTest: Boolean) {
        state = next
        settings = s
        this.panelOpen = panelOpen
        plateText = plate
        plateLine2 = plate2
        tabLabels = tabWords
        orientationTest = fTest
        if (!built) return
        onFrame(0f)
    }

    // ---------------------------------------------------------------- async textures / figure marks

    private fun refreshPlate() {
        val key = "$plateText|$plateLine2|$orientationTest"
        if (key == plateShown || plateBusy || plateText.isEmpty()) return
        plateBusy = true
        val (t, l2, test) = Triple(plateText, plateLine2, orientationTest)
        scope.launch {
            val tex = withContext(Dispatchers.IO) { runCatching { TextureResource.create(BoardTextures.plate(t, l2, test)) }.getOrNull() }
            if (tex != null) {
                plateMat?.setBaseColorTexture(tex)
                plateTex?.let { old -> runCatching { old.close() } }
                plateTex = tex
                plateShown = key
                Log.i(TAG, "PLATE \"$t\" ${l2 ?: ""}")
            }
            plateBusy = false
        }
    }

    private fun refreshTabs() {
        val key = tabLabels.joinToString("|") + "|$orientationTest"
        if (key == tabsShownFor || tabsBusy || tabLabels.any { it.isEmpty() }) return
        tabsBusy = true
        val labels = tabLabels; val test = orientationTest
        scope.launch {
            val icons = listOf(R.drawable.ic_rotate_left, R.drawable.ic_rotate_right, R.drawable.ic_flip)
            val tex = withContext(Dispatchers.IO) { icons.indices.map { k -> runCatching { TextureResource.create(BoardTextures.tabFace(context, icons[k], labels[k], test)) }.getOrNull() } }
            tex.forEachIndexed { k, t -> if (t != null) tabMats[k].setBaseColorTexture(t) }
            tabTex.forEach { old -> old?.let { runCatching { it.close() } } }
            tabTex = tex
            tabsShownFor = key
            tabsBusy = false
            Log.i(TAG, "TABS $labels test=$test")
        }
    }

    private fun refreshFigure() {
        val r = res ?: return
        val fig = state.figure
        if (figureMeshes?.figureId == fig.id || figureBusy) return
        figureBusy = true
        scope.launch {
            val fm = withContext(Dispatchers.IO) { runCatching { FigureMeshes.create(r, fig) }.onFailure { Log.e(TAG, "FIGURE meshes failed", it) }.getOrNull() }
            figureBusy = false
            if (fm == null) return@launch
            figureEntities.forEach { it.removeFromParent() }
            figureEntities.clear(); slotEntities.clear(); ringEntities.clear(); seamEntity = null
            fm.slots.forEachIndexed { s, mesh ->
                val e = ModelEntity(mesh, r.slotShadow).apply { setName("slot:$s"); place(this, V3(0f, 0.0004f, 0f)) }
                boardRoot.addChild(e); figureEntities += e; slotEntities += e
            }
            fm.outline?.let { m -> ModelEntity(m, r.slotRim).apply { setName("outline"); place(this, V3(0f, 0.0007f, 0f)); boardRoot.addChild(this); figureEntities += this } }
            fm.seams?.let { m -> seamEntity = ModelEntity(m, r.seam).apply { setName("seams"); enabled = false; place(this, V3(0f, 0.0009f, 0f)); boardRoot.addChild(this); figureEntities += this } }
            fm.rings.forEachIndexed { s, mesh ->
                val e = ModelEntity(mesh, r.snapRing).apply { setName("ring:$s"); enabled = false; place(this, V3(0f, 0.0011f, 0f)) }
                boardRoot.addChild(e); figureEntities += e; ringEntities += e
            }
            figureMeshes = fm
            tintedFor = -1
            Log.i(TAG, "FIGURE ${fig.id} marks built (${fm.slots.size} slots)")
        }
    }

    // ---------------------------------------------------------------- per frame

    private fun easeOut(t: Float): Float { val u = t.coerceIn(0f, 1f); return 1f - (1f - u) * (1f - u) * (1f - u) }
    private fun easeInOut(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3 - 2 * u) }

    fun onFrame(@Suppress("UNUSED_PARAMETER") dt: Float) {
        if (!built) return
        val r = res ?: return
        val st = state
        val now = SystemClock.uptimeMillis()
        val reduced = settings.reducedMotion
        fun elapsed(start: Long): Long = (now - start).let { e -> st.freezeMs?.let { f -> min(e, f) } ?: e }
        refreshPlate(); refreshTabs(); refreshFigure()

        val solvedT = if (st.phase == Phase.SOLVED && st.solvedAt > 0) elapsed(st.solvedAt) else -1L
        val rise = if (solvedT >= 0) Layout.SELECT_LIFT * 0f + 0.01f * easeOut(solvedT.toFloat() / GameFlow.RISE_MS) else 0f
        val coachOn = st.coach && (now / 750) % 2 == 0L

        for (i in 0 until Pieces.COUNT) {
            val e = pieces[i]
            val p = st.pieces[i]
            val lift = if (st.selected == i) Layout.SELECT_LIFT else 0f
            var pos = PiecePoses.position(i, p, st.figure, lift + if (p.snapped) rise else 0f)
            var q = PiecePoses.orientation(p)
            val m = st.motionOf(i)
            if (m != null) {
                val t = elapsed(m.startedAt).toFloat() / m.durationMs.coerceAtLeast(1)
                if (t < 1f || st.freezeMs != null) {
                    val from = PiecePoses.position(i, m.from, st.figure, 0f)
                    val qFrom = PiecePoses.orientation(m.from)
                    when (m.kind) {
                        MotionKind.FLY, MotionKind.HOME, MotionKind.SLOW_FLY -> {
                            val u = if (reduced) 1f else easeInOut(t)
                            val arc = if (reduced) 0f else Layout.ARC * sin(PI.toFloat() * u.coerceIn(0f, 1f))
                            pos = from.lerp(pos, u) + V3(0f, arc, 0f)
                            q = qFrom.slerp(q, u)
                        }
                        MotionKind.SNAP -> { pos = from.lerp(pos, easeOut(t)); q = qFrom.slerp(q, easeOut(t)) }
                        MotionKind.TURN -> q = qFrom.slerp(q, if (reduced) 1f else easeOut(t))
                        MotionKind.FLIP -> {
                            val u = if (reduced) 1f else easeInOut(t)
                            // lift to 4 cm and turn over about the piece's own front-back axis (design S04)
                            pos = pos + PiecePoses.normal(p) * (0.04f * sin(PI.toFloat() * u))
                            q = qFrom * Q4.rotZ(PI.toFloat() * u)
                        }
                        MotionKind.SHAKE -> {
                            if (!reduced) {
                                val k = 0.003f * sin(elapsed(m.startedAt) / 25f * PI.toFloat()).let { if (st.freezeMs != null) 1f else it }
                                pos = pos + V3(k, 0f, 0f)
                            }
                        }
                    }
                }
            }
            drawn[i] = pos
            place(e, pos)
            e.transform().setQuaternion(q.sdk())

            // a placed piece keeps no collider: the pad under it answers (GameFlow.tapPad says 「放好了」)
            val wantCollider = !p.snapped
            if (wantCollider != pieceColliderOn[i]) {
                pieceColliderOn[i] = wantCollider
                if (wantCollider) GazeTargets.install(e, r.pieceShape.getValue(Pieces.kind(i)), r.physics)
                else { e.components.remove(CollisionComponent::class.java); e.components.remove(InteractableComponent::class.java); e.components.remove(HoverEffectComponent::class.java) }
            }

            // the edge ring: selected (amber) > coach (flashing) > placed (warm white)
            val edge = pieceEdges[i]
            val mat: Material? = when {
                st.selected == i -> r.selectEdge
                st.coach && i == 0 -> if (coachOn) r.coachEdge else null
                p.snapped && st.phase != Phase.SOLVED -> r.doneEdge
                else -> null
            }
            if (mat == null) edge.enabled = false else {
                edge.components[ModelComponent::class.java]?.materials?.set(0, mat)
                place(edge, V3(0f, if (p.pose.flipped) -(Layout.T / 2 + 0.0004f) else Layout.T / 2 + 0.0004f, 0f))
                edge.enabled = true
            }
        }

        // ---- solved: tint toward the figure colour (0.6 s), contact shadow under the risen figure
        tint(r, st, solvedT)

        // ---- slot marks: snap flash (green 0.4 s), hint glow on the selected kind's empty slots
        seamEntity?.enabled = st.hintOn && !st.isFree && st.phase != Phase.SOLVED
        val snappedSlot = if (st.notice == Notice.SNAPPED && (now - st.noticeAt < 400 || st.freezeMs != null)) st.motions.lastOrNull { st.pieces[it.piece].snapped }?.let { st.pieces[it.piece].slot } else null
        val selKind: Kind? = st.selected?.let { Pieces.kind(it) }
        for ((s, ring) in ringEntities.withIndex()) {
            val slot = st.figure.slots.getOrNull(s) ?: continue
            val glow = st.hintOn && selKind != null && slot.kind == selKind && st.pieces.none { it.slot == s }
            val mat = when { s == snappedSlot -> r.snapRing; glow -> r.slotGlow; else -> null }
            if (mat == null) ring.enabled = false else { ring.components[ModelComponent::class.java]?.materials?.set(0, mat); ring.enabled = true }
        }

        // ---- tabs over the selected piece (two, or three for the parallelogram)
        val sel = st.selected
        val showTabs = sel != null && !st.inputLocked
        val n = if (sel != null && Pieces.kind(sel).canFlip) 3 else 2
        if (showTabs) {
            val p = st.pieces[sel!!]
            val normal = PiecePoses.normal(p)
            val face = drawn[sel] + normal * (Layout.T / 2)
            val bar = Layout.tabBarCentre(face, normal, n)
            for (k in 0 until 3) {
                val t = tabs[k]
                if (k >= n) { t.enabled = false; continue }
                place(t, Layout.tabCentre(bar, k, n))
                t.enabled = true
            }
        } else tabs.forEach { it.enabled = false }
    }

    private fun tint(r: SceneResources, st: PlayState, solvedT: Long) {
        val c = st.figure.color
        if (solvedT < 0) {
            if (tintedFor != 0L) { r.maple.forEach { it.setBaseColor(Color4(1f, 1f, 1f, 1f)) }; tintedFor = 0L; contact?.enabled = false }
            return
        }
        val k = (solvedT.toFloat() / GameFlow.TINT_MS).coerceIn(0f, 1f)
        if (k >= 1f && tintedFor == c) return
        val tr = ((c shr 16) and 0xFF) / 255f; val tg = ((c shr 8) and 0xFF) / 255f; val tb = (c and 0xFF) / 255f
        // the maple texture times a colour that walks from white to the figure colour (lightened so the grain shows)
        val col = Color4(1f + (tr * 1.15f - 1f) * k, 1f + (tg * 1.15f - 1f) * k, 1f + (tb * 1.15f - 1f) * k, 1f)
        r.maple.forEach { it.setBaseColor(col) }
        if (k >= 1f) tintedFor = c
        contact?.let { sh ->
            val (a, b) = Poly.bounds(st.figure.slots.flatMap { it.verts }.map { Layout.puzzleToBoard(it, st.figure) })
            sh.transform().setScaleVector(Vector3((b.x - a.x) * 1.15f, (b.y - a.y) * 1.15f, 1f))
            place(sh, V3((a.x + b.x) / 2, 0.0013f, -(a.y + b.y) / 2))
            sh.enabled = true
        }
    }

    /** Debug hit replay (template README 必带单测 4): the holder named [target] and its deepest child mesh. */
    fun replayEntities(target: String): List<Entity> {
        val out = ArrayList<Entity>()
        fun deepest(e: Entity): Entity { var x = e; while (x.getChildren().isNotEmpty()) x = x.getChildren().first(); return x }
        fun visit(e: Entity) {
            if (runCatching { e.getName() }.getOrNull() == target && e.enabled) { out += e; deepest(e).takeIf { it !== e }?.let { out += it } }
            e.getChildren().forEach { visit(it) }
        }
        visit(root)
        return out
    }

    fun evidence(): String {
        val st = state
        return "volume=${Layout.VOLUME} plate=\"$plateShown\" figure=${figureMeshes?.figureId} tabs=${tabs.count { it.enabled }} " +
            "pieces=" + (0 until Pieces.COUNT).joinToString(" ") { "$it:${drawn[it]}" } + " sel=${st.selected}"
    }

    fun dispose() {
        plateTex?.let { runCatching { it.close() } }
        tabTex.forEach { t -> t?.let { runCatching { it.close() } } }
        res?.close()
        res = null
    }
}

/** Every holder name (one place; never built inside Entity().apply {} where `id` means the entity's id, build.md). */
object BoardNames {
    const val TRAY = "tray"
    fun piece(i: Int) = "piece:$i"
    fun pad(i: Int, j: Int) = "pad:$i:$j"
    fun tab(k: Int) = "tab:$k"

    sealed interface Hit
    data class PieceHit(val piece: Int) : Hit
    data class PadHit(val i: Int, val j: Int) : Hit
    data class TabHit(val k: Int) : Hit
    data object TrayHit : Hit
    data object NoHit : Hit

    /** A holder name → what was hit; out-of-range names resolve to nothing (a naming bug shows in the log). */
    fun hitOf(name: String?): Hit {
        if (name == null) return NoHit
        val f = name.split(":")
        return when (f[0]) {
            "piece" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until Pieces.COUNT && f.size == 2 }?.let { PieceHit(it) } ?: NoHit
            "pad" -> {
                val i = f.getOrNull(1)?.toIntOrNull(); val j = f.getOrNull(2)?.toIntOrNull()
                if (f.size == 3 && i in 0 until Layout.PAD_N && j in 0 until Layout.PAD_N) PadHit(i!!, j!!) else NoHit
            }
            "tab" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..2 && f.size == 2 }?.let { TabHit(it) } ?: NoHit
            TRAY -> if (f.size == 1) TrayHit else NoHit
            else -> NoHit
        }
    }
}
