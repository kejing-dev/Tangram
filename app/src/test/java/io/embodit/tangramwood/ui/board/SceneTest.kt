package io.embodit.tangramwood.ui.board

import io.embodit.tangramwood.domain.Figures
import io.embodit.tangramwood.domain.GameFlowHarness
import io.embodit.tangramwood.domain.Kind
import io.embodit.tangramwood.domain.Layout
import io.embodit.tangramwood.domain.Phase
import io.embodit.tangramwood.domain.PieceState
import io.embodit.tangramwood.domain.Pieces
import io.embodit.tangramwood.domain.Poly
import io.embodit.tangramwood.domain.Pose
import io.embodit.tangramwood.domain.V2
import io.embodit.tangramwood.domain.V3
import io.embodit.tangramwood.domain.Where
import io.embodit.tangramwood.ui.board.input.parseLoggedPath
import io.embodit.tangramwood.ui.board.input.resolvePath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * build.md「命中目标 → 格子必须有回放单测」, the 3D pose ↔ puzzle outline mapping (a piece drawn on a slot covers that
 * slot's shadow exactly) and D35 (tabs over the selected piece, the title plate) from the design eyes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneTest {
    private val volume = listOf("root")
    private val board = listOf("boardRoot", "tiltRoot", "root")

    private fun target(path: List<String>) = BoardTargets.resolvePath(path)

    @Test fun everyHitNodeResolvesToItsHolder() {
        for (i in 0 until Pieces.COUNT) {
            val h = BoardNames.piece(i)
            for (child in listOf("pieceTop:$i", "pieceBottom:$i", "edge:$i")) assertEquals(h, target(listOf(child, h) + volume))
            assertEquals("piece$i", BoardTargets.squareOf(target(listOf(h) + volume)))
        }
        for ((i, j) in Layout.PADS) {
            val h = BoardNames.pad(i, j)
            assertEquals(h, target(listOf("mk:$h:0", h) + board))
            assertEquals("pad$i$j", BoardTargets.squareOf(target(listOf(h) + board)))
        }
        for (k in 0..2) assertEquals(BoardNames.tab(k), target(listOf("tabFace:$k", BoardNames.tab(k)) + volume))
        assertEquals("tray", target(listOf("trayBase", "tray") + volume))
        // things that take no taps (the board, slots, the plate) resolve to nothing, and bad names too
        for (bad in listOf("boardSlab", "slot:3", "outline", "titlePlate", "wedge", "piece:7", "pad:5:0", "tab:3", "piece:1:2"))
            assertNull(bad, target(listOf(bad) + board))
    }

    @Test fun replayedLogPathsDriveTheSameFlow() = runTest {
        // input-log lines look like `TAP … path=pieceTop:3 < piece:3 < root`
        val h = GameFlowHarness(this); h.flow.start(); advanceUntilIdle()
        routeFlow(h.flow, target(parseLoggedPath("pieceTop:0 < piece:0 < root")))
        assertEquals(0, h.st.selected)
        routeFlow(h.flow, target(parseLoggedPath("tabFace:0 < tab:0 < root")))
        assertEquals((Layout.HOMES[0].rot8 + 1) % 8, h.st.pieces[0].pose.rot8)
        val f = h.st.figure
        val slot = f.slots.indices.first { f.slots[it].kind == Kind.L }
        val want = f.slots[slot].pose
        while (h.st.pieces[0].pose.rot8 != want.rot8) routeFlow(h.flow, target(parseLoggedPath("tabFace:0 < tab:0 < root")))
        val pad = h.flow.bestPadFor(f, slot)
        routeFlow(h.flow, target(parseLoggedPath("mk:pad:${pad.first}:${pad.second}:0 < pad:${pad.first}:${pad.second} < boardRoot < tiltRoot < root")))
        advanceUntilIdle()
        assertEquals(slot, h.st.pieces[0].slot)
        routeFlow(h.flow, target(parseLoggedPath("pieceTop:5 < piece:5 < root")))
        routeFlow(h.flow, target(parseLoggedPath("trayBase < tray < root")))
        assertEquals(Where.TRAY, h.st.pieces[5].where); assertNull(h.st.selected)
    }

    @Test fun aPieceDrawnOnItsSlotCoversTheSlotShadow() {
        // the 3D orientation (tilt · turn · flip) of the mesh outline lands exactly on the slot drawn on the board
        for (f in Figures.ALL) for ((s, slot) in f.slots.withIndex()) {
            val i = Pieces.KINDS.indexOf(slot.kind)
            val p = PieceState(Where.BOARD, slot.pose, s)
            val q = PiecePoses.orientation(p)
            val c = PiecePoses.position(i, p, f) + Layout.BOARD_NORMAL * Layout.SINK
            val drawn = slot.kind.centred.map { v -> c + q.rotate(V3(v.x * Layout.U, 0f, -v.y * Layout.U)) }
            val want = slot.verts.map { Layout.boardToScene(Layout.puzzleToBoard(it, f), Layout.T / 2) }
            for (d in drawn) assertTrue("${f.id} slot $s ${slot.kind} vertex $d", want.any { it.dist(d) < 1e-3f })
            // the face that shows is the cherry back exactly when the parallelogram is flipped
            val up = q.rotate(V3(0f, 1f, 0f))
            assertEquals("${f.id} slot $s face", !slot.pose.flipped, up.dot(Layout.BOARD_NORMAL) > 0)
        }
    }

    @Test fun trayPiecesLieOnTheSlantedTray() {
        for (i in 0 until Pieces.COUNT) {
            val p = PieceState(Where.TRAY, Pose(V2.ZERO, Layout.HOMES[i].rot8, false))
            val q = PiecePoses.orientation(p)
            assertTrue(q.rotate(V3(0f, 1f, 0f)).dot(Layout.TRAY_NORMAL) > 0.999f)
            val c = PiecePoses.position(i, p, Figures.ALL[0])
            val want = Layout.trayToScene(Layout.HOMES[i].x, Layout.HOMES[i].z, Layout.T / 2)
            assertTrue("piece $i at $c, want $want", c.dist(want) < 1e-4f)
        }
    }

    // ---------------------------------------------------------------- D35 / D41 (QA #1 q1-2, q1-3): projected from the eyes

    private fun eyes() = Layout.ALL_EYES

    /** A point's direction from [eye] as (azimuth, elevation) in degrees: the screen the user sees. */
    private fun ang(eye: V3, p: V3): V2 {
        val d = p - eye
        val az = Math.toDegrees(kotlin.math.atan2(d.x.toDouble(), -d.z.toDouble())).toFloat()
        val el = Math.toDegrees(kotlin.math.atan2(d.y.toDouble(), kotlin.math.hypot(d.x.toDouble(), d.z.toDouble()))).toFloat()
        return V2(az, el)
    }

    private fun hull(pts: List<V2>): List<V2> {
        val s = pts.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (s.size < 3) return s
        fun cross(o: V2, a: V2, b: V2) = (a - o).cross(b - o)
        val lower = ArrayList<V2>(); for (p in s) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1); lower += p }
        val upper = ArrayList<V2>(); for (p in s.reversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1); upper += p }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /** The screen outline of a set of 3D points (convex hull of their directions). */
    private fun screen(eye: V3, pts: List<V3>) = hull(pts.map { ang(eye, it) })

    private fun tabRect(k: Int, n: Int): List<V3> {
        val c = Layout.tabCentre(Layout.RAIL_CENTRE, k, n) + V3(0f, 0f, Layout.TAB_T / 2)
        val h = Layout.TAB / 2
        return listOf(V3(-h, -h, 0f), V3(h, -h, 0f), V3(h, h, 0f), V3(-h, h, 0f)).map { c + it }
    }

    private fun padRect(i: Int, j: Int): List<V3> {
        val c = Layout.padCentreM(i, j); val h = Layout.PAD / 2
        return listOf(V2(-h, -h), V2(h, -h), V2(h, h), V2(-h, h)).map { Layout.boardToScene(c + it) }
    }

    private fun plateRect(): List<V3> {
        val c = Layout.plateCentre(); val w = Layout.PLATE_W / 2; val h = Layout.PLATE_H / 2
        return listOf(V3(-w, -h, 0f), V3(w, -h, 0f), V3(w, h, 0f), V3(-w, h, 0f)).map { c + it }
    }

    private fun pieceOutline(i: Int, p: PieceState, f: io.embodit.tangramwood.domain.Figure): List<V3> {
        val q = PiecePoses.orientation(p); val c = PiecePoses.position(i, p, f, Layout.SELECT_LIFT)
        return Pieces.kind(i).centred.flatMap { v -> listOf(-1f, 1f).map { s -> c + q.rotate(V3(v.x * Layout.U, s * Layout.T / 2, -v.y * Layout.U)) } }
    }

    @Test fun theTabRailNeverCoversAPadThePlateOrAPiece() {
        // QA #1 q1-2 (tabs over the pads: 5 pinches hit 「右转」) and q1-3 (tabs over the title plate): from every eye, the
        // screen outline of each tab must not overlap any pad, the plate, the tray or any piece wherever it can lie
        var minClear = Float.MAX_VALUE
        for ((en, e) in eyes()) for (n in 2..3) for (k in 0 until n) {
            val tab = screen(e, tabRect(k, n))
            fun check(what: String, other: List<V3>) {
                val o = screen(e, other)
                assertTrue("$en tab $k/$n covers $what", !io.embodit.tangramwood.domain.Poly.intersects(tab, o, 0f))
            }
            for ((i, j) in Layout.PADS) check("pad:$i:$j", padRect(i, j))
            check("title plate", plateRect())
            check("tray", listOf(V2(-1f, -1f), V2(1f, -1f), V2(1f, 1f), V2(-1f, 1f)).map { Layout.trayToScene(it.x * Layout.TRAY_OUTER_W / 2, it.y * Layout.TRAY_OUTER_D / 2) })
            for (f in Figures.ALL) f.slots.forEachIndexed { s, slot ->
                val i = Pieces.KINDS.indexOf(slot.kind)
                check("${f.id} slot $s", pieceOutline(i, PieceState(Where.BOARD, slot.pose), f))
            }
            // clearance to the lowest pad row on screen (degrees)
            val lowest = Layout.PADS.filter { it.second == Layout.PAD_N - 1 }.minOf { (i, j) -> screen(e, padRect(i, j)).minOf { it.y } }
            minClear = minOf(minClear, lowest - tab.maxOf { it.y })
        }
        File("build/reports").mkdirs()
        File("build/reports/d35-tabs.md").writeText("## D35 / D41 木签（SceneTest，v0.1.1）\n\n木签固定在题板下方一排。5 个眼位 × 2/3 个木签 × 25 个落点格 + 题名小牌 + 托盘 + 15 题 105 个槽位的木片：屏幕投影两两不重叠；木签上沿到最下一排落点格最少 ${"%.2f".format(minClear)}°。\n")
        assertTrue("rail too close to the pads on screen: $minClear°", minClear >= 0.5f)
    }

    @Test fun thePlateIsNotHiddenByTheBoard() {
        // the plate stands above the board's top edge: from every eye, the line to each plate corner never enters the board slab
        val corners = plateRect()
        val c = kotlin.math.cos(Layout.THETA); val sn = kotlin.math.sin(Layout.THETA)
        for ((en, e) in eyes()) for (corner in corners) for (k in 1..99) {
            val pt = e.lerp(corner, k / 100f)
            val d = pt - Layout.PIVOT
            val ly = d.y * c + d.z * sn; val lz = -d.y * sn + d.z * c
            val inside = abs(d.x) <= Layout.BOARD / 2 && ly in 0f..(Layout.BOARD_T + Layout.T + 0.012f) && lz in -Layout.BOARD..0f
            assertTrue("$en → plate corner $corner passes through the board at $pt", !inside)
        }
    }

    @Test fun solvedFigureRisesAndStaysInsideTheVolume() = runTest {
        val h = GameFlowHarness(this); h.flow.start(); advanceUntilIdle()
        h.flow.debugFillAndSolve(); advanceUntilIdle()
        assertEquals(Phase.SOLVED, h.st.phase)
        for (i in 0 until Pieces.COUNT) {
            val c = PiecePoses.position(i, h.st.pieces[i], h.st.figure, 0.01f)
            assertTrue(abs(c.y) < Layout.HALF.y && abs(c.z) < Layout.HALF.z && abs(c.x) < Layout.HALF.x)
        }
    }
}
