package dev.geode.ui.opaline.physics

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** contacts.js `ContactController.update`: `attack = .5` seconds of smoothstep ramp. */
private const val ATTACK = .5

/** presets.js scatter extents `a === 1 ? .9 : 1.6`. */
private val SCATTER = doubleArrayOf(1.6, .9, 1.6)

/** A physics-presets.json `fields` record (presets.js FIELD_PRESETS). */
internal class FieldPreset(
    val id: String,
    val name: String,
    val kind: String,
    val options: Map<String, Any?>,
    val limitation: String?,
)

/** A physics-presets.json `particles` record (presets.js PARTICLE_PRESETS). */
internal class ParticlePreset(
    val id: String,
    record: Map<String, Any?>,
) {
    val name = record["name"] as String
    val shape = record["shape"] as String
    val radius = requireNotNull(record.number("radius"))
    val count = requireNotNull(record.number("count")).toInt()
    val color = requireNotNull(record.vector("color"))
    val gravity = requireNotNull(record.vector("gravity"))
    val field = record["field"] as String
    val drag = record.number("drag")
    val spin = record.number("spin")
    val spread = record.number("spread")
    val restitution = record.number("restitution")
    val trailLength = record.number("trailLength")?.toInt()
    val emission = record.number("emission")
    val transmission = record.number("transmission")
    val adhesionAcceleration = record.number("adhesionAcceleration")
    val stickSpeed = record.number("stickSpeed")
    val burst = record["burst"] == true
    val ring = record["ring"] == true
    val chain = record["chain"] == true
    val limitation = record["limitation"] as? String
}

/** One contact-recipes.json `contacts` entry: region and delta in body-size units. */
internal class ContactRegion(
    val region: DoubleArray,
    val radius: Double,
    val delta: DoubleArray,
)

/**
 * contacts.js `createContactRecipe(id)`: the catalogue recipe over the JavaScript defaults
 * (duration 2.8, hold .8, compliance 2e-6, friction .3, restitution .08).
 */
internal class ContactRecipe(
    val id: String,
    record: Map<String, Any?>,
) {
    val name = record["name"] as String
    val operation = record["operation"] as String
    val duration = record.number("duration") ?: 2.8
    val hold = record.number("hold") ?: .8
    val compliance = record.number("compliance") ?: 2e-6
    val friction = record.number("friction") ?: .3
    val restitution = record.number("restitution") ?: .08
    val cancelAt = record.number("cancelAt")
    val twistAngle = record.number("twistAngle")
    val retainReleaseVelocity = record["retainReleaseVelocity"] == true
    val impulse = record.vector("impulse")
    val limitation = record["limitation"] as? String
    val contacts =
        (record["contacts"] as List<*>).map {
            val contact = it as Map<*, *>
            ContactRegion(
                requireNotNull(contact.vector("region")),
                requireNotNull(contact.number("radius")),
                requireNotNull(contact.vector("delta")),
            )
        }
}

/**
 * The H field presets, G particle presets and K contact recipes, parsed once from the app's
 * catalogue/physics-presets.json and contact-recipes.json.
 */
