package app.meetingrecorder.core.recording

import app.meetingrecorder.core.wav.RecordingLimits

/**
 * Decides when a running recording must warn the user or stop: free storage before and during the
 * recording, and the WAV size limit (FR-034, research R-3). It owns no clock and no file access, so the
 * rules are testable without a device: the caller passes the time and a way to read the free space.
 *
 * Not thread-safe: use it from one thread at a time (the capture thread once recording runs).
 */
public class RecordingGuard(
    private val sampleRate: Int,
    private val channels: Int,
    private val clockMs: () -> Long,
    private val config: Config = Config(),
) {
    public data class Config(
        /** A recording does not start with less free space than this. */
        val minStartBytes: Long = 10L * 1024 * 1024,
        /** Below this much free space the user is warned once. */
        val lowStorageBytes: Long = 100L * 1024 * 1024,
        /** How often the free space is read while recording. */
        val storageCheckIntervalMs: Long = 10_000L,
    )

    /** Something the recording has to tell the user, or act on. Each signal is given at most once. */
    public sealed interface Signal {
        /** At most [WARNING][RecordingLimits.WARNING_SECONDS] seconds of recording time are left. */
        public data class LimitApproaching(val secondsLeft: Long) : Signal

        /** The WAV size limit is reached: the recording must be finalized now. */
        public data object LimitReached : Signal

        public data class StorageLow(val bytesLeft: Long) : Signal
    }

    private var lastStorageCheckMs = clockMs()
    private var lowStorageWarned = false
    private var limitWarned = false
    private var limitReached = false

    /** False when the known free space is too small to start; unknown free space does not block a start. */
    public fun canStart(freeBytes: Long?): Boolean = freeBytes == null || freeBytes >= config.minStartBytes

    /** Call once when the recording has started: warns right away if the space is enough to start but low. */
    public fun onStarted(freeBytes: Long?): Signal.StorageLow? {
        if (freeBytes == null || freeBytes >= config.lowStorageBytes) return null
        lowStorageWarned = true
        return Signal.StorageLow(freeBytes)
    }

    /**
     * Call for every buffer with the frames recorded so far. [freeBytes] is only called when the check
     * interval has passed, because reading the free space is file I/O.
     */
    public fun check(framesAppended: Long, freeBytes: () -> Long?): List<Signal> {
        val signals = mutableListOf<Signal>()
        if (!limitWarned && RecordingLimits.isWarningDue(framesAppended, sampleRate, channels)) {
            limitWarned = true
            signals += Signal.LimitApproaching(RecordingLimits.remainingSeconds(framesAppended, sampleRate, channels))
        }
        if (!limitReached && RecordingLimits.isLimitReached(framesAppended, channels)) {
            limitReached = true
            signals += Signal.LimitReached
        }
        val now = clockMs()
        if (now - lastStorageCheckMs >= config.storageCheckIntervalMs) {
            lastStorageCheckMs = now
            val free = freeBytes()
            if (free != null && free < config.lowStorageBytes && !lowStorageWarned) {
                lowStorageWarned = true
                signals += Signal.StorageLow(free)
            }
        }
        return signals
    }

    public companion object {
        /** True when [error], or one of its causes, says the device ran out of space. */
        public fun isOutOfSpace(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.any { cause ->
                cause.message.orEmpty().let { "ENOSPC" in it || "No space left" in it }
            }
    }
}
