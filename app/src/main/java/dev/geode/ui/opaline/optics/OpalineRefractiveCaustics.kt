package dev.geode.ui.opaline.optics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private const val AIR = 1.0
private const val EPS = 1e-4

/** `this._raycaster.near = EPS * .25`. */
private const val NEAR = EPS * .25

/** src/optics.js `WAVELENGTHS`: representative RGB transport wavelengths in micrometres. */
private val WAVELENGTHS = doubleArrayOf(.610, .550, .460)
private val X_AXIS = doubleArrayOf(1.0, 0.0, 0.0)
private val Y_AXIS = doubleArrayOf(0.0, 1.0, 0.0)

/** RefractiveCaustics `transport`: which photon paths may deposit on the receiver. */
internal enum class CausticTransport {
    TRANSMISSION,
    REFLECTION,
    SPLIT,
}

/** `statistics.energy`: the categories sum to the launched energy (docs/OPTICS.md). */
internal data class CausticEnergy(
    var launched: Double = 0.0,
    var receiver: Double = 0.0,
    var escaped: Double = 0.0,
    var absorbed: Double = 0.0,
    var scattered: Double = 0.0,
    var discarded: Double = 0.0,
    var cutoff: Double = 0.0,
)

internal data class CausticStatistics(
    val samples: Long,
    val deposited: Long,
    val transportEvents: Long,
    val revision: Int,
    val volumeDepositedEnergy: Double,
    val energy: CausticEnergy,
)

/**
 * src/optics.js `RefractiveCaustics` (I01–I04, I12, I13) with its defaults: CPU photon transport
 * through the current triangles of [refractors] and [reflectors] onto [receiver], [occluders]
 * absorbing. Each [update] adds one deterministic Halton batch over the [aperture] facing
 * [lightTarget]; each photon's three bands follow Snell refraction, exact dielectric Fresnel,
 * total internal reflection and nested media with Beer-Lambert absorption, within the bounce,
 * branch and energy cutoffs. Receiver hits deposit Gaussian splats divided by the
 * world-area/UV-area Jacobian into [pixels]; a [volume] takes the exterior segments. Any change
 * of light, transport, volume or a mesh's vertices, matrix or material resets the estimate.
 * All inputs share one world frame. No GL work: [OpalineCausticTextures] uploads the result.
 */
