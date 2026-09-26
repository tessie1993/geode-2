package dev.geode.ui.opaline

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** Reader for the bundled, authored glTF 2.0 meshes. No network, scripts or external buffers. */
internal data class OpalineMesh(
    val pieces: List<Piece>,
    val minimum: FloatArray,
    val maximum: FloatArray,
) {
    /**
     * One glTF primitive. [transform] is its static model matrix relative to the element root and
     * [name] its node's name. Under a part src/motion.js moves, [joint] is that part and [child]
     * the static matrix from the part down to this piece, so [transform] is `joint.parent ×
     * rest TRS × child`; with nothing moving above it, [joint] is null and [child] is [transform].
     */
    data class Piece(
        val positions: FloatArray,
        val normals: FloatArray,
        val indices: IntArray,
        val morphs: List<FloatArray>,
        val transform: FloatArray,
        val color: FloatArray,
        val roughness: Float,
        val family: String,
        val ior: Float,
        val thickness: Float,
        val attenuation: FloatArray,
        val attenuationDistance: Float,
        val clearcoat: Float,
        val emission: FloatArray,
        val transmission: Float,
        val iridescence: Float,
        val motion: String,
        val motionIndex: Int,
        val motionPivot: FloatArray,
        val motionAxis: FloatArray,
        val role: String,
        val travel: FloatArray,
        val deformable: Boolean,
        val name: String,
        val joint: Joint?,
        val child: FloatArray,
    )

    /**
     * A part src/motion.js MotionController moves: a node with `userData.motion` (glTF node
     * `extras.motion`), or one whose lower-case name matches the controller's part pattern, which
     * moves as `{kind:'dial'}`. [parent] is the static matrix from the element root to the node's
     * parent; [translation], [rotation] (three.js Euler, order XYZ) and [scale] are the rest
     * `position`, `rotation` and `scale` the controller clones. Config numbers the node leaves
     * out are NaN and a missing axis is -1, so OpalinePieceMotion applies motion.js's fallbacks.
     */
    class Joint(
        val kind: String,
        val axis: Int,
        val parent: FloatArray,
        val translation: FloatArray,
        val rotation: FloatArray,
        val scale: FloatArray,
        val min: Float,
        val max: Float,
        val start: Float,
        val end: Float,
        val phase: Float,
        val radius: Float,
        val center: FloatArray,
        val bounds: FloatArray,
        val maxAngle: Float,
        val baseAngle: Float,
    )

    companion object {
        /** Library GLBs per family; J24, the meshless light rig, is not a mesh. */
        private val FAMILIES =
            listOf(
                'A' to 24,
                'B' to 24,
                'C' to 24,
                'D' to 18,
                'E' to 24,
                'F' to 12,
                'J' to 23,
                'N' to 7,
            )

        /** The 156 bundled element meshes, A01 to N07. */
        val ELEMENTS =
            FAMILIES.flatMap { (family, count) ->
                (1..count).map { "$family${it.toString().padStart(2, '0')}" }
            }

        fun read(bytes: ByteArray): OpalineMesh {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.int == 0x46546c67 && buffer.int == 2 && buffer.int == bytes.size) {
                "Invalid GLB header"
            }
            val jsonSize = buffer.int
            require(buffer.int == 0x4e4f534a && jsonSize in 1..buffer.remaining()) {
                "Missing GLB JSON"
            }
            val json = ByteArray(jsonSize).also { buffer.get(it) }
            val root = JSONObject(String(json, Charsets.UTF_8))
            val binarySize = buffer.int
            require(buffer.int == 0x004e4942 && binarySize == buffer.remaining()) {
                "Missing GLB binary"
            }
            return GlbReader(root, buffer.slice().order(ByteOrder.LITTLE_ENDIAN)).read()
        }
    }
}

/** src/motion.js MotionController: the node names it moves as dials without a motion config. */
private val PART_NAME = Regex("thumb|knob|rotor|puck|dial-cap|inner-dial|outer-dial")

/** motion.js `c.axis`, as an index into position / rotation / scale. */
private val AXES = listOf("x", "y", "z")

/**
 * The moving part a node hangs from: its motion config [motion] and [index], its static model
 * matrix [anchor], the [travel] the reader reports for it, the [joint] OpalinePieceMotion moves
 * and [child], the static matrix from the part down to the node.
 */
private class Lineage(
    val motion: JSONObject,
    val index: Int,
    val anchor: FloatArray,
    val travel: FloatArray,
    val joint: OpalineMesh.Joint,
    val child: FloatArray,
) {
    fun below(local: FloatArray) = Lineage(motion, index, anchor, travel, joint, multiply(child, local))
}

