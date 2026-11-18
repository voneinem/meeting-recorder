package app.meetingrecorder.transcription

import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.MeetingEditor
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.SourceInfo
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.models.Sha256Streaming
import app.meetingrecorder.core.transcript.TranscriptJson
import app.meetingrecorder.core.transcript.TranscriptValidator
import app.meetingrecorder.core.transcription.WorkFile
import app.meetingrecorder.core.transcription.WorkRecord
import app.meetingrecorder.core.transcription.WorkSegment
import app.meetingrecorder.storage.FakeFolderFiles
import app.meetingrecorder.storage.FolderStep
import app.meetingrecorder.storage.MeetingMetadataStore
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptCommitterTest {
    private val sha = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    private val now = OffsetDateTime.parse("2026-11-17T09:00:00+01:00")

    private val meeting: MeetingMetadata = MeetingEditor.finish(
        MeetingEditor.create("Weekly sync", OffsetDateTime.parse("2026-11-16T10:00:00+01:00"), 48_000, SourceInfo("Built-in", SourceType.BUILTIN, 1)),
        durationFrames = 960_000,
    )

    private val records: List<WorkRecord> = listOf(
        WorkRecord.Header(
            modelId = "small-q5_1", modelSha256 = sha, requestedLanguage = "auto", vad = false, sampleRate = 48_000,
            durationFrames = 960_000, engineVersion = "1.9.5", pipeline = 1, startedAt = "2026-11-17T08:55:00+01:00",
        ),
        WorkRecord.Language("de", 0.97, listOf(96_000, 384_000, 672_000)),
        WorkRecord.Window(0, 576_000, 8_000, listOf(WorkSegment(0, 201_600, "Guten Morgen zusammen."), WorkSegment(201_600, 470_400, "Die Zahlen sehen gut aus."))),
        WorkRecord.Window(576_000, 960_000, 6_200, listOf(WorkSegment(576_000, 744_000, "Hier ist das Whiteboard."), WorkSegment(744_000, 912_000, "Gibt es Fragen?"))),
        WorkRecord.End(4, 14_200),
    )

    private fun workFile(list: List<WorkRecord> = records) = list.joinToString("") { WorkFile.encodeLine(it) }

    private fun folder(
        extra: Map<String, String> = emptyMap(),
        work: String? = workFile(),
        meta: MeetingMetadata = MeetingEditor.startJob(meeting, "small-q5_1", "auto", now),
        direct: Boolean = false,
    ): FakeFolderFiles {
        val initial = mutableMapOf(
            "meeting.json" to MeetingJson.encode(meta),
            "audio.wav" to "RIFF-audio-bytes",
            "photos/photo-001.jpg" to "jpeg",
        )
        work?.let { initial["transcript.work.jsonl"] = it }
        initial += extra
        return FakeFolderFiles(initial, directReplace = direct)
    }

    private fun committer(files: FakeFolderFiles) = TranscriptCommitter(
        files = files,
        metadata = MeetingMetadataStore(warn = { _, _ -> }) { if (it == "m1") files else null },
        folderName = "m1",
        modelName = "Small (q5_1)",
        modelOrigin = ModelOrigin.CATALOG,
        now = { now },
    )

    private fun snapshot(files: FakeFolderFiles) = files.names().sorted().associateWith { files.text(it) }

    @Test
    fun aCommitWritesAllFilesAndDeletesTheWorkFileLast() {
        val files = folder()

        assertEquals(CommitOutcome.Committed, committer(files).commit())

        assertEquals(setOf("meeting.json", "CLAUDE.md", "audio.wav", "photos/photo-001.jpg", "transcript.json", "transcript.md"), files.names())
        assertEquals("delete:transcript.work.jsonl", files.operations.last())
        val json = TranscriptJson.decode(files.text("transcript.json")!!) as TranscriptJson.DecodeResult.Success
        assertEquals(emptyList(), TranscriptValidator.validate(json.transcript))
        assertEquals(4, json.transcript.segments.size)
        assertEquals("de", json.transcript.language.used)
    }

    @Test
    fun theMarkdownCarriesTheHashOfTheJsonBytes() {
        val files = folder()
        committer(files).commit()

        val hash = Sha256Streaming().update(files.text("transcript.json")!!.toByteArray()).hex()
        assertTrue(files.text("transcript.md")!!.contains(hash), "generated-from comment with the SHA-256 of transcript.json")
        assertTrue(files.text("transcript.md")!!.contains("Guten Morgen zusammen."))
    }

    @Test
    fun theMetadataEndsWithTheTranscriptAndWithoutTheJobAndTheGuideIsRegenerated() {
        val files = folder()
        committer(files).commit()

        val meta = (MeetingJson.decode(files.text("meeting.json")!!) as MeetingJson.DecodeResult.Success).meeting
        val block = assertNotNull(meta.transcription)
        assertNull(block.job)
        assertEquals("small-q5_1", block.transcript?.modelId)
        assertEquals(4, block.transcript?.segmentCount)
        assertEquals(14_200L, block.transcript?.processingMs)
        assertTrue(files.text("CLAUDE.md")!!.contains("## Transcript"))
    }

    @Test
    fun audioAndPhotosAreNeverWritten() {
        val files = folder()
        committer(files).commit()

        assertEquals("RIFF-audio-bytes", files.text("audio.wav"))
        assertEquals("jpeg", files.text("photos/photo-001.jpg"))
        assertTrue(files.operations.none { "audio.wav" in it || "photos/" in it })
    }

    @Test
    fun withoutACompleteWorkFileNothingIsCommitted() {
        val incomplete = folder(work = workFile(records.dropLast(1)))
        assertEquals(CommitOutcome.NothingToCommit, committer(incomplete).commit())
        assertFalse("transcript.json" in incomplete.names())

        val none = folder(work = null)
        assertEquals(CommitOutcome.NothingToCommit, committer(none).commit())
    }

    @Test
    fun aTornTailAfterTheEndLineIsIgnored() {
        val files = folder(work = workFile() + "{\"t\":\"window\",\"fromFr")
        assertEquals(CommitOutcome.Committed, committer(files).commit())
    }

    @Test
    fun theOldTranscriptSurvivesUntilTheNewOneIsComplete() {
        val files = folder(extra = mapOf("transcript.json" to "OLD", "transcript.md" to "OLD MD"))
        // Fail the first time the old transcript.json would be removed.
        files.failOnNth(FolderStep.DELETE_OLD, 1)

        val outcome = committer(files).commit()

        assertTrue(outcome is CommitOutcome.Failed)
        assertEquals("OLD", files.text("transcript.json"))
        assertEquals("OLD MD", files.text("transcript.md"))
        assertTrue("transcript.work.jsonl" in files.names(), "the commit record stays")
    }

    @Test
    fun aReplaceOverAnExistingTranscriptWorksWithTheStagedStrategyAndWithTheDirectFallback() {
        for (direct in listOf(false, true)) {
            val files = folder(extra = mapOf("transcript.json" to "OLD", "transcript.md" to "OLD MD"), direct = direct)

            assertEquals(CommitOutcome.Committed, committer(files).commit(), "direct=$direct")

            assertTrue(files.text("transcript.json")!!.startsWith("{"), "direct=$direct")
            assertFalse(files.names().any { it.endsWith(".part") }, "no staging file is left, direct=$direct")
        }
    }

    @Test
    fun afterAFailureAtAnyStepTheRepeatedCommitEndsInTheSameFinalState() {
        val expected = folder().also { committer(it).commit() }.let(::snapshot)
        var injected = 0
        for (step in listOf(FolderStep.LIST, FolderStep.READ, FolderStep.STAGE_PART, FolderStep.DELETE_OLD, FolderStep.RENAME, FolderStep.DELETE)) {
            var n = 1
            while (true) {
                val files = folder(extra = mapOf("transcript.json" to "OLD", "transcript.md" to "OLD MD"))
                files.failOnNth(step, n)
                val first = committer(files).commit()
                if (!files.fired(step)) break // fewer than n visits: all injection points of this step are covered
                injected++
                assertTrue(first is CommitOutcome.Failed, "$step #$n should fail")
                assertSafeAfterCrash(files, "$step #$n")

                assertEquals(CommitOutcome.Committed, committer(files).commit(), "retry after $step #$n")
                assertEquals(expected.filterKeys { it != "meeting.json" && it != "CLAUDE.md" }, snapshot(files).filterKeys { it != "meeting.json" && it != "CLAUDE.md" }, "files after $step #$n")
                assertEquals(expected["meeting.json"], files.text("meeting.json"), "meeting.json after $step #$n")
                assertEquals(expected["CLAUDE.md"], files.text("CLAUDE.md"), "CLAUDE.md after $step #$n")
                n++
            }
        }
        assertTrue(injected >= 10, "expected many injection points, got $injected")
    }

    /** The invariants of the commit protocol after a crash at any step. */
    private fun assertSafeAfterCrash(files: FakeFolderFiles, label: String) {
        // The work file is the commit record: it exists until everything else is done.
        val metaDone = (MeetingJson.decode(files.text("meeting.json") ?: "") as? MeetingJson.DecodeResult.Success)
            ?.meeting?.transcription?.job == null
        if (!metaDone) assertTrue("transcript.work.jsonl" in files.names(), "work file must survive: $label")
        // transcript.json is the old one, the new complete one, or missing with a complete .part next to it.
        val json = files.text("transcript.json")
        if (json == null) {
            assertNotNull(files.text("transcript.json.part"), "a missing transcript.json needs its .part: $label")
        } else {
            assertTrue(json == "OLD" || TranscriptJson.decode(json) is TranscriptJson.DecodeResult.Success, "transcript.json is never torn: $label")
        }
        assertEquals("RIFF-audio-bytes", files.text("audio.wav"))
    }
}
