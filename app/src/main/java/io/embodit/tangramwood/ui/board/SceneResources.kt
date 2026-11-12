package io.embodit.tangramwood.ui.board

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.tangramwood.domain.Figure
import io.embodit.tangramwood.domain.Kind
import io.embodit.tangramwood.domain.Layout
import io.embodit.tangramwood.domain.Pieces
import io.embodit.tangramwood.domain.Poly
import io.embodit.tangramwood.domain.V2
import io.embodit.tangramwood.ui.board.input.GazeTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/** A piece kind's outline in metres (x right, y away), around its centroid. */
internal fun outlineM(kind: Kind): List<V2> = kind.centred.map { it * Layout.U }

/**
 * Everything the volume draws, created once off the main thread (resource creation waits on the renderer, so it runs
 * concurrently on Dispatchers.IO) and kept global; [close] releases it. Wood is PBR with Canvas grain; marks (slot rims,
 * seams, the 「放好了」 edge, the snap ring) are unlit with tone mapping off so their colours are exact.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()
    fun <T : Resource> keep(r: T): T { r.toGlobal(); synchronized(all) { all += r }; return r }
    fun close() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    lateinit var pieceTop: Map<Kind, MeshResource>; private set
    lateinit var pieceBottom: Map<Kind, MeshResource>; private set
    lateinit var pieceEdge: Map<Kind, MeshResource>; private set
    lateinit var pieceShape: Map<Kind, ShapeResource>; private set
    lateinit var trayBase: MeshResource; private set
    lateinit var trayRimX: MeshResource; private set
    lateinit var trayRimZ: MeshResource; private set
    lateinit var boardSlab: MeshResource; private set
    lateinit var wedge: MeshResource; private set
    lateinit var trayWedge: MeshResource; private set
    lateinit var plinth: MeshResource; private set
    var plinthDepth: Float = 0.3f; private set
    lateinit var tabBody: MeshResource; private set
    lateinit var tabFace: MeshResource; private set
    lateinit var platePlane: MeshResource; private set
    lateinit var plateBack: MeshResource; private set
    lateinit var shadowPlane: MeshResource; private set

    lateinit var maple: List<PhysicallyBasedMaterial>; private set
    lateinit var cherry: Material; private set
    lateinit var walnut: Material; private set
    lateinit var trayWood: Material; private set
    lateinit var wedgeWood: Material; private set
    lateinit var tabWood: Material; private set
    lateinit var slotShadow: Material; private set
    lateinit var slotRim: Material; private set
    lateinit var seam: Material; private set
    lateinit var doneEdge: Material; private set
    lateinit var selectEdge: Material; private set
    lateinit var snapRing: Material; private set
    lateinit var slotGlow: Material; private set
    lateinit var coachEdge: Material; private set
    lateinit var contact: Material; private set
    lateinit var marker: Material; private set
    lateinit var physics: PhysicsMaterialResource; private set
    lateinit var padShape: ShapeResource; private set
    lateinit var trayShape: ShapeResource; private set
    lateinit var tabShape: ShapeResource; private set
    lateinit var markerParts: List<GazeTargets.Part>; private set

    companion object {
        /** The pad marker: a 5 mm plate, 1.2 mm proud (board-input template 「标记尺寸」 ≤ 5 mm). */
        val MARKER_STYLE = GazeTargets.Style(fraction = Layout.MARKER / Layout.PAD, thick = 0.0012f, corner = 0.001f)

        fun pbr(color: Color, rough: Float, tex: TextureResource? = null, glow: Float = 0.06f): PhysicallyBasedMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                if (tex != null) { setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(tex) } else setBaseColor(color.c4(1f))
                setRoughness(rough); setMetallic(0f)
                setEmissiveColor(Color4(color.red * glow, color.green * glow, color.blue * glow, 1f))
                setCullingMode(MaterialCullingMode.NONE)
            }

        fun unlit(color: Color, opacity: Float = 1f, tex: TextureResource? = null): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f || tex != null) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                tex?.let { setBaseColorTexture(it) }
                setCullingMode(MaterialCullingMode.NONE)
                runCatching { setApplyToneMapping(false) }
                if (opacity < 1f || tex != null) setDepthWrite(false)
            }

        suspend fun create(@Suppress("UNUSED_PARAMETER") context: Context): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.k(): T = r.keep(this)
            val io = Dispatchers.IO
            val T = Layout.T
            val half = T / 2
            val mapleTex = async(io) { TextureResource.create(BoardTextures.wood(BoardTextures.MAPLE, 3)).k() }
            val cherryTex = async(io) { TextureResource.create(BoardTextures.wood(BoardTextures.CHERRY, 5)).k() }
            val walnutTex = async(io) { TextureResource.create(BoardTextures.wood(BoardTextures.WALNUT, 7)).k() }
            val trayTex = async(io) { TextureResource.create(BoardTextures.wood(BoardTextures.TRAY, 9)).k() }
            val shadowTex = async(io) { TextureResource.create(BoardTextures.softShadow()).k() }
            val kinds = Kind.entries
            val tops = kinds.associateWith { k -> async(io) { Meshes.slab(outlineM(k), -half, half, Layout.CHAMFER, "top:$k", bottom = false, splitY = 0f).k() } }
            val bottoms = kinds.associateWith { k -> async(io) { Meshes.slab(outlineM(k), -half, half, Layout.CHAMFER, "bottom:$k", top = false, splitY = 0f).k() } }
            val edges = kinds.associateWith { k -> async(io) { Meshes.ring(Meshes.inset(outlineM(k), Layout.CHAMFER), 0.0018f, "edge:$k").k() } }
            val shapes = kinds.associateWith { k ->
                async(io) {
                    val full = Meshes.slab(outlineM(k), -half, half, Layout.CHAMFER, "hit:$k").k()
                    runCatching { ShapeResource.createConvexMesh(full) }.getOrElse {
                        val (a, b) = Poly.bounds(outlineM(k))
                        ShapeResource.createBox(Vector3(b.x - a.x, T, b.y - a.y))
                    }.k()
                }
            }
            listOf(
                async(io) { r.trayBase = Meshes.slab(Meshes.roundRect(Layout.TRAY_OUTER_W, Layout.TRAY_OUTER_D, 0.02f), 0f, Layout.TRAY_BASE, 0.003f, "trayBase").k() },
                async(io) { r.trayRimX = Meshes.slab(Meshes.roundRect(Layout.TRAY_WALL, Layout.TRAY_OUTER_D, 0.005f), 0f, Layout.TRAY_WALL_H, 0.002f, "trayRimX").k() },
                async(io) { r.trayRimZ = Meshes.slab(Meshes.roundRect(Layout.TRAY_OUTER_W - 2 * Layout.TRAY_WALL, Layout.TRAY_WALL, 0.005f), 0f, Layout.TRAY_WALL_H, 0.002f, "trayRimZ").k() },
                async(io) { r.boardSlab = Meshes.slab(Meshes.roundRect(Layout.BOARD, Layout.BOARD, 0.012f), 0f, Layout.BOARD_T, 0.003f, "board").k() },
                async(io) {
                    // the wedges' slopes lie under the slanted slabs (pivot at their lower front edge)
                    val c = kotlin.math.cos(Layout.THETA); val sn = kotlin.math.sin(Layout.THETA)
                    r.wedge = Meshes.wedge(Layout.BOARD * 0.9f, Layout.BOARD * c * 0.98f, Layout.BOARD * sn * 0.98f, "boardWedge").k()
                    r.trayWedge = Meshes.wedge(Layout.TRAY_OUTER_W * 0.9f, Layout.TRAY_OUTER_D * c * 0.98f, Layout.TRAY_OUTER_D * sn * 0.98f, "trayWedge").k()
                },
                async(io) {
                    r.plinthDepth = Layout.BOARD * kotlin.math.cos(Layout.THETA) + 0.02f
                    r.plinth = Meshes.slab(Meshes.roundRect(Layout.VOLUME.x - 0.02f, r.plinthDepth, 0.01f), 0f, Layout.PLINTH_TOP + Layout.HALF.y, 0.003f, "plinth").k()
                },
                async(io) { r.tabBody = Meshes.slab(Meshes.roundRect(Layout.TAB, Layout.TAB, 0.01f), -Layout.TAB_T / 2, Layout.TAB_T / 2, 0.0015f, "tabBody").k() },
                async(io) { r.tabFace = MeshResource.createPlane(Layout.TAB - 0.004f, Layout.TAB - 0.004f, 0.008f).k() },
                async(io) { r.platePlane = MeshResource.createPlane(Layout.PLATE_W, Layout.PLATE_H, 0f).k() },
                async(io) { r.plateBack = Meshes.slab(Meshes.roundRect(Layout.PLATE_W + 0.01f, 0.012f, 0.004f), 0f, Layout.PLATE_H + 0.01f, 0.001f, "plateBack").k() },
                async(io) { r.shadowPlane = MeshResource.createPlane(1f, 1f, 0f).k() },
                async(io) { r.markerParts = GazeTargets.markerMeshes(Layout.PAD, MARKER_STYLE).also { ps -> ps.map { it.mesh }.distinct().forEach { it.k() } } },
                async(io) { r.padShape = ShapeResource.createBox(Vector3(Layout.PAD, 0.004f, Layout.PAD)).k() },
                async(io) { r.trayShape = ShapeResource.createBox(Layout.TRAY_TARGET.let { Vector3(it.x, it.y, it.z) }).k() },
                async(io) { r.tabShape = ShapeResource.createBox(Vector3(Layout.TAB, Layout.TAB, Layout.TAB_T)).k() },
                async(io) { r.physics = PhysicsMaterialResource().k() },
            ).awaitAll()
            r.pieceTop = tops.mapValues { it.value.await() }
            r.pieceBottom = bottoms.mapValues { it.value.await() }
            r.pieceEdge = edges.mapValues { it.value.await() }
            r.pieceShape = shapes.mapValues { it.value.await() }
            val mt = mapleTex.await()
            r.maple = List(Pieces.COUNT) { pbr(Color(0xFFE8C99A), 0.62f, mt).k() }
            r.cherry = pbr(Color(0xFFD98A5F), 0.6f, cherryTex.await()).k()
            r.walnut = pbr(Color(0xFF5E4130), 0.7f, walnutTex.await()).k()
            r.trayWood = pbr(Color(0xFF6B4A34), 0.7f, trayTex.await()).k()
            r.wedgeWood = pbr(Color(0xFF4A3325), 0.75f, walnutTex.await()).k()
            r.tabWood = pbr(Color(0xFFC9A57A), 0.6f, mt).k()
            r.slotShadow = unlit(Color(0xFF231E1B)).k()
            r.slotRim = unlit(Color(0xFFC9A57A)).k()
            r.seam = unlit(Color(0xFFE8C99A), 0.6f).k()
            r.doneEdge = unlit(Color(0xFFFFF4DC)).k()
            r.selectEdge = unlit(Color(0xFFFFC94A)).k()
            r.snapRing = unlit(Color(0xFF5BD47A)).k()
            r.slotGlow = unlit(Color(0xFFFFC94A), 0.85f).k()
            r.coachEdge = unlit(Color(0xFFFFE08A)).k()
            r.contact = unlit(Color.White, 0.55f, shadowTex.await()).k()
            // the board walnut half a shade darker, opaque PBR: the system hover is drawn on it
            r.marker = pbr(Color(0xFF4E3527), 0.6f).k()
            r
        }
    }
}

