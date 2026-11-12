package io.embodit.tangramwood.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A 2D point / vector. Puzzle coordinates are in u (the small triangle's leg): x right, y away from the user. */
data class V2(val x: Float, val y: Float) {
    operator fun plus(o: V2) = V2(x + o.x, y + o.y)
    operator fun minus(o: V2) = V2(x - o.x, y - o.y)
    operator fun times(k: Float) = V2(x * k, y * k)
    fun dot(o: V2) = x * o.x + y * o.y
    fun cross(o: V2) = x * o.y - y * o.x
    val length: Float get() = hypot(x, y)
    fun dist(o: V2) = (this - o).length
    /** Counter-clockwise by [rot8] eighth turns (seen from above, y away from the user). */
    fun rotated(rot8: Int): V2 {
        val a = Math.toRadians(45.0 * Math.floorMod(rot8, 8))
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return V2(x * c - y * s, x * s + y * c)
    }
    override fun toString() = "(%.3f, %.3f)".format(x, y)

    companion object { val ZERO = V2(0f, 0f) }
}

/** The seven tangram pieces come in five kinds (design gameSpec.units). */
enum class Kind(val label: String) {
    L("L"), M("M"), S("S"), SQ("Sq"), P("P");

    /** Standard vertices (rot8 0, face up), counter-clockwise, in u. */
    val standard: List<V2> get() = when (this) {
        L -> listOf(V2(0f, 0f), V2(2f, 0f), V2(0f, 2f))
        M -> listOf(V2(0f, 0f), V2(R2, 0f), V2(0f, R2))
        S -> listOf(V2(0f, 0f), V2(1f, 0f), V2(0f, 1f))
        SQ -> listOf(V2(0f, 0f), V2(1f, 0f), V2(1f, 1f), V2(0f, 1f))
        P -> listOf(V2(0f, 0f), V2(1f, 0f), V2(2f, 1f), V2(1f, 1f))
    }

    /** Standard vertices around their own centroid: the mesh and every pose use this outline. */
    val centred: List<V2> get() { val c = Poly.centroid(standard); return standard.map { it - c } }

    /** Area in u² (sums to 8 over the seven pieces). */
    val area: Float get() = Poly.area(standard)

    /** Shortest edge in u (D17 "边长"). */
    val shortestEdge: Float get() = Poly.edges(standard).minOf { it.length }

    val canFlip: Boolean get() = this == P

    /** Two orientations look the same (design gameSpec.orientation): triangles exact, square mod 2, parallelogram mod 4 + same face. */
    fun sameOrientation(r1: Int, f1: Boolean, r2: Int, f2: Boolean): Boolean = when (this) {
        L, M, S -> Math.floorMod(r1, 8) == Math.floorMod(r2, 8)
        SQ -> Math.floorMod(r1, 2) == Math.floorMod(r2, 2)
        P -> f1 == f2 && Math.floorMod(r1, 4) == Math.floorMod(r2, 4)
    }

    companion object { val R2: Float = sqrt(2f) }
}

/** Where a piece lies in puzzle space: its centroid, turn and face. */
data class Pose(val centre: V2, val rot8: Int, val flipped: Boolean) {
    /** The outline in puzzle space: mirror x if flipped (then reverse to stay counter-clockwise), turn, move. */
    fun outline(kind: Kind): List<V2> {
        var pts = kind.centred
        if (flipped) pts = pts.map { V2(-it.x, it.y) }.reversed()
        return pts.map { it.rotated(rot8) + centre }
    }
}

/** Convex polygon helpers (all seven pieces are convex). */
object Poly {
    fun area(p: List<V2>): Float {
        var a = 0f
        for (i in p.indices) a += p[i].cross(p[(i + 1) % p.size])
        return abs(a) / 2f
    }

    fun signedArea(p: List<V2>): Float { var a = 0f; for (i in p.indices) a += p[i].cross(p[(i + 1) % p.size]); return a / 2f }

    fun centroid(p: List<V2>): V2 {
        var cx = 0.0; var cy = 0.0; var a = 0.0
        for (i in p.indices) {
            val q = p[i]; val r = p[(i + 1) % p.size]
            val k = (q.x * r.y - r.x * q.y).toDouble()
            a += k; cx += (q.x + r.x) * k; cy += (q.y + r.y) * k
        }
        a /= 2.0
        return V2((cx / (6 * a)).toFloat(), (cy / (6 * a)).toFloat())
    }

    fun edges(p: List<V2>): List<V2> = p.indices.map { p[(it + 1) % p.size] - p[it] }

    fun ccw(p: List<V2>): List<V2> = if (signedArea(p) < 0) p.reversed() else p

    /** Point inside (or on) a convex polygon. */
    fun contains(p: List<V2>, q: V2, eps: Float = 1e-5f): Boolean {
        val c = ccw(p)
        for (i in c.indices) if ((c[(i + 1) % c.size] - c[i]).cross(q - c[i]) < -eps) return false
        return true
    }

    /** Overlap area of two convex polygons (Sutherland–Hodgman clip of [a] by [b]). */
    fun overlapArea(a: List<V2>, b: List<V2>): Float {
        var out = ccw(a)
        val clip = ccw(b)
        for (i in clip.indices) {
            if (out.isEmpty()) return 0f
            val e0 = clip[i]; val e1 = clip[(i + 1) % clip.size]
            val inp = out
            out = ArrayList()
            fun inside(p: V2) = (e1 - e0).cross(p - e0) >= -1e-7f
            fun cut(s: V2, e: V2): V2 {
                val d = e - s; val n = e1 - e0
                val t = n.cross(e0 - s) / n.cross(d)
                return s + d * t
            }
            for (j in inp.indices) {
                val cur = inp[j]; val prev = inp[(j + inp.size - 1) % inp.size]
                if (inside(cur)) { if (!inside(prev)) out.add(cut(prev, cur)); out.add(cur) }
                else if (inside(prev)) out.add(cut(prev, cur))
            }
        }
        return if (out.size < 3) 0f else area(out)
    }

    /** Separating-axis test: the interiors overlap by more than [eps] along every axis (touching edges do not count). */
    fun intersects(a: List<V2>, b: List<V2>, eps: Float = 1e-4f): Boolean {
        for (poly in listOf(a, b)) for (e in edges(poly)) {
            val n = V2(-e.y, e.x)
            var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
            var minB = Float.MAX_VALUE; var maxB = -Float.MAX_VALUE
            for (p in a) { val d = n.dot(p); minA = min(minA, d); maxA = max(maxA, d) }
            for (p in b) { val d = n.dot(p); minB = min(minB, d); maxB = max(maxB, d) }
            val len = n.length
            if (maxA - minB <= eps * len || maxB - minA <= eps * len) return false
        }
        return true
    }

    /** Smallest width over all edge directions (narrowest caliper of a convex polygon). */
    fun minWidth(p: List<V2>): Float = edges(p).minOf { e ->
        val n = V2(-e.y, e.x) * (1f / e.length)
        val ds = p.map { n.dot(it) }
        ds.max() - ds.min()
    }

    fun bounds(p: List<V2>): Pair<V2, V2> = V2(p.minOf { it.x }, p.minOf { it.y }) to V2(p.maxOf { it.x }, p.maxOf { it.y })

    fun same(a: List<V2>, b: List<V2>, eps: Float = 1e-3f): Boolean =
        a.size == b.size && a.all { p -> b.any { q -> p.dist(q) < eps } }
}
