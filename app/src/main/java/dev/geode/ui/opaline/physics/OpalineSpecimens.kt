package dev.geode.ui.opaline.physics

import kotlin.math.sqrt

private const val NX = 7
private const val NY = 5
private const val NZ = 5

/** workbench.js `buildContactSpecimen` pins `y < minY + .12` on its 1.4 m tall body. */
private const val PIN_BAND = .12 / 1.4

/** workbench.js floor plane (offset .35) lies .2 m under that body's .55 m base. */
private const val FLOOR_GAP = .2 / 1.4

/** workbench.js pointer grabs: the nearest six vertices at compliance 1e-7. */
private const val GRAB_VERTICES = 6
private const val GRAB_COMPLIANCE = 1e-7
private const val POINTER = "pointer"

/**
 * The numerical gel of one catalogue part under touch (plan §10: K01 press, K02 hold, K07 drag
 * with inertia, K23 cancel, K24 several pointers). It is workbench.js `buildContactSpecimen`
 * (7 × 5 × 5 cells, edge 1e-5, volume 1e-8, damping 1.8, 10 iterations, mounted base, floor
 * plane, world 1/120 s × 8) sized to the part's catalogue metres. Two declared choices fill the
 * embedding the library leaves to the host: a "box" grid (the workbench's is "ellipsoid") so each
 * render vertex samples it at its normalised position, and a turn that faces the specimen's top
 * to the viewer (contact +Y = part +Z, contact +Z = part −Y; the mount is the part's back).
 */
internal class OpalineGelLattice(
    catalogue: OpalinePhysicsCatalogue,
    width: Float,
    height: Float,
    depth: Float,
) {
    private class Touch(
        val id: String,
        val start: DoubleArray,
        val hit: DoubleArray,
    ) {
        var elapsed = 0.0
        val offset = DoubleArray(3)
    }

    private val width = width.toDouble()
    private val height = height.toDouble()
    private val depth = depth.toDouble()
    private val size = doubleArrayOf(this.width, this.depth, this.height)
    private val press = catalogue.createContactRecipe("K01")
    private val touches = LinkedHashMap<Long, Touch>()
    private var quiet = 0.0
    val world = PhysicsWorld(fixedDt = 1.0 / 120, maxSubsteps = 8)
    val body: SoftBody

    init {
        val grid = makeTetGrid(nx = NX, ny = NY, nz = NZ, size = size)
        val base = (0 until grid.positions.size / 3).minOf { grid.positions(3 * it + 1) }
        val floor =
            PlaneCollider(
                offset = base - FLOOR_GAP * this.depth,
                restitution = press.restitution,
                friction = press.friction,
            )
        body =
            SoftBody(
                grid.positions,
                grid.tetrahedra,
                edgeCompliance = 1e-5,
                volumeCompliance = 1e-8,
                damping = 1.8,
                colliders = listOf(floor),
                iterations = 10,
            )
        for (i in 0 until body.count) {
            if (grid.positions(3 * i + 1) < base + PIN_BAND * this.depth) body.pin(i)
        }
        world.add(body)
    }

    /** True once untouched for the K01 recipe duration (2.8 s); the host may drop the lattice. */
    val settled: Boolean
        get() = touches.isEmpty() && quiet >= press.duration

    /** Pointer down at part-normalised [x], [y], [z] (−1..1, +Y up, +Z front): a K01 grab. */
    fun press(
        pointer: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        release(pointer)
        val hit = contactPoint(x, y, z)
        val indices = contactFootprint(body, hit, press.contacts[0].radius, size)
        val touch = Touch("$POINTER:$pointer", centroid(body, indices), hit)
        body.grab(touch.id, indices, touch.start, press.compliance)
        touches[pointer] = touch
    }

    /** K07: the footprint follows the finger; its velocity survives [release]. */
    fun drag(
        pointer: Long,
        x: Float,
        y: Float,
        z: Float,
    ) {
        val touch = touches[pointer] ?: return
        val point = contactPoint(x, y, z)
        for (a in 0 until 3) touch.offset[a] = point[a] - touch.hit[a]
    }

    fun release(pointer: Long) {
        touches.remove(pointer)?.let { body.releaseGrab(it.id) }
    }

    /** K23: `PhysicsWorld.cancelInteractions` for pointer cancel, blur and disposal. */
    fun cancel() {
        world.cancelInteractions()
        touches.clear()
    }

    /** Advances the K01 press envelope, then the world, by [elapsed] wall seconds. */
    fun update(elapsed: Double) {
        val delta = press.contacts[0].delta
        for (touch in touches.values) {
            touch.elapsed += elapsed
            val amount = contactAmount(touch.elapsed)
            val target = DoubleArray(3) { touch.start[it] + delta[it] * size[it] * amount }
            for (a in 0 until 3) target[a] += touch.offset[a]
            body.moveGrab(touch.id, target)
        }
        quiet = if (touches.isEmpty()) quiet + elapsed else 0.0
        world.step(elapsed)
    }

    /**
     * Writes into `out[offset..offset+2]` the displacement at part-normalised [x], [y], [z] as
     * fractions of the part's half-extents (vertex += value × (maximum − minimum) / 2).
     */
    fun sample(
        x: Float,
        y: Float,
        z: Float,
        out: FloatArray,
        offset: Int,
    ) {
        val gx = (x + 1.0) / 2 * NX
        val gy = (z + 1.0) / 2 * NY
        val gz = (1.0 - y) / 2 * NZ
        val i = gx.toInt().coerceIn(0, NX - 1)
        val j = gy.toInt().coerceIn(0, NY - 1)
        val k = gz.toInt().coerceIn(0, NZ - 1)
        var dx = 0.0
        var dy = 0.0
        var dz = 0.0
        for (corner in 0 until 8) {
            val a = corner and 1
            val b = corner shr 1 and 1
            val c = corner shr 2
            val share = weight(a, gx - i) * weight(b, gy - j) * weight(c, gz - k)
            val node = 3 * (i + a + (NX + 1) * (j + b + (NY + 1) * (k + c)))
            dx += share * (body.positions(node) - body.restPositions(node))
            dy += share * (body.positions(node + 1) - body.restPositions(node + 1))
            dz += share * (body.positions(node + 2) - body.restPositions(node + 2))
        }
        out[offset] = (2 * dx / width).toFloat()
        out[offset + 1] = (-2 * dz / height).toFloat()
        out[offset + 2] = (2 * dy / depth).toFloat()
    }

    private fun weight(
        corner: Int,
        fraction: Double,
    ): Double {
        val f = fraction.coerceIn(0.0, 1.0)
        return if (corner == 0) 1 - f else f
    }

    private fun contactPoint(
        x: Float,
        y: Float,
        z: Float,
    ) = doubleArrayOf(x * width / 2, z * depth / 2, -y * height / 2)
}

