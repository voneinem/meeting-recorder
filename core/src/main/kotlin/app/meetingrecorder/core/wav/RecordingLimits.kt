package app.meetingrecorder.core.wav

/**
 * Maximum recording length imposed by the 32-bit WAV data size (FR-034, research R-3), with a safety
 * margin so that header and a partial buffer always fit.
 */
public object RecordingLimits {
    private const val SAFETY_MARGIN_BYTES: Long = 1L shl 20
    private const val BYTES_PER_SAMPLE = 2
    public const val WARNING_SECONDS: Long = 15L * 60

    /** Largest number of audio data bytes a recording may reach. */
    public const val MAX_DATA_BYTES: Long = WavHeader.MAX_DECLARED_DATA_BYTES - SAFETY_MARGIN_BYTES

    public fun maxFrames(channels: Int): Long {
        require(channels in 1..2) { "channels must be 1 or 2" }
        return MAX_DATA_BYTES / (channels * BYTES_PER_SAMPLE)
    }

    public fun maxSeconds(sampleRate: Int, channels: Int): Long {
        require(sampleRate > 0) { "sampleRate must be positive" }
        return maxFrames(channels) / sampleRate
    }

    /** Seconds of audio that still fit after [framesRecorded] frames (never negative). */
    public fun remainingSeconds(framesRecorded: Long, sampleRate: Int, channels: Int): Long {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(framesRecorded >= 0) { "framesRecorded must not be negative" }
        return maxOf(0L, maxFrames(channels) - framesRecorded) / sampleRate
    }

    /** True once at most [WARNING_SECONDS] of recording time are left. */
    public fun isWarningDue(framesRecorded: Long, sampleRate: Int, channels: Int): Boolean =
        remainingSeconds(framesRecorded, sampleRate, channels) <= WARNING_SECONDS

    public fun isLimitReached(framesRecorded: Long, channels: Int): Boolean =
        framesRecorded >= maxFrames(channels)
}
