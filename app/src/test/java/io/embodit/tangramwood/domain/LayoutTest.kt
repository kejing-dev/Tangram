package io.embodit.tangramwood.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/**
 * profile D17 补 (2026-11-10, QA blocker): every tap target ≥ 4 cm on its shortest side and ≥ 3° from the viewing
 * eyes; neighbours ≥ max(1.5 cm, 0.3 × the larger size) apart. Computed with the real scale and the real places
 * (tray homes, every slot of all 15 figures, the 25 pads, the tab bar over every one of those places). The table goes
 * to app/build/reports/d17-audit.md for ui-audit.md.
 */
class LayoutTest {
    /** A tap target: [a] / [b] are its in-plane axes (foreshortening), [size] its shortest side. */
    private data class Target(val cls: String, val name: String, val size: Float, val at: V3, val a: V3, val b: V3)

    private fun pieceSize(i: Int) = Pieces.kind(i).shortestEdge * Layout.U

    private fun targets(): List<Target> {
        val out = ArrayList<Target>()
        val r = Layout.RIGHT; val sl = Layout.SLOPE
        for (i in 0 until Pieces.COUNT) out += Target("piece@tray", "piece $i ${Pieces.kind(i)}", pieceSize(i), Layout.homeScene(i), r, sl)
        for (f in Figures.ALL) f.slots.forEachIndexed { s, slot ->
            val i = Pieces.KINDS.indexOf(slot.kind)
            out += Target("piece@board", "${f.id} slot $s ${slot.kind}", pieceSize(i), Layout.boardToScene(Layout.puzzleToBoard(slot.centroid, f), Layout.T / 2), r, sl)
        }
        for ((i, j) in Layout.PADS) out += Target("pad", "pad:$i:$j", Layout.PAD, Layout.boardToScene(Layout.padCentreM(i, j)), r, sl)
        for (n in 2..3) for (k in 0 until n) out += Target("tab", "tab $k of $n", Layout.TAB, Layout.tabCentre(Layout.RAIL_CENTRE, k, n), r, Layout.UP)
        return out
    }

    private fun deg(t: Target, eye: V3) = Layout.viewDeg(t.size, t.at, t.a, t.b, eye)

