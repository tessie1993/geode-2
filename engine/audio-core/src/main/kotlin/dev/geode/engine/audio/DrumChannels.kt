package dev.geode.engine.audio

import dev.geode.engine.bridge.GeodeNative

class DrumChannels(
    private val bandCount: Int,
    hopRateHz: Float,
    sampleRateHz: Int,
) : AutoCloseable {
    private var handle: Long = GeodeNative.drumsCreate(bandCount, hopRateHz, sampleRateHz)
    private val impulses = FloatArray(3)

    var kick: Float = 0f
        private set

    var snare: Float = 0f
        private set

    var hat: Float = 0f
        private set

    fun step(bands: FloatArray) {
        // The native side reads bands[0..bandCount); require the array this handle was created for so a
        // mismatched caller fails loudly here instead of reading out of range in DrumChannels::step.
        require(bands.size >= bandCount) {
            "DrumChannels.step expects at least $bandCount bands, got ${bands.size}"
        }
        GeodeNative.drumsStep(handle, bands, impulses)
        kick = impulses[0]
        snare = impulses[1]
        hat = impulses[2]
    }

    override fun close() {
        if (handle != 0L) {
            GeodeNative.drumsDestroy(handle)
            handle = 0L
        }
    }
}
