package dev.geode.render

import dev.geode.render.scene.SceneParams
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualSafetyTest {
    @Test
    fun safetyClampsEliminateNanInfinityAndNegativeValues() {
        val nonFinites = listOf(Float.NaN, Float.POSITIVE_INFINITY, -1e30f)

        for (bad in nonFinites) {
            val badParams =
                SceneParams(
                    strobe = bad,
                    flash = bad,
                    glitch = bad,
                    bloom = bad,
                    brightness = bad,
                    intensity = bad,
                    contrast = bad,
                )

            val safe = VisualSafety.apply(badParams)

            assertTrue("strobe should be finite for $bad", safe.strobe.isFinite())
            assertTrue("strobe should be >= 0 for $bad", safe.strobe >= 0f)

            assertTrue("flash should be finite for $bad", safe.flash.isFinite())
            assertTrue("flash should be >= 0 for $bad", safe.flash >= 0f)

            assertTrue("glitch should be finite for $bad", safe.glitch.isFinite())
            assertTrue("glitch should be >= 0 for $bad", safe.glitch >= 0f)

            assertTrue("bloom should be finite for $bad", safe.bloom.isFinite())
            assertTrue("bloom should be >= 0 for $bad", safe.bloom >= 0f)

            assertTrue("brightness should be finite for $bad", safe.brightness.isFinite())
            assertTrue("brightness should be >= 0 for $bad", safe.brightness >= 0f)

            assertTrue("intensity should be finite for $bad", safe.intensity.isFinite())
            assertTrue("intensity should be >= 0 for $bad", safe.intensity >= 0f)

            assertTrue("contrast should be finite for $bad", safe.contrast.isFinite())
            assertTrue("contrast should be >= 0 for $bad", safe.contrast >= 0f)
        }
    }
}
