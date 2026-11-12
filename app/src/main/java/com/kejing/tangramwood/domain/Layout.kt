package com.kejing.tangramwood.domain

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A 3D point in metres (scene frame: volume centre, +X right, +Y up, +Z toward the user). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    val length: Float get() = sqrt(x * x + y * y + z * z)
    fun dist(o: V3) = (this - o).length
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)

    companion object { val ZERO = V3(0f, 0f, 0f) }
}

/**
 * Every size and place in the volume, in metres. One object, unit-tested (D16 / D17 / D35).
 *
 * D17 (2026-11-10 user: 「七巧板也可以做，就是要给格子本身和间距大一点，不然空间交互容易点错」; 2026-11-11 「Relax to 2°」): every
 * tap target is ≥ 4 cm on its shortest side and ≥ 2° from the system's default placement (volume 1.75 m ahead), counted
 * after foreshortening; neighbours ≥ max(1.5 cm, 0.3 × the larger size) apart. Versus design-package v1.2 (deviations):
 *  - u 0.0636 → 0.085 m (2×): the small triangle / square / parallelogram short side is 8.5 cm;
 *  - the 13 × 13 hidden 3.2 cm tap pads → 5 × 5 drop pads of 8.2 cm, 2.5 cm apart, a 5 mm hover marker each; the snap
 *    reach grows with them so every slot of every figure is reachable from a pad (unit test);
 *  - v0.1.1: tray (left) and board (right) side by side, both leaning back 70° (were flat / 25°, seen edge-on from
 *    1.75 m); the turn / flip tabs (8 cm) sit on a fixed rail under the board, clear of every pad and the title plate.
 */
object Layout {
    // ---------------------------------------------------------------- scale
    /** u: the small triangle's leg (design gameSpec.scale 0.0636 → 0.085, D17). */
    const val U = 0.085f
    /** Piece thickness (design 1 cm at 1.5×; 1.3 cm keeps the same look at 2×, D25). */
    const val T = 0.013f
    const val CHAMFER = 0.0012f
    /** Lift of the selected piece (design 1 cm). */
    const val SELECT_LIFT = 0.012f
    /** Snapped pieces sink into the 2 mm groove. */
    const val SINK = 0.002f
    /** Flight arc apex above start / end (design 3 cm). */
    const val ARC = 0.04f

    // ---------------------------------------------------------------- D17 rule
    const val MIN_TARGET = 0.04f
    const val MIN_DEG = 2f
    const val MIN_GAP = 0.015f
    const val GAP_FACTOR = 0.3f
    fun gapFor(sizeA: Float, sizeB: Float) = max(MIN_GAP, GAP_FACTOR * max(sizeA, sizeB))
    fun degrees(size: Float, dist: Float): Float = (2 * atan(size / 2 / dist) * 180 / PI).toFloat()

    // ---------------------------------------------------------------- tray (flat, near the user)
    const val TRAY_WALL = 0.012f
    const val TRAY_WALL_H = 0.008f
    const val TRAY_BASE = 0.016f
    /** Clear space between a piece's box and the tray wall. */
    const val TRAY_MARGIN = 0.018f

    /** A piece's home in the tray: centre (tray frame: x right, z toward the user) and turn (always face up). */
    data class Home(val x: Float, val z: Float, val rot8: Int)

    private fun bboxM(kind: Kind, rot8: Int): Pair<Float, Float> {
        val (a, b) = Poly.bounds(Pose(V2.ZERO, rot8, false).outline(kind))
        return (b.x - a.x) * U to (b.y - a.y) * U
    }
    private fun maxDim(kind: Kind, rot8: Int) = bboxM(kind, rot8).let { max(it.first, it.second) }

    /** Rows far → near (design spatialLayout.tray, re-spaced for D17): [L L] [M P Sq] [S S]. */
    private val ROWS: List<List<Pair<Int, Int>>> = listOf(
        listOf(0 to 5, 1 to 5),                 // two big triangles, long side toward you
        listOf(2 to 5, 6 to 0, 5 to 0),        // middle triangle, parallelogram, square
        listOf(3 to 5, 4 to 5),                 // two small triangles
    )

    val HOMES: List<Home>
    val TRAY_INNER_W: Float
    val TRAY_INNER_D: Float

