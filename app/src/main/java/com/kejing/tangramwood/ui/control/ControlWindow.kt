package com.kejing.tangramwood.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.kejing.tangramwood.BuildConfig
import com.kejing.tangramwood.R
import com.kejing.tangramwood.domain.ConfirmKind
import com.kejing.tangramwood.domain.Figure
import com.kejing.tangramwood.domain.Figures
import com.kejing.tangramwood.domain.Kind
import com.kejing.tangramwood.domain.Language
import com.kejing.tangramwood.domain.Layout
import com.kejing.tangramwood.domain.Notice
import com.kejing.tangramwood.domain.Phase
import com.kejing.tangramwood.domain.Pieces
import com.kejing.tangramwood.domain.PlayState
import com.kejing.tangramwood.domain.Pose
import com.kejing.tangramwood.domain.Poly
import com.kejing.tangramwood.domain.Settings
import com.kejing.tangramwood.domain.V2
import com.kejing.tangramwood.platform.Graph
import com.kejing.tangramwood.platform.TAG
import com.kejing.tangramwood.ui.Page
import com.kejing.tangramwood.ui.SettingsConfirm
import com.kejing.tangramwood.ui.Ui
import com.kejing.tangramwood.ui.board.clock
import com.kejing.tangramwood.ui.theme.TangramColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the volume, 640 x 620 dp): all 2D UI, four pages switched by the
 * native SpatialUI TabBar (D8): 拼 / 题库 / 规矩 / 设置. No page scrolls (D44); every page lays its content between a header
 * and a last row (SpaceBetween), so the blank outside the content is only the window padding.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // S01: the rules page opens beside the ready tangram; [开始拼] (or the first tap on a piece) ends it
    LaunchedEffect(state.welcome) { if (state.welcome) Ui.page = Page.RULES else if (Ui.page == Page.RULES && state.phase == Phase.PLAY && state.moves == 0 && state.selected != null) Ui.page = Page.PLAY }
    LaunchedEffect(state.phase) { if (state.phase in setOf(Phase.SOLVED, Phase.REPLAY, Phase.RESTORE, Phase.HELPING)) Ui.page = Page.PLAY }
    LaunchedEffect(state.confirm) { if (state.confirm != null) Ui.page = Page.PLAY }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.settingsConfirm = null
    }
    // the readout's clock
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); now = SystemClock.uptimeMillis() } }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph, now)
            Page.LIBRARY -> LibraryPage(state, settings, graph)
            Page.RULES -> RulesPage(state, settings, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    // The native TabBar is its own system window: resolve the labels here, in the app-language context, and pass
    // plain strings in (ui_gates tabbar-lang; an English cold start shows four English tabs).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_extension, stringResource(R.string.tab_play)),
        Triple(Page.LIBRARY, R.drawable.ic_grid_view, stringResource(R.string.tab_library)),
        Triple(Page.RULES, R.drawable.ic_menu_book, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_settings, stringResource(R.string.tab_settings)),
    )
    NativeTabs(tabs)
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

/** Page header (56 dp): a title and [静音] on every page (D15: mute is always one glance away). */
@Composable
private fun Header(title: String, tag: String, settings: Settings, graph: Graph) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 2)
        Spacer(Modifier.width(10.dp))
        ToggleButton(stringResource(if (settings.sound) R.string.btn_mute else R.string.btn_sound), "$tag/mute", on = !settings.sound,
            onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, icon = if (settings.sound) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }
}

@Composable
internal fun isZh(): Boolean = stringResource(R.string.lang_code) == "zh"

@Composable
internal fun kindName(kind: Kind): String = stringResource(when (kind) {
    Kind.L -> R.string.name_l; Kind.M -> R.string.name_m; Kind.S -> R.string.name_s; Kind.SQ -> R.string.name_sq; Kind.P -> R.string.name_p
})

@Composable
internal fun kindWhy(kind: Kind): String = stringResource(when (kind) {
    Kind.L -> R.string.why_l; Kind.M -> R.string.why_m; Kind.S -> R.string.why_s; Kind.SQ -> R.string.why_sq; Kind.P -> R.string.why_p
})

