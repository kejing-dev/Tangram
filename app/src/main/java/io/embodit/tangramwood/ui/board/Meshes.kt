package io.embodit.tangramwood.ui.board

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.tangramwood.domain.Poly
import io.embodit.tangramwood.domain.V2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Programmatic wooden meshes (design assets: 「多边形挤出 + 倒角」, no GLB, no Tripo — the exact geometry is what a
 * tangram is). Every solid is a convex outline in the local XZ plane (x right, z toward the user; outline points are
 * given as (x, y_away) and mapped to z = -y) extruded along +Y with a chamfer on both rims, flat-shaded, planar UVs for
 * the wood-grain texture. A two-tone piece is made of two halves (top half maple, bottom half cherry, design entities3d).
 */
internal object Meshes {
    private class Builder {
        val pos = ArrayList<Vector3>()
        val nor = ArrayList<Vector3>()
        val uv = ArrayList<Vector2>()
        val idx = ArrayList<Int>()

        fun tri(a: Vector3, b: Vector3, c: Vector3, uvScale: Float) {
            val n = normal(a, b, c)
            val base = pos.size
            for (p in listOf(a, b, c)) {
                pos += p; nor += n
                // planar UV on the dominant plane: tops / bottoms by (x, z), sides by (along, y)
                uv += if (abs(n.y) > 0.5f) Vector2(p.x / uvScale + 0.5f, p.z / uvScale + 0.5f) else Vector2((p.x + p.z) / uvScale + 0.5f, p.y / uvScale * 4 + 0.5f)
            }
            idx += base; idx += base + 1; idx += base + 2
        }

        fun quad(a: Vector3, b: Vector3, c: Vector3, d: Vector3, uvScale: Float) { tri(a, b, c, uvScale); tri(a, c, d, uvScale) }