internal class OpalinePhysicsCatalogue private constructor(
    val fieldPresets: Map<String, FieldPreset>,
    val particlePresets: Map<String, ParticlePreset>,
    private val recipes: Map<String, Map<String, Any?>>,
) {
    /** presets.js `createFieldPreset(id, overrides)`. */
    fun createFieldPreset(
        id: String,
        overrides: Map<String, Any?> = emptyMap(),
    ): ForceField {
        val preset = requireNotNull(fieldPresets[id]) { "Unknown field preset $id" }
        val field =
            when (preset.kind) {
                "wind", "quiet" -> WindField()
                "vortex" -> VortexField()
                "ring" -> VortexRingField()
                "radial" -> RadialField()
                "buoyancy" -> BuoyancyField()
                else -> error("Unknown field kind ${preset.kind}")
            }
        field.assign(preset.options + overrides)
        field.metadata = FieldMetadata(id, preset.name, preset.limitation)
        return field
    }

    /**
     * presets.js `createParticlePreset(id, {count, seed, origin, colliders, fields})`: the
     * preset's system with its seeded initial distribution (ring, chain or scatter) and links.
     */
    fun createParticlePreset(
        id: String,
        count: Int? = null,
        seed: Int = 32,
        origin: DoubleArray = doubleArrayOf(0.0, .6, 0.0),
        colliders: List<Collider>? = null,
        fields: List<ForceField>? = null,
    ): ParticleSystem {
        val preset = requireNotNull(particlePresets[id]) { "Unknown particle preset $id" }
        val n = count ?: preset.count
        val floor = PlaneCollider(offset = -.6, restitution = preset.restitution ?: .25)
        val system =
            ParticleSystem(
                capacity = max(n * 3, 256),
                seed = seed,
                gravity = preset.gravity,
                drag = preset.drag ?: .7,
                fields = fields ?: listOf(createFieldPreset(preset.field)),
                colliders = colliders ?: listOf(floor),
                restitution = preset.restitution ?: .35,
                trailLength = preset.trailLength ?: 0,
            )
        system.preset = preset
        system.adhesionAcceleration = preset.adhesionAcceleration ?: 0.0
        system.stickSpeed = preset.stickSpeed ?: 0.0
        val r = system.random
        val s = preset.spread ?: 1.0
        for (i in 0 until n) {
            val angle = i.toDouble() / n * PI * 2
            val position =
                when {
                    preset.ring ->
                        doubleArrayOf(
                            origin[0] + cos(angle) * .65,
                            origin[1] + (r.next() - .5) * .2,
                            origin[2] + sin(angle) * .65,
                        )
                    preset.chain -> {
                        val x = origin[0] + (i.toDouble() / (n - 1) - .5) * 1.8
                        doubleArrayOf(x, origin[1], origin[2])
                    }
                    else -> DoubleArray(3) { origin[it] + (r.next() - .5) * s * SCATTER[it] }
                }
            val velocity =
                if (preset.burst) {
                    doubleArrayOf((r.next() - .5) * 1.4, .8 + r.next() * 2, (r.next() - .5) * 1.4)
                } else {
                    DoubleArray(3) { (r.next() - .5) * .15 }
                }
            val radius = preset.radius * (.7 + r.next() * .6)
            val spin = DoubleArray(3) { r.next() - .5 }
            for (a in 0 until 3) spin[a] *= preset.spin ?: .4
            system.emit(position, velocity, radius, .001, preset.color, spin = spin)
        }
        if (preset.chain) system.connect((0 until n - 1).map { intArrayOf(it, it + 1) }, 1e-7)
        return system
    }

    /** contacts.js `createContactRecipe(id)`. */
    fun createContactRecipe(id: String): ContactRecipe = ContactRecipe(id, requireNotNull(recipes[id]) { "Unknown contact recipe $id" })

    companion object {
        @Volatile private var loaded: OpalinePhysicsCatalogue? = null

        /** Parsed once per process. */
        fun get(assets: AssetManager): OpalinePhysicsCatalogue =
            loaded ?: synchronized(this) { loaded ?: read(assets).also { loaded = it } }

        private fun read(assets: AssetManager): OpalinePhysicsCatalogue {
            val presets = json(assets, "physics-presets.json")
            val fields = presets.getJSONObject("fields")
            val particles = presets.getJSONObject("particles")
            val recipes = json(assets, "contact-recipes.json")
            return OpalinePhysicsCatalogue(
                fields.keys().asSequence().associateWith {
                    readField(it, fields.getJSONObject(it))
                },
                particles.keys().asSequence().associateWith {
                    ParticlePreset(it, particles.getJSONObject(it).toMap())
                },
                recipes.keys().asSequence().associateWith { recipes.getJSONObject(it).toMap() },
            )
        }

        private fun json(
            assets: AssetManager,
            name: String,
        ): JSONObject {
            val path = "opaline-native/catalogue/$name"
            return JSONObject(assets.open(path).bufferedReader().use { it.readText() })
        }

        private fun readField(
            id: String,
            json: JSONObject,
        ) = FieldPreset(
            id,
            json.getString("name"),
            json.getString("kind"),
            json.getJSONObject("options").toMap(),
            if (json.has("limitation")) json.getString("limitation") else null,
        )
    }
}

