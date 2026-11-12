package com.kejing.tangramwood.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The phases of design states[] that live in the game (S12 / S14 / S15 / S17 are window states). */
enum class Phase(val id: String) {
    LOADING("S00"), PLAY("S02"), HELPING("S09"), SOLVED("S10"), REPLAY("S11"), RESTORE("S18"),
}

enum class Sound { PICK, DROP, SNAP, TURN, FLIP, BLOCK, SOLVED, HINT }

/** The status sentence's one-off notes (design content.lines); they show for [GameFlow.NOTICE_MS]. */
enum class Notice { NONE, PICK_FIRST, LOCKED, TURN_ME, BLOCKED, SNAPPED, HELPED, UNDID, RESTORED }

enum class ConfirmKind { RESET, SWITCH }

/** How a piece moves to where the state says it is (the scene interpolates from [from] to the current piece state). */
enum class MotionKind { FLY, SNAP, TURN, FLIP, SHAKE, HOME, SLOW_FLY }

data class Motion(val piece: Int, val kind: MotionKind, val from: PieceState, val startedAt: Long, val durationMs: Long)

/** S10 card. */
data class Result(val elapsedMs: Long, val moves: Int, val newBest: Boolean, val counted: Boolean, val helps: Int)

/** The one state every surface reads (scene, control window, title plate, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val figure: Figure = Figures.ALL[0],
    val pieces: List<PieceState> = GameFlow.homePieces(),
    val selected: Int? = null,
    val moves: Int = 0,
    val elapsedMs: Long = 0,
    /** Clock time the timer last started (null = stopped). */
    val runningSince: Long? = null,
    val usedHint: Boolean = false,
    val helps: Int = 0,
    val hintOn: Boolean = false,
    val undo: List<Snapshot> = emptyList(),
    /** The pieces + moves + undo depth when the current selection began (cancelled by undo). */
    val selectStart: Snapshot? = null,
    val selectUndoDepth: Int = 0,
    val motions: List<Motion> = emptyList(),
    val notice: Notice = Notice.NONE,
    val noticeAt: Long = 0,
    val noticeKind: Kind? = null,
    val confirm: ConfirmKind? = null,
    val switchTo: String? = null,
    val result: Result? = null,
    /** S11: how many pieces the replay has put in so far, and the kind just placed. */
    val replayStep: Int = 0,
    val replayKind: Kind? = null,
    val solvedAt: Long = 0,
    /** S01: the rules page opened on first launch; the coach lights the left big triangle until the first tap. */
    val welcome: Boolean = false,
    val coach: Boolean = false,
    /** Debug hold (S04 / S05 / S06 / S07 / S09 / S11 are < 3 s): motions stop this long after their start. */
    val freezeMs: Long? = null,
) {
    val isFree: Boolean get() = figure.isFree
    val placed: Int get() = pieces.count { it.snapped }
    val hasProgress: Boolean get() = moves > 0 || pieces.any { it.onBoard }
    val inputLocked: Boolean get() = phase in setOf(Phase.LOADING, Phase.HELPING, Phase.REPLAY, Phase.SOLVED, Phase.RESTORE) || confirm != null
    val canUndo: Boolean get() = !inputLocked && (selected != null || undo.isNotEmpty())
    val canHint: Boolean get() = !isFree && phase == Phase.PLAY && confirm == null
    val canHelp: Boolean get() = !isFree && phase == Phase.PLAY && confirm == null && placed < Pieces.COUNT
    fun elapsedAt(now: Long): Long = elapsedMs + (runningSince?.let { now - it } ?: 0L)
    fun motionOf(i: Int): Motion? = motions.lastOrNull { it.piece == i }

    /** Design state id for the debug dump and the screenshot list. */
    fun stateId(now: Long): String = when {
        confirm != null -> "S16"
        phase == Phase.LOADING -> "S00"
        phase == Phase.RESTORE -> "S18"
        phase == Phase.HELPING -> "S09"
        phase == Phase.REPLAY -> "S11"
        phase == Phase.SOLVED -> "S10"
        welcome -> "S01"
        notice == Notice.BLOCKED && now - noticeAt < GameFlow.NOTICE_MS -> "S06"
        notice == Notice.SNAPPED && now - noticeAt < GameFlow.SNAPPED_MS -> "S07"
        selected != null && motions.any { it.piece == selected && it.kind in setOf(MotionKind.TURN, MotionKind.FLIP) && now - it.startedAt < it.durationMs } -> "S04"
        motions.any { it.kind == MotionKind.FLY && now - it.startedAt < it.durationMs } -> "S05"
        selected != null -> "S03"
        isFree -> "S13"
        else -> "S02"
    }
}