    init {
        // widths of each row with the D17 gaps between neighbours
        val rowW = ROWS.map { row ->
            var w = 0f
            row.forEachIndexed { i, (p, r) ->
                if (i > 0) { val (q, rq) = row[i - 1]; w += gapFor(maxDim(Pieces.kind(q), rq), maxDim(Pieces.kind(p), r)) }
                w += bboxM(Pieces.kind(p), r).first
            }
            w
        }
        val rowD = ROWS.map { row -> row.maxOf { (p, r) -> bboxM(Pieces.kind(p), r).second } }
        val rowGap = (1 until ROWS.size).map { k ->
            gapFor(ROWS[k - 1].maxOf { (p, r) -> maxDim(Pieces.kind(p), r) }, ROWS[k].maxOf { (p, r) -> maxDim(Pieces.kind(p), r) })
        }
        val innerW = rowW.max() + 2 * TRAY_MARGIN
        val innerD = rowD.sum() + rowGap.sum() + 2 * TRAY_MARGIN
        val homes = arrayOfNulls<Home>(Pieces.COUNT)
        var z = -innerD / 2 + TRAY_MARGIN
        ROWS.forEachIndexed { k, row ->
            val d = rowD[k]
            // spread the row across the tray width (equal spare on both ends and between pieces)
            val spare = (rowW.max() - rowW[k]) / (row.size + 1)
            var x = -rowW.max() / 2 + spare
            row.forEachIndexed { i, (p, r) ->
                if (i > 0) { val (q, rq) = row[i - 1]; x += gapFor(maxDim(Pieces.kind(q), rq), maxDim(Pieces.kind(p), r)) + spare }
                val (bw, bd) = bboxM(Pieces.kind(p), r)
                // centre the piece's bounding box in its slot of the row; home = the centroid
                val (lo, hi) = Poly.bounds(Pose(V2.ZERO, r, false).outline(Pieces.kind(p)))
                val bboxCentre = V2((lo.x + hi.x) / 2, (lo.y + hi.y) / 2) * U
                val cx = x + bw / 2 - bboxCentre.x
                val cz = z + d / 2 + bboxCentre.y // tray +Z = puzzle -y
                homes[p] = Home(cx, cz, r)
                x += bw
            }
            z += d + (rowGap.getOrNull(k) ?: 0f)
        }
        HOMES = homes.map { it!! }
        TRAY_INNER_W = innerW
        TRAY_INNER_D = innerD
    }

    val TRAY_OUTER_W = TRAY_INNER_W + 2 * TRAY_WALL
    val TRAY_OUTER_D = TRAY_INNER_D + 2 * TRAY_WALL

    // ---------------------------------------------------------------- tray + board: side by side, both tilted 70°
    /**
     * v0.1.1 (QA #1 q1-1, user 2026-11-11 「Relax to 2°」「直接改吧」): the system places the volume about 1.75 m ahead at
     * eye level, so a flat tray and a 25° board were seen almost edge-on (pads ≈ 1.0°, tray pieces ≈ 0.7° along the
     * slope). Tray (left) and board (right) now stand side by side, both leaning back 70° like easels, so every target
     * faces the user (foreshortening ≥ 0.9).
     */
    const val TILT_DEG = 70f
    val THETA = (TILT_DEG * PI / 180).toFloat()
    /** Board side: 6 u for the widest figure + 0.3 u each side. */
    const val BOARD = 6.6f * U
    const val BOARD_T = 0.02f
    /** Gap between the tray column and the board column. */
    const val COLUMN_GAP = 0.06f

    // ---------------------------------------------------------------- drop pads on the board (D17: the 「格子」)
    /** Pad pitch 1.25 u (10.6 cm). */
    const val PAD_PITCH = 1.25f * U
    /** Pad side: pitch minus the D17 gap (gap = 0.3 × side ≥ 1.5 cm) → 8.17 cm. */
    val PAD = PAD_PITCH / (1 + GAP_FACTOR)
    val PAD_GAP = PAD_PITCH - PAD
    const val PAD_N = 5
    /** Pad centres in board metres (board centre origin, x right, y up the slope), row-major far → near. */
    val PADS: List<Pair<Int, Int>> = (0 until PAD_N).flatMap { j -> (0 until PAD_N).map { i -> i to j } }
    fun padCentreM(i: Int, j: Int): V2 = V2((i - (PAD_N - 1) / 2f) * PAD_PITCH, ((PAD_N - 1) / 2f - j) * PAD_PITCH)
    /** Snap reach in u (design 3 cm at 1.5×): a pad centre this close to a slot's centroid snaps; unit-tested to reach every slot. */
    const val SNAP_REACH_U = 0.95f
    /** Marker on each pad: a 5 mm plate (board-input template「标记尺寸」 ≤ 5 mm), 1.2 mm proud. */
    const val MARKER = 0.005f