/** The status sentence (design content.lines / tone): one line that says what you can do next. */
@Composable
internal fun statusText(st: PlayState, now: Long): String {
    val fresh = now - st.noticeAt < com.kejing.tangramwood.domain.GameFlow.NOTICE_MS || st.freezeMs != null
    return when {
        st.phase == Phase.LOADING -> stringResource(R.string.s00)
        st.phase == Phase.HELPING || (st.notice == Notice.HELPED && fresh) -> st.noticeKind?.let { stringResource(R.string.help_line, kindName(it), kindWhy(it)) } ?: stringResource(R.string.s02)
        st.notice == Notice.LOCKED && fresh -> stringResource(R.string.locked)
        st.notice == Notice.TURN_ME && st.selected != null -> stringResource(R.string.t6)
        st.notice == Notice.BLOCKED && fresh -> stringResource(R.string.s06)
        st.notice == Notice.SNAPPED && (now - st.noticeAt < com.kejing.tangramwood.domain.GameFlow.SNAPPED_MS || st.freezeMs != null) -> stringResource(R.string.s07)
        st.notice == Notice.PICK_FIRST && fresh -> stringResource(R.string.s02_empty)
        st.notice == Notice.UNDID && fresh -> stringResource(R.string.undid)
        st.coach -> stringResource(R.string.s01_coach)
        st.selected != null -> stringResource(R.string.s03)
        st.isFree -> stringResource(R.string.s13)
        else -> stringResource(R.string.s02)
    }
}

@Composable
private fun figureTitle(st: PlayState): String {
    val zh = isZh()
    return if (st.isFree) stringResource(R.string.btn_free) else "${st.figure.name(zh)} · ${st.figure.slogan(zh)}"
}

@Composable
private fun readout(st: PlayState, settings: Settings, now: Long): String =
    if (st.isFree) stringResource(R.string.readout_free, st.moves)
    else stringResource(R.string.readout, st.placed, clock(st.elapsedAt(now)), st.moves, settings.record(st.figure.id).bestMs?.let { clock(it) } ?: stringResource(R.string.best_none))

/** What a reset / switch would lose (the confirm card's second line). */
@Composable
private fun progressLine(st: PlayState, now: Long): String =
    if (st.isFree) stringResource(R.string.readout_free, st.moves) else stringResource(R.string.confirm_progress, st.placed, clock(st.elapsedAt(now)), st.moves)

// ------------------------------------------------------------------ 拼 Play

@Composable
private fun PlayPage(st: PlayState, settings: Settings, graph: Graph, now: Long) {
    val flow = graph.flow
    if (st.phase == Phase.LOADING) {
        PageColumn {
            Header(stringResource(R.string.app_name), "play", settings, graph)
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Txt(stringResource(R.string.s00), "play/loading", style = PicoTheme.typography.titleMedium)
            }
        }
        return
    }
    if (!graph.window.volumeOpen) { VolumeClosedPage(st, settings, graph); return }
    when (st.confirm) {
        ConfirmKind.RESET -> { ConfirmPage(stringResource(R.string.s16_reset), stringResource(R.string.btn_confirm_reset), R.drawable.ic_restart_alt, figureTitle(st), "reset", settings, graph, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() }, line = progressLine(st, now)); return }
        ConfirmKind.SWITCH -> {
            val target = st.switchTo?.let { if (it == Figure.FREE.id) stringResource(R.string.btn_free) else Figures.byId(it)?.name(isZh()) }.orEmpty()
            ConfirmPage(stringResource(R.string.s16_switch, target), stringResource(R.string.btn_confirm_switch), R.drawable.ic_grid_view, figureTitle(st), "switch", settings, graph, onYes = { flow.confirm() }, onNo = { flow.cancelConfirm() }, line = progressLine(st, now))
            return
        }
        null -> Unit
    }
    when (st.phase) {
        Phase.RESTORE -> { RestorePage(st, settings, graph); return }
        Phase.SOLVED -> { ResultPage(st, settings, graph); return }
        Phase.REPLAY -> { ReplayPage(st, settings, graph); return }
        else -> Unit
    }
    PageColumn {
        Header(figureTitle(st), "play", settings, graph)
        // the sentence (what to do next), on a card
        Box(Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(24.dp)).background(TangramColors.CardFace).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
            Txt(statusText(st, now), "play/status", style = PicoTheme.typography.titleMedium, color = TangramColors.Ink, maxLines = 3)
        }
        Txt(readout(st, settings, now), "play/readout", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.undo() }, enabled = st.canUndo, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
            ToggleButton(stringResource(if (st.hintOn) R.string.btn_hint_on else R.string.btn_hint_off), "play/hint", on = st.hintOn,
                onClick = { flow.setHint(!st.hintOn) }, enabled = st.hintOn || st.canHint, icon = R.drawable.ic_lightbulb, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.btn_help), "play/help", onClick = { flow.help() }, enabled = st.canHelp, icon = R.drawable.ic_auto_fix_high, modifier = Modifier.weight(1.3f))
            SecondaryButton(stringResource(R.string.btn_reset), "play/reset", onClick = { flow.requestReset() }, icon = R.drawable.ic_restart_alt,
                enabled = !st.inputLocked && st.hasProgress, modifier = Modifier.weight(1f))
        }
        // why hint / help wait (D43: a greyed control says why)
        // the last line: why hint / help wait in free play (D43), otherwise this group's tip (design content.groups)
        val tip = Figures.GROUPS.firstOrNull { it.id == st.figure.group }
        val why = when { st.isFree -> stringResource(R.string.s13_no_hint); tip != null -> if (isZh()) tip.tipZh else tip.tipEn; else -> "" }
        Txt(why, "play/why", style = PicoTheme.typography.bodyMedium, color = TangramColors.InkSecondary, maxLines = 2)
    }
}

