package app.meetingrecorder.core.transcript

import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.time.Timeline
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Generates `transcript.md`, deterministically (contracts/transcript-folder.md, "transcript.md"). */
public object TranscriptMarkdown {

    /**
     * [transcriptJsonSha256] is the lower-case SHA-256 of the bytes of the `transcript.json` this
     * file is generated from; it goes into a comment so that a reader can see that the two belong together.
     */
    public fun generate(meeting: MeetingMetadata, transcript: Transcript, transcriptJsonSha256: String): String {
        val blocks = mutableListOf<String>()
        blocks += "# ${meeting.title}"
        blocks += header(transcript)
        blocks += "<!-- generated-from: transcript.json sha256=$transcriptJsonSha256 -->"

        val items = TranscriptTimeline.interleave(transcript.segments, meeting.photos)
        if (transcript.segments.isEmpty() && items.isEmpty()) {
            blocks += "*No speech was detected.*"
        } else {
            val rate = transcript.audio.sampleRate
            for (item in items) {
                blocks += when (item) {
                    is TranscriptItem.SegmentItem ->
                        "**[${Timeline.format(Timeline.framesToMs(item.segment.startFrame, rate))}]** ${item.segment.text}"
                    is TranscriptItem.PhotoItem ->
                        "![Photo ${item.number} (${Timeline.format(item.photo.atMs)})](${item.photo.file})"
                }
            }
        }
        return blocks.joinToString("\n\n") + "\n"
    }

    private fun header(transcript: Transcript): String {
        val language = transcript.language
        val detected = if (language.detected) " (detected)" else ""
        return "*Transcript · model ${transcript.model.name} · language ${language.used}$detected · " +
            "${dateOf(transcript.generatedAt)} · whisper.cpp ${transcript.engine.version}*"
    }

    /** The calendar date of [generatedAt] in its own offset (`YYYY-MM-DD`). */
    private fun dateOf(generatedAt: String): String =
        try {
            OffsetDateTime.parse(generatedAt).toLocalDate().toString()
        } catch (_: DateTimeParseException) {
            generatedAt.take(DATE_LENGTH)
        }

    private const val DATE_LENGTH = 10
}