/**
 * The Bubble hero's F01 film: the library's motion-preview bubble (tools/render-motion.html
 * `reset`) — makeBubbleMesh (3 subdivisions, radius .61) about the origin, BubbleFilm (380 nm,
 * gas compliance 1e-9, edge 8e-4, drainage × 80000, damping 2, 10 iterations), world 1/120 s × 4.
 * Draw `film.positions` / `film.triangles` with [normals]; `film.thickness[i] × 1e9` is the live
 * film thickness in nm.
 */
internal class OpalineBubbleSpecimen {
    val world = PhysicsWorld(fixedDt = 1.0 / 120, maxSubsteps = 4)
    val film: BubbleFilm
    val normals: FloatArray

    init {
        val mesh = makeBubbleMesh(subdivisions = 3, radius = RADIUS)
        film =
            world.add(
                BubbleFilm(
                    mesh.positions,
                    mesh.triangles,
                    initialThickness = 380e-9,
                    drainageTimeScale = 80000.0,
                    gasCompliance = 1e-9,
                    edgeCompliance = 8e-4,
                    damping = 2.0,
                    iterations = 10,
                ),
            )
        normals = FloatArray(film.positions.size)
        computeVertexNormals(film.positions, film.triangles, normals)
    }

    fun update(elapsed: Double) {
        world.step(elapsed)
        computeVertexNormals(film.positions, film.triangles, normals)
    }

    /** workbench.js `buildBubbleSpecimen` pointer grab: six vertices nearest [point], 1e-7. */
    fun grab(point: DoubleArray) {
        val p = film.positions
        val nearest =
            (0 until film.count).sortedBy {
                hypot(p(3 * it) - point[0], p(3 * it + 1) - point[1], p(3 * it + 2) - point[2])
            }
        val indices = nearest.take(GRAB_VERTICES).toIntArray()
        film.grab(POINTER, indices, point, GRAB_COMPLIANCE)
    }

    fun moveGrab(point: DoubleArray) = film.moveGrab(POINTER, point)

    fun releaseGrab() = film.releaseGrab(POINTER)

    companion object {
        const val RADIUS = .61
    }
}

/**
 * The Drop hero's liquid: workbench.js `buildFluidSpecimen` — 11 × 5 × 8 particles .17 m apart,
 * PBFFluid (particle radius .065, smoothing .29, bounds [−1, −.1, −.85]..[1.3, 1.5, .85],
 * 4 iterations, gravity −3.2), world 1/90 s × 5. Draw `fluid.count` particles of
 * `fluid.positions` with `fluid.pigment`; L13 packets move through `remove` / `inject`.
 */
internal class OpalineFluidSpecimen {
    val world = PhysicsWorld(fixedDt = 1.0 / 90, maxSubsteps = 5)
    val fluid: PBFFluid

    init {
        val positions = FloatArray(11 * 5 * 8 * 3)
        for (v in 0 until 11 * 5 * 8) {
            positions[3 * v] = ((v % 11 - 5) * .17 + .15).toFloat()
            positions[3 * v + 1] = (v / 88 * .17 + .35).toFloat()
            positions[3 * v + 2] = ((v / 11 % 8 - 3.5) * .17).toFloat()
        }
        val bounds =
            BoxCollider(min = doubleArrayOf(-1.0, -.1, -.85), max = doubleArrayOf(1.3, 1.5, .85))
        fluid =
            world.add(
                PBFFluid(
                    positions,
                    particleRadius = .065,
                    smoothingRadius = .29,
                    iterations = 4,
                    gravity = doubleArrayOf(0.0, -3.2, 0.0),
                    bounds = bounds,
                ),
            )
    }