/** S16 (reset / switch): a whole-page confirmation card; the volume is locked while it is open (PlayState.inputLocked). */
@Composable
private fun ConfirmPage(q: String, yes: String, icon: Int, title: String, tag: String, settings: Settings, graph: Graph, onYes: () -> Unit, onNo: () -> Unit, danger: Boolean = false, line: String? = null) {
    PageColumn {
        Header(title, "confirm", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(TangramColors.CardFace).padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(88.dp), tint = TangramColors.Ink)
            Txt(q, "confirm/$tag", style = PicoTheme.typography.titleLarge.copy(fontSize = 32.sp), color = TangramColors.Ink, maxLines = 3)
            line?.let { Txt(it, "confirm/$tag-line", style = PicoTheme.typography.titleMedium, color = TangramColors.InkSecondary, maxLines = 2) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = onNo, modifier = Modifier.weight(1f).height(80.dp))
                SecondaryButton(yes, "confirm/yes", danger = danger, primary = !danger, onClick = onYes, modifier = Modifier.weight(1f).height(80.dp))
            }
        }
    }
}

/** S18: pick up where you left off. */
@Composable
private fun RestorePage(st: PlayState, settings: Settings, graph: Graph) {
    PageColumn {
        Header(figureTitle(st), "restore", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(TangramColors.CardFace).padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)) {
            FigureThumb(st.figure, Modifier.size(120.dp))
            Txt(stringResource(R.string.s18, st.placed, st.figure.name(isZh()), clock(st.elapsedMs)), "restore/line", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp), color = TangramColors.Ink, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.btn_resume), "restore/resume", onClick = { graph.flow.resume() }, modifier = Modifier.weight(1f).height(80.dp))
                SecondaryButton(stringResource(R.string.btn_pick_other), "restore/other", onClick = { graph.flow.pickOther(); Ui.page = Page.LIBRARY }, modifier = Modifier.weight(1f).height(80.dp))
            }
        }
    }
}