    @Test fun everyTargetIsBigEnoughFromTheDefaultPlacement() {
        val ts = targets()
        val eye = Layout.DEFAULT_EYE.second
        val rows = StringBuilder()
        rows.append("| 目标类 | 数量 | 最短边 (cm) | 默认摆位最小视角（透视后）(°) | 正对时 (°) | 头移开 30 cm / 上下 15 cm 最小 (°) | 最远视距 (m) |\n|---|---|---|---|---|---|---|\n")
        for ((cls, group) in ts.groupBy { it.cls }) {
            val minSize = group.minOf { it.size }
            val def = group.minOf { deg(it, eye) }
            val frontal = group.minOf { Layout.degrees(it.size, eye.dist(it.at)) }
            val near = group.minOf { t -> Layout.NEAR_EYES.minOf { deg(t, it.second) } }
            val far = group.maxOf { eye.dist(it.at) }
            rows.append("| $cls | ${group.size} | ${"%.1f".format(minSize * 100)} | ${"%.2f".format(def)} | ${"%.2f".format(frontal)} | ${"%.2f".format(near)} | ${"%.2f".format(far)} |\n")
            for (t in group) {
                assertTrue("${t.name}: ${t.size} m < 4 cm", t.size >= Layout.MIN_TARGET)
                val d = deg(t, eye)
                assertTrue("${t.name}: %.2f° < 2° from the default placement".format(d), d >= Layout.MIN_DEG)
            }
        }
        val gaps = StringBuilder()
        gaps.append("| 相邻关系 | 实际间隙 (cm) | 要求 (cm) |\n|---|---|---|\n")
        var worstTray = Float.MAX_VALUE; var worstNeed = 0f
        for (a in 0 until Pieces.COUNT) for (b in a + 1 until Pieces.COUNT) {
            val (ga, gb) = trayBox(a) to trayBox(b)
            val dx = max(0f, max(ga.first.x - gb.second.x, gb.first.x - ga.second.x))
            val dz = max(0f, max(ga.first.y - gb.second.y, gb.first.y - ga.second.y))
            val gap = sqrt(dx * dx + dz * dz)
            val need = Layout.gapFor(trayMaxDim(a), trayMaxDim(b))
            assertTrue("tray $a-$b gap ${gap * 100} cm < ${need * 100} cm", gap >= need - 1e-4f)
            if (gap - need < worstTray - worstNeed) { worstTray = gap; worstNeed = need }
        }
        gaps.append("| 托盘里两块木片（最紧的一对） | ${"%.1f".format(worstTray * 100)} | ${"%.1f".format(worstNeed * 100)} |\n")
        assertTrue(Layout.PAD_GAP >= Layout.gapFor(Layout.PAD, Layout.PAD) - 1e-4f)
        gaps.append("| 题板相邻落点格 | ${"%.1f".format(Layout.PAD_GAP * 100)} | ${"%.1f".format(Layout.gapFor(Layout.PAD, Layout.PAD) * 100)} |\n")
        assertTrue(Layout.TAB_GAP >= Layout.gapFor(Layout.TAB, Layout.TAB) - 1e-4f)
        gaps.append("| 相邻木签 | ${"%.1f".format(Layout.TAB_GAP * 100)} | ${"%.1f".format(Layout.gapFor(Layout.TAB, Layout.TAB) * 100)} |\n")
        // the tray's right rim ↔ the board's leftmost pad column (side by side)
        val trayRight = Layout.TRAY_X + Layout.TRAY_OUTER_W / 2
        val padLeft = Layout.BOARD_X + Layout.padCentreM(0, 0).x - Layout.PAD / 2
        val trayPad = padLeft - trayRight
        assertTrue("tray-pad gap $trayPad", trayPad >= Layout.gapFor(Layout.PAD, Layout.PAD))
        gaps.append("| 托盘右沿 ↔ 最左一列落点格 | ${"%.1f".format(trayPad * 100)} | ${"%.1f".format(Layout.gapFor(Layout.PAD, Layout.PAD) * 100)} |\n")
        // the rail's top ↔ the nearest (lowest) pad row's lower edge, in 3D
        val lowPad = Layout.boardToScene(Layout.padCentreM(2, Layout.PAD_N - 1) + V2(0f, -Layout.PAD / 2))
        val railTop = Layout.RAIL_CENTRE + V3(0f, Layout.TAB / 2, 0f)
        val railPad = railTop.dist(lowPad)
        assertTrue("rail-pad gap $railPad", railPad >= Layout.gapFor(Layout.PAD, Layout.TAB))
        gaps.append("| 木签上沿 ↔ 最下一排落点格 | ${"%.1f".format(railPad * 100)} | ${"%.1f".format(Layout.gapFor(Layout.PAD, Layout.TAB) * 100)} |\n")
        val railTray = (Layout.RAIL_CENTRE.x - Layout.tabBarWidth(3) / 2) - trayRight
        assertTrue("rail-tray gap $railTray", railTray >= Layout.gapFor(Layout.TAB, Layout.TAB))
        gaps.append("| 木签左沿 ↔ 托盘右沿 | ${"%.1f".format(railTray * 100)} | ${"%.1f".format(Layout.gapFor(Layout.TAB, Layout.TAB) * 100)} |\n")

        val f = File("build/reports").apply { mkdirs() }
        File(f, "d17-audit.md").writeText(
            "## D17 可点目标尺寸 / 间距 / 视角（LayoutTest 生成，v0.1.1）\n\n" +
                "下限：边长 ≥ 4 cm、系统默认摆位下视角 ≥ 2°（用户 2026-11-11「Relax to 2°」）、相邻间隙 ≥ max(1.5 cm, 0.3 × 目标)。\n\n" +
                "u = ${Layout.U} m · 体积 ${Layout.VOLUME} m · 托盘 / 题板倾 ${Layout.TILT_DEG.toInt()}° 并排 · 落点格 ${"%.1f".format(Layout.PAD * 100)} cm（格距 ${"%.1f".format(Layout.PAD_PITCH * 100)} cm，${Layout.PAD_N} × ${Layout.PAD_N}）· 木签 ${"%.1f".format(Layout.TAB * 100)} cm（题板下方固定一排）\n\n" +
                "眼位（相对体积中心）：" + Layout.ALL_EYES.joinToString("；") { "${it.first} ${it.second}" } + "（默认摆位按 QA #1 实测 WorldSpace：体积中心在头前 1.749 m、低 6.1 cm）\n\n" +
                "视角 = 2·atan(最短边 × 透视系数 / 2 / 视距)，透视系数 = 目标两条面内轴里 sqrt(1 − (轴·视线)²) 的较小者；断言用默认摆位眼位。\n\n" +
                rows + "\n" + gaps,
        )
    }

