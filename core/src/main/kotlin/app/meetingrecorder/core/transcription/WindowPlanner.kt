package app.meetingrecorder.core.transcription

/**
 * The window rule of research R-5. Windows are decoded one after the other; the end of a window is
 * adaptive: a segment that touches the end may be cut by it, so it is not accepted and the next
 * window starts at its beginning. No two accepted segments ever cover the same audio.
 */
public class WindowPlanner(public val windowSeconds: Int = WINDOW_SECONDS) {

    init {
        require(windowSeconds > MIN_ADVANCE_SECONDS) { "the window must be longer than $MIN_ADVANCE_SECONDS s" }
    }

    /** The audio to decode next: frames `[fromFrame, toFrame)` of the file. */
    public data class Window(val fromFrame: Long, val toFrame: Long, val isLast: Boolean)

    /** The segments to keep (absolute frames) and where the next window starts. */
    public data class Accepted(val segments: List<WorkSegment>, val nextFrame: Long)

    public fun next(cursorFrame: Long, durationFrames: Long, sampleRate: Int): Window {
        require(cursorFrame in 0 until durationFrames) { "no audio left at $cursorFrame of $durationFrames" }
        require(sampleRate > 0) { "sampleRate must be positive" }
        val end = minOf(cursorFrame + windowSeconds.toLong() * sampleRate, durationFrames)
        return Window(cursorFrame, end, isLast = end == durationFrames)
    }

    /**
     * Converts [segments] (centiseconds relative to the window start, text as the engine returned it)
     * to absolute frames as `from + cs * sampleRate / 100`, clamped to the window, drops segments that
     * are empty after trimming and applies the rule.
     */
    public fun accept(window: Window, segments: List<RawSegment>, sampleRate: Int): Accepted {
        val frames = segments.mapNotNull { raw ->
            val text = raw.text.trim()
            if (text.isEmpty()) {
                null
            } else {
                val start = clamp(window.fromFrame + raw.startCs * sampleRate / CS_PER_SECOND, window)
                val end = clamp(window.fromFrame + raw.endCs * sampleRate / CS_PER_SECOND, window)
                WorkSegment(start, maxOf(start, end), text)
            }
        }
        if (frames.isEmpty()) return Accepted(emptyList(), window.toFrame)
        if (window.isLast) return Accepted(frames, window.toFrame)

        val last = frames.last()
        if (last.endFrame <= window.toFrame - TAIL_GUARD_SECONDS.toLong() * sampleRate) {
            return Accepted(frames, window.toFrame)
        }
        val next = last.startFrame
        if (next - window.fromFrame < MIN_ADVANCE_SECONDS.toLong() * sampleRate) {
            // Progress guarantee: the last segment starts right at the window start (or spans it).
            val advanced = maxOf(last.endFrame, window.fromFrame + MIN_ADVANCE_SECONDS.toLong() * sampleRate)
            return Accepted(frames, minOf(advanced, window.toFrame))
        }
        return Accepted(frames.dropLast(1), next)
    }

    private fun clamp(frame: Long, window: Window): Long = frame.coerceIn(window.fromFrame, window.toFrame)

    public companion object {
        public const val WINDOW_SECONDS: Int = 120
        public const val TAIL_GUARD_SECONDS: Int = 2
        public const val MIN_ADVANCE_SECONDS: Int = 5

        /** The engine refuses input shorter than 1.1 s at 16 kHz; shorter windows are padded with silence. */
        public const val MIN_DECODE_SAMPLES: Int = 17_600

        public fun paddedSampleCount(count: Int): Int = maxOf(count, MIN_DECODE_SAMPLES)

        private const val CS_PER_SECOND = 100L
    }
}
