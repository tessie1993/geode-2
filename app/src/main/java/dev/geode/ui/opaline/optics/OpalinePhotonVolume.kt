package dev.geode.ui.opaline.optics

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One photon segment's result: surviving energy, absorbed and scattered loss. */
internal data class PhotonSegment(
    val energy: Double,
    val absorbed: Double,
    val scattered: Double,
)

/**
 * src/optics.js `PhotonVolume` (I04), with its defaults: world-space photon segments integrated
 * through a bounded, world-aligned voxel medium (exterior homogeneous mist) by exact voxel DDA.
 * Each cell receives the Beer-Lambert scattering loss over the exact path length inside it,
 * divided by the cell volume, into six directional grids (+X, −X, +Y, −Y, +Z, −Z) weighted by the
 * squared direction components. [textures] hold RGBA per voxel at x + rx·(y + ry·z) as
 * `THREE.Data3DTexture` does: the six directional grids, then their total.
 */
internal class OpalinePhotonVolume(
    boundsMin: DoubleArray = doubleArrayOf(-2.0, 0.0, -2.0),
    boundsMax: DoubleArray = doubleArrayOf(2.0, 3.0, 2.0),
    resolution: IntArray = intArrayOf(32, 32, 32),
    extinction: Double = .18,
    albedo: Double = .92,
    var onlyCaustics: Boolean = true,
) {
    val boundsMin = boundsMin.copyOf()
    val boundsMax = boundsMax.copyOf()
    val resolution = IntArray(3) { max(4, resolution[it]) }
    var extinction = max(0.0, extinction)
    var albedo = albedo.coerceIn(0.0, 1.0)
    private val cellSize =
        DoubleArray(3) { (this.boundsMax[it] - this.boundsMin[it]) / this.resolution[it] }
    private val voxelVolume = cellSize[0] * cellSize[1] * cellSize[2]
    private val voxels = this.resolution[0] * this.resolution[1] * this.resolution[2]
    private val sums = List(6) { DoubleArray(voxels * 3) }
    val textures = List(7) { FloatArray(voxels * 4) }
    var depositedEnergy = 0.0
        private set

    /** Bumped whenever [textures] change (three.js `needsUpdate`). */
    var version = 0
        private set

    init {
        require(cellSize.all { it > 0 && it.isFinite() }) {
            "Photon volume must have positive finite bounds."
        }
    }

    fun reset() {
        for (sum in sums) sum.fill(0.0)
        for (texture in textures) texture.fill(0f)
        depositedEnergy = 0.0
        version++
    }

    /** The ray's [near, far] parameter interval inside the bounds, or null when it misses. */
    fun interval(
        origin: DoubleArray,
        direction: DoubleArray,
        maxDistance: Double = Double.POSITIVE_INFINITY,
    ): DoubleArray? {
        var near = 0.0
        var far = maxDistance
        for (axis in 0..2) {
            if (abs(direction[axis]) < 1e-12) {
                if (origin[axis] < boundsMin[axis] || origin[axis] > boundsMax[axis]) return null
            } else {
                val a = (boundsMin[axis] - origin[axis]) / direction[axis]
                val b = (boundsMax[axis] - origin[axis]) / direction[axis]
                near = max(near, min(a, b))
                far = min(far, max(a, b))
                if (far <= near) return null
            }
        }
        return doubleArrayOf(near, far)
    }

    /** Exact voxel DDA path lengths; energy deposits are normalized by voxel volume. */
    fun segment(
        origin: DoubleArray,
        direction: DoubleArray,
        maxDistance: Double,
        energy: Double,
        channel: Int,
        record: Boolean,
    ): PhotonSegment {
        val interval = interval(origin, direction, maxDistance)
        if (interval == null || extinction == 0.0) return PhotonSegment(energy, 0.0, 0.0)
        val exit = interval[1]
        var t = interval[0]
        val cell = IntArray(3)
        val step = IntArray(3)
        val next = DoubleArray(3)
        val delta = DoubleArray(3)
        for (a in 0..2) {
            val d = direction[a]
            val p = origin[a] + direction[a] * (t + 1e-8)
            cell[a] = floor((p - boundsMin[a]) / cellSize[a]).toInt().coerceIn(0, resolution[a] - 1)
            step[a] = if (d >= 0) 1 else -1
            val parallel = abs(d) < 1e-12
            delta[a] = if (parallel) Double.POSITIVE_INFINITY else cellSize[a] / abs(d)
            val boundary = boundsMin[a] + (cell[a] + if (d >= 0) 1 else 0) * cellSize[a]
            next[a] = if (parallel) Double.POSITIVE_INFINITY else (boundary - origin[a]) / d
        }
        // Bin 2k + s holds the squared component k of rays travelling in its sign s (+, −).
        val weights =
            DoubleArray(6) { bin ->
                val d = direction[bin / 2]
                if ((if (bin % 2 == 0) d else -d) > 0) d * d else 0.0
            }
        var remaining = energy
        var guard = 0
        while (guard < resolution.sum() + 6 && t < exit - 1e-10) {
            val end = min(exit, minOf(next[0], next[1], next[2]))
            val transmission = exp(-extinction * max(0.0, end - t))
            val scatter = remaining * (1 - transmission) * albedo
            if (record && scatter > 0) deposit(cell, channel, scatter, weights)
            remaining *= transmission
            t = end
            for (a in 0..2) {
                if (next[a] <= end + 1e-10) {
                    cell[a] += step[a]
                    next[a] += delta[a]
                }
            }
            if ((0..2).any { cell[it] < 0 || cell[it] >= resolution[it] }) break
            guard++
        }
        val loss = energy - remaining
        return PhotonSegment(remaining, loss * (1 - albedo), loss * albedo)
    }

    /** Writes every grid scaled to the running estimate, and their total, into [textures]. */
    fun flush(scale: Double) {
        val total = textures[6]
        total.fill(0f)
        for (bin in 0 until 6) {
            val data = textures[bin]
            val sum = sums[bin]
            for (i in 0 until voxels) {
                for (c in 0..2) {
                    data[i * 4 + c] = (sum[i * 3 + c] * scale).toFloat()
                    total[i * 4 + c] += data[i * 4 + c]
                }
                data[i * 4 + 3] = 1f
                total[i * 4 + 3] = 1f
            }
        }
        version++
    }

    private fun deposit(
        cell: IntArray,
        channel: Int,
        scatter: Double,
        weights: DoubleArray,
    ) {
        val index = (cell[0] + resolution[0] * (cell[1] + resolution[1] * cell[2])) * 3 + channel
        for (bin in 0 until 6) {
            if (weights[bin] > 0) sums[bin][index] += scatter * weights[bin] / voxelVolume
        }
        depositedEnergy += scatter
    }
}