    private fun trayBox(i: Int): Pair<V2, V2> {
        val h = Layout.HOMES[i]
        val pts = Pose(V2.ZERO, h.rot8, false).outline(Pieces.kind(i)).map { V2(h.x + it.x * Layout.U, h.z - it.y * Layout.U) }
        return Poly.bounds(pts)
    }

    private fun trayMaxDim(i: Int) = trayBox(i).let { (a, b) -> max(b.x - a.x, b.y - a.y) }

    @Test fun trayHomesStayInsideTheTray() {
        for (i in 0 until Pieces.COUNT) {
            val (a, b) = trayBox(i)
            assertTrue("piece $i x", a.x >= -Layout.TRAY_INNER_W / 2 + 0.005f && b.x <= Layout.TRAY_INNER_W / 2 - 0.005f)
            assertTrue("piece $i z", a.y >= -Layout.TRAY_INNER_D / 2 + 0.005f && b.y <= Layout.TRAY_INNER_D / 2 - 0.005f)
        }
    }

    @Test fun everySlotOfEveryFigureCanBeReachedFromAPad() {
        val worst = Rules.worstPadReach()
        assertTrue("worst pad reach $worst u > snap reach ${Layout.SNAP_REACH_U} u", worst <= Layout.SNAP_REACH_U)
    }

    @Test fun figuresFitTheBoardAndThePads() {
        for (f in Figures.ALL) for (s in f.slots) for (v in s.verts) {
            val m = Layout.puzzleToBoard(v, f)
            assertTrue("${f.id} vertex $v off the board", kotlin.math.abs(m.x) <= Layout.BOARD / 2 && kotlin.math.abs(m.y) <= Layout.BOARD / 2)
        }
        val span = (Layout.PAD_N - 1) * Layout.PAD_PITCH + Layout.PAD
        assertTrue("pads $span > board ${Layout.BOARD}", span <= Layout.BOARD)
    }

    @Test fun everythingStaysInsideTheVolume() {
        val h = Layout.HALF
        fun inside(p: V3, what: String) {
            assertTrue("$what $p outside $h", kotlin.math.abs(p.x) <= h.x + 1e-4f && kotlin.math.abs(p.y) <= h.y + 1e-4f && kotlin.math.abs(p.z) <= h.z + 1e-4f)
        }
        for (t in targets()) {
            if (t.cls == "tab") {
                inside(t.at + V3(-Layout.TAB / 2, Layout.TAB / 2, 0f), t.name)
                inside(t.at + V3(Layout.TAB / 2, Layout.TAB / 2, 0f), t.name)
            } else inside(t.at, t.name)
        }
        inside(Layout.boardToScene(V2(-Layout.BOARD / 2, Layout.BOARD / 2)), "board far corner")
        inside(Layout.plateCentre() + V3(Layout.PLATE_W / 2, Layout.PLATE_H / 2, 0f), "plate")
        inside(Layout.trayToScene(Layout.TRAY_OUTER_W / 2, Layout.TRAY_OUTER_D / 2), "tray lower corner")
        inside(Layout.trayToScene(-Layout.TRAY_OUTER_W / 2, -Layout.TRAY_OUTER_D / 2, Layout.T), "tray upper corner")
        inside(Layout.boardToScene(V2(Layout.BOARD / 2, -Layout.BOARD / 2)), "board lower corner")
        inside(Layout.RAIL_CENTRE + V3(-Layout.TAB, -Layout.TAB / 2, Layout.TAB_T / 2), "rail")
        assertTrue("tray and board side by side", Layout.TRAY_X + Layout.TRAY_OUTER_W / 2 < Layout.BOARD_X - Layout.BOARD / 2)
    }

    @Test fun manifestSizeMatchesTheVolume() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val v = Layout.VOLUME
        val want = "%sx%sx%s".format(fmt(v.x), fmt(v.y), fmt(v.z))
        assertTrue("manifest defaultsize should be $want", manifest.contains("android:value=\"$want\""))
        assertEquals(want, want)
    }

    private fun fmt(f: Float) = java.math.BigDecimal(f.toDouble()).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