/** One GLB's default scene walked into pieces, with the element bounds they span. */
private class GlbReader(
    private val root: JSONObject,
    private val binary: ByteBuffer,
) {
    private val nodes = root.getJSONArray("nodes")
    private val pieces = mutableListOf<OpalineMesh.Piece>()
    private val low = FloatArray(3) { Float.POSITIVE_INFINITY }
    private val high = FloatArray(3) { Float.NEGATIVE_INFINITY }

    fun read(): OpalineMesh {
        val scene =
            root
                .getJSONArray("scenes")
                .getJSONObject(root.optInt("scene"))
                .getJSONArray("nodes")
        for (i in 0 until scene.length()) visit(scene.getInt(i), identity(), null)
        // geometry.js createElement: `if(bounds.isEmpty())bounds.set(V(-1,-1,-1),V(1,1,1))`,
        // the bounds of the meshless J24 light rig.
        if (pieces.isEmpty()) {
            return OpalineMesh(pieces, FloatArray(3) { -1f }, FloatArray(3) { 1f })
        }
        require(low.all { it.isFinite() } && high.all { it.isFinite() })
        return OpalineMesh(pieces, low, high)
    }

    private fun visit(
        index: Int,
        parent: FloatArray,
        inherited: Lineage?,
    ) {
        val node = nodes.getJSONObject(index)
        val local = transform(node)
        val transform = multiply(parent, local)
        val motion = motionOf(node)
        val lineage =
            if (motion != null) {
                own(node, motion, parent, transform)
            } else {
                inherited?.below(local)
            }
        if (node.has("mesh")) {
            val mesh = root.getJSONArray("meshes").getJSONObject(node.getInt("mesh"))
            val primitives = mesh.getJSONArray("primitives")
            for (p in 0 until primitives.length()) {
                pieces += piece(primitives.getJSONObject(p), node, transform, lineage)
            }
        }
        val children = node.optJSONArray("children") ?: return
        for (i in 0 until children.length()) visit(children.getInt(i), transform, lineage)
    }

    /**
     * motion.js collects a node when `mesh.userData.motion` is set or its lower-case name matches
     * [PART_NAME]; the config is `mesh.userData.motion||{kind:'dial'}`.
     */
    private fun motionOf(node: JSONObject): JSONObject? {
        node.optJSONObject("extras")?.optJSONObject("motion")?.let { return it }
        val named = PART_NAME.containsMatchIn(node.optString("name").lowercase())
        return if (named) JSONObject().put("kind", DIAL) else null
    }

    private fun own(
        node: JSONObject,
        motion: JSONObject,
        parent: FloatArray,
        transform: FloatArray,
    ): Lineage {
        val axis = axisOf(motion).coerceAtLeast(0)
        val translation = node.optJSONArray("translation")?.optDouble(axis, 0.0)?.toFloat() ?: 0f
        val min = motion.optDouble("min", -0.94).toFloat()
        val max = motion.optDouble("max", 0.94).toFloat()
        val travel =
            floatArrayOf(
                parent[axis * 4],
                parent[axis * 4 + 1],
                parent[axis * 4 + 2],
                min - translation,
                max - min,
            )
        // B15's two dials are independent parts (motion.js `independent=this.id==='B04'||
        // this.id==='B15'`); UI024's dual-angular-value drives the outer one with the secondary
        // value, which the renderer reads for motion index 1.
        val index = if (node.optString("name") == OUTER_DIAL) 1 else motion.optInt("index", 0)
        return Lineage(motion, index, transform, travel, joint(node, motion, parent), identity())
    }

    private fun joint(
        node: JSONObject,
        motion: JSONObject,
        parent: FloatArray,
    ): OpalineMesh.Joint {
        // glTF 2.0: a node whose transform is driven carries TRS properties, never `matrix`.
        require(!node.has("matrix")) { "Moving node ${node.optString("name")} has a matrix" }
        return OpalineMesh.Joint(
            kind = motion.optString("kind"),
            axis = axisOf(motion),
            parent = parent,
            translation = node.optJSONArray("translation")?.floats(3) ?: FloatArray(3),
            rotation = euler(rotation(node)),
            scale = node.optJSONArray("scale")?.floats(3) ?: floatArrayOf(1f, 1f, 1f),
            min = motion.number("min"),
            max = motion.number("max"),
            start = motion.number("start"),
            end = motion.number("end"),
            phase = motion.number("phase"),
            radius = motion.number("radius"),
            center = motion.numbers("center", 2),
            bounds = motion.numbers("bounds", 4),
            maxAngle = motion.number("maxAngle"),
            baseAngle = motion.number("baseAngle"),
        )
    }

    private fun piece(
        primitive: JSONObject,
        node: JSONObject,
        transform: FloatArray,
        lineage: Lineage?,
    ): OpalineMesh.Piece {
        require(primitive.optInt("mode", 4) == 4)
        val attributes = primitive.getJSONObject("attributes")
        val positions = floats(attributes.getInt("POSITION"))
        val normals = floats(attributes.getInt("NORMAL"))
        val indices =
            if (primitive.has("indices")) {
                indices(primitive.getInt("indices"))
            } else {
                IntArray(positions.size / 3) { it }
            }
        require(normals.size == positions.size && indices.all { it in 0 until positions.size / 3 })
        grow(positions, transform)
        val targets = primitive.optJSONArray("targets") ?: JSONArray()
        val morphs = List(targets.length()) { floats(targets.getJSONObject(it).getInt("POSITION")) }
        require(morphs.all { it.size == positions.size })
        val material = root.getJSONArray("materials").getJSONObject(primitive.getInt("material"))
        val pbr = material.getJSONObject("pbrMetallicRoughness")
        val extensions = material.optJSONObject("extensions")
        val attenuation = extensions?.optJSONObject(VOLUME)?.optJSONArray("attenuationColor")
        val extras = node.optJSONObject("extras")
        val anchor = lineage?.anchor ?: transform
        val axis = lineage?.let { axisOf(it.motion) }?.coerceAtLeast(0) ?: 0
        return OpalineMesh.Piece(
            positions = positions,
            normals = normals,
            indices = indices,
            morphs = morphs,
            transform = transform,
            color = pbr.getJSONArray("baseColorFactor").floats(4),
            roughness = pbr.optDouble("roughnessFactor", 0.2).toFloat(),
            family = material.optString("name").substringAfterLast('/'),
            ior = extensions.factor(IOR, "ior", 1.5),
            thickness = extensions.factor(VOLUME, "thicknessFactor", 0.1),
            attenuation = attenuation?.floats(3) ?: floatArrayOf(1f, 1f, 1f),
            attenuationDistance = extensions.factor(VOLUME, "attenuationDistance", 8.0),
            clearcoat = extensions.factor(CLEARCOAT, "clearcoatFactor", 0.0),
            emission = material.optJSONArray("emissiveFactor")?.floats(3) ?: FloatArray(3),
            transmission = extensions.factor(TRANSMISSION, "transmissionFactor", 0.0),
            iridescence = extensions.factor(IRIDESCENCE, "iridescenceFactor", 0.0),
            motion = lineage?.motion?.optString("kind").orEmpty(),
            motionIndex = lineage?.index ?: 0,
            motionPivot = floatArrayOf(anchor[12], anchor[13], anchor[14]),
            motionAxis = floatArrayOf(anchor[axis * 4], anchor[axis * 4 + 1], anchor[axis * 4 + 2]),
            role = extras?.optString("role").orEmpty(),
            travel = lineage?.travel ?: FloatArray(5),
            deformable = extras?.optBoolean("deformable") == true,
            name = node.optString("name"),
            joint = lineage?.joint,
            child = lineage?.child ?: transform,
        )
    }

    /** Grows the element bounds by [positions] carried through [transform]. */
    private fun grow(
        positions: FloatArray,
        transform: FloatArray,
    ) {
        for (v in positions.indices step 3) {
            for (axis in 0..2) {
                val value =
                    transform[axis] * positions[v] + transform[4 + axis] * positions[v + 1] +
                        transform[8 + axis] * positions[v + 2] + transform[12 + axis]
                low[axis] = minOf(low[axis], value)
                high[axis] = maxOf(high[axis], value)
            }
        }
    }

    private fun floats(index: Int): FloatArray {
        val accessor = root.getJSONArray("accessors").getJSONObject(index)
        require(accessor.getInt("componentType") == 5126 && !accessor.has("sparse"))
        val components =
            when (accessor.getString("type")) {
                "VEC3" -> 3
                else -> error("Expected VEC3")
            }
        val view = view(accessor)
        require(view.optInt("buffer") == 0)
        val start = view.optInt("byteOffset") + accessor.optInt("byteOffset")
        val stride = view.optInt("byteStride", components * 4)
        return FloatArray(accessor.getInt("count") * components) { i ->
            val value = binary.getFloat(start + i / components * stride + i % components * 4)
            require(value.isFinite())
            value
        }
    }

    private fun indices(index: Int): IntArray {
        val accessor = root.getJSONArray("accessors").getJSONObject(index)
        val view = view(accessor)
        val start = view.optInt("byteOffset") + accessor.optInt("byteOffset")
        return IntArray(accessor.getInt("count")) { i ->
            when (accessor.getInt("componentType")) {
                5123 -> binary.getShort(start + i * 2).toInt() and 0xffff
                5125 -> binary.getInt(start + i * 4)
                else -> error("Unsupported index type")
            }
        }
    }

    private fun view(accessor: JSONObject) = root.getJSONArray("bufferViews").getJSONObject(accessor.getInt("bufferView"))

    private companion object {
        /** motion.js's configless part kind. */
        const val DIAL = "dial"

        /** B15's outer ring (geometry.js B15 `outer-dial`). */
        const val OUTER_DIAL = "outer-dial"

        const val IOR = "KHR_materials_ior"
        const val VOLUME = "KHR_materials_volume"
        const val CLEARCOAT = "KHR_materials_clearcoat"
        const val TRANSMISSION = "KHR_materials_transmission"
        const val IRIDESCENCE = "KHR_materials_iridescence"
    }
}

