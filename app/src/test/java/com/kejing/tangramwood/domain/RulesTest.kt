package com.kejing.tangramwood.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design content.figureEncoding checks, T5 (snap), T7 (blocked, SAT) and gameSpec.landingOrder. */
class RulesTest {
    private val home = (0 until Pieces.COUNT).map { PieceState(Where.TRAY, Pose(V2.ZERO, Layout.HOMES[it].rot8, false)) }

    @Test fun everyFigureImportsCleanly() {
        assertEquals(15, Figures.ALL.size)
        assertEquals(listOf(5, 5, 5), Figures.GROUPS.map { g -> Figures.ALL.count { it.group == g.id } })
        for (f in Figures.ALL) {
            assertEquals(f.id, Pieces.KINDS.sorted(), f.slots.map { it.kind }.sorted())
            assertEquals(f.id, 8f, f.slots.sumOf { Poly.area(it.verts).toDouble() }.toFloat(), 1e-3f)
            for (a in f.slots.indices) for (b in a + 1 until f.slots.size)
                assertTrue("${f.id} $a/$b overlap", Poly.overlapArea(f.slots[a].verts, f.slots[b].verts) < 1e-4f)
            val (lo, hi) = Poly.bounds(f.slots.flatMap { it.verts })
            assertTrue("${f.id} frame", hi.x - lo.x <= 6.001f && hi.y - lo.y <= 6.001f)
            // every slot is exactly its kind laid at the derived pose (rot8 / face)
            for (s in f.slots) assertTrue("${f.id} ${s.kind}", Poly.same(s.pose.outline(s.kind), s.verts))
        }
    }

    @Test fun orientationSymmetryFollowsTheDesign() {
        assertTrue(Kind.SQ.sameOrientation(0, false, 2, false))
        assertFalse(Kind.SQ.sameOrientation(0, false, 1, false))
        assertTrue(Kind.P.sameOrientation(1, false, 5, false))
        assertFalse(Kind.P.sameOrientation(1, false, 1, true))
        assertFalse(Kind.L.sameOrientation(0, false, 4, false))
        // flipping P twice is the original outline
        val p = Pose(V2(1f, 1f), 3, false)
        assertTrue(Poly.same(p.outline(Kind.P), p.copy(flipped = true).copy(flipped = false).outline(Kind.P)))
        assertFalse(Poly.same(p.outline(Kind.P), p.copy(flipped = true).outline(Kind.P)))
    }

    private fun pieceOf(kind: Kind, used: Set<Int> = emptySet()) = Pieces.KINDS.indices.first { Pieces.kind(it) == kind && it !in used }

    @Test fun theRightPadAndTurnSnapEverySlotOfEveryFigure() {
        for (f in Figures.ALL) for ((s, slot) in f.slots.withIndex()) {
            val i = pieceOf(slot.kind)
            val flow = GameFlowHarness()
            val pad = flow.flow.bestPadFor(f, s)
            val at = Rules.padPoint(pad.first, pad.second, f)
            val l = Rules.land(f, home, i, at, slot.pose.rot8, slot.pose.flipped, Rules.entryFor(f, at))
            assertTrue("${f.id} slot $s: $l", l is Landing.Snapped)
        }
    }

    @Test fun snapReachAndTurnRules() {
        val f = Figures.byId("house")!!
        val slot = f.slots.indices.first { f.slots[it].kind == Kind.SQ }
        val sq = f.slots[slot]
        val i = pieceOf(Kind.SQ)
        fun land(at: V2, rot: Int, flip: Boolean = false) = Rules.land(f, home, i, at, rot, flip, V2(at.x, -5f))
        // inside the slot polygon (any distance): snaps
        assertTrue(land(sq.centroid, sq.pose.rot8) is Landing.Snapped)
        // a square turned 90° fits too (mod 2); 45° does not: right place, wrong turn
        assertTrue(land(sq.centroid, sq.pose.rot8 + 2) is Landing.Snapped)
        assertTrue(land(sq.centroid, sq.pose.rot8 + 1) is Landing.TurnMe)
        // just inside / outside the reach from the centroid, away from every other square slot
        val outward = V2(-1f, 0f)
        val inReach = sq.centroid + outward * (Layout.SNAP_REACH_U - 0.02f)
        val outReach = sq.centroid + outward * (Layout.SNAP_REACH_U + 0.02f)
        assertTrue(land(inReach, sq.pose.rot8) is Landing.Snapped)
        assertFalse(land(outReach, sq.pose.rot8) is Landing.Snapped)
        // the parallelogram: turned 180° fits, the wrong face does not
        val ps = f.slots.indices.first { f.slots[it].kind == Kind.P }
        val pp = f.slots[ps].pose
        val ip = pieceOf(Kind.P)
        assertTrue(Rules.land(f, home, ip, pp.centre, pp.rot8 + 4, pp.flipped, V2(0f, -5f)) is Landing.Snapped)
        assertTrue(Rules.land(f, home, ip, pp.centre, pp.rot8, !pp.flipped, V2(0f, -5f)) is Landing.TurnMe)
    }