internal class OpalineRefractiveCaustics(
    val receiver: OpticalMesh,
    val refractors: List<OpticalMesh> = emptyList(),
    val reflectors: List<OpticalMesh> = emptyList(),
    val occluders: List<OpticalMesh> = emptyList(),
    resolution: Int = 128,
    photonsPerUpdate: Int = 768,
    lightPosition: DoubleArray = doubleArrayOf(3.0, 7.0, 3.0),
    lightTarget: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    aperture: DoubleArray = doubleArrayOf(8.0, 8.0),
    var irradiance: Double = 2.0,
    var maxBounces: Int = 12,
    var splatRadius: Double = 1.3,
    var transport: CausticTransport = CausticTransport.TRANSMISSION,
    val volume: OpalinePhotonVolume? = null,
    var minPhotonEnergy: Double = 1e-5,
    var maxBranches: Int = 128,
) {
    val resolution = max(16, resolution)
    val photonsPerUpdate = max(1, photonsPerUpdate)
    val lightPosition = lightPosition.copyOf()
    val lightTarget = lightTarget.copyOf()
    val aperture = aperture.copyOf()

    /** Receiver irradiance, RGBA per texel; texel (x, y) sits at uv · (resolution − 1). */
    val pixels = FloatArray(this.resolution * this.resolution * 4)

    /** Bumped whenever [pixels] change (three.js `texture.needsUpdate`). */
    var version = 0
        private set
    var samples = 0L
        private set
    var deposited = 0L
        private set
    var transportEvents = 0L
        private set
    var revision = 0
        private set
    private val sum = DoubleArray(this.resolution * this.resolution * 3)
    private val cache = HashMap<OpticalMesh, Proxy>()
    private var proxies = emptyList<Proxy>()
    private var signature = emptyList<Any?>()
    private var ledger = CausticEnergy()
    private var branches = 0
    private val hit = Hit()
    private val corners = List(3) { DoubleArray(3) }

    init {
        requireNotNull(receiver.uvs) { "Caustic receiver geometry needs UV coordinates." }
    }

    val statistics: CausticStatistics
        get() =
            CausticStatistics(
                samples = samples,
                deposited = deposited,
                transportEvents = transportEvents,
                revision = revision,
                volumeDepositedEnergy = volume?.depositedEnergy ?: 0.0,
                energy = ledger.copy(),
            )

    /** Adds one deterministic photon batch; a changed scene first resets the accumulation. */
    fun update(
        photons: Int = photonsPerUpdate,
        forceReset: Boolean = false,
    ) {
        refreshProxies()
        val current = stateSignature()
        if (forceReset || current != signature) {
            reset()
            signature = current
        }
        val direction = normalize(DoubleArray(3) { lightTarget[it] - lightPosition[it] })
        if (dot(direction, direction) < .5) return
        val right = normalize(cross(direction, if (abs(direction[1]) > .95) X_AXIS else Y_AXIS))
        val vertical = normalize(cross(right, direction))
        for (i in 0 until photons) {
            val index = samples + i + 1
            val across = (radicalInverse(index, 2) - .5) * aperture[0]
            val up = (radicalInverse(index, 3) - .5) * aperture[1]
            val origin =
                DoubleArray(3) { lightPosition[it] + right[it] * across + vertical[it] * up }
            // Transport all three spectral bands through the same incident sample.
            for (channel in 0..2) trace(origin, direction, channel)
        }
        samples += photons
        val scale = irradiance * aperture[0] * aperture[1] / max(1L, samples)
        for (i in 0 until sum.size / 3) {
            for (c in 0..2) pixels[i * 4 + c] = (sum[i * 3 + c] * scale).toFloat()
            pixels[i * 4 + 3] = 1f
        }
        version++
        volume?.flush(scale)
    }

    fun reset() {
        sum.fill(0.0)
        pixels.fill(0f)
        samples = 0
        deposited = 0
        transportEvents = 0
        revision++
        version++
        volume?.reset()
        ledger = CausticEnergy()
    }

    private fun refreshProxies() {
        val sources = LinkedHashSet<OpticalMesh>()
        sources.addAll(refractors)
        sources.addAll(reflectors)
        sources.add(receiver)
        sources.addAll(occluders)
        proxies =
            sources.map { source ->
                cache.getOrPut(source) { Proxy(source) }.also { it.refresh(kindOf(source)) }
            }
    }

    private fun kindOf(source: OpticalMesh): Kind =
        when (source) {
            receiver -> Kind.RECEIVER
            in occluders -> Kind.OCCLUDER
            in reflectors -> Kind.REFLECTOR
            else -> Kind.REFRACTOR
        }

    private fun stateSignature(): List<Any?> =
        buildList {
            addAll(lightPosition.toList())
            addAll(lightTarget.toList())
            addAll(aperture.toList())
            add(irradiance)
            add(transport)
            if (volume != null) {
                addAll(volume.boundsMin.toList())
                addAll(volume.boundsMax.toList())
                add(volume.extinction)
                add(volume.albedo)
                add(volume.onlyCaustics)
            }
            for (proxy in proxies) {
                add(proxy.mesh)
                add(proxy.mesh.version)
                addAll(proxy.mesh.matrix.toList())
                add(proxy.mesh.material)
            }
        }

    private fun trace(
        origin: DoubleArray,
        direction: DoubleArray,
        channel: Int,
    ) {
        val pending = arrayListOf(Packet(origin, direction, 1.0, emptyList(), 0, false, false))
        ledger.launched++
        branches = 0
        while (pending.isNotEmpty()) {
            propagate(pending.removeAt(pending.lastIndex), channel, pending)
        }
    }

    private fun enqueue(
        packet: Packet,
        pending: MutableList<Packet>,
    ) {
        val spent = packet.energy < minPhotonEnergy || packet.depth >= maxBounces
        if (spent || branches >= maxBranches) {
            ledger.cutoff += packet.energy
        } else {
            pending.add(packet)
            branches++
        }
    }

    private fun propagate(
        packet: Packet,
        channel: Int,
        pending: MutableList<Packet>,
    ) {
        val found = intersect(packet.origin, packet.direction)
        val medium = packet.stack.lastOrNull()
        val length = if (found) hit.distance else exitLength(packet)
        var energy = packet.energy
        if (medium != null) energy = attenuate(medium, channel, length, energy)
        // Mist occupies the exterior medium, not the interior of dielectric solids.
        if (volume != null && medium == null) {
            val record = !volume.onlyCaustics || packet.hadRefraction || packet.hadReflection
            val result =
                volume.segment(packet.origin, packet.direction, length, energy, channel, record)
            energy = result.energy
            ledger.absorbed += result.absorbed
            ledger.scattered += result.scattered
        }
        if (!found) {
            ledger.escaped += energy
            return
        }
        val proxy = proxies[hit.proxy]
        when (proxy.kind) {
            Kind.OCCLUDER -> ledger.absorbed += energy
            Kind.RECEIVER -> receive(proxy, packet, channel, energy)
            Kind.REFLECTOR -> reflectAt(proxy, packet, channel, energy, pending)
            Kind.REFRACTOR -> refractAt(proxy, packet, channel, energy, pending)
        }
    }

    private fun exitLength(packet: Packet): Double =
        volume?.interval(packet.origin, packet.direction)?.get(1) ?: 0.0

    private fun attenuate(
        medium: Medium,
        channel: Int,
        length: Double,
        energy: Double,
    ): Double {
        val material = medium.material ?: return energy
        val distance = material.attenuationDistance.toDouble()
        if (distance <= 0 || !distance.isFinite()) return energy
        val absorption = max(1e-6, material.attenuationColor[channel].toDouble())
        val remaining = energy * absorption.pow(length / distance)
        ledger.absorbed += energy - remaining
        return remaining
    }

    private fun receive(
        proxy: Proxy,
        packet: Packet,
        channel: Int,
        energy: Double,
    ) {
        val eligible =
            when (transport) {
                CausticTransport.REFLECTION -> packet.hadReflection
                CausticTransport.TRANSMISSION -> packet.hadRefraction
                CausticTransport.SPLIT -> packet.hadRefraction || packet.hadReflection
            }
        if (eligible) {
            deposit(proxy, channel, energy)
            ledger.receiver += energy
        } else {
            ledger.discarded += energy
        }
    }

    /** `_deposit`: a normalised Gaussian splat at the hit UV over the texel's world area. */
    private fun deposit(
        proxy: Proxy,
        channel: Int,
        energy: Double,
    ) {
        val uvs = proxy.mesh.uvs ?: return
        val a = proxy.mesh.indices[hit.triangle * 3] * 2
        val b = proxy.mesh.indices[hit.triangle * 3 + 1] * 2
        val c = proxy.mesh.indices[hit.triangle * 3 + 2] * 2
        val u = uvs[a] * hit.wa + uvs[b] * hit.wb + uvs[c] * hit.wc
        val v = uvs[a + 1] * hit.wa + uvs[b + 1] * hit.wb + uvs[c + 1] * hit.wc
        if (u !in 0.0..1.0 || v !in 0.0..1.0) return
        val area = pixelArea(proxy, uvs)
        if (!area.isFinite() || area <= 1e-12) return
        val x = u * (resolution - 1)
        val y = v * (resolution - 1)
        val reach = splatRadius * 2
        val minX = max(0, floor(x - reach).toInt())
        val maxX = min(resolution - 1, ceil(x + reach).toInt())
        val minY = max(0, floor(y - reach).toInt())
        val maxY = min(resolution - 1, ceil(y + reach).toInt())
        var weightSum = 0.0
        for (py in minY..maxY) for (px in minX..maxX) weightSum += splat(px - x, py - y)
        for (py in minY..maxY) {
            for (px in minX..maxX) {
                val w = splat(px - x, py - y) / weightSum
                sum[(py * resolution + px) * 3 + channel] += energy * w / area
            }
        }
        deposited++
    }

    private fun splat(
        dx: Double,
        dy: Double,
    ): Double = exp(-(dx * dx + dy * dy) / (2 * splatRadius * splatRadius))

    /** `_pixelArea`: world triangle area over UV area, per receiver texel. */
    private fun pixelArea(
        proxy: Proxy,
        uvs: FloatArray,
    ): Double {
        val a = proxy.mesh.indices[hit.triangle * 3]
        val b = proxy.mesh.indices[hit.triangle * 3 + 1]
        val c = proxy.mesh.indices[hit.triangle * 3 + 2]
        proxy.corner(a, corners[0])
        proxy.corner(b, corners[1])
        proxy.corner(c, corners[2])
        val ab = DoubleArray(3) { corners[1][it] - corners[0][it] }
        val ac = DoubleArray(3) { corners[2][it] - corners[0][it] }
        val normal = cross(ab, ac)
        val area = sqrt(dot(normal, normal)) * .5
        val ubx = uvs[b * 2].toDouble() - uvs[a * 2]
        val uby = uvs[b * 2 + 1].toDouble() - uvs[a * 2 + 1]
        val ucx = uvs[c * 2].toDouble() - uvs[a * 2]
        val ucy = uvs[c * 2 + 1].toDouble() - uvs[a * 2 + 1]
        val uvArea = abs(ubx * ucy - uby * ucx) * .5
        if (uvArea <= 1e-12) return Double.POSITIVE_INFINITY
        return area / uvArea / (resolution * resolution)
    }

    /** A dedicated reflector: metal colour, dielectric Fresnel, or the authored reflectance. */
    private fun reflectAt(
        proxy: Proxy,
        packet: Packet,
        channel: Int,
        energy: Double,
        pending: MutableList<Packet>,
    ) {
        val material = proxy.mesh.material
        val normal = facing(packet.direction)
        val cosIncident = (-dot(packet.direction, normal)).coerceIn(0.0, 1.0)
        val outside = packet.stack.lastOrNull()?.let { spectralIor(it.material, channel) } ?: AIR
        val fresnel = fresnelDielectric(cosIncident, outside, spectralIor(material, channel))
        val metal = material?.metalness?.toDouble() ?: 0.0
        val color = material?.color?.get(channel)?.toDouble() ?: 1.0
        val authored = material?.reflectance?.get(channel)?.toDouble()
        val reflectance = (authored ?: (metal * color + (1 - metal) * fresnel)).coerceIn(0.0, 1.0)
        val reflected = reflect(packet.direction, normal)
        ledger.absorbed += energy * (1 - reflectance)
        enqueue(
            Packet(
                offset(hit.point, reflected),
                reflected,
                energy * reflectance,
                packet.stack,
                packet.depth + 1,
                packet.hadRefraction,
                true,
            ),
            pending,
        )
        transportEvents++
    }

    /** A dielectric interface: the medium stack gives n1 and n2, Fresnel splits the energy. */
    private fun refractAt(
        proxy: Proxy,
        packet: Packet,
        channel: Int,
        energy: Double,
        pending: MutableList<Packet>,
    ) {
        val material = proxy.mesh.material
        val stack = packet.stack
        val entering = dot(packet.direction, hit.normal) < 0
        val normal = facing(packet.direction)
        val cosIncident = (-dot(packet.direction, normal)).coerceIn(0.0, 1.0)
        var n1 = stack.lastOrNull()?.let { spectralIor(it.material, channel) } ?: AIR
        val n2: Double
        val proposed: List<Medium>
        if (entering) {
            n2 = spectralIor(material, channel)
            proposed = stack + Medium(proxy.mesh, material)
        } else {
            val index = stack.indexOfLast { it.mesh === proxy.mesh }
            // The origin was inside a boundary.
            if (index < 0) n1 = spectralIor(material, channel)
            proposed = if (index < 0) stack else stack.filterIndexed { i, _ -> i != index }
            n2 = proposed.lastOrNull()?.let { spectralIor(it.material, channel) } ?: AIR
        }
        val transmitted = refractDirection(packet.direction, normal, n1, n2)
        val fresnel = if (transmitted == null) 1.0 else fresnelDielectric(cosIncident, n1, n2)
        if (transport != CausticTransport.TRANSMISSION || transmitted == null) {
            val reflected = reflect(packet.direction, normal)
            val next =
                Packet(
                    offset(hit.point, reflected),
                    reflected,
                    energy * fresnel,
                    stack,
                    packet.depth + 1,
                    packet.hadRefraction,
                    true,
                )
            enqueue(next, pending)
        } else {
            ledger.discarded += energy * fresnel
        }
        if (transmitted != null && transport == CausticTransport.REFLECTION) {
            ledger.discarded += energy * (1 - fresnel)
        } else if (transmitted != null) {
            val next =
                Packet(
                    offset(hit.point, transmitted),
                    transmitted,
                    energy * (1 - fresnel),
                    proposed,
                    packet.depth + 1,
                    true,
                    packet.hadReflection,
                )
            enqueue(next, pending)
        }
        transportEvents++
    }

    /** The hit's geometric normal turned toward the incoming ray (`entering ? n : −n`). */
    private fun facing(direction: DoubleArray): DoubleArray {
        val sign = if (dot(direction, hit.normal) < 0) 1.0 else -1.0
        return DoubleArray(3) { hit.normal[it] * sign }
    }

    /** `_intersect`: the nearest proxy hit at least NEAR away, as Raycaster sorts intersections. */
    private fun intersect(
        origin: DoubleArray,
        direction: DoubleArray,
    ): Boolean {
        hit.proxy = -1
        hit.distance = Double.POSITIVE_INFINITY
        for (index in proxies.indices) proxies[index].raycast(index, origin, direction, hit)
        if (hit.proxy < 0) return false
        proxies[hit.proxy].normal(hit.triangle, hit.normal)
        return true
    }

    private enum class Kind {
        REFRACTOR,
        REFLECTOR,
        RECEIVER,
        OCCLUDER,
    }

    private class Packet(
        val origin: DoubleArray,
        val direction: DoubleArray,
        val energy: Double,
        val stack: List<Medium>,
        val depth: Int,
        val hadRefraction: Boolean,
        val hadReflection: Boolean,
    )

    private class Medium(
        val mesh: OpticalMesh,
        val material: OpticalMaterial?,
    )

    /** The nearest intersection: proxy index, triangle, world distance, barycentric weights. */
    private class Hit {
        var proxy = -1
        var triangle = 0
        var distance = Double.POSITIVE_INFINITY
        var wa = 0.0
        var wb = 0.0
        var wc = 0.0
        val point = DoubleArray(3)
        val normal = DoubleArray(3)
    }

    /**
     * One source as three.js r186 Mesh.raycast tests it: a world bounding-sphere test, the ray in
     * local space (direction normalised by `transformDirection`), a local bounding-box test, then
     * every triangle with the watertight Ray.intersectTriangle, unculled (`DoubleSide` proxies).
     * The world distance of the transformed hit point must reach NEAR.
     */
    private class Proxy(
        val mesh: OpticalMesh,
    ) {
        var kind = Kind.REFRACTOR
        private val linear = DoubleArray(9)
        private val inverse = DoubleArray(9)
        private val low = DoubleArray(3)
        private val high = DoubleArray(3)
        private val localCenter = DoubleArray(3)
        private var localRadius = 0.0
        private val center = DoubleArray(3)
        private var radius = 0.0
        private var boundsVersion = -1
        private var singular = false
        private val origin = DoubleArray(3)
        private val direction = DoubleArray(3)
        private val scratch = DoubleArray(3)
        private val world = DoubleArray(3)
        private var kx = 0
        private var ky = 0
        private var kz = 0
        private var sx = 0.0
        private var sy = 0.0
        private var sz = 0.0

        /** `_refreshProxies` for this source; `matrixWorld.invert()` by the 3×3 adjugate. */
        fun refresh(kind: Kind) {
            this.kind = kind
            if (boundsVersion != mesh.version) bound()
            val m = mesh.matrix
            for (r in 0..2) for (c in 0..2) linear[r * 3 + c] = m[c * 4 + r].toDouble()
            for (r in 0..2) for (c in 0..2) inverse[r * 3 + c] = cofactor(c, r)
            val determinant =
                linear[0] * inverse[0] + linear[1] * inverse[3] + linear[2] * inverse[6]
            singular = determinant == 0.0
            for (i in inverse.indices) inverse[i] /= determinant
            toWorld(localCenter, center)
            var scale = 0.0
            for (c in 0..2) {
                scale = max(scale, linear[c].pow(2) + linear[3 + c].pow(2) + linear[6 + c].pow(2))
            }
            radius = localRadius * sqrt(scale)
        }

        fun raycast(
            index: Int,
            rayOrigin: DoubleArray,
            rayDirection: DoubleArray,
            hit: Hit,
        ) {
            if (singular || missesSphere(rayOrigin, rayDirection)) return
            for (r in 0..2) scratch[r] = rayOrigin[r] - mesh.matrix[12 + r]
            for (r in 0..2) {
                origin[r] = local(r, scratch)
                direction[r] = local(r, rayDirection)
            }
            normalize(direction).copyInto(direction)
            if (!entersBox() || !shear()) return
            for (triangle in 0 until mesh.indices.size / 3) test(index, triangle, rayOrigin, hit)
        }

        /** `face.normal`, (c − b) × (a − b), through the normal matrix, normalised. */
        fun normal(
            triangle: Int,
            out: DoubleArray,
        ) {
            val p = mesh.positions
            val a = mesh.indices[triangle * 3] * 3
            val b = mesh.indices[triangle * 3 + 1] * 3
            val c = mesh.indices[triangle * 3 + 2] * 3
            val n =
                cross(
                    DoubleArray(3) { p[c + it].toDouble() - p[b + it] },
                    DoubleArray(3) { p[a + it].toDouble() - p[b + it] },
                )
            for (r in 0..2) {
                scratch[r] = inverse[r] * n[0] + inverse[3 + r] * n[1] + inverse[6 + r] * n[2]
            }
            normalize(scratch).copyInto(out)
        }

        /** The world position of [vertex]. */
        fun corner(
            vertex: Int,
            out: DoubleArray,
        ) {
            for (axis in 0..2) scratch[axis] = mesh.positions[vertex * 3 + axis].toDouble()
            toWorld(scratch, out)
        }

        /** computeBoundingBox, then computeBoundingSphere around the box centre. */
        private fun bound() {
            val p = mesh.positions
            low.fill(Double.POSITIVE_INFINITY)
            high.fill(Double.NEGATIVE_INFINITY)
            for (i in p.indices) {
                low[i % 3] = min(low[i % 3], p[i].toDouble())
                high[i % 3] = max(high[i % 3], p[i].toDouble())
            }
            for (axis in 0..2) localCenter[axis] = (low[axis] + high[axis]) * .5
            var farthest = 0.0
            for (vertex in 0 until p.size / 3) {
                var distance = 0.0
                for (axis in 0..2) distance += (p[vertex * 3 + axis] - localCenter[axis]).pow(2)
                farthest = max(farthest, distance)
            }
            localRadius = sqrt(farthest)
            boundsVersion = mesh.version
        }

        /** Row [row] of the inverse matrix applied to [vector]. */
        private fun local(
            row: Int,
            vector: DoubleArray,
        ): Double =
            inverse[row * 3] * vector[0] + inverse[row * 3 + 1] * vector[1] +
                inverse[row * 3 + 2] * vector[2]

        private fun cofactor(
            row: Int,
            column: Int,
        ): Double {
            val r1 = (row + 1) % 3 * 3
            val r2 = (row + 2) % 3 * 3
            val c1 = (column + 1) % 3
            val c2 = (column + 2) % 3
            return linear[r1 + c1] * linear[r2 + c2] - linear[r1 + c2] * linear[r2 + c1]
        }

        private fun toWorld(
            point: DoubleArray,
            out: DoubleArray,
        ) {
            for (r in 0..2) {
                out[r] =
                    linear[r * 3] * point[0] + linear[r * 3 + 1] * point[1] +
                        linear[r * 3 + 2] * point[2] + mesh.matrix[12 + r]
            }
        }

        /** Sphere.containsPoint / Ray.intersectSphere from the ray recast to `near`. */
        private fun missesSphere(
            rayOrigin: DoubleArray,
            rayDirection: DoubleArray,
        ): Boolean {
            for (r in 0..2) scratch[r] = center[r] - (rayOrigin[r] + rayDirection[r] * NEAR)
            val lengthSq = dot(scratch, scratch)
            val radiusSq = radius * radius
            if (lengthSq <= radiusSq) return false
            val tca = dot(scratch, rayDirection)
            val d2 = lengthSq - tca * tca
            return d2 > radiusSq || tca + sqrt(radiusSq - d2) < 0
        }

        /** Ray.intersectsBox in local space, NaN-tolerant as three.js is. */
        private fun entersBox(): Boolean {
            var tmin = Double.NaN
            var tmax = Double.NaN
            for (axis in 0..2) {
                val inverseDirection = 1 / direction[axis]
                val near = if (inverseDirection >= 0) low[axis] else high[axis]
                val far = if (inverseDirection >= 0) high[axis] else low[axis]
                val axisMin = (near - origin[axis]) * inverseDirection
                val axisMax = (far - origin[axis]) * inverseDirection
                if (tmin > axisMax || axisMin > tmax) return false
                if (axisMin > tmin || tmin.isNaN()) tmin = axisMin
                if (axisMax < tmax || tmax.isNaN()) tmax = axisMax
            }
            return !(tmax < 0)
        }

        /** Ray.intersectTriangle's projection axis and shear for the local direction. */
        private fun shear(): Boolean {
            val x = abs(direction[0])
            val y = abs(direction[1])
            val z = abs(direction[2])
            kz =
                when {
                    x >= y && x >= z -> 0
                    y >= z -> 1
                    else -> 2
                }
            // kx and ky swap when the kz component is negative, to preserve the winding order.
            val positive = direction[kz] >= 0
            kx = (kz + if (positive) 1 else 2) % 3
            ky = (kz + if (positive) 2 else 1) % 3
            sx = direction[kx] / direction[kz]
            sy = direction[ky] / direction[kz]
            sz = 1 / direction[kz]
            // A zero direction has no maximal axis and cannot intersect.
            return direction[kz] != 0.0
        }

        /** Watertight ray/triangle intersection (Woop, Benthin, Wald, JCGT 2013). */
        private fun test(
            index: Int,
            triangle: Int,
            rayOrigin: DoubleArray,
            hit: Hit,
        ) {
            val p = mesh.positions
            val a = mesh.indices[triangle * 3] * 3
            val b = mesh.indices[triangle * 3 + 1] * 3
            val c = mesh.indices[triangle * 3 + 2] * 3
            val akz = p[a + kz] - origin[kz]
            val bkz = p[b + kz] - origin[kz]
            val ckz = p[c + kz] - origin[kz]
            val ax = p[a + kx] - origin[kx] - sx * akz
            val ay = p[a + ky] - origin[ky] - sy * akz
            val bx = p[b + kx] - origin[kx] - sx * bkz
            val by = p[b + ky] - origin[ky] - sy * bkz
            val cx = p[c + kx] - origin[kx] - sx * ckz
            val cy = p[c + ky] - origin[ky] - sy * ckz
            val u = cx * by - cy * bx
            val v = ax * cy - ay * cx
            val w = bx * ay - by * ax
            val anyNegative = u < 0 || v < 0 || w < 0
            val anyPositive = u > 0 || v > 0 || w > 0
            val det = u + v + w
            if ((anyNegative && anyPositive) || det == 0.0) return
            val tScaled = sz * (u * akz + v * bkz + w * ckz)
            val behind = if (det > 0) tScaled < 0 else tScaled > 0
            if (behind) return
            for (r in 0..2) scratch[r] = origin[r] + direction[r] * (tScaled / det)
            toWorld(scratch, world)
            val distance = sqrt((0..2).sumOf { (rayOrigin[it] - world[it]).pow(2) })
            if (distance < NEAR || distance >= hit.distance) return
            hit.proxy = index
            hit.triangle = triangle
            hit.distance = distance
            hit.wa = u / det
            hit.wb = v / det
            hit.wc = w / det
            world.copyInto(hit.point)
        }
    }
}