    // ---------------------------------------------------------------- tabs: a fixed rail under the board (q1-2 / q1-3)
    const val TAB = 0.08f
    const val TAB_T = 0.012f
    val TAB_GAP = gapFor(TAB, TAB)
    fun tabBarWidth(n: Int) = n * TAB + (n - 1) * TAB_GAP
    /** Rail bottom above the volume floor, and the clear gap between the rail's top and the board's lower edge. */
    const val RAIL_BOTTOM = 0.012f
    const val RAIL_GAP = 0.04f
    /** The rail stands this far in front of the board's lower edge. */
    const val RAIL_FRONT = 0.03f

    // ---------------------------------------------------------------- title plate (above the board's top edge)
    const val PLATE_W = 0.36f
    const val PLATE_H = 0.06f
    const val PLATE_PX_W = 1536
    const val PLATE_PX_H = 256
    const val PLATE_TEXT_PX = 1430f
    const val PLATE_GAP = 0.03f

    // ---------------------------------------------------------------- the volume
    const val SIDE_MARGIN = 0.03f
    const val FRONT_MARGIN = 0.03f
    const val BACK_MARGIN = 0.03f
    const val TOP_MARGIN = 0.02f

    /** Volume size (x, y, z) in metres; the manifest default size must match (LayoutTest). */
    val VOLUME: V3
    val HALF: V3
    /** The board's lower front edge (pivot of its tilt) and the tray's; centres of the two columns in x. */
    val PIVOT: V3
    val TRAY_PIVOT: V3
    val TRAY_X: Float
    val BOARD_X: Float
    /** The fixed tab rail's centre (the bar of tabs, centred under the board). */
    val RAIL_CENTRE: V3
    /** Top of the plinth both columns stand on. */
    val PLINTH_TOP: Float

    init {
        val total = TRAY_OUTER_W + COLUMN_GAP + BOARD
        val w = total + 2 * SIDE_MARGIN
        val boardDepth = BOARD * cos(THETA) + BOARD_T * sin(THETA)
        val d = FRONT_MARGIN + TAB_T + RAIL_FRONT + boardDepth + BACK_MARGIN
        // heights from the floor: rail, gap, board (slanted), plate gap, plate
        val pivotY = RAIL_BOTTOM + TAB + RAIL_GAP
        val boardTop = pivotY + BOARD * sin(THETA) + BOARD_T * cos(THETA)
        val h = boardTop + PLATE_GAP + PLATE_H + TOP_MARGIN
        VOLUME = V3(round2(w), round2(h), round2(d))
        HALF = VOLUME * 0.5f
        TRAY_X = -total / 2 + TRAY_OUTER_W / 2
        BOARD_X = total / 2 - BOARD / 2
        val pivotZ = HALF.z - FRONT_MARGIN - TAB_T - RAIL_FRONT
        PIVOT = V3(BOARD_X, -HALF.y + pivotY, pivotZ)
        TRAY_PIVOT = V3(TRAY_X, -HALF.y + pivotY, pivotZ)
        RAIL_CENTRE = V3(BOARD_X, -HALF.y + RAIL_BOTTOM + TAB / 2, pivotZ + RAIL_FRONT + TAB_T / 2)
        PLINTH_TOP = -HALF.y + pivotY
    }

    private fun round2(v: Float) = (ceil(v * 100) / 100f)

    // ---------------------------------------------------------------- frames
    val UP = V3(0f, 1f, 0f)
    /** Surface normal of the tilted board and tray (scene frame). */
    val BOARD_NORMAL = V3(0f, cos(THETA), sin(THETA))
    val TRAY_NORMAL = BOARD_NORMAL
    /** Up the slope, away from the user (the puzzle's +y on the board, the tray's −z). */
    val SLOPE = V3(0f, sin(THETA), -cos(THETA))
    val RIGHT = V3(1f, 0f, 0f)

