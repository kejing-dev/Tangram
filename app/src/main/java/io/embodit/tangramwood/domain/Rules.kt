package io.embodit.tangramwood.domain

import kotlin.math.max
import kotlin.math.min

/** What one put-down did (design gameSpec.landingOrder, one result per drop). */
sealed interface Landing {
    /** ② snapped into [slot]; floating pieces lying on that slot went back to the tray. */
    data class Snapped(val slot: Int, val pose: Pose, val bounced: List<Int>) : Landing
    /** T6: right place, wrong turn: the piece hovers on the slot, still selected. */
    data class TurnMe(val slot: Int, val pose: Pose) : Landing
    /** ③ pushed back along its way until it just touches a neighbour. */
    data class Blocked(val pose: Pose) : Landing
    /** ③ pushing back left the board: back to its home in the tray. */
    data object BackToTray : Landing
    /** ④ lies where it was put. */
    data class Floating(val pose: Pose) : Landing
}

/** The rules of putting a piece down (pure; unit-tested T5 / T7 / landingOrder). */
object Rules {
    /** Overlap that counts as "on top of another piece" (design: > 2 % of the smaller area). */
    const val OVERLAP_SHARE = 0.02f

    /** Half the board side in u. */
    val BOARD_HALF_U: Float get() = Layout.BOARD / 2 / Layout.U

    fun outline(i: Int, p: PieceState): List<V2> = p.pose.outline(Pieces.kind(i))

    /** The board area in puzzle u for [figure] (board centre = figure centre). */
    fun boardBounds(figure: Figure): Pair<V2, V2> {
        val c = figure.centre; val h = BOARD_HALF_U
        return V2(c.x - h, c.y - h) to V2(c.x + h, c.y + h)
    }

    /** ① Shift [pose] so the whole piece lies on the board. */
    fun clampToBoard(kind: Kind, pose: Pose, figure: Figure): Pose {
        val (lo, hi) = boardBounds(figure)
        val (a, b) = Poly.bounds(pose.outline(kind))
        var dx = 0f; var dy = 0f
        if (a.x < lo.x) dx = lo.x - a.x else if (b.x > hi.x) dx = hi.x - b.x
        if (a.y < lo.y) dy = lo.y - a.y else if (b.y > hi.y) dy = hi.y - b.y
        return pose.copy(centre = pose.centre + V2(dx, dy))
    }

    fun insideBoard(kind: Kind, pose: Pose, figure: Figure): Boolean {
        val (lo, hi) = boardBounds(figure)
        val (a, b) = Poly.bounds(pose.outline(kind))
        return a.x >= lo.x - 1e-4f && a.y >= lo.y - 1e-4f && b.x <= hi.x + 1e-4f && b.y <= hi.y + 1e-4f
    }

    fun overlapping(a: List<V2>, aArea: Float, b: List<V2>, bArea: Float): Boolean =
        Poly.intersects(a, b) && Poly.overlapArea(a, b) > OVERLAP_SHARE * min(aArea, bArea)

    /** Pieces on the board (other than [self]) that [outline] overlaps by more than 2 %. */
    fun overlaps(pieces: List<PieceState>, self: Int, outline: List<V2>): List<Int> {
        val area = Pieces.kind(self).area
        return pieces.indices.filter { j ->
            j != self && pieces[j].onBoard && overlapping(outline, area, outline(j, pieces[j]), Pieces.kind(j).area)
        }
    }

    /** Slot [s] taken by a snapped piece? */
    fun occupied(pieces: List<PieceState>, s: Int) = pieces.any { it.slot == s }

    /**
     * Same-kind empty slots whose position [at] reaches (design gameSpec.snap: inside the slot, or the slot's centroid
     * within the snap reach), nearest first.
     */
    fun reachableSlots(figure: Figure, pieces: List<PieceState>, kind: Kind, at: V2, reachU: Float = Layout.SNAP_REACH_U): List<Int> =
        figure.slots.indices.filter { s ->
            val slot = figure.slots[s]
            slot.kind == kind && !occupied(pieces, s) && (Poly.contains(slot.verts, at) || slot.centroid.dist(at) <= reachU)
        }.sortedBy { figure.slots[it].centroid.dist(at) }

