package dev.geode.playback

import androidx.media3.common.C
import dev.geode.engine.audioandroid.PcmTap
import dev.geode.engine.audioandroid.SinkClockDriver
import dev.geode.RingLog
import dev.geode.engine.bridge.GeodeNative
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Drains the native mixer's tap into the same [PcmTap] the Media3 chain feeds, so analysis and the
 * presentation clock see the native player exactly as they see ExoPlayer.
 */
class NativeTapPump(
    private val tap: PcmTap,
    private val clock: SinkClockDriver,
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start(handle: Long) {
        running = true
        thread =
            Thread({ loop(handle) }, "geode-native-tap").apply {
                isDaemon = true
                start()
            }
    }

    /**
     * Returns once the thread has let go of the handle, or once the wait runs out; call before the
     * player is destroyed.
     *
     * The wait is bounded because this runs on the application looper — `NativePlayer.handleRelease`
     * is called there — and the worker can be inside a blocking `playerReadTap`. An unbounded join
     * turns a stalled Oboe stream into an ANR at teardown. Every other join in this codebase is
     * bounded the same way (`AudioCapturePump`, `NativePlayer`, `VisualizerView`); a timeout is
     * logged rather than swallowed so a wedged tap thread is diagnosable.
     */
    fun stop() {
        running = false
        thread?.let { t ->
            t.join(JOIN_TIMEOUT_MS)
            if (t.isAlive) RingLog.note(TAG, "native tap did not stop within ${JOIN_TIMEOUT_MS}ms")
        }
        thread = null
    }

    private fun loop(handle: Long) {
        val buffer = ByteBuffer.allocateDirect(FRAMES * CHANNELS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        var rate = 0
        clock.attachSkippedFrames { 0L }
        while (running) {
            val current = GeodeNative.playerOutputSampleRate(handle)
            if (current > 0 && current != rate) {
                rate = current
                // The clock driver trusts a boundary only after both sink hooks reported; native has neither.
                clock.onSpeedApplied(1f)
                clock.onSkipSilenceApplied(false)
                tap.flush(rate, CHANNELS, C.ENCODING_PCM_FLOAT)
            }
            val frames = if (rate > 0) GeodeNative.playerReadTap(handle, buffer, FRAMES) else 0
            if (frames > 0) {
                buffer.limit(frames * CHANNELS * Float.SIZE_BYTES)
                buffer.position(0)
                tap.handleBuffer(buffer)
            } else {
                Thread.sleep(IDLE_SLEEP_MS)
            }
        }
    }

    private companion object {
        const val TAG = "NativeTapPump"

        /** Matches the bound the rest of the codebase uses for a teardown join. */
        const val JOIN_TIMEOUT_MS = 500L

        const val FRAMES = 2048
        const val CHANNELS = 2
        const val IDLE_SLEEP_MS = 10L
    }
}