/** S10: the result card (whole page, [静音] still in the header); the play buttons rest with one line why. */
@Composable
private fun ResultPage(st: PlayState, settings: Settings, graph: Graph) {
    val r = st.result ?: return
    val zh = isZh()
    val next = graph.flow.nextAfter(st.figure.id)
    val group = Figures.GROUPS.firstOrNull { it.id == st.figure.group }
    PageColumn {
        Header(figureTitle(st), "result", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 10.dp).clip(RoundedCornerShape(28.dp)).background(TangramColors.CardFace).padding(22.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Txt(stringResource(R.string.s10_line, clock(r.elapsedMs), r.moves, next.name(zh)), "result/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold), color = TangramColors.Ink, maxLines = 3)
            when {
                r.newBest -> Box(Modifier.clip(RoundedCornerShape(12.dp)).background(TangramColors.Primary).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Txt(stringResource(R.string.s10_best), "result/best", style = PicoTheme.typography.labelLarge, color = TangramColors.OnPrimary)
                }
                st.usedHint -> Txt(stringResource(R.string.s10_hint), "result/hint", style = PicoTheme.typography.bodyMedium, color = TangramColors.InkSecondary, maxLines = 2)
                r.helps > 0 -> Txt(stringResource(R.string.s10_helped, r.helps), "result/helped", style = PicoTheme.typography.bodyMedium, color = TangramColors.InkSecondary, maxLines = 2)
                else -> Unit
            }
            PrimaryButton(stringResource(R.string.btn_next), "result/next", onClick = { graph.flow.next() }, icon = R.drawable.ic_arrow_forward, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_replay), "result/replay", onClick = { graph.flow.replay() }, icon = R.drawable.ic_slow_motion_video, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, icon = R.drawable.ic_replay, modifier = Modifier.weight(1f))
            }
            group?.let { Txt(if (zh) it.tipZh else it.tipEn, "result/tip", style = PicoTheme.typography.bodySmall, color = TangramColors.InkSecondary, maxLines = 2) }
        }
        Txt(stringResource(R.string.disabled), "result/disabled", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

/** S11: one piece at a time, with the reason for its kind; [跳过]. */
@Composable
private fun ReplayPage(st: PlayState, settings: Settings, graph: Graph) {
    PageColumn {
        Header(figureTitle(st), "replay", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(TangramColors.CardFace).padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Txt(stringResource(R.string.replay_title, st.replayStep), "replay/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 26.sp), color = TangramColors.Ink)
            Txt(st.replayKind?.let { kindWhy(it) } ?: "", "replay/why", style = PicoTheme.typography.titleMedium, color = TangramColors.Ink, maxLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.btn_undo), "replay/undo", onClick = {}, enabled = false, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_hint_off), "replay/hint", onClick = {}, enabled = false, icon = R.drawable.ic_lightbulb, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_help), "replay/help", onClick = {}, enabled = false, icon = R.drawable.ic_auto_fix_high, modifier = Modifier.weight(1.3f))
            }
        }
        SecondaryButton(stringResource(R.string.btn_skip), "replay/skip", onClick = { graph.flow.finishReplay() }, icon = R.drawable.ic_skip_next, modifier = Modifier.fillMaxWidth())
    }
}

/** The system [×] closed the tangram volume; the play buttons wait (D41: controls follow their window). */
@Composable
private fun VolumeClosedPage(st: PlayState, settings: Settings, graph: Graph) {
    PageColumn {
        Header(figureTitle(st), "volume", settings, graph)
        Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp).clip(RoundedCornerShape(28.dp)).background(TangramColors.CardFace).padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)) {
            Txt(stringResource(R.string.volume_closed), "volume/title", style = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp), color = TangramColors.Ink)
            Txt(stringResource(R.string.volume_closed_line), "volume/line", style = PicoTheme.typography.bodyLarge, color = TangramColors.InkSecondary, maxLines = 2)
            PrimaryButton(stringResource(R.string.btn_bring_volume), "volume/bring", onClick = { graph.window.reopenVolume() }, modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_undo), "volume/undo", onClick = {}, enabled = false, icon = R.drawable.ic_undo, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_hint_off), "volume/hint", onClick = {}, enabled = false, icon = R.drawable.ic_lightbulb, modifier = Modifier.weight(1f))
        }
    }
}

// ------------------------------------------------------------------ 题库 Library

