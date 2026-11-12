package io.embodit.tangramwood.ui.board

import com.pico.spatial.core.math.Quat
import io.embodit.tangramwood.domain.V3
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A small pure quaternion (x, y, z, w) for the scene's poses and blends; unit-testable without the SDK. */
data class Q4(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(o: Q4) = Q4(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun rotate(v: V3): V3 {
        // v' = q v q*
        val p = this * Q4(v.x, v.y, v.z, 0f) * Q4(-x, -y, -z, w)
        return V3(p.x, p.y, p.z)
    }

    fun normalized(): Q4 { val l = sqrt(x * x + y * y + z * z + w * w).coerceAtLeast(1e-9f); return Q4(x / l, y / l, z / l, w / l) }

    fun slerp(o: Q4, t: Float): Q4 {
        var b = o
        var d = x * o.x + y * o.y + z * o.z + w * o.w
        if (d < 0) { d = -d; b = Q4(-o.x, -o.y, -o.z, -o.w) }
        if (d > 0.9995f) return Q4(x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t, w + (b.w - w) * t).normalized()
        val th = acos(d.coerceIn(-1f, 1f)); val s = sin(th)
        val k0 = sin((1 - t) * th) / s; val k1 = sin(t * th) / s
        return Q4(x * k0 + b.x * k1, y * k0 + b.y * k1, z * k0 + b.z * k1, w * k0 + b.w * k1)
    }

    fun sdk() = Quat(x, y, z, w)

    companion object {
        val ID = Q4(0f, 0f, 0f, 1f)
        fun axis(ax: Float, ay: Float, az: Float, angle: Float): Q4 { val s = sin(angle / 2); return Q4(ax * s, ay * s, az * s, cos(angle / 2)) }
        fun rotX(a: Float) = axis(1f, 0f, 0f, a)
        fun rotY(a: Float) = axis(0f, 1f, 0f, a)
        fun rotZ(a: Float) = axis(0f, 0f, 1f, a)
    }
}
