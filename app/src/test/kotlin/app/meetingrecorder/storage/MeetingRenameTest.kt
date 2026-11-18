package app.meetingrecorder.storage

import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.models.Sha256Streaming
import app.meetingrecorder.core.transcript.EngineInfo
import app.meetingrecorder.core.transcript.Transcript
import app.meetingrecorder.core.transcript.TranscriptAudio
import app.meetingrecorder.core.transcript.TranscriptJson
import app.meetingrecorder.core.transcript.TranscriptLanguage
import app.meetingrecorder.core.transcript.TranscriptModel
import app.meetingrecorder.core.transcript.TranscriptSegment
import app.meetingrecorder.core.model.TranscriptInfo
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Renaming a meeting (FR-027) also regenerates `transcript.md` when a transcript exists (research R-13). */
class MeetingRenameTest {
    private val base = MeetingEditor.finish(
        MeetingEditor.create("Weekly", OffsetDateTime.parse("2026-11-16T10:00:00+01:00"), 48_000, SourceInfo("Built-in", SourceType.BUILTIN, 1)),
        durationFrames = 960_000,
    )
    private val transcript = Transcript(
        generatedAt = "2026-11-17T09:00:00+01:00",
        audio = TranscriptAudio(48_000, 960_000),
        model = TranscriptModel("tiny", "Tiny", "a".repeat(64), ModelOrigin.CATALOG),
        language = TranscriptLanguage("auto", "de", true, 0.9),
        engine = EngineInfo(version = "1.9.5"),
        segments = listOf(TranscriptSegment(0, 0, 100_000, "Guten Morgen.")),
    )
    private val json = TranscriptJson.encode(transcript)

    private fun transcribed() = MeetingEditor.commitTranscript(
        base, TranscriptInfo("tiny", "Tiny", "auto", "de", "2026-11-17T09:00:00+01:00", 1000, 1),
    )

    private fun folder(withTranscript: Boolean): FakeFolderFiles {
        val files = FakeFolderFiles(mapOf("meeting.json" to MeetingJson.encode(if (withTranscript) transcribed() else base)))
        if (withTranscript) {
            files.put("transcript.json", json)
            files.put("transcript.md", "OLD MD with title Weekly")
        }
        return files
    }

    private fun renamer(files: FakeFolderFiles) = MeetingRenamer(MeetingMetadataStore(warn = { _, _ -> }) { if (it == "m1") files else null }) { if (it == "m1") files else null }

    @Test
    fun theTitleInMeetingJsonTranscriptMdAndTheGuideChanges() {
        val files = folder(withTranscript = true)

        assertTrue(renamer(files).rename("m1", "Quarterly review"))

        val meta = (MeetingJson.decode(files.text("meeting.json")!!) as MeetingJson.DecodeResult.Success).meeting
        assertEquals("Quarterly review", meta.title)
        assertNotNull(meta.transcription?.transcript, "the status stays done")
        val markdown = files.text("transcript.md")!!
        assertTrue(markdown.contains("Quarterly review") && markdown.contains("Guten Morgen."))
        assertFalse(markdown.contains("OLD MD"))
        assertTrue(files.text("CLAUDE.md")!!.contains("Quarterly review"))
        assertEquals(json, files.text("transcript.json"), "transcript.json is never touched")
        assertTrue(markdown.contains(Sha256Streaming().update(json.toByteArray()).hex()))
    }

    @Test
    fun aMeetingWithoutATranscriptGetsNoTranscriptMarkdown() {
        val files = folder(withTranscript = false)

        assertTrue(renamer(files).rename("m1", "Quarterly review"))

        assertFalse("transcript.md" in files.names())
        assertTrue(files.text("meeting.json")!!.contains("Quarterly review"))
    }

    @Test
    fun anUnreadableTranscriptDoesNotStopTheRename() {
        val files = folder(withTranscript = true)
        files.put("transcript.json", "{ broken")

        assertTrue(renamer(files).rename("m1", "Quarterly review"))

        assertEquals("OLD MD with title Weekly", files.text("transcript.md"), "an unreadable transcript is left alone")
    }

    @Test
    fun anInvalidTitleOrAnUnknownMeetingFails() {
        val files = folder(withTranscript = false)
        assertFalse(renamer(files).rename("m1", "   "))
        assertFalse(renamer(files).rename("unknown", "Title"))
        assertEquals("Weekly", (MeetingJson.decode(files.text("meeting.json")!!) as MeetingJson.DecodeResult.Success).meeting.title)
    }
}