    private fun tilt(lx: Float, ly: Float, lz: Float): V3 = V3(lx, ly * cos(THETA) - lz * sin(THETA), ly * sin(THETA) + lz * cos(THETA))

    /** Board metres (board centre origin, x right, y up the slope, on the top surface) → scene. */
    fun boardToScene(p: V2, above: Float = 0f): V3 = PIVOT + tilt(p.x, BOARD_T + above, -BOARD / 2 - p.y)

    /** Puzzle u (figure frame) → board metres: the figure's bounding-box centre sits on the board centre. */
    fun puzzleToBoard(p: V2, figure: Figure): V2 = (p - figure.centre) * U
    fun boardToPuzzle(m: V2, figure: Figure): V2 = m * (1f / U) + figure.centre

    /** Tray frame (x right, z toward the lower front edge, on the tray floor) → scene. */
    fun trayToScene(x: Float, z: Float, above: Float = 0f): V3 = TRAY_PIVOT + tilt(x, TRAY_BASE + above, -TRAY_OUTER_D / 2 + z)

    /** The tray's floor centre (scene). */
    val TRAY_CENTRE: V3 get() = trayToScene(0f, 0f)

    /** A piece's scene centre at home (resting on the tray floor). */
    fun homeScene(piece: Int, above: Float = 0f): V3 = HOMES[piece].let { trayToScene(it.x, it.z, T / 2 + above) }

    /** The tray is one tap target over its floor (local box). */
    val TRAY_TARGET: V3 get() = V3(TRAY_INNER_W, 0.006f, TRAY_INNER_D)

    /** Title plate centre (scene): 3 cm above the board's top edge, upright, facing +Z. */
    fun plateCentre(): V3 {
        val top = boardToScene(V2(0f, BOARD / 2))
        return V3(BOARD_X, top.y + BOARD_T + PLATE_GAP + PLATE_H / 2, top.z - 0.01f)
    }

    /** Centre of the tab bar: the fixed rail under the board (v0.1.1; QA q1-2 / q1-3: never over a pad or the plate). */
    @Suppress("UNUSED_PARAMETER")
    fun tabBarCentre(face: V3, normal: V3, n: Int): V3 = RAIL_CENTRE

    /** Centre of tab [k] of [n] in a bar centred at [bar] (left to right). */
    fun tabCentre(bar: V3, k: Int, n: Int): V3 = V3(bar.x - tabBarWidth(n) / 2 + TAB / 2 + k * (TAB + TAB_GAP), bar.y, bar.z)

    // ---------------------------------------------------------------- eyes (relative to the volume centre)
    /**
     * The system's default placement, measured by QA #1 on the emulator (WorldSpace ContainerTransformSolver: volume
     * Position (0, −0.061, −1.749) from the head): the eye is 1.749 m in front of the volume centre, 6.1 cm above it.
     * D17 is asserted from there; D35 also from the head moved 0.3 m left / right and 0.15 m up / down.
     */
    val DEFAULT_EYE: Pair<String, V3> = "systemDefault" to V3(0f, 0.061f, 1.749f)
    val NEAR_EYES: List<Pair<String, V3>> = listOf(
        "left30cm" to V3(-0.3f, 0.061f, 1.749f), "right30cm" to V3(0.3f, 0.061f, 1.749f),
        "up15cm" to V3(0f, 0.211f, 1.749f), "down15cm" to V3(0f, -0.089f, 1.749f),
    )
    val ALL_EYES: List<Pair<String, V3>> = listOf(DEFAULT_EYE) + NEAR_EYES

    /**
     * View angle of a target of [size] metres centred at [at] whose two in-plane axes are [a] and [b] (unit), seen from
     * [eye]: each axis is foreshortened by sqrt(1 − (axis · view)²) (QA #1: 「透视后」), the smaller one counts.
     */
    fun viewDeg(size: Float, at: V3, a: V3, b: V3, eye: V3): Float {
        val v = (at - eye).let { it * (1f / it.length) }
        val k = minOf(kotlin.math.sqrt(1 - a.dot(v) * a.dot(v)), kotlin.math.sqrt(1 - b.dot(v) * b.dot(v)))
        return degrees(size * k, eye.dist(at))
    }
}
