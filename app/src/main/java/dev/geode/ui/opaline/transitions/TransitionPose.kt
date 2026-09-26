package dev.geode.ui.opaline.transitions

/**
 * A world pose as src/transitions.js carries it (`{position, quaternion, scale}` from
 * `worldPose`): position in catalogue metres, orientation, and scale.
 */
internal class TransitionPose {
    val position = TransitionVector()
    val quaternion = TransitionQuaternion()
    val scale = TransitionVector(1.0, 1.0, 1.0)

    /** `copyPose` into this pose. */
    fun copy(source: TransitionPose): TransitionPose {
        position.copy(source.position)
        quaternion.copy(source.quaternion)
        scale.copy(source.scale)
        return this
    }

    /** `copyPose`. */
    fun clone(): TransitionPose = TransitionPose().copy(this)

    /**
     * Sets the pose from compositions.json `position`, `rotation` (Euler XYZ radians) and
     * `scale`, as `resolvePose` reads `{position, rotation, scale}`.
     */
    fun set(
        position: TransitionVector,
        rotation: TransitionVector,
        scale: TransitionVector,
    ): TransitionPose {
        this.position.copy(position)
        quaternion.setFromEuler(rotation.x, rotation.y, rotation.z)
        this.scale.copy(scale)
        return this
    }

    /** `setWorldPose` refuses a non-finite position or scale. */
    fun isFinite(): Boolean = position.isFinite() && scale.isFinite()

    /**
     * Writes into [out] (16 floats, column-major, android.opengl.Matrix layout) this pose's
     * world matrix, three.js `Matrix4.compose(position, quaternion, scale)`.
     */
    fun writeMatrix(out: FloatArray) {
        for (column in 0..2) {
            val factor = component(scale, column)
            for (row in 0..2) {
                out[column * 4 + row] = (rotation(quaternion, row, column) * factor).toFloat()
            }
            out[column * 4 + 3] = 0f
        }
        out[12] = position.x.toFloat()
        out[13] = position.y.toFloat()
        out[14] = position.z.toFloat()
        out[15] = 1f
    }

    /**
     * Writes into [out] (16 floats, column-major, android.opengl.Matrix layout) the change from
     * [rest] to this pose, D = compose(this) × compose(rest)⁻¹, seen from [pivot]
     * (`T(-pivot) × D × T(pivot)`) and with its translation carried from catalogue metres into
     * view units by [metresToWorld]. Geometry drawn at [rest] around [pivot] lands at this pose
     * when [out] is applied to it. Allocation-free.
     */
    fun writeDelta(
        rest: TransitionPose,
        pivot: TransitionVector,
        metresToWorld: Float,
        out: FloatArray,
    ) {
        // Linear part L = R S S0⁻¹ R0ᵀ: L[i][j] = Σm R[i][m] (s_m / s0_m) R0[j][m].
        for (column in 0..2) {
            for (row in 0..2) out[column * 4 + row] = linear(rest, row, column).toFloat()
            out[column * 4 + 3] = 0f
        }
        // D(x) = L x + p - L p0, so D(c + v) - c = L v + p + L (c - p0) - c.
        val cx = pivot.x - rest.position.x
        val cy = pivot.y - rest.position.y
        val cz = pivot.z - rest.position.z
        for (row in 0..2) {
            val moved =
                component(position, row) + linear(rest, row, 0) * cx + linear(rest, row, 1) * cy +
                    linear(rest, row, 2) * cz - component(pivot, row)
            out[12 + row] = (moved * metresToWorld).toFloat()
        }
        out[15] = 1f
    }

    private fun linear(
        rest: TransitionPose,
        row: Int,
        column: Int,
    ): Double {
        var sum = 0.0
        for (m in 0..2) {
            val ratio = component(scale, m) / component(rest.scale, m)
            sum += rotation(quaternion, row, m) * ratio * rotation(rest.quaternion, column, m)
        }
        return sum
    }

    private companion object {
        fun component(
            vector: TransitionVector,
            axis: Int,
        ): Double =
            when (axis) {
                0 -> vector.x
                1 -> vector.y
                else -> vector.z
            }

        /** Entry ([row], [column]) of the rotation matrix Matrix4.compose builds from [q]. */
        fun rotation(
            q: TransitionQuaternion,
            row: Int,
            column: Int,
        ): Double =
            when (row * 3 + column) {
                0 -> 1 - 2 * (q.y * q.y + q.z * q.z)
                1 -> 2 * (q.x * q.y - q.w * q.z)
                2 -> 2 * (q.x * q.z + q.w * q.y)
                3 -> 2 * (q.x * q.y + q.w * q.z)
                4 -> 1 - 2 * (q.x * q.x + q.z * q.z)
                5 -> 2 * (q.y * q.z - q.w * q.x)
                6 -> 2 * (q.x * q.z - q.w * q.y)
                7 -> 2 * (q.y * q.z + q.w * q.x)
                else -> 1 - 2 * (q.x * q.x + q.y * q.y)
            }
    }
}