    /**
     * Put piece [i] down at [at] (puzzle u) with turn [rot8] / face [flipped], coming from [from] (puzzle u; for a piece
     * coming out of the tray: the board's near edge below [at]). Returns one landing (design landingOrder ① – ④).
     */
    fun land(figure: Figure, pieces: List<PieceState>, i: Int, at: V2, rot8: Int, flipped: Boolean, from: V2): Landing {
        val kind = Pieces.kind(i)
        // ② snap first (the snap reach is generous, so it is judged on the pad the user picked, before any clamp)
        if (!figure.isFree) {
            val near = reachableSlots(figure, pieces, kind, at)
            val fits = near.firstOrNull { s -> figure.slots[s].pose.let { kind.sameOrientation(it.rot8, it.flipped, rot8, flipped) } }
            if (fits != null) {
                val slotPose = figure.slots[fits].pose
                val slotPoly = figure.slots[fits].verts
                val slotArea = Poly.area(slotPoly)
                val bounced = pieces.indices.filter { j ->
                    j != i && pieces[j].onBoard && !pieces[j].snapped &&
                        overlapping(slotPoly, slotArea, outline(j, pieces[j]), Pieces.kind(j).area)
                }
                return Landing.Snapped(fits, slotPose, bounced)
            }
            val right = near.firstOrNull()
            if (right != null) return Landing.TurnMe(right, Pose(figure.slots[right].centroid, rot8, flipped))
        }
        // ① on the board
        val pose = clampToBoard(kind, Pose(at, rot8, flipped), figure)
        // ③ on top of another piece: back off toward where it came from until it just touches
        val others = { p: Pose -> overlaps(pieces, i, p.outline(kind)) }
        if (others(pose).isEmpty()) return Landing.Floating(pose)
        val dir = from - pose.centre
        var lo = 0f; var hi = 1f
        // the far end must be clear; otherwise walk further back until it is (or the piece leaves the board)
        var end = pose.copy(centre = pose.centre + dir)
        var k = 1f
        while (others(end).isNotEmpty() && k < 8f) { k *= 2f; end = pose.copy(centre = pose.centre + dir * k) }
        if (others(end).isNotEmpty()) return Landing.BackToTray
        repeat(24) {
            val mid = (lo + hi) / 2
            if (others(pose.copy(centre = pose.centre + dir * (mid * k))).isEmpty()) hi = mid else lo = mid
        }
        val back = pose.copy(centre = pose.centre + dir * (hi * k))
        return if (insideBoard(kind, back, figure)) Landing.Blocked(back) else Landing.BackToTray
    }

    /** Where a piece coming out of the tray enters the board: the near edge, straight below [at]. */
    fun entryFor(figure: Figure, at: V2): V2 = V2(at.x, boardBounds(figure).first.y - 2f)

    /** All seven slots filled. */
    fun solved(figure: Figure, pieces: List<PieceState>): Boolean =
        !figure.isFree && figure.slots.indices.all { occupied(pieces, it) }

    /** The pad (i, j) → puzzle u. */
    fun padPoint(i: Int, j: Int, figure: Figure): V2 = Layout.boardToPuzzle(Layout.padCentreM(i, j), figure)

    /** Free play: put the piece's first vertex on the half-u lattice so neighbours can sit flush. */
    fun latticeAlign(kind: Kind, pose: Pose): Pose {
        val v = pose.outline(kind).first()
        val snapped = V2(Math.round(v.x * 2) / 2f, Math.round(v.y * 2) / 2f)
        return pose.copy(centre = pose.centre + (snapped - v))
    }

    /** Snap reach needed so every slot of every figure can be reached from at least one pad (LayoutTest). */
    fun worstPadReach(): Float = Figures.ALL.maxOf { f ->
        f.slots.maxOf { s ->
            val pads = Layout.PADS.map { (i, j) -> padPoint(i, j, f) }
            if (pads.any { Poly.contains(s.verts, it) }) 0f else pads.minOf { it.dist(s.centroid) }
        }
    }

    fun clampF(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
}
