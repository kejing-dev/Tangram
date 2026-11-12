package com.kejing.tangramwood.ui.board

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.kejing.tangramwood.R
import com.kejing.tangramwood.domain.GameFlow
import com.kejing.tangramwood.domain.Layout
import com.kejing.tangramwood.domain.Phase
import com.kejing.tangramwood.domain.PlayState
import com.kejing.tangramwood.platform.Graph
import com.kejing.tangramwood.platform.TAG
import com.kejing.tangramwood.ui.board.input.BoardInputConfig
import com.kejing.tangramwood.ui.board.input.BoardInputListener
import com.kejing.tangramwood.ui.board.input.BoardInputLog
import com.kejing.tangramwood.ui.board.input.InputSource
import com.kejing.tangramwood.ui.board.input.Offset3M
import com.kejing.tangramwood.ui.board.input.TargetResolver
import com.kejing.tangramwood.ui.board.input.boardInput
import com.kejing.tangramwood.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** Runs the volume's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
    /** Debug: draw the 「F↑」 test image on the plate and the tabs (D10). */
    @Volatile var orientationTest: Boolean = false
}

/**
 * The tangram volume (default container). Opens the control window beside it through the level gate, builds the scene
 * and routes every pinch / trigger / drag on a holder (piece, pad, tab, tray) to [GameFlow] through the factory
 * board-input template (D50: hand ray, controller ray and eye-hand alike).
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val scene = remember { BoardScene(context.applicationContext, scope) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.request("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                // S17 fallback: the app icon tapped again brings the volume to the front; the panel comes back
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    Log.i(TAG, "LIFECYCLE resume panel=${graph.window.panelState}")
                    if (graph.window.panelState == "ON_DESTROY") graph.window.request("resume")
                }
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> { Log.i(TAG, "VOLUME destroyed"); graph.window.onVolumeLifecycle("ON_DESTROY") }
                androidx.lifecycle.Lifecycle.Event.ON_CREATE -> graph.window.onVolumeLifecycle("ON_CREATE")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    // the plate's clock ticks once a second while a puzzle is running
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    val loc = remember(context, settings.language) { context.localized(settings.language) }
    val zh = loc.getString(R.string.lang_code) == "zh"
    @Suppress("UNUSED_VARIABLE") val tickNow = tick // read: recompose each second
    val plate = plateText(state, graph.window.panelOpen, zh) { id, args -> loc.getString(id, *args) }
    // S17 only once the user closed the window (not in the second before it first opens)
    val plate2 = if (graph.window.panelState == "ON_DESTROY" && state.phase != Phase.LOADING) loc.getString(R.string.s17) else null
    val tabWords = listOf(loc.getString(R.string.tab_left), loc.getString(R.string.tab_right), loc.getString(R.string.tab_flip))

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .boardInput(remember { BoardTargets() }, remember(graph, scene) { BoardListener(graph, scene) }, BoardInputConfig(dragStartM = 0.03f, logTag = TAG)),
        initial = { content, _ ->
            content.addEntity(scene.root)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            val resources = withContext(NonCancellable + Dispatchers.Default) { runCatching { SceneResources.create(context) }.onFailure { Log.e(TAG, "SCENE resources failed", it) }.getOrNull() }
            Log.i(TAG, "SCENE resources ${resources != null} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            if (resources != null) scene.install(resources)
            if (graph.flow.state.value.phase == Phase.LOADING) graph.flow.start()
        },
        update = { _, _ ->
            scene.apply(state, settings, graph.window.panelOpen, plate, plate2, tabWords, SceneHolder.orientationTest)
        },
    )
}

/** The title plate line (design spatialLayout.titlePlate; D38: the same source as the panel). */
internal fun plateText(st: PlayState, @Suppress("UNUSED_PARAMETER") panelOpen: Boolean, zh: Boolean, s: (Int, Array<Any>) -> String): String = when {
    st.phase == Phase.LOADING -> s(R.string.s00, emptyArray())
    st.isFree -> s(R.string.plate_free, arrayOf(st.moves))
    st.phase == Phase.SOLVED || st.phase == Phase.REPLAY -> s(R.string.plate_solved, arrayOf(st.figure.name(zh)))
    else -> s(R.string.plate, arrayOf(st.figure.name(zh), st.placed, clock(st.elapsedAt(android.os.SystemClock.uptimeMillis()))))
}