/** The per-figure marks: shadow slots, their rims (snap flash / hint glow), the figure's outline and the seams. */
internal class FigureMeshes(val figureId: String, val slots: List<MeshResource>, val rings: List<MeshResource>, val outline: MeshResource?, val seams: MeshResource?) {
    companion object {
        suspend fun create(r: SceneResources, figure: Figure): FigureMeshes = coroutineScope {
            val io = Dispatchers.IO
            if (figure.isFree) return@coroutineScope FigureMeshes(figure.id, emptyList(), emptyList(), null, null)
            val polys = figure.slots.map { s -> s.verts.map { Layout_puzzleToBoard(it, figure) } }
            val slots = polys.mapIndexed { i, p -> async(io) { r.keep(Meshes.flat(p, "slot:${figure.id}:$i")) } }
            val rings = polys.mapIndexed { i, p -> async(io) { r.keep(Meshes.ring(p, 0.004f, "ring:${figure.id}:$i", outward = 0.002f)) } }
            val outline = async(io) { r.keep(Meshes.strips(Meshes.boundary(polys), 0.0015f, "outline:${figure.id}")) }
            val seams = async(io) { r.keep(Meshes.strips(polys.flatMap { p -> p.indices.map { p[it] to p[(it + 1) % p.size] } }, 0.002f, "seams:${figure.id}")) }
            FigureMeshes(figure.id, slots.awaitAll(), rings.awaitAll(), outline.await(), seams.await())
        }

        private fun Layout_puzzleToBoard(p: V2, f: Figure) = Layout.puzzleToBoard(p, f)
    }
}