private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { raw(get(it)) }

/** JSONObject → Map, JSONArray → List and JSONObject.NULL → null, recursively. */
private fun raw(value: Any?): Any? =
    when (value) {
        JSONObject.NULL -> null
        is JSONObject -> value.toMap()
        is JSONArray -> List(value.length()) { raw(value.get(it)) }
        else -> value
    }

/** contacts.js `ContactController` footprint: vertices within [radius] in body-size units. */
internal fun contactFootprint(
    body: ConstraintBody,
    center: DoubleArray,
    radius: Double,
    size: DoubleArray,
): IntArray {
    val indices = ArrayList<Int>()
    var nearest = 0
    var nearestDistance = Double.POSITIVE_INFINITY
    for (i in 0 until body.count) {
        var distance = 0.0
        for (a in 0 until 3) {
            val d = (body.positions(i * 3 + a) - center[a]) / size[a]
            distance += d * d
        }
        if (distance < nearestDistance) {
            nearest = i
            nearestDistance = distance
        }
        if (distance < radius * radius && body.inverseMass[i] > 0) indices.add(i)
    }
    if (indices.isEmpty()) indices.add(nearest)
    return indices.toIntArray()
}

/** The footprint centroid a contact grab starts from. */
internal fun centroid(
    body: ConstraintBody,
    indices: IntArray,
): DoubleArray =
    DoubleArray(3) { a ->
        indices.fold(0.0) { sum, i -> sum + body.positions(3 * i + a) / indices.size }
    }

/** contacts.js smoothstep press envelope over [ATTACK] seconds. */
internal fun contactAmount(elapsed: Double): Double {
    val x = clamp(elapsed / ATTACK, 0.0, 1.0)
    return x * x * (3 - 2 * x)
}

/**
 * contacts.js `ContactController`: a repeatable recipe demonstration on [body]. Call [update]
 * before the world steps; call [cancel] on real pointer input.
 */
internal class ContactController(
    val body: ConstraintBody,
    val recipe: ContactRecipe,
    origin: DoubleArray? = null,
    size: DoubleArray? = null,
    var loop: Boolean = false,
) {
    var elapsed = 0.0
        private set
    var active = false
        private set
    var finished = false
        private set
    val origin: DoubleArray
    val size: DoubleArray
    private val ids: List<String>
    private val footprints: List<IntArray>
    private val starts: List<DoubleArray>

    init {
        val low = DoubleArray(3) { Double.POSITIVE_INFINITY }
        val high = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        for (k in 0 until body.count * 3) {
            low[k % 3] = min(low[k % 3], body.positions(k))
            high[k % 3] = max(high[k % 3], body.positions(k))
        }
        this.origin = origin ?: DoubleArray(3) { (low[it] + high[it]) / 2 }
        this.size = size ?: DoubleArray(3) { max(.01, high[it] - low[it]) }
        ids = recipe.contacts.indices.map { "recipe:${recipe.id}:$it" }
        footprints =
            recipe.contacts.map { contact ->
                val center = DoubleArray(3) { this.origin[it] + contact.region[it] * this.size[it] }
                contactFootprint(body, center, contact.radius, this.size)
            }
        starts = footprints.map { centroid(body, it) }
    }

    fun update(dt: Double) {
        if (finished) return
        elapsed += dt
        if (!active) {
            active = true
            for (c in ids.indices) body.grab(ids[c], footprints[c], starts[c], recipe.compliance)
            val impulse = recipe.impulse
            if (impulse != null && body is SoftBody) body.addImpulse(origin, size.max(), impulse)
        }
        val amount = contactAmount(elapsed)
        for (c in ids.indices) {
            val delta = recipe.contacts[c].delta
            body.moveGrab(ids[c], DoubleArray(3) { starts[c][it] + delta[it] * size[it] * amount })
        }
        if (elapsed >= (recipe.cancelAt ?: (ATTACK + recipe.hold))) cancel(false)
        if (elapsed < recipe.duration) return
        if (loop) {
            elapsed = 0.0
            active = false
        } else {
            finished = true
        }
    }

    fun cancel(finish: Boolean = true) {
        for (id in ids) body.releaseGrab(id)
        if (finish) finished = true
    }
}
