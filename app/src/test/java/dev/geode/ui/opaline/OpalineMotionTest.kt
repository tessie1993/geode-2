package dev.geode.ui.opaline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OpalineMotionTest {
    @Test
    fun pressReleasePreservesMomentumAndSettles() {
        val spring = OpalineSpring()
        spring.target = 1f
        repeat(10) { spring.step(1f / 120f) }
        val atRelease = spring.value
        val velocity = spring.velocity
        spring.target = 0f
        assertEquals(atRelease, spring.value, 0f)
        assertEquals(velocity, spring.velocity, 0f)
        repeat(480) { spring.step(1f / 120f) }
        assertEquals(0f, spring.value, 0.001f)
        assertTrue(!spring.moving)
    }

    @Test
    fun clockIsStableAcrossDisplayRatesAndStalls() {
        val results =
            listOf(30, 60, 120).map { hz ->
                OpalineSpring()
                    .apply {
                        target = 1f
                        repeat(hz * 2) { step(1f / hz) }
                    }.value
            }
        assertTrue(results.all { abs(it - 1f) < .001f })
        val spring = OpalineSpring().apply { target = 1f }
        spring.step(Float.NaN)
        spring.step(12f)
        assertTrue(spring.value.isFinite())
        spring.reset(1f)
        assertEquals(1f, spring.value, 0f)
        assertEquals(0f, spring.velocity, 0f)
    }
}