        fun build(name: String): MeshResource {
            val minX = pos.minOf { it.x }; val maxX = pos.maxOf { it.x }
            val minY = pos.minOf { it.y }; val maxY = pos.maxOf { it.y }
            val minZ = pos.minOf { it.z }; val maxZ = pos.maxOf { it.z }
            val bounds = BoundingBox(
                center = Vector3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2),
                halfExtent = Vector3(max(1e-4f, (maxX - minX) / 2), max(1e-4f, (maxY - minY) / 2), max(1e-4f, (maxZ - minZ) / 2)),
            )
            return MeshResource.createWithMeshModel(MeshModel(positions = pos, triangleIndices = idx, normals = nor, uv0 = uv), bounds, name)
        }
    }

    private fun normal(a: Vector3, b: Vector3, c: Vector3): Vector3 {
        val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
        val vx = c.x - a.x; val vy = c.y - a.y; val vz = c.z - a.z
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
        return Vector3(nx / l, ny / l, nz / l)
    }

    /** Convex outline (x, y_away in metres, counter-clockwise seen from above) shrunk by [d] (miter offset). */
    fun inset(poly: List<V2>, d: Float): List<V2> {
        val p = Poly.ccw(poly)
        val n = p.size
        return p.indices.map { i ->
            val prev = p[(i + n - 1) % n]; val cur = p[i]; val next = p[(i + 1) % n]
            val e1 = (cur - prev).let { it * (1f / it.length) }
            val e2 = (next - cur).let { it * (1f / it.length) }
            val n1 = V2(-e1.y, e1.x); val n2 = V2(-e2.y, e2.x) // inward normals for a CCW outline
            val bis = (n1 + n2).let { it * (1f / it.length) }
            val k = d / bis.dot(n1)
            cur + bis * k
        }
    }

    private fun v(p: V2, y: Float) = Vector3(p.x, y, -p.y)

    /**
     * A chamfered slab between [y0] and [y1] of the convex [outline]. [top] / [bottom] choose which caps and chamfer rims
     * to build, so a piece can be split in two tones at [splitY] (the sides are cut there).
     */
    fun slab(outline: List<V2>, y0: Float, y1: Float, chamfer: Float, name: String, uvScale: Float = 0.25f, top: Boolean = true, bottom: Boolean = true, splitY: Float? = null): MeshResource {
        val b = Builder()
        val outer = Poly.ccw(outline)
        val innerPts = inset(outer, chamfer)
        val n = outer.size
        val lo = if (bottom) y0 else (splitY ?: y0)
        val hi = if (top) y1 else (splitY ?: y1)
        val sideLo = if (bottom) y0 + chamfer else lo
        val sideHi = if (top) y1 - chamfer else hi
        // side walls
        for (i in 0 until n) {
            val a = outer[i]; val c = outer[(i + 1) % n]
            b.quad(v(a, sideLo), v(c, sideLo), v(c, sideHi), v(a, sideHi), uvScale)
        }
        if (top) {
            for (i in 0 until n) {
                val a = outer[i]; val c = outer[(i + 1) % n]; val ai = innerPts[i]; val ci = innerPts[(i + 1) % n]
                b.quad(v(a, sideHi), v(c, sideHi), v(ci, y1), v(ai, y1), uvScale)
            }
            for (i in 1 until n - 1) b.tri(v(innerPts[0], y1), v(innerPts[i], y1), v(innerPts[i + 1], y1), uvScale)
        }
        if (bottom) {
            for (i in 0 until n) {
                val a = outer[i]; val c = outer[(i + 1) % n]; val ai = innerPts[i]; val ci = innerPts[(i + 1) % n]
                b.quad(v(ai, y0), v(ci, y0), v(c, sideLo), v(a, sideLo), uvScale)
            }
            for (i in 1 until n - 1) b.tri(v(innerPts[0], y0), v(innerPts[i + 1], y0), v(innerPts[i], y0), uvScale)
        }
        return b.build(name)
    }

    /** A rounded-corner rectangle outline (w along x, d along y_away), [r] corner radius, 4 segments per corner. */
    fun roundRect(w: Float, d: Float, r: Float, seg: Int = 4): List<V2> {
        val out = ArrayList<V2>()
        val cs = listOf(V2(w / 2 - r, d / 2 - r) to 0.0, V2(-w / 2 + r, d / 2 - r) to 90.0, V2(-w / 2 + r, -d / 2 + r) to 180.0, V2(w / 2 - r, -d / 2 + r) to 270.0)
        for ((c, a0) in cs) for (k in 0..seg) {
            val a = Math.toRadians(a0 + 90.0 * k / seg)
            out += V2(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat())
        }
        return out
    }

    /** The wedge under the board: a right-triangle prism, base [depth] along z, rising to [height] at the far end. */
    fun wedge(width: Float, depth: Float, height: Float, name: String): MeshResource {
        val b = Builder()
        val x0 = -width / 2; val x1 = width / 2
        // profile in (z, y): near bottom (0,0), far bottom (-depth, 0), far top (-depth, height)
        fun p(x: Float, z: Float, y: Float) = Vector3(x, y, z)
        val nb = 0f; val fb = -depth
        // sloped top
        b.quad(p(x0, nb, 0f), p(x1, nb, 0f), p(x1, fb, height), p(x0, fb, height), 0.3f)
        // back
        b.quad(p(x1, fb, 0f), p(x0, fb, 0f), p(x0, fb, height), p(x1, fb, height), 0.3f)
        // bottom
        b.quad(p(x0, nb, 0f), p(x0, fb, 0f), p(x1, fb, 0f), p(x1, nb, 0f), 0.3f)
        // sides
        b.tri(p(x0, nb, 0f), p(x0, fb, height), p(x0, fb, 0f), 0.3f)
        b.tri(p(x1, nb, 0f), p(x1, fb, 0f), p(x1, fb, height), 0.3f)
        return b.build(name)
    }

    /** A flat convex polygon facing +Y at y = 0 (shadow slots, contact shadow). */
    fun flat(outline: List<V2>, name: String, uvScale: Float = 0.25f): MeshResource {
        val b = Builder()
        val p = Poly.ccw(outline)
        for (i in 1 until p.size - 1) b.tri(v(p[0], 0f), v(p[i], 0f), v(p[i + 1], 0f), uvScale)
        return b.build(name)
    }

    /** A flat band of [width] along the inside of a convex outline (slot rims, seams, the 「放好了」 edge). */
    fun ring(outline: List<V2>, width: Float, name: String, outward: Float = 0f): MeshResource {
        val b = Builder()
        val outer = if (outward > 0f) inset(outline, -outward) else Poly.ccw(outline)
        val inner = inset(outer, width)
        val n = outer.size
        for (i in 0 until n) {
            val a = outer[i]; val c = outer[(i + 1) % n]; val ai = inner[i]; val ci = inner[(i + 1) % n]
            b.quad(v(a, 0f), v(c, 0f), v(ci, 0f), v(ai, 0f), 1f)
        }
        return b.build(name)
    }

    /** Flat strips of [width] centred on each segment (x, y_away metres) at y = 0: the figure outline and the seams. */
    fun strips(segments: List<Pair<V2, V2>>, width: Float, name: String): MeshResource {
        val b = Builder()
        for ((a, c) in segments) {
            val d = c - a
            val len = d.length
            if (len < 1e-5f) continue
            val e = d * (1f / len)
            val n = V2(-e.y, e.x) * (width / 2)
            // extend each end by half the width so corners close
            val a2 = a - e * (width / 2); val c2 = c + e * (width / 2)
            b.quad(v(a2 - n, 0f), v(c2 - n, 0f), v(c2 + n, 0f), v(a2 + n, 0f), 1f)
        }
        if (b.pos.isEmpty()) b.tri(Vector3(0f, -1f, 0f), Vector3(0.0001f, -1f, 0f), Vector3(0f, -1f, -0.0001f), 1f)
        return b.build(name)
    }

    /**
     * The outline of a union of polygons that tile without overlap: every edge minus the parts another polygon's edge
     * covers (shared seams), in the polygons' own units.
     */
    fun boundary(polys: List<List<V2>>): List<Pair<V2, V2>> {
        val out = ArrayList<Pair<V2, V2>>()
        val edges = polys.flatMapIndexed { k, p -> p.indices.map { Triple(k, p[it], p[(it + 1) % p.size]) } }
        for ((k, a, c) in edges) {
            val d = c - a
            val len2 = d.dot(d)
            val covered = ArrayList<Pair<Float, Float>>()
            for ((k2, a2, c2) in edges) {
                if (k2 == k) continue
                // collinear with a–c?
                if (abs(d.cross(a2 - a)) > 1e-4f || abs(d.cross(c2 - a)) > 1e-4f) continue
                val t1 = d.dot(a2 - a) / len2; val t2 = d.dot(c2 - a) / len2
                val lo = minOf(t1, t2).coerceIn(0f, 1f); val hi = maxOf(t1, t2).coerceIn(0f, 1f)
                if (hi - lo > 1e-4f) covered += lo to hi
            }
            covered.sortBy { it.first }
            var t = 0f
            for ((lo, hi) in covered) {
                if (lo > t + 1e-4f) out += (a + d * t) to (a + d * lo)
                t = maxOf(t, hi)
            }
            if (t < 1f - 1e-4f) out += (a + d * t) to c
        }
        return out
    }

    /** A quad in the XY plane facing +Z, UV (0,0) top-left → (1,1) bottom-right (unlit textured: plate, tab faces). */
    fun face(w: Float, h: Float, name: String, flipV: Boolean = false): MeshResource {
        val pos = listOf(Vector3(-w / 2, h / 2, 0f), Vector3(w / 2, h / 2, 0f), Vector3(w / 2, -h / 2, 0f), Vector3(-w / 2, -h / 2, 0f))
        val nz = Vector3(0f, 0f, 1f)
        val uv = if (flipV) listOf(Vector2(0f, 1f), Vector2(1f, 1f), Vector2(1f, 0f), Vector2(0f, 0f))
        else listOf(Vector2(0f, 0f), Vector2(1f, 0f), Vector2(1f, 1f), Vector2(0f, 1f))
        return MeshResource.createWithMeshModel(
            MeshModel(positions = pos, triangleIndices = listOf(0, 3, 2, 0, 2, 1), normals = List(4) { nz }, uv0 = uv),
            BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(w / 2, h / 2, 0.0005f)), name,
        )
    }
}
