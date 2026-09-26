package dev.geode.ui.opaline

import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject

/** Catalogue metres or radians: +Y up, +Z front, Euler XYZ (compositions.json coordinateSystem). */
internal data class OpalineVec3(
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * One `parts[]` record of catalogue/compositions.json. [dimensions] are the element's final
 * bounding-box extents (geometry.js `createElement(id, materials, {dimensions})`), and [material]
 * is the selector instantiate-composition.js renders the element's gel pieces with.
 */
internal data class OpalineCompositionPart(
    val id: String,
    val element: String,
    val position: OpalineVec3,
    val rotation: OpalineVec3,
    val scale: OpalineVec3,
    val dimensions: OpalineVec3,
    val material: String,
    val role: String,
)

/** One `contentFrames[]` record: a rigid readable plane of `size` [width] × [height] metres. */
internal data class OpalineContentFrame(
    val id: String,
    val owner: String,
    val position: OpalineVec3,
    val width: Float,
    val height: Float,
    val padding: Float,
    val role: String,
    val attachment: String,
)

/** One `behaviorBindings[]` record: a host-adapter contract, returned as data. */
internal data class OpalineBehaviorBinding(
    val event: String,
    val action: String,
    val target: String,
    /** Raw JSON values: Number, String, Boolean, List<Any?> or Map<String, Any?>. */
    val parameters: Map<String, Any?>,
    val execution: String,
)

/** One text-free recipe of catalogue/compositions.json. */
internal data class OpalineComposition(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val dimensions: OpalineVec3,
    val parts: List<OpalineCompositionPart>,
    val contentFrames: List<OpalineContentFrame>,
    val behaviorBindings: List<OpalineBehaviorBinding>,
) {
    /** The part with this exact id; throws IllegalArgumentException if absent. */
    fun part(id: String): OpalineCompositionPart =
        requireNotNull(parts.find { it.id == id }) {
            "Opaline composition ${this.id} has no part \"$id\"; parts: ${parts.map { it.id }}"
        }

    /** The content frame with this exact id; throws IllegalArgumentException if absent. */
    fun frame(id: String): OpalineContentFrame =
        requireNotNull(contentFrames.find { it.id == id }) {
            "Opaline composition ${this.id} has no content frame \"$id\"; " +
                "frames: ${contentFrames.map { it.id }}"
        }
}

/**
 * Every recipe of catalogue/compositions.json by id. Parsed once per process and immutable
 * afterwards, so the main thread and the EGL render thread share one instance.
 */
internal class OpalineCatalogue private constructor(
    val compositions: Map<String, OpalineComposition>,
) {
    /** Throws IllegalArgumentException for an unknown id. */
    fun composition(id: String): OpalineComposition =
        requireNotNull(compositions[id]) {
            "Unknown Opaline composition \"$id\"; the catalogue holds ${compositions.keys}"
        }

    companion object {
        private const val SOURCE = "opaline-native/catalogue/compositions.json"

        @Volatile private var loaded: OpalineCatalogue? = null

        /** Parsed once per process from opaline-native/catalogue/compositions.json. */
        fun get(assets: AssetManager): OpalineCatalogue =
            loaded ?: synchronized(this) { loaded ?: read(assets).also { loaded = it } }

        private fun read(assets: AssetManager): OpalineCatalogue {
            val records = JSONArray(assets.open(SOURCE).bufferedReader().use { it.readText() })
            val compositions = LinkedHashMap<String, OpalineComposition>()
            for (i in 0 until records.length()) {
                val composition = readComposition(records.getJSONObject(i))
                require(compositions.put(composition.id, composition) == null) {
                    "Duplicate Opaline composition ${composition.id}"
                }
            }
            return OpalineCatalogue(compositions)
        }
    }
}

/** The recipe for [id], read through LocalContext's assets. */
@Composable
internal fun rememberOpalineComposition(id: String): OpalineComposition {
    val assets = LocalContext.current.assets
    return remember(id) { OpalineCatalogue.get(assets).composition(id) }
}

/** `createMaterials(theme)` keys in src/materials.js: the selectors a part may name. */
private val SELECTORS =
    setOf("gel", "blue", "water", "shell", "pigment", "film", "nacre", "stone", "leaf", "glow")

private val ORIGIN = OpalineVec3(0f, 0f, 0f)
private val UNIT = OpalineVec3(1f, 1f, 1f)

/** One record, rejected where catalogue/instantiate-composition.js throws. */
private fun readComposition(json: JSONObject): OpalineComposition {
    val id = json.getString("id")
    val parts = json.getJSONArray("parts").objects().map { readPart(it) }
    val frames = json.getJSONArray("contentFrames").objects().map { readFrame(it) }
    require(parts.distinctBy { it.id }.size == parts.size) { "$id: duplicate part ID" }
    require(frames.distinctBy { it.id }.size == frames.size) { "$id: duplicate content frame" }
    for (part in parts) {
        require(part.material in SELECTORS) { "$id: unknown material selector ${part.material}" }
    }
    for (frame in frames) {
        require(parts.any { it.id == frame.owner }) { "$id: unknown content owner ${frame.owner}" }
    }
    return OpalineComposition(
        id = id,
        name = json.getString("name"),
        category = json.getString("category"),
        description = json.getString("description"),
        dimensions = json.getJSONArray("dimensions").vec3(),
        parts = parts,
        contentFrames = frames,
        behaviorBindings = json.getJSONArray("behaviorBindings").objects().map { readBinding(it) },
    )
}

/**
 * instantiate-composition.js defaults: position and rotation `|| [0, 0, 0]`, and a part without
 * a selector keeps its gel body.
 */
private fun readPart(json: JSONObject) =
    OpalineCompositionPart(
        id = json.getString("id"),
        element = json.getString("element"),
        position = json.vec3("position", ORIGIN),
        rotation = json.vec3("rotation", ORIGIN),
        scale = json.scale(),
        dimensions = json.getJSONArray("dimensions").vec3(),
        material = json.optString("material", "gel"),
        role = json.getString("role"),
    )

/**
 * instantiate-composition.js: an array multiplies the fitted scale, a finite number scales it
 * uniformly, and anything else leaves it at one.
 */
private fun JSONObject.scale(): OpalineVec3 {
    val uniform = optDouble("scale").toFloat()
    return if (uniform.isFinite()) OpalineVec3(uniform, uniform, uniform) else vec3("scale", UNIT)
}

private fun readFrame(json: JSONObject): OpalineContentFrame {
    val size = json.getJSONArray("size")
    return OpalineContentFrame(
        id = json.getString("id"),
        owner = json.getString("owner"),
        position = json.vec3("position", ORIGIN),
        width = size.getDouble(0).toFloat(),
        height = size.getDouble(1).toFloat(),
        padding = json.getDouble("padding").toFloat(),
        role = json.getString("role"),
        attachment = json.getString("attachment"),
    )
}

private fun readBinding(json: JSONObject) =
    OpalineBehaviorBinding(
        event = json.getString("event"),
        action = json.getString("action"),
        target = json.getString("target"),
        parameters = json.getJSONObject("parameters").toMap(),
        execution = json.getString("execution"),
    )

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

private fun JSONArray.vec3(): OpalineVec3 {
    require(length() == 3) { "Expected [x, y, z], found $this" }
    return OpalineVec3(getDouble(0).toFloat(), getDouble(1).toFloat(), getDouble(2).toFloat())
}

private fun JSONObject.vec3(
    name: String,
    fallback: OpalineVec3,
): OpalineVec3 = optJSONArray(name)?.vec3() ?: fallback

private fun JSONObject.toMap(): Map<String, Any?> =
    keys().asSequence().associateWith { raw(get(it)) }

/** JSONObject → Map, JSONArray → List and JSONObject.NULL → null, recursively. */
private fun raw(value: Any?): Any? =
    when (value) {
        JSONObject.NULL -> null
        is JSONObject -> value.toMap()
        is JSONArray -> List(value.length()) { raw(value.get(it)) }
        else -> value
    }