/** m:ss (design readout 「1:12」). */
fun clock(ms: Long): String { val t = ms / 1000; return "%d:%02d".format(t / 60, t % 60) }

/** Hit entity (or an ancestor) → the holder name the game knows (pieces, pads, tabs, the tray). */
internal class BoardTargets : TargetResolver<String> by TargetResolver.byName(::targetOf) {
    companion object {
        fun targetOf(name: String): String? = name.takeIf { BoardNames.hitOf(it) != BoardNames.NoHit }

        /** Hit replay (build.md 命中回放): [path] = the hit node's name first, then its parents'. */
        fun resolvePath(path: List<String>): String? = com.kejing.tangramwood.ui.board.input.resolvePath(path, ::targetOf)

        /** The 「square」 a target stands for in input-log lines: a piece, a pad, a tab, the tray, or "-". */
        fun squareOf(target: String?): String = when (val h = BoardNames.hitOf(target)) {
            is BoardNames.PieceHit -> "piece${h.piece}"
            is BoardNames.PadHit -> "pad${h.i}${h.j}"
            is BoardNames.TabHit -> "tab${h.k}"
            BoardNames.TrayHit -> "tray"
            BoardNames.NoHit -> "-"
        }
    }
}

/** Every resolved tap / drag goes through [routeTap] / [GameFlow] (the same calls the panel and the debug broadcast make). */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<String> {
    private var dragging = false

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> square=${BoardTargets.squareOf(target)}")
        routeTap(graph, target)
    }

    override fun onDragStart(target: String, source: InputSource): Boolean {
        BoardInputLog.write(TAG, "INPUT drag src=${source.label} target=$target -> square=${BoardTargets.squareOf(target)}")
        if (!graph.window.panelOpen) { graph.window.request("pinch"); return true } // S17: the first pinch only brings the panel back
        val piece = (BoardNames.hitOf(target) as? BoardNames.PieceHit)?.piece ?: return false
        dragging = graph.flow.dragStart(piece)
        return dragging
    }

    /** The drag in volume metres → board metres (x right, y away along the tilted board). */
    override fun onDragMove(totalM: Offset3M) {
        if (!dragging) return
        val along = -totalM.z * cos(Layout.THETA) + totalM.y * sin(Layout.THETA)
        graph.flow.dragMove(totalM.x, along)
    }

    override fun onDragEnd() { if (dragging) graph.flow.dragEnd(); dragging = false }

    override fun onDragCancel() { if (dragging) graph.flow.dragCancel(); dragging = false }

    /** D50 hover: a hint only, never a state change. */
    override fun onHover(target: String?, source: InputSource) { scene.setHover(target) }
}

/** Holder name → the one flow call (S17: the first tap reopens the panel and is consumed). */
internal fun routeTap(graph: Graph, name: String?) {
    if (!graph.window.panelOpen) { Log.i(TAG, "S17 tap $name -> reopen panel"); graph.window.request("pinch"); return }
    routeFlow(graph.flow, name)
}

/** [routeTap] without the Android graph (hit-replay unit tests drive a bare GameFlow through it). */
internal fun routeFlow(flow: GameFlow, name: String?) {
    when (val h = BoardNames.hitOf(name)) {
        is BoardNames.PieceHit -> flow.tapPiece(h.piece)
        is BoardNames.PadHit -> flow.tapPad(h.i, h.j)
        is BoardNames.TabHit -> flow.tapTab(h.k)
        BoardNames.TrayHit -> flow.tapTray()
        BoardNames.NoHit -> Unit
    }
}
