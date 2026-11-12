package io.embodit.tangramwood.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A GameFlow on a test clock with an in-memory store (no Android). */
@OptIn(ExperimentalCoroutinesApi::class)
class GameFlowHarness(val scope: TestScope = TestScope()) {
    var settings = Settings(firstRunDone = true)
    var saved: SavedGame? = null
    val sounds = ArrayList<Sound>()
    val store = object : GameStore {
        override fun load() = saved
        override fun save(game: SavedGame) { saved = game }
        override fun clear() { saved = null }
    }
    val flow = GameFlow(scope, { scope.testScheduler.currentTime }, store, { settings }, { settings = it }, { sounds += it })

    val st: PlayState get() = flow.state.value

    /** The real tap path a player takes for [slot]: tap a piece of its kind, turn with the tabs, tap the pad. */
    fun place(slot: Int) {
        val f = st.figure
        val kind = f.slots[slot].kind
        val i = st.pieces.indices.first { Pieces.kind(it) == kind && !st.pieces[it].snapped }
        flow.tapPiece(i)
        val want = f.slots[slot].pose
        var guard = 0
        while (!kind.sameOrientation(st.pieces[i].pose.rot8, st.pieces[i].pose.flipped, want.rot8, want.flipped) && guard++ < 10) {
            if (kind.canFlip && st.pieces[i].pose.flipped != want.flipped) flow.tapTab(2) else flow.tapTab(0)
        }
        val pad = flow.bestPadFor(f, slot)
        flow.tapPad(pad.first, pad.second)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    @Test fun firstLaunchWelcomesThenEveryFigureSolvesThroughTheTapPath() = runTest {
        val h = GameFlowHarness(this)
        h.settings = Settings()
        h.flow.start()
        assertTrue(h.st.welcome); assertTrue(h.st.coach)
        for (f in Figures.ALL) {
            h.flow.debugFresh(f.id)
            for (slot in f.replayOrder) { h.place(slot); advanceUntilIdle() }
            assertEquals(f.id, Phase.SOLVED, h.st.phase)
            assertEquals(7, h.st.placed)
            assertEquals(7, h.st.moves)
            assertTrue(h.settings.record(f.id).solved)
        }
        assertTrue(Sound.SOLVED in h.sounds)
    }

    @Test fun undoFollowsT12() = runTest {
        // T12: put A down, turn B then put B down, pick C and turn it without putting it down;
        // undo once = C back, no step taken back; twice more = back to the start, moves 0
        val h = GameFlowHarness(this); h.flow.start(); advanceUntilIdle()
        val pad = { i: Int, j: Int -> h.flow.tapPad(i, j); advanceUntilIdle() }
        h.flow.tapPiece(5); pad(0, 0)
        h.flow.tapPiece(3); h.flow.tapTab(0); pad(4, 0)
        assertEquals(2, h.st.moves)
        h.flow.tapPiece(6); h.flow.tapTab(1)
        h.flow.undo()
        assertNull(h.st.selected); assertEquals(2, h.st.moves)
        assertEquals(Layout.HOMES[6].rot8, h.st.pieces[6].pose.rot8)
        h.flow.undo(); h.flow.undo()
        assertEquals(0, h.st.moves)
        assertEquals(GameFlow.homePieces(), h.st.pieces)
        assertFalse(h.st.canUndo)
    }

    @Test fun wrongTurnHoversThenATabTurnSnapsWithoutAnExtraStep() = runTest {
        val h = GameFlowHarness(this); h.flow.start(); advanceUntilIdle()
        val f = h.st.figure
        val s = f.slots.indices.first { f.slots[it].kind == Kind.SQ }
        val i = h.st.pieces.indices.first { Pieces.kind(it) == Kind.SQ }
        h.flow.tapPiece(i)
        if (Kind.SQ.sameOrientation(h.st.pieces[i].pose.rot8, false, f.slots[s].pose.rot8, false)) h.flow.tapTab(0)
        val pad = h.flow.bestPadFor(f, s)
        h.flow.tapPad(pad.first, pad.second); advanceUntilIdle()
        assertEquals(Notice.TURN_ME, h.st.notice)
        assertEquals(i, h.st.selected)
        assertEquals(1, h.st.moves)
        h.flow.tapTab(0); advanceUntilIdle()
        assertEquals(s, h.st.pieces[i].slot)
        assertEquals(1, h.st.moves)
    }

    @Test fun aPlacedPieceIsLockedAndSaysSo() = runTest {
        val h = GameFlowHarness(this); h.flow.start(); advanceUntilIdle()
        val f = h.st.figure
        val s = f.replayOrder.first()
        h.place(s); advanceUntilIdle()
        val i = h.st.pieces.indexOfFirst { it.slot == s }
        h.flow.tapPiece(i)
        assertNull(h.st.selected); assertEquals(Notice.LOCKED, h.st.notice)
        // a pad under it answers the same way when nothing is selected
        val pad = Layout.PADS.firstOrNull { (a, b) -> Poly.contains(h.st.pieces[i].pose.outline(Pieces.kind(i)), Rules.padPoint(a, b, f)) }
        if (pad != null) { h.flow.tapPad(pad.first, pad.second); assertEquals(Notice.LOCKED, h.st.notice) }
    }

    @Test fun helpPlacesTheBiggestPieceAndNothingCountsAsBest() = runTest {
        val h = GameFlowHarness(this); h.flow.start(); advanceUntilIdle()
        h.flow.help(); advanceUntilIdle()
        assertEquals(1, h.st.placed); assertEquals(1, h.st.helps); assertEquals(1, h.st.moves)
        assertEquals(Kind.L, Pieces.kind(h.st.pieces.indexOfFirst { it.snapped }))
        val f = h.st.figure
        for (slot in f.replayOrder) if (!Rules.occupied(h.st.pieces, slot)) { h.place(slot); advanceUntilIdle() }
        assertEquals(Phase.SOLVED, h.st.phase)
        assertFalse(h.st.result!!.counted)
        assertNull(h.settings.record(f.id).bestMs)
        // the panel's rest: no undo / hint / help on the result card
        assertFalse(h.st.canUndo); assertFalse(h.st.canHelp); assertFalse(h.st.canHint)
    }

    @Test fun hintIsASwitchKeptAcrossFiguresAndMarksTheGame() = runTest {
        val h = GameFlowHarness(this); h.flow.start(); advanceUntilIdle()
        h.flow.setHint(true)
        assertTrue(h.st.hintOn); assertTrue(h.st.usedHint)
        h.flow.next(); advanceUntilIdle()
        assertTrue(h.st.hintOn)
        h.flow.setHint(false); assertFalse(h.st.hintOn)
    }

    @Test fun freePlayNeverSnapsAndCountsMoves() = runTest {
        val h = GameFlowHarness(this); h.flow.start(); advanceUntilIdle()
        h.flow.pickFigure(Figure.FREE.id)
        assertTrue(h.st.isFree)
        h.flow.tapPiece(0); h.flow.tapPad(2, 2); advanceUntilIdle()
        assertTrue(h.st.pieces[0].onBoard); assertNull(h.st.pieces[0].slot)
        assertEquals(1, h.st.moves); assertFalse(h.st.canHint); assertFalse(h.st.canHelp)
        h.flow.setHint(true); assertFalse(h.st.hintOn)
    }

    @Test fun switchingAwayFromProgressAsksFirst() = runTest {
        val h = GameFlowHarness(this); h.flow.start(); advanceUntilIdle()
        h.flow.tapPiece(0); h.flow.tapPad(0, 0); advanceUntilIdle()
        h.flow.pickFigure("cat")
        assertEquals(ConfirmKind.SWITCH, h.st.confirm); assertEquals("house", h.st.figure.id)
        h.flow.cancelConfirm(); assertNull(h.st.confirm)
        h.flow.requestReset(); assertEquals(ConfirmKind.RESET, h.st.confirm)
        h.flow.confirm(); advanceUntilIdle()
        assertEquals(0, h.st.moves); assertEquals(GameFlow.homePieces(), h.st.pieces)
        h.flow.pickFigure("cat"); assertEquals("cat", h.st.figure.id)
    }

    @Test fun aSavedGameAsksToKeepGoing() = runTest {
        val h = GameFlowHarness(this); h.flow.start(); advanceUntilIdle()
        h.place(h.st.figure.replayOrder.first()); advanceUntilIdle()
        val saved = h.saved
        assertNotNull(saved)
        val h2 = GameFlowHarness(this); h2.saved = saved
        h2.flow.start()
        assertEquals(Phase.RESTORE, h2.st.phase); assertEquals(1, h2.st.placed)
        assertTrue(h2.st.inputLocked)
        h2.flow.resume(); assertEquals(Phase.PLAY, h2.st.phase)
    }

    @Test fun aSolvedSaveGoesToTheNextFigure() = runTest {
        val h = GameFlowHarness(this)
        h.saved = SavedGame("pine", GameFlow.homePieces(), 7, 1000, false, 0, true, emptyList())
        h.flow.start()
        assertEquals("cat", h.st.figure.id) // the warm-up group's last → the next group's first unsolved
        assertEquals(Phase.PLAY, h.st.phase)
    }

    @Test fun nextFollowsTheGroupOrder() {
        val h = GameFlowHarness()
        assertEquals("sailboat", h.flow.nextAfter("house").id)
        assertEquals("cat", h.flow.nextAfter("pine").id)
        h.settings = h.settings.copy(records = mapOf("square" to Record(true)))
        assertEquals("triangle", h.flow.nextAfter("swan").id)
        assertEquals("house", h.flow.nextAfter("parallelogram").id)
    }

    @Test fun replayPutsEveryPieceBackInItsSlot() = runTest {
        val h = GameFlowHarness(this); h.flow.start(); advanceUntilIdle()
        h.flow.debugFillAndSolve(); advanceUntilIdle()
        h.flow.replay()
        assertEquals(Phase.REPLAY, h.st.phase)
        advanceUntilIdle()
        assertEquals(Phase.SOLVED, h.st.phase); assertEquals(7, h.st.placed)
    }
}