/** src/optics.js `refractDirection`: [normal] points into the incident medium; null is TIR. */
internal fun refractDirection(
    direction: DoubleArray,
    normal: DoubleArray,
    etaIncident: Double,
    etaTransmitted: Double,
): DoubleArray? {
    val eta = etaIncident / etaTransmitted
    val cos = (-dot(direction, normal)).coerceIn(0.0, 1.0)
    val k = 1 - eta * eta * (1 - cos * cos)
    if (k < 0) return null
    return normalize(DoubleArray(3) { direction[it] * eta + normal[it] * (eta * cos - sqrt(k)) })
}

/** src/optics.js `fresnelDielectric`: exact unpolarised dielectric reflectance. */
internal fun fresnelDielectric(
    cosIncident: Double,
    n1: Double,
    n2: Double,
): Double {
    val sinT2 = (n1 / n2).pow(2) * max(0.0, 1 - cosIncident * cosIncident)
    if (sinT2 >= 1) return 1.0
    val cosT = sqrt(1 - sinT2)
    val rs = (n1 * cosIncident - n2 * cosT) / (n1 * cosIncident + n2 * cosT)
    val rp = (n1 * cosT - n2 * cosIncident) / (n1 * cosT + n2 * cosIncident)
    return (rs * rs + rp * rp) * .5
}