@Composable
private fun LibraryPage(st: PlayState, settings: Settings, graph: Graph) {
    val zh = isZh()
    val groups = Figures.GROUPS
    val g = groups[Ui.libraryGroup.coerceIn(0, groups.size - 1)]
    PageColumn {
        Header(stringResource(R.string.tab_library), "library", settings, graph)
        Row(Modifier.fillMaxWidth()) {
            Segments(groups.mapIndexed { i, gr -> i to (if (zh) gr.zh else gr.en) }, Ui.libraryGroup, "library/group", { Ui.libraryGroup = it }, Modifier.weight(1f))
        }
        // 「随便拼」 card on top (design panels.library)
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(if (st.isFree) TangramColors.PrimaryContainer else TangramColors.CardFace)
                .clickable { graph.flow.pickFigure(Figure.FREE.id); if (graph.flow.state.value.confirm == null) Ui.page = Page.PLAY }.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painter = painterResource(R.drawable.ic_draw), contentDescription = null, modifier = Modifier.size(28.dp), tint = TangramColors.Ink)
            Spacer(Modifier.width(12.dp))
            Txt(stringResource(R.string.btn_free), "library/free", style = PicoTheme.typography.titleMedium, color = TangramColors.Ink)
            Spacer(Modifier.width(12.dp))
            Txt(stringResource(R.string.s13), "library/free-line", style = PicoTheme.typography.bodySmall, color = TangramColors.InkSecondary, modifier = Modifier.weight(1f), maxLines = 2)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Figures.ALL.filter { it.group == g.id }.forEach { f ->
                FigureCard(f, current = st.figure.id == f.id, solved = settings.record(f.id).solved, zh = zh, modifier = Modifier.weight(1f)) {
                    graph.flow.pickFigure(f.id)
                    if (graph.flow.state.value.confirm == null) Ui.page = Page.PLAY
                }
            }
        }
        Txt(if (zh) g.tipZh else g.tipEn, "library/tip", style = PicoTheme.typography.bodyMedium, color = TangramColors.InkSecondary, maxLines = 2)
    }
}

/** One puzzle: the shadow thumbnail (88 dp), the name (two lines allowed), 1–3 difficulty dots, a tick once solved. */
@Composable
private fun FigureCard(f: Figure, current: Boolean, solved: Boolean, zh: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(196.dp).clip(RoundedCornerShape(18.dp)).background(if (current) TangramColors.PrimaryContainer else TangramColors.CardFace)
            .border(if (current) 2.dp else 0.dp, if (current) TangramColors.Primary else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.size(88.dp)) {
            FigureThumb(f, Modifier.size(88.dp))
            if (solved) Icon(painter = painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = TangramColors.Done, modifier = Modifier.size(24.dp).align(Alignment.TopEnd))
        }
        Txt(f.name(zh), "library/name", style = PicoTheme.typography.labelLarge, color = TangramColors.Ink, maxLines = 2, align = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { k -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (k < f.difficulty) TangramColors.Walnut else TangramColors.Walnut.copy(alpha = 0.2f))) }
        }
    }
}

/** The figure's shadow drawn from its slots (the same data the board uses), fitted to the box. */
@Composable
internal fun FigureThumb(f: Figure, modifier: Modifier) {
    Canvas(modifier) {
        val polys = f.slots.map { it.verts }
        if (polys.isEmpty()) return@Canvas
        val (a, b) = Poly.bounds(polys.flatten())
        val k = minOf(size.width / (b.x - a.x), size.height / (b.y - a.y)) * 0.9f
        val ox = (size.width - (b.x - a.x) * k) / 2; val oy = (size.height - (b.y - a.y) * k) / 2
        fun pt(v: V2) = Offset(ox + (v.x - a.x) * k, oy + (b.y - v.y) * k)
        for (p in polys) {
            val path = Path().apply { moveTo(pt(p[0]).x, pt(p[0]).y); p.drop(1).forEach { lineTo(pt(it).x, pt(it).y) }; close() }
            drawPath(path, TangramColors.Shadow)
        }
    }
}

// ------------------------------------------------------------------ 规矩 How to

