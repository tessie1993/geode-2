package dev.geode.ui.opaline.transitions

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The three.js `Quaternion` subset src/transitions.js uses (vendor/three r186
 * src/math/Quaternion.js), in double precision. Orientation of a [TransitionPose].
 */
internal class TransitionQuaternion(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
    var w: Double = 1.0,
) {
    fun set(
        x: Double,
        y: Double,
        z: Double,
        w: Double,
    ): TransitionQuaternion {
        this.x = x
        this.y = y
        this.z = z
        this.w = w
        return this
    }

    fun copy(source: TransitionQuaternion): TransitionQuaternion = set(source.x, source.y, source.z, source.w)

    fun identity(): TransitionQuaternion = set(0.0, 0.0, 0.0, 1.0)

    fun dot(other: TransitionQuaternion): Double = x * other.x + y * other.y + z * other.z + w * other.w

    fun length(): Double = sqrt(x * x + y * y + z * z + w * w)

    /** `Quaternion.setFromAxisAngle`; [axis] must be normalised. */
    fun setFromAxisAngle(
        axis: TransitionVector,
        angle: Double,
    ): TransitionQuaternion {
        val s = sin(angle / 2)
        return set(axis.x * s, axis.y * s, axis.z * s, cos(angle / 2))
    }

    /** `Quaternion.setFromEuler` for the catalogue's Euler order XYZ (radians). */
    fun setFromEuler(
        ex: Double,
        ey: Double,
        ez: Double,
    ): TransitionQuaternion {
        val c1 = cos(ex / 2)
        val c2 = cos(ey / 2)
        val c3 = cos(ez / 2)
        val s1 = sin(ex / 2)
        val s2 = sin(ey / 2)
        val s3 = sin(ez / 2)
        return set(
            s1 * c2 * c3 + c1 * s2 * s3,
            c1 * s2 * c3 - s1 * c2 * s3,
            c1 * c2 * s3 + s1 * s2 * c3,
            c1 * c2 * c3 - s1 * s2 * s3,
        )
    }

    /**
     * `setFromRotationMatrix(new Matrix4().lookAt(eye, target, up))` (r186): the orientation
     * whose −Z axis looks from [eye] at [target], as L18 aims its camera.
     */
    fun setFromLookAt(
        eye: TransitionVector,
        target: TransitionVector,
        up: TransitionVector,
    ): TransitionQuaternion {
        val zAxis = eye.clone().sub(target)
        if (zAxis.lengthSquared() == 0.0) zAxis.z = 1.0
        zAxis.normalize()
        val xAxis = TransitionVector().cross(up, zAxis)
        if (xAxis.lengthSquared() == 0.0) {
            if (abs(up.z) == 1.0) zAxis.x += 0.0001 else zAxis.z += 0.0001
            zAxis.normalize()
            xAxis.cross(up, zAxis)
        }
        xAxis.normalize()
        val yAxis = TransitionVector().cross(zAxis, xAxis)
        val m11 = xAxis.x
        val m12 = yAxis.x
        val m13 = zAxis.x
        val m21 = xAxis.y
        val m22 = yAxis.y
        val m23 = zAxis.y
        val m31 = xAxis.z
        val m32 = yAxis.z
        val m33 = zAxis.z
        val trace = m11 + m22 + m33
        if (trace > 0) {
            val s = 0.5 / sqrt(trace + 1.0)
            return set((m32 - m23) * s, (m13 - m31) * s, (m21 - m12) * s, 0.25 / s)
        }
        if (m11 > m22 && m11 > m33) {
            val s = 2.0 * sqrt(1.0 + m11 - m22 - m33)
            return set(0.25 * s, (m12 + m21) / s, (m13 + m31) / s, (m32 - m23) / s)
        }
        if (m22 > m33) {
            val s = 2.0 * sqrt(1.0 + m22 - m11 - m33)
            return set((m12 + m21) / s, 0.25 * s, (m23 + m32) / s, (m13 - m31) / s)
        }
        val s = 2.0 * sqrt(1.0 + m33 - m11 - m22)
        return set((m13 + m31) / s, (m23 + m32) / s, 0.25 * s, (m21 - m12) / s)
    }

    /** `Quaternion.multiplyQuaternions(a, b)`; [a] or [b] may be this quaternion. */
    fun multiplyQuaternions(
        a: TransitionQuaternion,
        b: TransitionQuaternion,
    ): TransitionQuaternion =
        set(
            a.x * b.w + a.w * b.x + a.y * b.z - a.z * b.y,
            a.y * b.w + a.w * b.y + a.z * b.x - a.x * b.z,
            a.z * b.w + a.w * b.z + a.x * b.y - a.y * b.x,
            a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
        )

    /** `Quaternion.premultiply(q)`: this = [q] × this. */
    fun premultiply(q: TransitionQuaternion): TransitionQuaternion = multiplyQuaternions(q, this)

    /** `Quaternion.normalize`: a zero quaternion becomes the identity. */
    fun normalize(): TransitionQuaternion {
        val length = length()
        if (length == 0.0) return identity()
        val inverse = 1 / length
        return set(x * inverse, y * inverse, z * inverse, w * inverse)
    }

    /** `Quaternion.slerp(qb, t)`: shortest arc, normalised lerp above a 0.9995 dot. */
    fun slerp(
        qb: TransitionQuaternion,
        t: Double,
    ): TransitionQuaternion {
        var bx = qb.x
        var by = qb.y
        var bz = qb.z
        var bw = qb.w
        var dot = dot(qb)
        if (dot < 0) {
            bx = -bx
            by = -by
            bz = -bz
            bw = -bw
            dot = -dot
        }
        var s = 1 - t
        var u = t
        if (dot < 0.9995) {
            val theta = acos(dot)
            val sine = sin(theta)
            s = sin(s * theta) / sine
            u = sin(u * theta) / sine
            return set(x * s + bx * u, y * s + by * u, z * s + bz * u, w * s + bw * u)
        }
        set(x * s + bx * u, y * s + by * u, z * s + bz * u, w * s + bw * u)
        return normalize()
    }
}
