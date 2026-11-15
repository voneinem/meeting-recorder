package app.meetingrecorder.core.time

/**
 * The single conversion between sample frames and milliseconds (Constitution Principle V).
 * Every position in a meeting is a frame index; milliseconds are derived and rounded down.
 */
public object Timeline {

    public fun framesToMs(frames: Long, sampleRate: Int): Long {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(frames >= 0) { "frames must not be negative: $frames" }
        return frames * MS_PER_SECOND / sampleRate
    }

    public fun msToFrames(ms: Long, sampleRate: Int): Long {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(ms >= 0) { "ms must not be negative: $ms" }
        return ms * sampleRate / MS_PER_SECOND
    }

    /** True when [atFrame] lies on the timeline of a recording of [durationFrames] frames. */
    public fun isValidPosition(atFrame: Long, durationFrames: Long): Boolean =
        atFrame in 0..durationFrames

    /** Formats a position as `mm:ss`, or `h:mm:ss` from one hour on. */
    public fun format(ms: Long): String {
        require(ms >= 0) { "ms must not be negative: $ms" }
        val totalSeconds = ms / MS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private const val MS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
}
