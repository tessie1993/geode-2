package dev.geode.ui.opaline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil

/** Direct native port of src/motion.js Spring. Semantic values never wait for this clock. */
internal class OpalineSpring(
    initial: Float = 0f,
    private val frequency: Float = 4f,
    private val damping: Float = 0.68f,
) {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial

    val moving: Boolean get() = abs(value - target) > 0.0001f || abs(velocity) > 0.001f

    fun step(seconds: Float): Float {
        val elapsed = if (seconds.isFinite()) seconds.coerceIn(0f, 0.05f) else 0f
        val count = ceil(elapsed * 240f).toInt().coerceAtLeast(1)
        val dt = elapsed / count
        val omega = frequency * 2f * PI.toFloat()
        repeat(count) {
            velocity += (omega * omega * (target - value) - 2f * damping * omega * velocity) * dt
            value += velocity * dt
        }
        if (!moving) reset(target)
        return value
    }

    fun reset(next: Float = target) {
        value = next
        target = next
        velocity = 0f
    }
}

/** Shared native optical palette; matches catalogue/design-tokens.json and materials.js. */
enum class OpalinePalette(
    val asset: String,
    val gel: Int,
    val accent: Int,
    val background: Int,
) {
    TIDAL("tidal", 0xC6F0F3, 0xA8FFF1, 0x122B3C),
    OPAL("opal", 0xF1E5F3, 0xFFF0C9, 0x252C44),
    MOSS("moss", 0xCEE7CB, 0xD9F8A7, 0x162F28),
    OBSIDIAN("obsidian", 0x788296, 0xA3DCF0, 0x0B111D),
    AURORA("aurora", 0xD0CEF5, 0xA0FFE0, 0x171E39),
    AMBER("amber", 0xF0D7AF, 0xFFE4A3, 0x302C2A),
    ;

    /** Full material-family palette from src/materials.js, not just an accent overlay. */
    internal fun materialColor(family: String): Int {
        val tones =
            when (this) {
                TIDAL -> intArrayOf(0x77AEDA, 0x3C728D, 0x599D9D, 0x56727A, 0x467C66)
                OPAL -> intArrayOf(0x9DAEDE, 0xC99FC6, 0x95BDC9, 0xA8A8B4, 0x77978C)
                MOSS -> intArrayOf(0x77B8A3, 0x426C55, 0x739E8A, 0x5E7265, 0x7FA665)
                OBSIDIAN -> intArrayOf(0x657CA8, 0x27384C, 0x405C70, 0x303A47, 0x476477)
                AURORA -> intArrayOf(0x8DA5EF, 0x8371BD, 0x668BA9, 0x616C86, 0x829C99)
                AMBER -> intArrayOf(0xD4AB7C, 0xB77744, 0x92B5A4, 0x877668, 0x8B976A)
            }
        return when (family) {
            "blue" -> tones[0]
            "pigment" -> tones[1]
            "water" -> tones[2]
            "stone" -> tones[3]
            "leaf" -> tones[4]
            "glow" -> accent
            else -> gel
        }
    }
}