@Composable
private fun RulesPage(st: PlayState, settings: Settings, graph: Graph) {
    PageColumn {
        Header(stringResource(R.string.tab_rules), "rules", settings, graph)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PiecesFigure(Modifier.size(width = 200.dp, height = 150.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3).forEachIndexed { i, id ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(TangramColors.Primary), contentAlignment = Alignment.Center) {
                            Txt("${i + 1}", "rules/n", style = PicoTheme.typography.labelMedium, color = TangramColors.OnPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Txt(stringResource(id), "rules/line", style = PicoTheme.typography.bodyMedium, maxLines = 3, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Txt(stringResource(R.string.names_line), "rules/names", style = PicoTheme.typography.bodySmall, color = TangramColors.InkSecondary, maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3).forEach { id ->
                Box(Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(14.dp)).background(TangramColors.CardFace).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    Txt(stringResource(id), "rules/mistake", style = PicoTheme.typography.bodySmall, color = TangramColors.Ink, maxLines = 4)
                }
            }
        }
        Txt(stringResource(R.string.rules_source), "rules/source", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.about_text, BuildConfig.VERSION_NAME), "rules/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            PrimaryButton(stringResource(if (st.welcome) R.string.s01_start else R.string.tab_play), "rules/start", onClick = { graph.flow.welcomeDone(); Ui.page = Page.PLAY }, icon = R.drawable.ic_play_arrow)
        }
    }
}

/** The seven pieces laid out as in the tray (the same homes the volume uses), drawn flat. */
@Composable
private fun PiecesFigure(modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(TangramColors.Walnut)) {
        val polys = (0 until Pieces.COUNT).map { i ->
            val h = Layout.HOMES[i]
            Pose(V2.ZERO, h.rot8, false).outline(Pieces.kind(i)).map { V2(h.x + it.x * Layout.U, h.z - it.y * Layout.U) }
        }
        val (a, b) = Poly.bounds(polys.flatten())
        val k = minOf(size.width / (b.x - a.x), size.height / (b.y - a.y)) * 0.88f
        val ox = (size.width - (b.x - a.x) * k) / 2; val oy = (size.height - (b.y - a.y) * k) / 2
        for (p in polys) {
            val path = Path().apply { p.forEachIndexed { j, v -> val x = ox + (v.x - a.x) * k; val y = oy + (v.y - a.y) * k; if (j == 0) moveTo(x, y) else lineTo(x, y) }; close() }
            drawPath(path, TangramColors.Maple)
        }
    }
}

// ------------------------------------------------------------------ 设置 Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change) }
    when (Ui.settingsConfirm) {
        SettingsConfirm.EXIT -> { ConfirmPage(stringResource(R.string.s16_exit), stringResource(R.string.btn_confirm_quit), R.drawable.ic_logout, stringResource(R.string.tab_settings), "exit", settings, graph, onYes = { exitApp(context, graph) }, onNo = { Ui.settingsConfirm = null }, danger = true, line = stringResource(R.string.confirm_exit_line)); return }
        SettingsConfirm.CLEAR -> { ConfirmPage(stringResource(R.string.s16_clear), stringResource(R.string.btn_confirm_clear), R.drawable.ic_delete_sweep, stringResource(R.string.tab_settings), "clear", settings, graph, onYes = { upd { it.copy(records = emptyMap()) }; Ui.settingsConfirm = null; Log.i(TAG, "RECORDS cleared") }, onNo = { Ui.settingsConfirm = null }, danger = true, line = stringResource(R.string.confirm_clear_line)); return }
        null -> Unit
    }
    val onOff = listOf(true to stringResource(R.string.opt_on), false to stringResource(R.string.opt_off))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Header(stringResource(R.string.tab_settings), "settings", settings, graph)
        OptionRow(stringResource(R.string.settings_language), "settings/lang", labelWidth = 150.dp) {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_sound), "settings/sound", labelWidth = 150.dp) {
            Segments(onOff, settings.sound, "settings/sound", { v -> upd { it.copy(sound = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume", labelWidth = 150.dp) {
            VolumeSlider(settings.volume, listOf(stringResource(R.string.vol_low), stringResource(R.string.vol_mid), stringResource(R.string.vol_high)), onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_reduced), "settings/reduced", labelWidth = 150.dp) {
            Segments(onOff, settings.reducedMotion, "settings/reduced", { v -> upd { it.copy(reducedMotion = v) } }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.btn_reopen), "settings/reopen", onClick = { graph.window.reopen() }, icon = R.drawable.ic_center_focus_strong, modifier = Modifier.width(230.dp))
            Spacer(Modifier.width(12.dp))
            Txt(stringResource(R.string.reopen_note), "settings/reopen-note", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.btn_clear), "settings/clear", onClick = { Ui.settingsConfirm = SettingsConfirm.CLEAR }, icon = R.drawable.ic_delete_sweep, modifier = Modifier.weight(1f))
            // D26: quit is on the last row; the confirmation is a whole-page card
            SecondaryButton(stringResource(R.string.btn_quit), "settings/exit", onClick = { Ui.settingsConfirm = SettingsConfirm.EXIT; Log.i(TAG, "CONFIRM EXIT S16") }, icon = R.drawable.ic_logout, modifier = Modifier.weight(1f), danger = true)
        }
    }
}

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.flow.stopJobs() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 600)
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language. */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}