/** motion.js `c.axis` as 0, 1 or 2 for x, y or z; -1 when the config names none. */
private fun axisOf(motion: JSONObject) = AXES.indexOf(motion.optString("axis"))

/** A motion config number; NaN where the config leaves it out (JavaScript `undefined`). */
private fun JSONObject.number(key: String) = optDouble(key).toFloat()

/** A motion config array of [size] numbers; NaN where the config leaves one out. */
private fun JSONObject.numbers(
    key: String,
    size: Int,
): FloatArray {
    val array = optJSONArray(key)
    return FloatArray(size) { array?.optDouble(it)?.toFloat() ?: Float.NaN }
}

/** A KHR material extension's [key], or [fallback] when the extension or the key is absent. */
private fun JSONObject?.factor(
    extension: String,
    key: String,
    fallback: Double,
) = (this?.optJSONObject(extension)?.optDouble(key, fallback) ?: fallback).toFloat()

private fun JSONArray.floats(size: Int) = FloatArray(size) { getDouble(it).toFloat() }

private fun identity() = FloatArray(16) { if (it % 5 == 0) 1f else 0f }

private fun multiply(
    a: FloatArray,
    b: FloatArray,
) = FloatArray(16) { i ->
    (0..3).sumOf { k -> (a[k * 4 + i % 4] * b[i / 4 * 4 + k]).toDouble() }.toFloat()
}