    @Test fun aFloatingPieceOnTheSlotGoesBackToTheTrayWhenTheRightOneSnaps() {
        val f = Figures.byId("house")!!
        val sl = f.slots.indices.first { f.slots[it].kind == Kind.M }
        val sqi = pieceOf(Kind.SQ)
        val pieces = home.replace(sqi, PieceState(Where.BOARD, Pose(f.slots[sl].centroid, 0, false)))
        val mi = pieceOf(Kind.M)
        val l = Rules.land(f, pieces, mi, f.slots[sl].centroid, f.slots[sl].pose.rot8, false, V2(0f, -5f))
        assertTrue(l is Landing.Snapped && l.bounced == listOf(sqi))
    }

    @Test fun droppingOnAnotherPieceBacksOffUntilItJustTouches() {
        val f = Figure.FREE
        val sq = pieceOf(Kind.SQ)
        val pieces = home.replace(sq, PieceState(Where.BOARD, Pose(V2(0f, 0f), 0, false)))
        val s = pieceOf(Kind.S)
        val l = Rules.land(f, pieces, s, V2(0.1f, 0.1f), 0, false, V2(0.1f, -3f))
        assertTrue(l is Landing.Blocked)
        val out = (l as Landing.Blocked).pose.outline(Kind.S)
        assertTrue(Rules.overlaps(pieces, s, out).isEmpty())
        // it stopped close to the square (within 2 % of a u), coming from below
        assertTrue(l.pose.centre.y < 0f && l.pose.centre.y > -1.2f)
    }

    @Test fun backingOffTheBoardSendsThePieceHome() {
        val f = Figure.FREE
        val h = Rules.BOARD_HALF_U
        val bigL = pieceOf(Kind.L)
        val pieces = home.replace(bigL, PieceState(Where.BOARD, Pose(V2(0f, -h + 1f), 0, false)))
        val s = pieceOf(Kind.S)
        val l = Rules.land(f, pieces, s, V2(-0.2f, -h + 0.6f), 0, false, V2(-0.2f, -h - 3f))
        assertTrue("$l", l is Landing.BackToTray || l is Landing.Blocked)
    }

    @Test fun piecesTouchingAlongAnEdgeDoNotOverlap() {
        // every kind next to a unit square, in all eight turns, shifted until the SAT says apart: touching is not overlap
        for (k in Kind.entries) for (r in 0 until 8) {
            val a = Pose(V2(0f, 0f), r, false).outline(k)
            val (lo, hi) = Poly.bounds(a)
            val b = Pose(V2.ZERO, 0, false).outline(Kind.SQ).map { it + V2(hi.x + 0.5f, 0f) }
            assertFalse("$k r$r", Rules.overlapping(a, k.area, b, 1f))
            val c = Pose(V2.ZERO, 0, false).outline(Kind.SQ).map { it + V2(lo.x + 0.5f, 0f) }
            assertTrue("$k r$r overlaps", Rules.overlapping(a, k.area, c, 1f))
        }
    }

    @Test fun clampKeepsAPieceOnTheBoard() {
        val f = Figures.byId("arrow")!!
        val p = Rules.clampToBoard(Kind.L, Pose(V2(100f, 100f), 0, false), f)
        assertTrue(Rules.insideBoard(Kind.L, p, f))
    }

    @Test fun freePlayAlignsToTheHalfULattice() {
        val p = Rules.latticeAlign(Kind.S, Pose(V2(0.31f, 0.77f), 0, false))
        val v = p.outline(Kind.S).first()
        assertEquals(0f, (v.x * 2) - Math.round(v.x * 2), 1e-4f)
        assertEquals(0f, (v.y * 2) - Math.round(v.y * 2), 1e-4f)
    }

    @Test fun saveCodecRoundTrips() {
        val g = SavedGame("cat", home.replace(2, PieceState(Where.BOARD, Pose(V2(1.25f, -0.5f), 3, true), 4)), 9, 72_000, true, 1, false,
            listOf(Snapshot(home, 0), Snapshot(home.replace(0, PieceState(Where.BOARD, Pose(V2(0.5f, 0.5f), 1, false))), 1)))
        val back = SaveCodec.decode(SaveCodec.encode(g))!!
        assertEquals(g.copy(pieces = back.pieces, undo = back.undo), back)
        assertEquals(3, back.pieces[2].pose.rot8); assertTrue(back.pieces[2].pose.flipped); assertEquals(4, back.pieces[2].slot)
        assertEquals(2, back.undo.size)
        assertEquals(null, SaveCodec.decode("tw1|nope|"))
        val r = mapOf("cat" to Record(true, 65_000, 12), "house" to Record(true, null, null))
        assertEquals(r, RecordsCodec.decode(RecordsCodec.encode(r)))
    }
}
