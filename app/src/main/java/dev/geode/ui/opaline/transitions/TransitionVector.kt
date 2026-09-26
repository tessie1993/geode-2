package dev.geode.ui.opaline.transitions

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The three.js `Vector3` subset src/transitions.js uses, in double precision like JS numbers.
 * Catalogue metres: +Y up, +Z front (compositions.json `coordinateSystem`). Mutating methods
 * return `this` so recipes read as the library's chained vector code.
 */
internal class TransitionVector(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
) {
    fun set(
        x: Double,
        y: Double,
        z: Double,
    ): TransitionVector {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun copy(source: TransitionVector): TransitionVector = set(source.x, source.y, source.z)

    fun clone(): TransitionVector = TransitionVector(x, y, z)

    fun add(other: TransitionVector): TransitionVector = set(x + other.x, y + other.y, z + other.z)

    fun sub(other: TransitionVector): TransitionVector = set(x - other.x, y - other.y, z - other.z)

    /** Component-wise product (`Vector3.multiply`). */
    fun multiply(other: TransitionVector): TransitionVector = set(x * other.x, y * other.y, z * other.z)

    fun multiplyScalar(scalar: Double): TransitionVector = set(x * scalar, y * scalar, z * scalar)

    fun divideScalar(scalar: Double): TransitionVector = multiplyScalar(1 / scalar)

    fun addScaled(
        other: TransitionVector,
        scalar: Double,
    ): TransitionVector = set(x + other.x * scalar, y + other.y * scalar, z + other.z * scalar)

    fun lerp(
        other: TransitionVector,
        alpha: Double,
    ): TransitionVector = set(x + (other.x - x) * alpha, y + (other.y - y) * alpha, z + (other.z - z) * alpha)

    fun dot(other: TransitionVector): Double = x * other.x + y * other.y + z * other.z

    fun lengthSquared(): Double = x * x + y * y + z * z

    fun length(): Double = sqrt(lengthSquared())

    fun distanceTo(other: TransitionVector): Double = sqrt(distanceToSquared(other))

    fun distanceToSquared(other: TransitionVector): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    /** `Vector3.normalize`: `divideScalar(this.length() || 1)`. */
    fun normalize(): TransitionVector {
        val length = length()
        return multiplyScalar(1 / if (length == 0.0 || length.isNaN()) 1.0 else length)
    }

    /** `Vector3.crossVectors(a, b)`; [a] or [b] may be this vector. */
    fun cross(
        a: TransitionVector,
        b: TransitionVector,
    ): TransitionVector = set(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    /** `Vector3.applyAxisAngle(V(0,1,0), angle)`: the quaternion (0, sin(a/2), 0, cos(a/2)). */
    fun applyYRotation(angle: Double): TransitionVector {
        val s = sin(angle / 2)
        val w = cos(angle / 2)
        val tx = 2 * (s * z)
        val tz = 2 * (-s * x)
        return set(x + w * tx + s * tz, y, z + w * tz - s * tx)
    }

    /**
     * `applyMatrix4(new Matrix4().makeRotationAxis(axis, angle))` (r186), as Curve
     * computeFrenetFrames turns its normals; [axis] must be normalised.
     */
    fun applyRotationAxis(
        axis: TransitionVector,
        angle: Double,
    ): TransitionVector {
        val c = cos(angle)
        val s = sin(angle)
        val t = 1 - c
        val tx = t * axis.x
        val ty = t * axis.y
        return set(
            (tx * axis.x + c) * x + (tx * axis.y - s * axis.z) * y + (tx * axis.z + s * axis.y) * z,
            (tx * axis.y + s * axis.z) * x + (ty * axis.y + c) * y + (ty * axis.z - s * axis.x) * z,
            (tx * axis.z - s * axis.y) * x + (ty * axis.z + s * axis.x) * y +
                (t * axis.z * axis.z + c) * z,
        )
    }

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}