/** A glTF node's local matrix: its `matrix`, or translation × rotation × scale. */
private fun transform(node: JSONObject): FloatArray {
    node.optJSONArray("matrix")?.let { return it.floats(16) }
    val matrix = rotation(node)
    val scale = node.optJSONArray("scale")?.floats(3) ?: floatArrayOf(1f, 1f, 1f)
    for (c in 0..2) for (r in 0..2) matrix[c * 4 + r] *= scale[c]
    node.optJSONArray("translation")?.floats(3)?.copyInto(matrix, 12)
    return matrix
}

/** The column-major rotation matrix of a glTF node's quaternion, normalised first. */
private fun rotation(node: JSONObject): FloatArray {
    val q = node.optJSONArray("rotation")?.floats(4) ?: floatArrayOf(0f, 0f, 0f, 1f)
    val length = sqrt(q.sumOf { (it * it).toDouble() }).toFloat()
    require(length > 0f)
    val x = q[0] / length
    val y = q[1] / length
    val z = q[2] / length
    val w = q[3] / length
    return floatArrayOf(
        1 - 2 * y * y - 2 * z * z,
        2 * x * y + 2 * z * w,
        2 * x * z - 2 * y * w,
        0f,
        2 * x * y - 2 * z * w,
        1 - 2 * x * x - 2 * z * z,
        2 * y * z + 2 * x * w,
        0f,
        2 * x * z + 2 * y * w,
        2 * y * z - 2 * x * w,
        1 - 2 * x * x - 2 * y * y,
        0f,
        0f,
        0f,
        0f,
        1f,
    )
}

/**
 * three.js Euler.setFromRotationMatrix, order XYZ: the `rotation` a loaded Object3D keeps for
 * its quaternion, which motion.js edits one axis at a time.
 */
private fun euler(m: FloatArray): FloatArray {
    val m13 = m[8].toDouble()
    val y = asin(m13.coerceIn(-1.0, 1.0)).toFloat()
    return if (abs(m13) < 0.9999999) {
        floatArrayOf(atan2(-m[9], m[10]), y, atan2(-m[4], m[0]))
    } else {
        floatArrayOf(atan2(m[6], m[5]), y, 0f)
    }
}
