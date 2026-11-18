package app.meetingrecorder.core.transcription

/**
 * Whisper sometimes repeats one phrase over silence or noise. More than three consecutive segments
 * with identical text (compared trimmed and lower-cased) collapse to one segment that keeps the
 * first segment's start and the last segment's end (research R-5).
 */
public object RepetitionGuard {

    public const val MAX_REPEATS: Int = 3

    public fun collapse(segments: List<WorkSegment>): List<WorkSegment> {
        val result = ArrayList<WorkSegment>(segments.size)
        var index = 0
        while (index < segments.size) {
            val key = normalize(segments[index].text)
            var run = index + 1
            while (run < segments.size && normalize(segments[run].text) == key) run++
            val length = run - index
            if (length > MAX_REPEATS) {
                result += segments[index].copy(endFrame = segments[run - 1].endFrame)
            } else {
                for (i in index until run) result += segments[i]
            }
            index = run
        }
        return result
    }

    private fun normalize(text: String): String = text.trim().lowercase()
}
