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
enum class OpalinePalette(val asset: String, val gel: Int, val accent: Int, val background: Int) {
    TIDAL("tidal", 0xC6F0F3, 0xA8FFF1, 0x122B3C),
    OPAL("opal", 0xF1E5F3, 0xFFF0C9, 0x252C44),
    MOSS("moss", 0xCEE7CB, 0xD9F8A7, 0x162F28),
    OBSIDIAN("obsidian", 0x788296, 0xA3DCF0, 0x0B111D),
    AURORA("aurora", 0xD0CEF5, 0xA0FFE0, 0x171E39),
    AMBER("amber", 0xF0D7AF, 0xFFE4A3, 0x302C2A),
}
