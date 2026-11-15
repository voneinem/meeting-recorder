package app.meetingrecorder.core.level

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Peak and RMS of one channel in dBFS (0 is full scale, [LevelMeter.FLOOR_DB] is silence). */
public data class ChannelLevel(val peakDb: Float, val rmsDb: Float)

/** Level metering of interleaved 16-bit PCM buffers. */
public object LevelMeter {
    public const val FLOOR_DB: Float = -90f
    private const val FULL_SCALE = 32768.0

    public fun measure(buffer: ShortArray, frames: Int, channels: Int): List<ChannelLevel> {
        require(channels in 1..2) { "channels must be 1 or 2" }
        require(frames >= 0 && frames * channels <= buffer.size) { "buffer too small for $frames frames" }
        return List(channels) { channel ->
            var peak = 0.0
            var sumSquares = 0.0
            for (frame in 0 until frames) {
                val sample = buffer[frame * channels + channel].toDouble() / FULL_SCALE
                peak = maxOf(peak, abs(sample))
                sumSquares += sample * sample
            }
            val rms = if (frames > 0) sqrt(sumSquares / frames) else 0.0
            ChannelLevel(toDb(peak), toDb(rms))
        }
    }

    private fun toDb(linear: Double): Float =
        if (linear <= 0.0) FLOOR_DB else maxOf(FLOOR_DB, (20.0 * log10(linear)).toFloat())
}