private fun radicalInverse(
    index: Long,
    base: Int,
): Double {
    val inverse = 1.0 / base
    var weight = inverse
    var value = 0.0
    var i = index
    while (i > 0) {
        value += (i % base) * weight
        weight *= inverse
        i /= base
    }
    return value
}

/** `spectralIor`: Cauchy-like sampling of three bands, an explicitly approximate spectrum. */
private fun spectralIor(
    material: OpticalMaterial?,
    channel: Int,
): Double {
    val ior = material?.ior?.toDouble() ?: 0.0
    val base = if (ior != 0.0 && !ior.isNaN()) ior else 1.333
    val dispersion = material?.dispersion?.toDouble() ?: 0.0
    return base + dispersion * .006 * (1 / WAVELENGTHS[channel].pow(2) - 1 / 0.55.pow(2))
}

private fun dot(
    a: DoubleArray,
    b: DoubleArray,
): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

private fun cross(
    a: DoubleArray,
    b: DoubleArray,
): DoubleArray =
    doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

/** Vector3.normalize: scaled by 1 / length, a zero vector left as it is. */
private fun normalize(v: DoubleArray): DoubleArray {
    val length = sqrt(dot(v, v))
    val scale = 1 / if (length != 0.0) length else 1.0
    return DoubleArray(3) { v[it] * scale }
}

/** `direction.clone().reflect(normal).normalize()`. */
private fun reflect(
    direction: DoubleArray,
    normal: DoubleArray,
): DoubleArray {
    val twice = 2 * dot(direction, normal)
    return normalize(DoubleArray(3) { direction[it] - normal[it] * twice })
}

/** `hit.point.clone().addScaledVector(direction, EPS)`: the offset against self-intersection. */
private fun offset(
    point: DoubleArray,
    direction: DoubleArray,
): DoubleArray = DoubleArray(3) { point[it] + direction[it] * EPS }
