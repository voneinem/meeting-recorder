package app.meetingrecorder.core.transcription

/** Progress 0..1 and the estimated remaining time; [remainingMs] is `null` until the estimate is usable. */
public data class Progress(val progress: Float, val remainingMs: Long?, val elapsedMs: Long)

/**
 * Progress and remaining time of one transcription job (contracts/transcription-service.md). Pure:
 * the caller passes the wall-clock time. One instance feeds both the notification and the screen.
 *
 * - progress is `framesDone / durationFrames` at the last anchor, interpolated between anchors at the
 *   measured rate, never decreasing, never more than [MAX_LEAD_MS] of audio ahead of the last anchor
 *   and below 1.0 until [finish] (the commit) is called;
 * - the remaining time is the remaining audio times the recent real-time factor (an exponential moving
 *   average over the anchors), `null` before the first anchor that advanced, rounded to 5 s above one minute.
 */
public class ProgressEstimator(
    private val durationFrames: Long,
    private val sampleRate: Int,
    private val elapsedBeforeMs: Long = 0,
    private val startFrame: Long = 0,
) {
    init {
        require(durationFrames > 0) { "durationFrames must be positive" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(elapsedBeforeMs >= 0) { "elapsedBeforeMs must not be negative" }
        require(startFrame in 0..durationFrames) { "startFrame is outside the audio" }
    }

    private var beganAtMs: Long? = null
    private var anchorFrames: Long = startFrame
    private var anchorWallMs: Long = 0

    /** Wall-clock time per millisecond of audio (smoothed); `null` until an anchor advanced. */
    private var realTimeFactor: Double? = null
    private var highWater: Float = startFrame.toFloat() / durationFrames
    private var finishedAtMs: Long? = null

    /** Marks the moment decoding starts at [startFrame]. */
    public fun begin(wallClockMs: Long) {
        beganAtMs = wallClockMs
        anchorWallMs = wallClockMs
        anchorFrames = startFrame
    }

    /** [framesDone] is the position up to which the audio is decoded (a window end or a segment end). */
    public fun anchor(framesDone: Long, wallClockMs: Long) {
        check(beganAtMs != null) { "begin() has not been called" }
        val frames = framesDone.coerceIn(anchorFrames, durationFrames)
        val deltaFrames = frames - anchorFrames
        val deltaWall = wallClockMs - anchorWallMs
        if (deltaFrames > 0 && deltaWall >= 0) {
            val sample = deltaWall.toDouble() / framesToMs(deltaFrames)
            realTimeFactor = realTimeFactor?.let { SMOOTHING * sample + (1 - SMOOTHING) * it } ?: sample
            anchorFrames = frames
            anchorWallMs = wallClockMs
        }
    }

    /** The commit starts: from now on the progress is 1.0. */
    public fun finish(wallClockMs: Long) {
        finishedAtMs = wallClockMs
    }

    public fun snapshot(wallClockMs: Long): Progress {
        val began = beganAtMs ?: return Progress(highWater, null, elapsedBeforeMs)
        val elapsed = elapsedBeforeMs + ((finishedAtMs ?: wallClockMs) - began).coerceAtLeast(0)
        if (finishedAtMs != null) {
            highWater = 1f
            return Progress(1f, 0, elapsed)
        }
        val rtf = realTimeFactor
        val leadMs = if (rtf != null && rtf > 0) {
            ((wallClockMs - anchorWallMs).coerceAtLeast(0) / rtf).toLong().coerceAtMost(MAX_LEAD_MS)
        } else {
            0L
        }
        val frames = (anchorFrames + msToFrames(leadMs)).coerceAtMost(durationFrames)
        val progress = (frames.toFloat() / durationFrames).coerceAtMost(MAX_BEFORE_COMMIT)
        highWater = maxOf(highWater, progress)
        val remaining = rtf?.let { roundRemaining(((framesToMs(durationFrames - frames)) * it).toLong()) }
        return Progress(highWater, remaining, elapsed)
    }

    private fun framesToMs(frames: Long): Long = frames * MS_PER_SECOND / sampleRate

    private fun msToFrames(ms: Long): Long = ms * sampleRate / MS_PER_SECOND

    private fun roundRemaining(ms: Long): Long =
        if (ms > ONE_MINUTE_MS) (ms + ROUND_MS / 2) / ROUND_MS * ROUND_MS else ms

    public companion object {
        /** One decode window: progress may run up to the next anchor, so it keeps moving between window ends (T136). */
        public const val MAX_LEAD_MS: Long = WindowPlanner.WINDOW_SECONDS * 1000L
        public const val MAX_BEFORE_COMMIT: Float = 0.99f
        private const val SMOOTHING = 0.3
        private const val ONE_MINUTE_MS = 60_000L
        private const val ROUND_MS = 5_000L
        private const val MS_PER_SECOND = 1000L
    }
}
