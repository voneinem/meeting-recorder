package app.meetingrecorder.core.transcription

import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.JobInfo
import app.meetingrecorder.core.model.JobState
import app.meetingrecorder.core.model.MeetingTranscription
import app.meetingrecorder.core.transcription.RecoveryAction.DeleteStaging
import app.meetingrecorder.core.transcription.RecoveryAction.MarkInterrupted
import app.meetingrecorder.core.transcription.RecoveryAction.PromoteMetaPart
import app.meetingrecorder.core.transcription.RecoveryAction.RedoCommit
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitPlannerTest {

    private val running = MeetingTranscription(job = JobInfo(JobState.IN_PROGRESS, "m", "auto", "2026-11-16T22:40:00+01:00"))
    private val failed = MeetingTranscription(
        job = JobInfo(JobState.FAILED, "m", "auto", "2026-11-16T22:40:00+01:00", FailureReason.ERROR),
    )

    private fun plan(
        names: Set<String> = setOf("audio.wav", "meeting.json"),
        work: WorkFileState = WorkFileState.NONE,
        transcript: TranscriptState = TranscriptState.MISSING,
        block: MeetingTranscription? = null,
        metaReadable: Boolean = true,
        live: Boolean = false,
    ) = CommitPlanner.plan(names, work, transcript, block, metaReadable, live)

    @Test
    fun `staging files without a work file are deleted`() {
        val names = setOf("meeting.json", "transcript.json", "transcript.json.part", "transcript.md.part")
        assertEquals(listOf<RecoveryAction>(DeleteStaging(setOf("transcript.json.part", "transcript.md.part"))), plan(names = names, transcript = TranscriptState.VALID))
    }

    @Test
    fun `staging files next to a usable work file are left for the commit`() {
        val names = setOf("meeting.json", "transcript.work.jsonl", "transcript.json.part")
        assertEquals(emptyList(), plan(names = names, work = WorkFileState.INCOMPLETE))
        assertEquals(listOf<RecoveryAction>(RedoCommit), plan(names = names, work = WorkFileState.COMPLETE))
    }

    @Test
    fun `a work file whose header is unusable is deleted with the staging files`() {
        val names = setOf("meeting.json", "transcript.work.jsonl", "transcript.json.part")
        assertEquals(
            listOf<RecoveryAction>(DeleteStaging(setOf("transcript.work.jsonl", "transcript.json.part"))),
            plan(names = names, work = WorkFileState.NONE),
        )
    }

    @Test
    fun `a work file with an end line yields a redo of the commit`() {
        val names = setOf("meeting.json", "transcript.work.jsonl")
        assertEquals(listOf<RecoveryAction>(RedoCommit), plan(names = names, work = WorkFileState.COMPLETE, block = running))
    }

    @Test
    fun `an in-progress job without a live job is marked interrupted and resumable follows the work file`() {
        val names = setOf("meeting.json", "transcript.work.jsonl")
        assertEquals(listOf<RecoveryAction>(MarkInterrupted(resumable = true)), plan(names = names, work = WorkFileState.INCOMPLETE, block = running))
        assertEquals(listOf<RecoveryAction>(MarkInterrupted(resumable = false)), plan(block = running))
    }

    @Test
    fun `a failed job is left alone`() {
        assertEquals(emptyList(), plan(names = setOf("meeting.json", "transcript.work.jsonl"), work = WorkFileState.INCOMPLETE, block = failed))
    }

    @Test
    fun `a meeting json part next to an unreadable meeting json is promoted first`() {
        val names = setOf("meeting.json", "meeting.json.part", "transcript.work.jsonl")
        assertEquals(
            listOf<RecoveryAction>(PromoteMetaPart, RedoCommit),
            plan(names = names, work = WorkFileState.COMPLETE, metaReadable = false),
        )
        assertEquals(
            listOf<RecoveryAction>(PromoteMetaPart),
            plan(names = setOf("meeting.json.part"), metaReadable = false),
        )
    }

    @Test
    fun `a stale meeting json part next to a readable meeting json is deleted`() {
        assertEquals(
            listOf<RecoveryAction>(DeleteStaging(setOf("meeting.json.part"))),
            plan(names = setOf("meeting.json", "meeting.json.part")),
        )
    }

    @Test
    fun `a meeting with a live job is never touched`() {
        val names = setOf("meeting.json", "transcript.work.jsonl", "transcript.json.part")
        assertEquals(emptyList(), plan(names = names, work = WorkFileState.INCOMPLETE, block = running, live = true))
    }

    @Test
    fun `a second run on the repaired state yields no actions`() {
        assertEquals(emptyList(), plan(transcript = TranscriptState.VALID, names = setOf("meeting.json", "transcript.json", "transcript.md")))
        assertEquals(emptyList(), plan(names = setOf("meeting.json", "transcript.work.jsonl"), work = WorkFileState.INCOMPLETE, block = failed))
    }
}
