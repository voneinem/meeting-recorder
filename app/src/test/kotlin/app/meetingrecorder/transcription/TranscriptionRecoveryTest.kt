package app.meetingrecorder.transcription

import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.JobState
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.transcription.WorkFile
import app.meetingrecorder.core.transcription.WorkRecord
import app.meetingrecorder.core.transcription.WorkSegment
import app.meetingrecorder.storage.FakeFolderFiles
import app.meetingrecorder.storage.MeetingMetadataStore
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptionRecoveryTest {
    private val sha = "e".repeat(64)
    private val now = OffsetDateTime.parse("2026-11-17T10:00:00+01:00")
    private val base: MeetingMetadata = MeetingEditor.finish(
        MeetingEditor.create("Weekly", OffsetDateTime.parse("2026-11-16T10:00:00+01:00"), 48_000, SourceInfo("Built-in", SourceType.BUILTIN, 1)),
        960_000,
    )
    private val header = WorkRecord.Header(
        modelId = "tiny", modelSha256 = sha, requestedLanguage = "de", vad = false, sampleRate = 48_000,
        durationFrames = 960_000, engineVersion = "1.9.5", pipeline = 1, startedAt = "2026-11-17T09:00:00+01:00",
    )
    private val language = WorkRecord.Language("de", null, emptyList())
    private val window = WorkRecord.Window(0, 960_000, 5000, listOf(WorkSegment(0, 100_000, "Hallo zusammen.")))

    private fun work(vararg records: WorkRecord) = records.joinToString("") { WorkFile.encodeLine(it) }

    private val incompleteWork = work(header, language)
    private val completeWork = work(header, language, window, WorkRecord.End(1, 5000))

    private fun inProgress(): MeetingMetadata = MeetingEditor.startJob(base, "tiny", "de", now)

    private class Canceller {
        val cancelled = mutableListOf<String>()
        fun cancel(name: String) {
            cancelled += name
        }
    }

    private val canceller = Canceller()
    private var live: (String) -> Boolean = { false }

    private fun recovery(folders: Map<String, FakeFolderFiles>) = TranscriptionRecovery(
        folderNames = { folders.keys.toList() },
        folderFiles = { folders[it] },
        metadata = MeetingMetadataStore(warn = { _, _ -> }) { folders[it] },
        isLive = { live(it) },
        cancelWork = canceller::cancel,
        modelInfo = { _ -> "Tiny" to ModelOrigin.CATALOG },
        now = { now },
    )

    private fun folder(meta: MeetingMetadata?, extra: Map<String, String> = emptyMap()): FakeFolderFiles {
        val initial = mutableMapOf("audio.wav" to "RIFF")
        meta?.let { initial["meeting.json"] = MeetingJson.encode(it) }
        initial += extra
        return FakeFolderFiles(initial)
    }

    private fun metaOf(files: FakeFolderFiles): MeetingMetadata =
        (MeetingJson.decode(files.text("meeting.json")!!) as MeetingJson.DecodeResult.Success).meeting

    private fun snapshot(folders: Map<String, FakeFolderFiles>) = folders.mapValues { (_, f) -> f.names().sorted().associateWith { f.text(it) } }

    @Test
    fun bothUniqueWorkNamesAreCancelledAtStart() {
        recovery(emptyMap()).run()
        assertEquals(listOf("transcription", "model-install"), canceller.cancelled)
    }

    @Test
    fun stagingFilesWithoutAWorkFileAreDeleted() {
        val files = folder(base, mapOf("transcript.json.part" to "x", "transcript.md.part" to "y"))
        recovery(mapOf("m1" to files)).run()
        assertFalse(files.names().any { it.endsWith(".part") })
    }

    @Test
    fun aCompleteWorkFileRedoesTheCommit() {
        val files = folder(inProgress(), mapOf("transcript.work.jsonl" to completeWork))

        recovery(mapOf("m1" to files)).run()

        assertTrue("transcript.json" in files.names() && "transcript.md" in files.names())
        assertFalse("transcript.work.jsonl" in files.names())
        val block = assertNotNull(metaOf(files).transcription)
        assertNull(block.job)
        assertEquals("Hallo zusammen.", (app.meetingrecorder.core.transcript.TranscriptJson.decode(files.text("transcript.json")!!) as app.meetingrecorder.core.transcript.TranscriptJson.DecodeResult.Success).transcript.segments.single().text)
    }

    @Test
    fun aStaleInProgressBlockBecomesFailedInterruptedWithResumableFromTheWorkFile() {
        val withWork = folder(inProgress(), mapOf("transcript.work.jsonl" to incompleteWork))
        val withoutWork = folder(inProgress())

        recovery(mapOf("a" to withWork, "b" to withoutWork)).run()

        val resumable = assertNotNull(metaOf(withWork).transcription?.job)
        assertEquals(JobState.FAILED, resumable.state)
        assertEquals(FailureReason.INTERRUPTED, resumable.failureReason)
        assertTrue(resumable.resumable)
        assertFalse(assertNotNull(metaOf(withoutWork).transcription?.job).resumable)
        assertTrue("transcript.work.jsonl" in withWork.names(), "the checkpoint stays for a resume")
    }

    @Test
    fun aMetaPartIsPromotedWhenTheMetadataIsUnreadable() {
        val files = folder(null, mapOf("meeting.json" to "{ broken", "meeting.json.part" to MeetingJson.encode(base)))

        recovery(mapOf("m1" to files)).run()

        assertEquals("Weekly", metaOf(files).title)
        assertFalse("meeting.json.part" in files.names())
    }

    @Test
    fun aSecondRunChangesNothing() {
        val folders = mapOf(
            "a" to folder(inProgress(), mapOf("transcript.work.jsonl" to incompleteWork)),
            "b" to folder(inProgress(), mapOf("transcript.work.jsonl" to completeWork)),
            "c" to folder(null, mapOf("meeting.json" to "{ broken", "meeting.json.part" to MeetingJson.encode(base), "transcript.md.part" to "x")),
        )
        recovery(folders).run()
        val afterFirst = snapshot(folders)

        recovery(folders).run()

        assertEquals(afterFirst, snapshot(folders))
    }

    @Test
    fun aMeetingWithALiveJobIsNeverTouched() {
        val files = folder(inProgress(), mapOf("transcript.work.jsonl" to completeWork, "transcript.json.part" to "x"))
        val before = snapshot(mapOf("m1" to files))
        live = { it == "m1" }

        recovery(mapOf("m1" to files)).run()

        assertEquals(before, snapshot(mapOf("m1" to files)))
    }

    @Test
    fun aFolderThatIsNotAMeetingIsIgnoredAndAFailureInOneMeetingDoesNotStopTheOthers() {
        val broken = FakeFolderFiles(emptyMap())
        broken.failAt(app.meetingrecorder.storage.FolderStep.LIST, 50)
        val good = folder(inProgress(), mapOf("transcript.work.jsonl" to incompleteWork))

        recovery(mapOf("broken" to broken, "good" to good)).run()

        assertEquals(JobState.FAILED, metaOf(good).transcription?.job?.state)
    }
}
