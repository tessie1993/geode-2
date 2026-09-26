package dev.geode.ui.opaline.transitions

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local-space vertex data as a three.js r186 `BufferGeometry` holds it: Float32 positions,
 * normals and uvs, optional triangle indices, geometry.js's absolute morph targets
 * (`morphTargetsRelative = false`, only for the bounding box) and the L15 annulus metadata
 * (`userData.aperture`: angle, radial fraction, side per vertex). [version] increases whenever
 * the system rewrites the vertices (it recomputes normals after every rewrite); the renderer
 * re-uploads on a new version.
 */
internal class TransitionGeometry(
    val positions: FloatArray,
    val indices: IntArray?,
    normals: FloatArray = FloatArray(0),
    val uvs: FloatArray = FloatArray(0),
    val morphPositions: List<FloatArray> = emptyList(),
    val aperture: DoubleArray? = null,
) {
    var normals = normals
        private set
    var version = 0
        private set

    val count: Int
        get() = positions.size / 3

    /** `geometry.clone()`. */
    fun clone(): TransitionGeometry =
        TransitionGeometry(
            positions.copyOf(),
            indices?.copyOf(),
            normals.copyOf(),
            uvs.copyOf(),
            morphPositions.map { it.copyOf() },
            aperture?.copyOf(),
        )

    /** transitions.js `sameTopology(this, other)`: same vertex count and identical indices. */
    fun sameTopology(other: TransitionGeometry): Boolean {
        val own = indices
        val theirs = other.indices
        if (count != other.count || (own == null) != (theirs == null)) return false
        return own == null || (theirs != null && own.contentEquals(theirs))
    }

    /**
     * `computeBoundingBox()` then `getCenter(center)` and `getSize(size)`: the box over the
     * positions and every absolute morph target; zero for an empty geometry.
     */
    fun bounds(
        center: TransitionVector,
        size: TransitionVector,
    ) {
        val box = extent(positions)
        for (morph in morphPositions) {
            val extra = extent(morph)
            for (axis in 0..2) {
                box[axis] = min(box[axis], min(extra[axis], extra[axis + 3]))
                box[axis + 3] = max(box[axis + 3], max(extra[axis], extra[axis + 3]))
            }
        }
        if (box[3] < box[0] || box[4] < box[1] || box[5] < box[2]) {
            center.set(0.0, 0.0, 0.0)
            size.set(0.0, 0.0, 0.0)
            return
        }
        center.set((box[0] + box[3]) * 0.5, (box[1] + box[4]) * 0.5, (box[2] + box[5]) * 0.5)
        size.set(box[3] - box[0], box[4] - box[1], box[5] - box[2])
    }

    /** `computeVertexNormals()` (r186): face normals summed per vertex in Float32, normalised. */
    fun computeVertexNormals() {
        if (normals.size == positions.size) {
            normals.fill(0f)
        } else {
            normals = FloatArray(positions.size)
        }
        val index = indices
        if (index != null) {
            for (i in 0 until index.size step 3) accumulate(index[i], index[i + 1], index[i + 2])
        } else {
            for (i in 0 until count step 3) accumulate(i, i + 1, i + 2)
        }
        for (k in normals.indices step 3) {
            val x = normals[k].toDouble()
            val y = normals[k + 1].toDouble()
            val z = normals[k + 2].toDouble()
            val length = sqrt(x * x + y * y + z * z)
            val inverse = 1 / if (length == 0.0 || length.isNaN()) 1.0 else length
            normals[k] = (x * inverse).toFloat()
            normals[k + 1] = (y * inverse).toFloat()
            normals[k + 2] = (z * inverse).toFloat()
        }
        version++
    }

    /** One face of computeVertexNormals: cb = (pC − pB) × (pA − pB) added to each corner. */
    private fun accumulate(
        a: Int,
        b: Int,
        c: Int,
    ) {
        val bx = positions[b * 3].toDouble()
        val by = positions[b * 3 + 1].toDouble()
        val bz = positions[b * 3 + 2].toDouble()
        val cbx = positions[c * 3] - bx
        val cby = positions[c * 3 + 1] - by
        val cbz = positions[c * 3 + 2] - bz
        val abx = positions[a * 3] - bx
        val aby = positions[a * 3 + 1] - by
        val abz = positions[a * 3 + 2] - bz
        val nx = cby * abz - cbz * aby
        val ny = cbz * abx - cbx * abz
        val nz = cbx * aby - cby * abx
        // A repeated corner makes the product zero, so adding corner by corner matches r186.
        add(a, nx, ny, nz)
        add(b, nx, ny, nz)
        add(c, nx, ny, nz)
    }

    private fun add(
        vertex: Int,
        x: Double,
        y: Double,
        z: Double,
    ) {
        normals[vertex * 3] = (normals[vertex * 3] + x).toFloat()
        normals[vertex * 3 + 1] = (normals[vertex * 3 + 1] + y).toFloat()
        normals[vertex * 3 + 2] = (normals[vertex * 3 + 2] + z).toFloat()
    }

    companion object {
        /** three.js r186 `TubeGeometry(path, tubularSegments, radius, radialSegments, false)`. */
        fun tube(
            path: TransitionCurve,
            tubularSegments: Int,
            radius: Double,
            radialSegments: Int,
        ): TransitionGeometry {
            val frames = path.computeFrenetFrames(tubularSegments)
            val columns = radialSegments + 1
            val vertices = (tubularSegments + 1) * columns
            val positions = FloatArray(vertices * 3)
            val normals = FloatArray(vertices * 3)
            val uvs = FloatArray(vertices * 2)
            val point = TransitionVector()
            val normal = TransitionVector()
            for (i in 0..tubularSegments) {
                path.getPointAt(i.toDouble() / tubularSegments, point)
                val n = frames.normals[i]
                val b = frames.binormals[i]
                for (j in 0..radialSegments) {
                    val v = j.toDouble() / radialSegments * PI * 2
                    val sine = sin(v)
                    val cosine = -cos(v)
                    normal.set(
                        cosine * n.x + sine * b.x,
                        cosine * n.y + sine * b.y,
                        cosine * n.z + sine * b.z,
                    )
                    normal.normalize()
                    val k = i * columns + j
                    put(normals, k, normal.x, normal.y, normal.z)
                    put(
                        positions,
                        k,
                        point.x + radius * normal.x,
                        point.y + radius * normal.y,
                        point.z + radius * normal.z,
                    )
                    uvs[k * 2] = (i.toDouble() / tubularSegments).toFloat()
                    uvs[k * 2 + 1] = (j.toDouble() / radialSegments).toFloat()
                }
            }
            val indices = IntArray(tubularSegments * radialSegments * 6)
            var cursor = 0
            for (j in 1..tubularSegments) {
                for (i in 1..radialSegments) {
                    val a = columns * (j - 1) + (i - 1)
                    val b = columns * j + (i - 1)
                    val c = columns * j + i
                    val d = columns * (j - 1) + i
                    for (corner in intArrayOf(a, b, d, b, c, d)) indices[cursor++] = corner
                }
            }
            return TransitionGeometry(positions, indices, normals, uvs)
        }

        /** three.js r186 `new SphereGeometry(radius, widthSegments, heightSegments)`. */
        fun sphere(
            radius: Double,
            widthSegments: Int,
            heightSegments: Int,
        ): TransitionGeometry {
            val columns = widthSegments + 1
            val vertices = (heightSegments + 1) * columns
            val positions = FloatArray(vertices * 3)
            val normals = FloatArray(vertices * 3)
            val uvs = FloatArray(vertices * 2)
            val vertex = TransitionVector()
            for (iy in 0..heightSegments) {
                val v = iy.toDouble() / heightSegments
                val y = radius * cos(v * PI)
                val ring = sqrt(radius * radius - y * y)
                val uOffset =
                    when (iy) {
                        0 -> 0.5 / widthSegments
                        heightSegments -> -0.5 / widthSegments
                        else -> 0.0
                    }
                for (ix in 0..widthSegments) {
                    val u = ix.toDouble() / widthSegments
                    val phi = u * (PI * 2)
                    val k = iy * columns + ix
                    vertex.set(-ring * cos(phi), y, ring * sin(phi))
                    put(positions, k, vertex.x, vertex.y, vertex.z)
                    vertex.normalize()
                    put(normals, k, vertex.x, vertex.y, vertex.z)
                    uvs[k * 2] = (u + uOffset).toFloat()
                    uvs[k * 2 + 1] = (1 - v).toFloat()
                }
            }
            val indices = mutableListOf<Int>()
            for (iy in 0 until heightSegments) {
                for (ix in 0 until widthSegments) {
                    val a = iy * columns + ix + 1
                    val b = iy * columns + ix
                    val c = (iy + 1) * columns + ix
                    val d = (iy + 1) * columns + ix + 1
                    if (iy != 0) indices += listOf(a, b, d)
                    if (iy != heightSegments - 1) indices += listOf(b, c, d)
                }
            }
            return TransitionGeometry(positions, indices.toIntArray(), normals, uvs)
        }

        /**
         * transitions.js `apertureGeometry(96, 8)`: a finite-thickness unit annulus with front
         * (z .045), back (z −.045), inner wall and outer wall.
         */
        fun aperture(): TransitionGeometry {
            val segments = 96
            val radial = 8
            val perSide = (radial + 1) * (segments + 1)
            val positions = FloatArray(perSide * 6)
            val uvs = FloatArray(perSide * 4)
            val meta = DoubleArray(perSide * 6)
            val indices = mutableListOf<Int>()
            for ((surface, side) in intArrayOf(1, -1).withIndex()) {
                val start = surface * perSide
                for (j in 0..radial) {
                    for (i in 0..segments) {
                        val a = i.toDouble() / segments * PI * 2
                        val r = j.toDouble() / radial
                        val k = start + j * (segments + 1) + i
                        put(positions, k, cos(a), sin(a), side * .045)
                        uvs[k * 2] = (i.toDouble() / segments).toFloat()
                        uvs[k * 2 + 1] = r.toFloat()
                        meta[k * 3] = a
                        meta[k * 3 + 1] = r
                        meta[k * 3 + 2] = side.toDouble()
                    }
                }
                faces(indices, start, side, segments, radial)
            }
            for (i in 0 until segments) {
                val c = perSide + i
                indices += listOf(i, c, c + 1, i, c + 1, i + 1)
                val e = radial * (segments + 1) + i
                val g = perSide + e
                indices += listOf(e, e + 1, g + 1, e, g + 1, g)
            }
            val annulus =
                TransitionGeometry(positions, indices.toIntArray(), uvs = uvs, aperture = meta)
            annulus.computeVertexNormals()
            return annulus
        }

        /** One annulus surface's quads, wound outward for the front (side 1) and back (−1). */
        private fun faces(
            indices: MutableList<Int>,
            start: Int,
            side: Int,
            segments: Int,
            radial: Int,
        ) {
            for (j in 0 until radial) {
                for (i in 0 until segments) {
                    val a = start + j * (segments + 1) + i
                    val c = a + segments + 1
                    indices +=
                        if (side > 0) {
                            listOf(a, a + 1, c + 1, a, c + 1, c)
                        } else {
                            listOf(a, c + 1, a + 1, a, c, c + 1)
                        }
                }
            }
        }

        /** `Box3.setFromBufferAttribute`: min xyz then max xyz. */
        private fun extent(values: FloatArray): DoubleArray {
            val box =
                doubleArrayOf(
                    Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY,
                    Double.NEGATIVE_INFINITY,
                    Double.NEGATIVE_INFINITY,
                )
            for (k in values.indices) {
                val axis = k % 3
                box[axis] = min(box[axis], values[k].toDouble())
                box[axis + 3] = max(box[axis + 3], values[k].toDouble())
            }
            return box
        }

        private fun put(
            array: FloatArray,
            vertex: Int,
            x: Double,
            y: Double,
            z: Double,
        ) {
            array[vertex * 3] = x.toFloat()
            array[vertex * 3 + 1] = y.toFloat()
            array[vertex * 3 + 2] = z.toFloat()
        }
    }
}