    fun update(elapsed: Double) {
        world.step(elapsed)
    }

    /** workbench.js touch: `addImpulse(point, 1.8, [s × 1.4, s × 3, .5])` in specimen metres. */
    fun impulse(
        point: DoubleArray,
        strength: Double,
    ) = fluid.addImpulse(point, 1.8, doubleArrayOf(strength * 1.4, strength * 3, .5))
}

/** workbench.js particle material: emission → glow, transmission → shell, otherwise nacre. */
internal fun particleFamily(preset: ParticlePreset): String =
    when {
        (preset.emission ?: 0.0) != 0.0 -> "glow"
        (preset.transmission ?: 0.0) != 0.0 -> "shell"
        else -> "nacre"
    }

/**
 * workbench.js `buildParticleSpecimen` instance of particle [i] into `out[offset..offset+15]`
 * (column-major, three.js Matrix4.compose): position, orientation, scale radius × 1.55, and
 * petals × [.6, 1.8, .24] (drawn as spheres, the rest as icosahedra).
 */
internal fun writeParticleMatrix(
    system: ParticleSystem,
    i: Int,
    out: FloatArray,
    offset: Int,
) {
    val r = system.radii(i) * 1.55
    val petal = system.preset?.shape == "petal"
    val sx = if (petal) r * .6 else r
    val sy = if (petal) r * 1.8 else r
    val sz = if (petal) r * .24 else r
    val q = system.orientations
    val x = q(4 * i)
    val y = q(4 * i + 1)
    val z = q(4 * i + 2)
    val w = q(4 * i + 3)
    val xx = x * (x + x)
    val xy = x * (y + y)
    val xz = x * (z + z)
    val yy = y * (y + y)
    val yz = y * (z + z)
    val zz = z * (z + z)
    val wx = w * (x + x)
    val wy = w * (y + y)
    val wz = w * (z + z)
    val m =
        doubleArrayOf(
            (1 - (yy + zz)) * sx,
            (xy + wz) * sx,
            (xz - wy) * sx,
            0.0,
            (xy - wz) * sy,
            (1 - (xx + zz)) * sy,
            (yz + wx) * sy,
            0.0,
            (xz + wy) * sz,
            (yz - wx) * sz,
            (1 - (xx + yy)) * sz,
            0.0,
            system.positions(3 * i),
            system.positions(3 * i + 1),
            system.positions(3 * i + 2),
            1.0,
        )
    for (e in 0 until 16) out[offset + e] = m[e].toFloat()
}

/**
 * workbench.js trail sync: line-segment vertex pairs from the newest sample backwards (drawn in
 * 0x99d8f2 at opacity .22). [out] holds `capacity × (trailLength − 1) × 6` floats; returns the
 * vertex count.
 */
internal fun writeTrailSegments(
    system: ParticleSystem,
    out: FloatArray,
): Int {
    val length = system.trailLength
    var k = 0
    for (i in 0 until system.count) {
        for (j in 0 until length - 1) {
            val a = ((system.trailHead - j + length) % length * system.capacity + i) * 3
            val b = ((system.trailHead - j - 1 + length) % length * system.capacity + i) * 3
            system.trails.copyInto(out, k, a, a + 3)
            system.trails.copyInto(out, k + 3, b, b + 3)
            k += 6
        }
    }
    return k / 3
}

/** three.js BufferGeometry.computeVertexNormals for an indexed mesh (area-weighted). */
internal fun computeVertexNormals(
    positions: FloatArray,
    indices: IntArray,
    out: FloatArray,
) {
    out.fill(0f)
    for (t in 0 until indices.size step 3) {
        val a = 3 * indices[t]
        val b = 3 * indices[t + 1]
        val c = 3 * indices[t + 2]
        val cbx = positions(c) - positions(b)
        val cby = positions(c + 1) - positions(b + 1)
        val cbz = positions(c + 2) - positions(b + 2)
        val abx = positions(a) - positions(b)
        val aby = positions(a + 1) - positions(b + 1)
        val abz = positions(a + 2) - positions(b + 2)
        val nx = cby * abz - cbz * aby
        val ny = cbz * abx - cbx * abz
        val nz = cbx * aby - cby * abx
        for (q in 0 until 3) {
            val v = 3 * indices[t + q]
            out[v] = (out(v) + nx).toFloat()
            out[v + 1] = (out(v + 1) + ny).toFloat()
            out[v + 2] = (out(v + 2) + nz).toFloat()
        }
    }
    for (v in 0 until out.size step 3) {
        val length = sqrt(out(v) * out(v) + out(v + 1) * out(v + 1) + out(v + 2) * out(v + 2))
        val scale = 1 / (if (length == 0.0) 1.0 else length)
        for (a in 0 until 3) out[v + a] = (out(v + a) * scale).toFloat()
    }
}