/**
 * The middle layer (rules → state → render). Main-thread confined. The volume input, the panel buttons and the debug
 * broadcast all call these same functions (profile D29 / build.md「调试命令走同一条路径」).
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val FLY_MS = 300L
        const val SNAP_MS = 120L
        const val TURN_MS = 200L
        const val FLIP_MS = 400L
        const val SHAKE_MS = 150L
        const val HOME_MS = 300L
        const val SLOW_MS = 1200L
        const val NOTICE_MS = 2500L
        const val SNAPPED_MS = 1200L
        const val RISE_MS = 400L
        const val TINT_MS = 600L
        const val UNDO_MAX = 50

        fun homePieces(): List<PieceState> = Layout.HOMES.map { PieceState(Where.TRAY, Pose(V2.ZERO, it.rot8, false)) }
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value
    private var job: Job? = null
    /** Debug: `hold` keeps a selection / motion on screen. */
    var held = false

    private fun set(next: PlayState) { _state.value = next }
    private val reduced: Boolean get() = settings().reducedMotion
    private fun now() = clock()
    private fun dur(ms: Long) = if (reduced) minOf(ms, 120L) else ms

    // ------------------------------------------------------------------ lifecycle

    /** Cold start: restore the save (S18 / S13 / next figure) or start the first figure (S01 on first launch). */
    fun start() {
        val saved = store.load()
        val first = !settings().firstRunDone
        when {
            saved == null -> fresh(Figures.ALL[0], welcome = first)
            saved.solved -> fresh(nextAfter(saved.figureId))
            else -> {
                val fig = if (saved.figureId == Figure.FREE.id) Figure.FREE else Figures.byId(saved.figureId)!!
                set(PlayState(
                    phase = if (fig.isFree || saved.moves == 0) Phase.PLAY else Phase.RESTORE,
                    figure = fig, pieces = saved.pieces, moves = saved.moves, elapsedMs = saved.elapsedMs,
                    usedHint = saved.usedHint, helps = saved.helps, undo = saved.undo,
                    notice = if (fig.isFree) Notice.RESTORED else Notice.NONE, noticeAt = now(),
                ))
            }
        }
        log("FLOW start phase=${s.phase} figure=${s.figure.id} placed=${s.placed} moves=${s.moves}")
    }

    private fun fresh(figure: Figure, welcome: Boolean = false) {
        val keepHint = s.hintOn
        set(PlayState(phase = Phase.PLAY, figure = figure, hintOn = keepHint && !figure.isFree, welcome = welcome, coach = welcome && figure.id == Figures.ALL[0].id))
        save()
    }

    fun onBackground() {
        // design lifecycle: stop the clock and save (a piece in flight is already at its destination in the state)
        val st = s
        if (st.runningSince != null) set(st.copy(elapsedMs = st.elapsedAt(now()), runningSince = null))
        save()
    }

    fun onForeground() {
        val st = s
        if (st.runningSince == null && timerShouldRun(st)) set(st.copy(runningSince = now()))
    }

    private fun timerShouldRun(st: PlayState) = !st.isFree && st.phase == Phase.PLAY && (st.moves > 0 || st.selected != null || st.elapsedMs > 0)

    private fun save() {
        val st = s
        if (st.phase == Phase.LOADING) return
        store.save(SavedGame(st.figure.id, st.pieces, st.moves, st.elapsedAt(now()), st.usedHint, st.helps, st.phase == Phase.SOLVED || st.phase == Phase.REPLAY, st.undo.takeLast(UNDO_MAX)))
    }

    // ------------------------------------------------------------------ notices / sounds

    private fun PlayState.note(n: Notice, kind: Kind? = null) = copy(notice = n, noticeAt = now(), noticeKind = kind)
    private fun sound(x: Sound) = onSound(x)

    private fun motion(i: Int, kind: MotionKind, from: PieceState, ms: Long) = Motion(i, kind, from, now(), dur(ms))
    private fun PlayState.withMotion(m: Motion) = copy(motions = motions.filter { now() - it.startedAt < it.durationMs + 50 && !(it.piece == m.piece && m.kind != MotionKind.SHAKE && it.kind == MotionKind.SHAKE) } + m)

    private fun startTimer(st: PlayState): PlayState = if (!st.isFree && st.runningSince == null && st.phase == Phase.PLAY) st.copy(runningSince = now()) else st

    // ------------------------------------------------------------------ taps (volume, debug)

    /** A tap on piece [i] (design input.tapMain). */
    fun tapPiece(i: Int) {
        var st = s
        if (st.inputLocked) { log("TAP piece $i ignored (locked ${st.stateId(now())})"); return }
        if (st.welcome) welcomeDone()
        st = s
        val p = st.pieces[i]
        if (p.snapped) {
            // T18: a placed piece shakes and says so; it is not selected
            set(st.withMotion(motion(i, MotionKind.SHAKE, p, SHAKE_MS)).note(Notice.LOCKED)); sound(Sound.BLOCK); return
        }
        val sel = st.selected
        if (sel == i) { putDownInPlace(); return }
        if (sel != null) { putDownInPlace(); st = s }
        // select: remember the state before (undo cancels a selection), lift, tabs appear
        st = startTimer(st)
        set(st.copy(selected = i, selectStart = Snapshot(st.pieces, st.moves), selectUndoDepth = st.undo.size, coach = false, notice = Notice.NONE))
        sound(Sound.PICK)
        log("SELECT $i ${p.where}")
    }

    /** A tap on drop pad (pi, pj) of the board. */
    fun tapPad(pi: Int, pj: Int) {
        val st = s
        if (st.inputLocked) return
        val at = Rules.padPoint(pi, pj, st.figure)
        val sel = st.selected
        if (sel == null) {
            // a pad under a placed piece answers like the piece (its collider is off once it snaps)
            val under = st.pieces.indices.firstOrNull { st.pieces[it].snapped && Poly.contains(Rules.outline(it, st.pieces[it]), at) }
            if (under != null) { tapPiece(under); return }
            set(st.note(Notice.PICK_FIRST)); return
        }
        dropAt(sel, if (st.isFree) at else at, fromPad = true)
    }

    /** A tap on the tray: the selected piece goes home (T8). */
    fun tapTray() {
        val st = s
        if (st.inputLocked) return
        val sel = st.selected ?: run { set(st.note(Notice.PICK_FIRST)); return }
        val p = st.pieces[sel]
        if (!p.onBoard) {
            // still in the tray: put the preview turn back, no step
            set(st.copy(pieces = st.pieces.replace(sel, home(sel)), selected = null, selectStart = null).withMotion(motion(sel, MotionKind.TURN, p, TURN_MS)))
            sound(Sound.DROP); save(); return
        }
        val before = Snapshot(st.pieces, st.moves)
        val pieces = st.pieces.replace(sel, home(sel))
        set(st.copy(pieces = pieces, selected = null, selectStart = null, moves = st.moves + 1, undo = (st.undo + before).takeLast(UNDO_MAX)).withMotion(motion(sel, MotionKind.FLY, p, FLY_MS)))
        sound(Sound.DROP); save()
        log("TRAY $sel home moves=${s.moves}")
    }

    /** A tap on tab [k] of the selected piece: 0 = turn left (+45°), 1 = turn right (−45°), 2 = flip (parallelogram). */
    fun tapTab(k: Int) {
        val st = s
        if (st.inputLocked) return
        val sel = st.selected ?: return
        when (k) { 0 -> turn(+1); 1 -> turn(-1); 2 -> if (Pieces.kind(sel).canFlip) flip() }
    }

    /** T3: turn the selected piece by [d] eighths; clicks during the animation add up (D43). */
    fun turn(d: Int) {
        val st = s
        val sel = st.selected ?: return
        val p = st.pieces[sel]
        val q = p.copy(pose = p.pose.copy(rot8 = Math.floorMod(p.pose.rot8 + d, 8)))
        set(st.copy(pieces = st.pieces.replace(sel, q)).withMotion(motion(sel, MotionKind.TURN, p, TURN_MS)))
        sound(Sound.TURN)
        afterTurn(sel)
    }

    /** T4: flip the parallelogram. */
    fun flip() {
        val st = s
        val sel = st.selected ?: return
        if (!Pieces.kind(sel).canFlip) return
        val p = st.pieces[sel]
        val q = p.copy(pose = p.pose.copy(flipped = !p.pose.flipped))
        set(st.copy(pieces = st.pieces.replace(sel, q)).withMotion(motion(sel, MotionKind.FLIP, p, FLIP_MS)))
        sound(Sound.FLIP)
        afterTurn(sel)
    }

    /** On the board, a turn that now fits a reachable slot snaps it in right away (S04 → S07, same step). */
    private fun afterTurn(sel: Int) {
        val st = s
        val p = st.pieces[sel]
        if (!p.onBoard || st.isFree) return
        val near = Rules.reachableSlots(st.figure, st.pieces, Pieces.kind(sel), p.pose.centre, reachU = 0.05f)
        val fit = near.firstOrNull { sIdx -> st.figure.slots[sIdx].pose.let { Pieces.kind(sel).sameOrientation(it.rot8, it.flipped, p.pose.rot8, p.pose.flipped) } }
            ?: return
        scope.launch {
            delay(dur(if (Pieces.kind(sel).canFlip) FLIP_MS else TURN_MS))
            val now = s
            if (now.selected != sel || now.pieces[sel] != p) return@launch
            val landing = Rules.land(now.figure, now.pieces, sel, p.pose.centre, p.pose.rot8, p.pose.flipped, p.pose.centre)
            if (landing is Landing.Snapped && landing.slot == fit) applySnap(sel, landing, countStep = false)
        }
    }

    /** Drop the selected piece at [at] (puzzle u): fly, then land by the rules. */
    private fun dropAt(sel: Int, at: V2, fromPad: Boolean) {
        val st = s
        val p = st.pieces[sel]
        val kind = Pieces.kind(sel)
        val from = if (p.onBoard) p.pose.centre else Rules.entryFor(st.figure, at)
        var target = at
        if (st.isFree) target = Rules.latticeAlign(kind, Pose(at, p.pose.rot8, p.pose.flipped)).centre
        val landing = Rules.land(st.figure, st.pieces, sel, target, p.pose.rot8, p.pose.flipped, from)
        val before = Snapshot(st.pieces, st.moves)
        val base = st.copy(moves = st.moves + 1, undo = (st.undo + before).takeLast(UNDO_MAX))
        log("DROP $sel at=$at -> ${landing::class.simpleName}")
        when (landing) {
            is Landing.Snapped -> { set(base); applySnap(sel, landing, countStep = false, from = p) }
            is Landing.TurnMe -> {
                val q = PieceState(Where.BOARD, landing.pose)
                set(base.copy(pieces = base.pieces.replace(sel, q)).withMotion(motion(sel, MotionKind.FLY, p, FLY_MS)).note(Notice.TURN_ME))
                sound(Sound.DROP)
            }
            is Landing.Blocked -> {
                val q = PieceState(Where.BOARD, landing.pose)
                set(base.copy(pieces = base.pieces.replace(sel, q), selected = null, selectStart = null).withMotion(motion(sel, MotionKind.FLY, p, FLY_MS)).note(Notice.BLOCKED))
                scope.launch { delay(dur(FLY_MS)); val n = s; set(n.withMotion(motion(sel, MotionKind.SHAKE, n.pieces[sel], SHAKE_MS))); sound(Sound.BLOCK) }
            }
            Landing.BackToTray -> {
                set(base.copy(pieces = base.pieces.replace(sel, home(sel)), selected = null, selectStart = null).withMotion(motion(sel, MotionKind.FLY, p, FLY_MS)).note(Notice.BLOCKED))
                sound(Sound.BLOCK)
            }
            is Landing.Floating -> {
                set(base.copy(pieces = base.pieces.replace(sel, PieceState(Where.BOARD, landing.pose)), selected = null, selectStart = null).withMotion(motion(sel, MotionKind.FLY, p, FLY_MS)))
                sound(Sound.DROP)
            }
        }
        save()
    }

    private fun applySnap(sel: Int, landing: Landing.Snapped, countStep: Boolean, from: PieceState? = null, slow: Boolean = false) {
        val st = s
        val p = from ?: st.pieces[sel]
        var pieces = st.pieces.replace(sel, PieceState(Where.BOARD, landing.pose, landing.slot))
        var next = st
        for (j in landing.bounced) {
            next = next.withMotion(motion(j, MotionKind.HOME, pieces[j], HOME_MS))
            pieces = pieces.replace(j, home(j))
        }
        val kind = if (slow) MotionKind.SLOW_FLY else if (p.onBoard && p.pose.centre.dist(landing.pose.centre) < 0.1f) MotionKind.SNAP else MotionKind.FLY
        val ms = when (kind) { MotionKind.SLOW_FLY -> SLOW_MS; MotionKind.SNAP -> SNAP_MS; else -> FLY_MS }
        next = next.copy(pieces = pieces, selected = null, selectStart = null, moves = next.moves + if (countStep) 1 else 0)
            .withMotion(motion(sel, kind, p, ms))
        if (st.phase != Phase.HELPING) next = next.note(Notice.SNAPPED)
        set(next)
        scope.launch { delay(dur(ms)); sound(Sound.SNAP) }
        log("SNAP $sel slot=${landing.slot} placed=${next.placed}")
        if (Rules.solved(next.figure, next.pieces)) scope.launch { delay(dur(ms) + 60); solve() }
        save()
    }

    /** Put the selected piece down where it is (deselect / switch / before help / reset). */
    private fun putDownInPlace() {
        val st = s
        val sel = st.selected ?: return
        val p = st.pieces[sel]
        if (!p.onBoard) {
            // a tray preview turn is dropped (design gameSpec.rotation)
            set(st.copy(pieces = st.pieces.replace(sel, home(sel)), selected = null, selectStart = null).withMotion(motion(sel, MotionKind.TURN, p, TURN_MS)))
            return
        }
        if (Rules.overlaps(st.pieces, sel, p.pose.outline(Pieces.kind(sel))).isEmpty()) {
            set(st.copy(selected = null, selectStart = null)); sound(Sound.DROP); save(); return
        }
        // overlapping where it is: undo this selection's turns (back to the turn it had when picked), shake, click
        val startPose = st.selectStart?.pieces?.get(sel)?.pose
        val back = p.copy(pose = p.pose.copy(rot8 = startPose?.rot8 ?: p.pose.rot8, flipped = startPose?.flipped ?: p.pose.flipped))
        val q = if (Rules.overlaps(st.pieces, sel, back.pose.outline(Pieces.kind(sel))).isEmpty()) back else home(sel)
        set(st.copy(pieces = st.pieces.replace(sel, q), selected = null, selectStart = null).withMotion(motion(sel, if (q.onBoard) MotionKind.SHAKE else MotionKind.HOME, p, if (q.onBoard) SHAKE_MS else HOME_MS)).note(Notice.BLOCKED))
        sound(Sound.BLOCK); save()
    }

    // ------------------------------------------------------------------ drag (T9, experienced players; board pieces)

    private var dragPiece: Int? = null
    private var dragStart: V2 = V2.ZERO

    /** Start dragging piece [i] if it floats on the board; returns false (the press stays a tap) otherwise. */
    fun dragStart(i: Int): Boolean {
        val st = s
        if (st.inputLocked) return false
        val p = st.pieces[i]
        if (!p.onBoard || p.snapped) return false
        if (st.selected != i) tapPiece(i)
        dragPiece = i
        dragStart = s.pieces[i].pose.centre
        return true
    }

    /** [dx] / [dy]: the drag in board metres (x right, y away from the user). */
    fun dragMove(dx: Float, dy: Float) {
        val i = dragPiece ?: return
        val st = s
        val p = st.pieces[i]
        val c = dragStart + V2(dx / Layout.U, dy / Layout.U)
        set(st.copy(pieces = st.pieces.replace(i, p.copy(pose = Rules.clampToBoard(Pieces.kind(i), p.pose.copy(centre = c), st.figure)))))
    }

    fun dragEnd() {
        val i = dragPiece ?: return
        dragPiece = null
        val st = s
        // put it down by the same rules as a tap on that spot (coming from where the drag began)
        val p = st.pieces[i]
        val restore = st.pieces.replace(i, p.copy(pose = p.pose.copy(centre = dragStart)))
        set(st.copy(pieces = restore))
        dropAt(i, p.pose.centre, fromPad = false)
    }

    fun dragCancel() {
        val i = dragPiece ?: return
        dragPiece = null
        val st = s
        val p = st.pieces[i]
        set(st.copy(pieces = st.pieces.replace(i, p.copy(pose = p.pose.copy(centre = dragStart)))))
    }

    // ------------------------------------------------------------------ panel buttons

    /** T12 (design gameSpec.moveCount): a selection is cancelled; otherwise the last put-down is taken back. */
    fun undo() {
        val st = s
        if (!st.canUndo) return
        val start = st.selectStart
        if (st.selected != null && start != null) {
            val sel = st.selected
            set(st.copy(pieces = start.pieces, moves = start.moves, undo = st.undo.take(st.selectUndoDepth), selected = null, selectStart = null)
                .withMotion(motion(sel, MotionKind.FLY, st.pieces[sel], FLY_MS)).note(Notice.UNDID))
            sound(Sound.DROP); save(); log("UNDO selection $sel"); return
        }
        val last = st.undo.lastOrNull() ?: return
        var next = st.copy(pieces = last.pieces, moves = last.moves, undo = st.undo.dropLast(1)).note(Notice.UNDID)
        for (j in st.pieces.indices) if (st.pieces[j] != last.pieces[j]) next = next.withMotion(motion(j, MotionKind.FLY, st.pieces[j], FLY_MS))
        set(next); sound(Sound.DROP); save()
        log("UNDO step moves=${next.moves}")
    }

    /** T10: the hint is a switch (D19); its state is kept across states and figures until pressed again. */
    fun setHint(on: Boolean) {
        val st = s
        if (on && !st.canHint) return
        set(st.copy(hintOn = on, usedHint = st.usedHint || (on && !st.isFree)))
        if (on) sound(Sound.HINT)
        log("HINT $on")
    }

    /** T11: the biggest piece not yet placed glides into its slot in 1.2 s; one step, one help. */
    fun help() {
        if (!s.canHelp) return
        if (s.selected != null) cancelSelection()
        val st = startTimer(s)
        val fig = st.figure
        val slot = fig.replayOrder.firstOrNull { !Rules.occupied(st.pieces, it) } ?: return
        val kind = fig.slots[slot].kind
        // a piece of that kind that is not placed (prefer one floating on the board, then the tray)
        val i = st.pieces.indices.filter { Pieces.kind(it) == kind && !st.pieces[it].snapped }.minByOrNull { if (st.pieces[it].onBoard) 0 else 1 } ?: return
        val before = Snapshot(st.pieces, st.moves)
        val slotArea = Poly.area(fig.slots[slot].verts)
        val bounced = st.pieces.indices.filter { j -> j != i && st.pieces[j].onBoard && !st.pieces[j].snapped && Rules.overlapping(fig.slots[slot].verts, slotArea, Rules.outline(j, st.pieces[j]), Pieces.kind(j).area) }
        set(st.copy(phase = Phase.HELPING, moves = st.moves + 1, helps = st.helps + 1, undo = (st.undo + before).takeLast(UNDO_MAX)).note(Notice.HELPED, kind))
        applySnap(i, Landing.Snapped(slot, fig.slots[slot].pose, bounced), countStep = false, slow = true)
        scope.launch {
            delay(dur(SLOW_MS) + 40)
            val n = s
            if (n.phase == Phase.HELPING) set(n.copy(phase = Phase.PLAY, notice = Notice.HELPED, noticeAt = now(), noticeKind = kind))
        }
        log("HELP piece=$i slot=$slot kind=$kind")
    }

    private fun cancelSelection() {
        val st = s
        val start = st.selectStart ?: run { set(st.copy(selected = null)); return }
        set(st.copy(pieces = start.pieces, moves = start.moves, undo = st.undo.take(st.selectUndoDepth), selected = null, selectStart = null))
    }

    /** T16: [重摆] asks first when there is progress. */
    fun requestReset() {
        val st = s
        if (st.inputLocked && st.phase != Phase.SOLVED) return
        if (st.selected != null) cancelSelection()
        if (s.hasProgress && s.phase != Phase.SOLVED) set(s.copy(confirm = ConfirmKind.RESET)) else again()
    }

    /** T15: a figure from the library (or free play); asks first when the current one has progress (D44). */
    fun pickFigure(id: String) {
        val st = s
        if (st.phase == Phase.HELPING || st.phase == Phase.REPLAY || st.phase == Phase.LOADING) return
        if (st.selected != null) cancelSelection()
        if (s.hasProgress && s.phase != Phase.SOLVED && s.phase != Phase.RESTORE && id != s.figure.id) { set(s.copy(confirm = ConfirmKind.SWITCH, switchTo = id)); return }
        switchNow(id)
    }

    private fun switchNow(id: String) {
        val fig = if (id == Figure.FREE.id) Figure.FREE else Figures.byId(id) ?: return
        fresh(fig)
        log("FIGURE ${fig.id}")
    }

    fun confirm() {
        val st = s
        when (st.confirm) {
            ConfirmKind.RESET -> { set(st.copy(confirm = null)); again() }
            ConfirmKind.SWITCH -> { set(st.copy(confirm = null)); st.switchTo?.let { switchNow(it) } }
            null -> Unit
        }
    }

    fun cancelConfirm() { set(s.copy(confirm = null, switchTo = null)) }

    /** S10 [再拼一次] / reset: the pieces fly home, the same figure starts again. */
    fun again() {
        val st = s
        job?.cancel()
        var next = PlayState(phase = Phase.PLAY, figure = st.figure, hintOn = st.hintOn && !st.figure.isFree)
        for (j in st.pieces.indices) if (st.pieces[j] != next.pieces[j]) next = next.withMotion(motion(j, MotionKind.HOME, st.pieces[j], HOME_MS))
        set(next); save()
        log("AGAIN ${st.figure.id}")
    }

    /** S10 [下一题]: the next in the group; after a group's last, the next group's first unsolved; after the 15th, the first unsolved. */
    fun next() { job?.cancel(); fresh(nextAfter(s.figure.id)) }

    fun nextAfter(id: String): Figure {
        val all = Figures.ALL
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return all[0]
        val cur = all[i]
        val inGroup = all.filter { it.group == cur.group }
        val pos = inGroup.indexOf(cur)
        if (pos < inGroup.size - 1) return inGroup[pos + 1]
        val solved = settings().records.filterValues { it.solved }.keys
        val gIndex = Figures.GROUPS.indexOfFirst { it.id == cur.group }
        val nextGroup = Figures.GROUPS.getOrNull(gIndex + 1)
        if (nextGroup != null) {
            val g = all.filter { it.group == nextGroup.id }
            return g.firstOrNull { it.id !in solved } ?: g.first()
        }
        return all.firstOrNull { it.id !in solved && it.id != id } ?: all[0]
    }

    /** S18 [接着拼]. */
    fun resume() {
        val st = s
        if (st.phase != Phase.RESTORE) return
        set(st.copy(phase = Phase.PLAY, runningSince = now()))
    }

    /** S18 [换一道]: the game stays as it is; the library page opens (the panel switches page). */
    fun pickOther() {
        val st = s
        if (st.phase != Phase.RESTORE) return
        set(st.copy(phase = Phase.PLAY))
    }

    fun welcomeDone() {
        val st = s
        if (!st.welcome) return
        set(st.copy(welcome = false))
        onSettings(settings().copy(firstRunDone = true))
    }

    // ------------------------------------------------------------------ solved / replay

    private fun solve() {
        val st = s
        if (st.phase == Phase.SOLVED || st.phase == Phase.REPLAY) return
        val elapsed = st.elapsedAt(now())
        val counted = !st.usedHint && st.helps == 0
        val rec = settings().record(st.figure.id)
        val newBest = counted && (rec.bestMs == null || elapsed < rec.bestMs)
        val updated = Record(
            solved = true,
            bestMs = if (counted) minOf(rec.bestMs ?: Long.MAX_VALUE, elapsed) else rec.bestMs,
            bestMoves = if (counted) minOf(rec.bestMoves ?: Int.MAX_VALUE, st.moves) else rec.bestMoves,
        )
        onSettings(settings().copy(records = settings().records + (st.figure.id to updated)))
        set(st.copy(phase = Phase.SOLVED, elapsedMs = elapsed, runningSince = null, selected = null, solvedAt = now(),
            result = Result(elapsed, st.moves, newBest, counted, st.helps)))
        sound(Sound.SOLVED); save()
        log("SOLVED ${st.figure.id} ${elapsed}ms moves=${st.moves} best=$newBest counted=$counted")
    }

    /** T14: pieces home, then one by one into their slots (1.2 s each, design replayOrder), a reason per kind. */
    fun replay() {
        val st = s
        if (st.phase != Phase.SOLVED) return
        job?.cancel()
        var start = st.copy(phase = Phase.REPLAY, replayStep = 0, replayKind = null)
        for (j in st.pieces.indices) start = start.withMotion(motion(j, MotionKind.HOME, st.pieces[j], HOME_MS)).copy(pieces = start.pieces.replace(j, home(j)))
        set(start)
        job = scope.launch {
            delay(dur(HOME_MS) + 200)
            val order = st.figure.replayOrder
            val used = HashSet<Int>()
            for ((n, slot) in order.withIndex()) {
                val cur = s
                if (cur.phase != Phase.REPLAY) return@launch
                val kind = st.figure.slots[slot].kind
                val i = cur.pieces.indices.first { Pieces.kind(it) == kind && it !in used && !cur.pieces[it].snapped }
                used += i
                val from = cur.pieces[i]
                set(cur.copy(pieces = cur.pieces.replace(i, PieceState(Where.BOARD, st.figure.slots[slot].pose, slot)), replayStep = n + 1, replayKind = kind)
                    .withMotion(motion(i, MotionKind.SLOW_FLY, from, SLOW_MS)))
                delay(dur(SLOW_MS))
                sound(Sound.SNAP)
                delay(if (reduced) 200 else 450)
            }
            finishReplay()
        }
    }

    /** S11 [跳过] / the end of the replay: every piece in its slot, back to S10. */
    fun finishReplay() {
        val st = s
        if (st.phase != Phase.REPLAY) return
        job?.cancel()
        val pieces = MutableList(Pieces.COUNT) { st.pieces[it] }
        val used = HashSet<Int>()
        for (slot in st.figure.slots.indices) {
            val kind = st.figure.slots[slot].kind
            val i = pieces.indices.first { Pieces.kind(it) == kind && it !in used }
            used += i
            pieces[i] = PieceState(Where.BOARD, st.figure.slots[slot].pose, slot)
        }
        set(st.copy(phase = Phase.SOLVED, pieces = pieces, replayStep = Pieces.COUNT, solvedAt = now() - RISE_MS - TINT_MS))
    }

    // ------------------------------------------------------------------ debug helpers (same paths)

    fun freeze(ms: Long?) { set(s.copy(freezeMs = ms)) }

    /** Debug: a fresh figure (S01 with [welcome]). */
    fun debugFresh(id: String, welcome: Boolean = false) {
        job?.cancel()
        val fig = if (id == Figure.FREE.id) Figure.FREE else Figures.byId(id) ?: Figures.ALL[0]
        set(s.copy(freezeMs = null, confirm = null, selected = null, selectStart = null))
        fresh(fig, welcome)
    }

    /** Debug: run the cold-start path again on the saved game (S18 shots without killing the process). */
    fun debugRestart() { job?.cancel(); save(); set(PlayState()); start() }

    /** Debug `solve [ms]`: put every remaining piece into its slot through the real tap path (tap piece, turn, tap pad). */
    fun debugSolve(stepMs: Long = 300L) {
        job?.cancel()
        job = scope.launch {
            val fig = s.figure
            for (slot in fig.replayOrder) {
                val st = s
                if (st.phase != Phase.PLAY || Rules.occupied(st.pieces, slot)) continue
                val kind = fig.slots[slot].kind
                val i = st.pieces.indices.firstOrNull { Pieces.kind(it) == kind && !st.pieces[it].snapped } ?: continue
                tapPiece(i); delay(60)
                val want = fig.slots[slot].pose
                var guard = 0
                while (!Pieces.kind(i).sameOrientation(s.pieces[i].pose.rot8, s.pieces[i].pose.flipped, want.rot8, want.flipped) && guard++ < 10) {
                    if (Pieces.kind(i).canFlip && s.pieces[i].pose.flipped != want.flipped) flip() else turn(+1)
                    delay(40)
                }
                val pad = bestPadFor(fig, slot)
                if (s.selected != i) tapPiece(i)
                tapPad(pad.first, pad.second)
                delay(stepMs)
            }
        }
    }

    fun stopJobs() { job?.cancel() }

    /** The pad a player would tap for [slot]: inside it, else the nearest (debug solve, hit-replay tests). */
    fun bestPadFor(fig: Figure, slot: Int): Pair<Int, Int> {
        val sl = fig.slots[slot]
        return Layout.PADS.minByOrNull { (i, j) -> val p = Rules.padPoint(i, j, fig); if (Poly.contains(sl.verts, p)) -1f else p.dist(sl.centroid) }!!
    }

    /** Debug `place "<piece> <x> <y> <rot8> [flip]"`: a piece put straight on the board (screenshot set-ups). */
    fun debugPlace(i: Int, at: V2, rot8: Int, flipped: Boolean) {
        val st = s
        set(st.copy(pieces = st.pieces.replace(i, PieceState(Where.BOARD, Pose(at, rot8, flipped))), selected = null, selectStart = null))
    }

    /** Debug: the solved state straight away (S10 shots), through the same solve(). */
    fun debugFillAndSolve() {
        val st = s
        if (st.isFree) return
        val pieces = MutableList(Pieces.COUNT) { st.pieces[it] }
        val used = HashSet<Int>()
        for (slot in st.figure.slots.indices) {
            val kind = st.figure.slots[slot].kind
            val i = pieces.indices.first { Pieces.kind(it) == kind && it !in used }
            used += i
            pieces[i] = PieceState(Where.BOARD, st.figure.slots[slot].pose, slot)
        }
        // a plausible run for the result-card screenshots (design example 「1:42，9 步」), through the same solve()
        set(st.copy(pieces = pieces, moves = maxOf(st.moves, 9), elapsedMs = maxOf(st.elapsedMs, 102_000L), runningSince = null, selected = null, selectStart = null))
        solve()
    }

    /** Debug: the widest readout (design densityCap 「Placed 7/7 · 12:48 · 116 moves · Best 9:59」) for the overflow scan. */
    fun debugStress() {
        val st = s
        set(st.copy(moves = 116, elapsedMs = 768_000L, runningSince = null))
        onSettings(settings().copy(records = settings().records + (st.figure.id to Record(true, 599_000L, 30))))
    }

    fun dump(): String {
        val st = s
        return "state=${st.stateId(now())} phase=${st.phase} figure=${st.figure.id} placed=${st.placed}/7 moves=${st.moves} t=${st.elapsedAt(now())} " +
            "sel=${st.selected} hint=${st.hintOn} helps=${st.helps} undo=${st.undo.size} confirm=${st.confirm} notice=${st.notice} " +
            "pieces=" + st.pieces.mapIndexed { i, p -> "$i:${p.where.name[0]}${p.pose.centre}r${p.pose.rot8}${if (p.pose.flipped) "F" else ""}${p.slot?.let { "#$it" } ?: ""}" }.joinToString(" ")
    }

    private fun home(i: Int) = PieceState(Where.TRAY, Pose(V2.ZERO, Layout.HOMES[i].rot8, false))
}

internal fun <T> List<T>.replace(i: Int, v: T): List<T> = toMutableList().also { it[i] = v }
