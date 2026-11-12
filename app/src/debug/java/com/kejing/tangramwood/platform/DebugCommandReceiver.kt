package com.kejing.tangramwood.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kejing.tangramwood.domain.Figure
import com.kejing.tangramwood.domain.Figures
import com.kejing.tangramwood.domain.Kind
import com.kejing.tangramwood.domain.Language
import com.kejing.tangramwood.domain.Layout
import com.kejing.tangramwood.domain.Pieces
import com.kejing.tangramwood.domain.Rules
import com.kejing.tangramwood.domain.Settings
import com.kejing.tangramwood.domain.V2
import com.kejing.tangramwood.ui.Containers
import com.kejing.tangramwood.ui.Page
import com.kejing.tangramwood.ui.SettingsConfirm
import com.kejing.tangramwood.ui.Ui
import com.kejing.tangramwood.ui.board.BoardListener
import com.kejing.tangramwood.ui.board.BoardNames
import com.kejing.tangramwood.ui.board.BoardTargets
import com.kejing.tangramwood.ui.board.SceneHolder
import com.kejing.tangramwood.ui.board.input.BoardInputLog
import com.kejing.tangramwood.ui.board.input.InputSource
import com.kejing.tangramwood.ui.board.input.resolveUp
import com.kejing.tangramwood.ui.board.routeTap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only. Every command goes through the same GameFlow calls the volume and the window use (D29): taps go
 * through [routeTap] with the holder name a ray would hit.
 *
 *   adb shell am broadcast -a com.kejing.tangramwood.DEBUG -p com.kejing.tangramwood --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S00..S18 (S03–S07 / S09 are held)      tap <holder> (piece:0..6 · pad:i:j · tab:0..2 · tray)
 *   pick <piece> · pad "<i> <j>" · slotpad "<slot>" (tap the pad a player would use for that slot) · rot -1|+1 · flip
 *   hint on|off · help · undo · reset · confirm · no · solve [ms] · solvenow · fig <id> · free · next · again · slow · skip
 *   resume · other · welcome · place "<piece> <x> <y> <rot8> [f]" · hold <state>|off · lang zh|en|system · sound on|off
 *   volume 0-2 · reduced on|off · page play|library|rules|settings · group 0-2 · exitask · clearask · reopen
 *   closepanel · closevolume · reopenvolume · s00hold on|off · ftest on|off · restart · clearsave
 *   replay "<holder> [hand-ray|eye-hand|controller]" · hover <holder>|off · dump · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        val f = a.split(" ").filter { it.isNotBlank() }
        Log.i(TAG, "CMD $cmd $a")
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun later(ms: Long, block: () -> Unit) { graph.scope.launch { delay(ms); block() } }
        fun fresh(id: String = "house") { flow.debugFresh(id); Ui.page = Page.PLAY }
        fun tap(holder: String) = routeTap(graph, holder)
        /** Tap the pad a player would use for [slot] of the current figure (turning the selected piece first). */
        fun slotPad(slot: Int) { val p = flow.bestPadFor(flow.state.value.figure, slot); tap(BoardNames.pad(p.first, p.second)) }
        /** Select piece [i] and turn / flip it to fit [slot]. */
        fun pickFor(i: Int, slot: Int) {
            tap(BoardNames.piece(i))
            val want = flow.state.value.figure.slots[slot].pose
            var guard = 0
            while (!Pieces.kind(i).sameOrientation(flow.state.value.pieces[i].pose.rot8, flow.state.value.pieces[i].pose.flipped, want.rot8, want.flipped) && guard++ < 10) {
                if (Pieces.kind(i).canFlip && flow.state.value.pieces[i].pose.flipped != want.flipped) tap(BoardNames.tab(2)) else tap(BoardNames.tab(0))
            }
        }
        fun slotOf(kind: Kind) = flow.state.value.figure.slots.indices.first { flow.state.value.figure.slots[it].kind == kind && !Rules.occupied(flow.state.value.pieces, it) }
        when (cmd) {
            "goto", "hold" -> when (a.uppercase()) {
                "OFF" -> flow.freeze(null)
                "S00" -> Log.i(TAG, "S00 is the first second after launch: s00hold on, force-stop and start, then capture")
                "S01" -> { settings { it.copy(firstRunDone = false) }; flow.debugFresh("house", welcome = true); Ui.page = Page.RULES }
                "S02" -> fresh()
                "S03" -> { fresh(); later(300) { tap(BoardNames.piece(0)) } }
                "S04" -> { fresh(); later(300) { tap(BoardNames.piece(6)); later(400) { flow.freeze(200); tap(BoardNames.tab(2)) } } }
                "S05" -> { fresh(); later(300) { tap(BoardNames.piece(0)); later(400) { flow.freeze(150); slotPad(slotOf(Kind.L)) } } }
                "S06" -> { fresh(); later(300) {
                    // a square floating on a pad no square / small-triangle slot reaches, then a small triangle dropped on it
                    val fig = flow.state.value.figure
                    val pad = Layout.PADS.firstOrNull { (i, j) -> val p = Rules.padPoint(i, j, fig); Rules.reachableSlots(fig, flow.state.value.pieces, Kind.SQ, p).isEmpty() && Rules.reachableSlots(fig, flow.state.value.pieces, Kind.S, p).isEmpty() } ?: (0 to 0)
                    tap(BoardNames.piece(5)); tap(BoardNames.pad(pad.first, pad.second))
                    later(500) { tap(BoardNames.piece(3)); later(300) { flow.freeze(500); tap(BoardNames.pad(pad.first, pad.second)) } }
                } }
                "S07" -> { fresh(); later(300) { pickFor(0, slotOf(Kind.L)); later(300) { flow.freeze(360); slotPad(slotOf(Kind.L)) } } }
                "S09" -> { fresh(); later(300) { flow.freeze(600); flow.help() } }
                "S10" -> { fresh(); later(300) { flow.debugFillAndSolve() } }
                "S11" -> { fresh(); later(300) { flow.debugFillAndSolve(); later(1500) { flow.replay() } } }
                "S12" -> Ui.page = Page.LIBRARY
                "S13" -> { fresh(Figure.FREE.id) }
                "S14" -> Ui.page = Page.RULES
                "S15" -> Ui.page = Page.SETTINGS
                "S16" -> { fresh(); later(300) { pickFor(0, slotOf(Kind.L)); slotPad(slotOf(Kind.L)); later(700) { flow.requestReset() } } }
                "S17" -> graph.window.closeControlOnly()
                "S18" -> { fresh(); later(300) { pickFor(0, slotOf(Kind.L)); slotPad(slotOf(Kind.L)); later(700) { flow.debugRestart() } } }
                else -> Log.w(TAG, "unknown state $a")
            }
            "tap" -> tap(a)
            "pick" -> a.toIntOrNull()?.let { tap(BoardNames.piece(it)) }
            "pad" -> { val i = f.getOrNull(0)?.toIntOrNull(); val j = f.getOrNull(1)?.toIntOrNull(); if (i != null && j != null) tap(BoardNames.pad(i, j)) }
            "slotpad" -> a.toIntOrNull()?.let { slotPad(it) }
            "rot" -> tap(BoardNames.tab(if ((a.toIntOrNull() ?: 1) > 0) 0 else 1))
            "flip" -> tap(BoardNames.tab(2))
            "undo" -> flow.undo()
            "hint" -> flow.setHint(on)
            "help" -> flow.help()
            "reset" -> flow.requestReset()
            "confirm" -> flow.confirm()
            "no" -> flow.cancelConfirm()
            "solve" -> flow.debugSolve(a.toLongOrNull() ?: 300L)
            "solvenow" -> flow.debugFillAndSolve()
            "stress" -> flow.debugStress()
            "fig" -> fresh(a)
            "free" -> fresh(Figure.FREE.id)
            "next" -> flow.next()
            "again" -> flow.again()
            "slow" -> flow.replay()
            "skip" -> flow.finishReplay()
            "resume" -> flow.resume()
            "other" -> { flow.pickOther(); Ui.page = Page.LIBRARY }
            "welcome" -> flow.welcomeDone()
            "restart" -> flow.debugRestart()
            "clearsave" -> { context.deleteFile("game.txt"); context.deleteFile("game.bak") }
            "place" -> {
                val i = f.getOrNull(0)?.toIntOrNull(); val x = f.getOrNull(1)?.toFloatOrNull(); val y = f.getOrNull(2)?.toFloatOrNull()
                if (i != null && x != null && y != null) flow.debugPlace(i, V2(x, y), f.getOrNull(3)?.toIntOrNull() ?: 0, f.getOrNull(4) == "f")
            }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> settings { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "page" -> Ui.page = when (a) { "rules" -> Page.RULES; "settings" -> Page.SETTINGS; "library" -> Page.LIBRARY; else -> Page.PLAY }
            "group" -> a.toIntOrNull()?.let { Ui.libraryGroup = it.coerceIn(0, Figures.GROUPS.size - 1); Ui.page = Page.LIBRARY }
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.settingsConfirm = SettingsConfirm.EXIT }
            "clearask" -> { Ui.page = Page.SETTINGS; Ui.settingsConfirm = SettingsConfirm.CLEAR }
            "reopen" -> graph.window.reopen()
            "closepanel" -> graph.window.closeControlOnly()
            "closevolume" -> graph.window.closeVolumeOnly()
            "reopenvolume" -> graph.window.reopenVolume()
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "ftest" -> SceneHolder.orientationTest = on
            // replay <holder> [src]: the real scene entities a ray lands on for that holder (holder, its deepest mesh
            // child) through the template's resolveUp, then the deepest one into the same listener.onTap
            "replay" -> {
                val scene = SceneHolder.scene ?: run { Log.w(TAG, "REPLAY not ready"); return }
                val src = InputSource.entries.firstOrNull { it.label == f.getOrNull(1) } ?: InputSource.HAND_RAY
                var last: String? = null
                val ents = scene.replayEntities(f.getOrNull(0).orEmpty())
                if (ents.isEmpty()) BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} no entity")
                for (e in ents) {
                    val (t, path) = resolveUp(e, BoardTargets())
                    BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} entity=${e.getName()} path=$path -> square=${BoardTargets.squareOf(t)}")
                    last = t
                }
                if (ents.isNotEmpty()) BoardListener(graph, scene).onTap(last, src, null)
            }
            "hover" -> SceneHolder.scene?.setHover(a.ifBlank { null }?.takeIf { it != "off" })
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState} volume=${graph.window.volumeState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"} containers=${Containers.BOARD}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}
