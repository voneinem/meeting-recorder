package app.meetingrecorder.recording

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import app.meetingrecorder.MainActivity
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.model.JobState
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.grantRecordingPermissions
import app.meetingrecorder.transcription.ActivePhase
import app.meetingrecorder.transcription.Mode
import app.meetingrecorder.transcription.StartResult
import app.meetingrecorder.transcription.TranscriptionRequest
import app.meetingrecorder.transcription.TranscriptionState
import app.meetingrecorder.transcription.TranscriptionTestEnvironment
import app.meetingrecorder.waitUntil
import app.meetingrecorder.work.JobKind
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A recording has priority over a transcription (FR-017, T159): started during a running transcription it begins as
 * fast as one without, the transcription stops within 5 s and shows as `failed / recording-started / resumable`, the
 * recording has no gaps compared with a recording made without a transcription, and while it runs no transcription can
 * start. Needs the tiny model and `jfk.wav` pushed and an emulator; see [TranscriptionTestEnvironment].
 */
class RecordingPriorityTest {
    private var environment: TranscriptionTestEnvironment? = null
    private val env get() = checkNotNull(environment)

    @Before
    fun setUp() {
        environment = TranscriptionTestEnvironment.create("recording-priority")
        grantRecordingPermissions()
    }

    @After
    fun tearDown() {
        environment?.let { e ->
            runBlocking { if (e.app.recordingController.state.value.phase != Phase.IDLE) e.app.recordingController.stop() }
            e.close()
        }
    }

    private class Recorded(val startMs: Long, val meeting: MeetingMetadata)

    /** Records for [seconds] of audio and returns how long the start took (call to first frame) and the saved meeting. */
    private fun record(title: String, seconds: Int, whileRecording: (calledStartAt: Long) -> Unit = {}): Recorded {
        val controller = env.app.recordingController
        val before = SystemClock.elapsedRealtime()
        assertTrue("recording did not start", runBlocking { controller.start(title, env.tree.uri) })
        waitUntil("audio arrives", 20_000) { controller.state.value.phase == Phase.RECORDING && controller.state.value.framesAppended > 0 }
        val startMs = SystemClock.elapsedRealtime() - before
        whileRecording(before)
        waitUntil("$seconds s recorded", 60_000) { controller.state.value.elapsedMs >= seconds * 1000L }
        runBlocking { controller.stop() }
        waitUntil("idle after stop", 20_000) { controller.state.value.phase == Phase.IDLE }
        val saved = env.storage.listChildren(env.storage.rootUri).filter { it.isDirectory }.mapNotNull { folder ->
            val file = env.storage.findChild(folder.uri, MeetingFolderLayout.METADATA_FILE) ?: return@mapNotNull null
            (MeetingJson.decode(env.storage.readText(file.uri).orEmpty()) as? MeetingJson.DecodeResult.Success)?.meeting
        }.filter { it.title == title }
        val decoded = saved.single()
        return Recorded(startMs, decoded)
    }

    @Test
    fun aRecordingStopsARunningTranscriptionAndLosesNoAudio() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val baseline = record("Baseline", seconds = 8)
            // A new recording's meeting.json has no transcription key, as in Phase 1 (T177).
            assertNull("a new recording must not carry a transcription block", baseline.meeting.transcription)

            // About 5.5 minutes of audio, far more than the test needs: the job is still running when the recording starts.
            val meeting = env.buildMeeting(repeat = 30)
            val controller = env.app.transcriptionController
            assertEquals(StartResult.Started, runBlocking { controller.start(TranscriptionRequest(meeting.folderName, env.modelId, "en", Mode.START)) })
            waitUntil("the job is decoding", 120_000) { (controller.state.value as? TranscriptionState.Active)?.phase == ActivePhase.RUNNING }

            // The transcription must stop within 5 s of the recording's start, not at the end of the recording.
            var stoppedAfterMs = Long.MAX_VALUE
            val interrupted = record("Interrupting", seconds = 8) { calledStartAt ->
                waitUntil("the transcription stops", 30_000) { controller.state.value == TranscriptionState.Idle }
                stoppedAfterMs = SystemClock.elapsedRealtime() - calledStartAt
            }
            assertTrue("the transcription took $stoppedAfterMs ms to stop, the limit is 5000 ms (SC-012)", stoppedAfterMs <= 5_000)

            val block = checkNotNull(env.readMetadata(meeting).transcription) { "no transcription block" }
            val job = checkNotNull(block.job) { "no job block: ${env.storage.readText(meeting.metadataUri)}" }
            assertEquals(JobState.FAILED, job.state)
            assertEquals(FailureReason.RECORDING_STARTED, job.failureReason)
            assertTrue("the job must be resumable", job.resumable)
            assertNull("no transcript was committed", block.transcript)

            // As fast to start as without a transcription.
            assertTrue(
                "the recording started in ${interrupted.startMs} ms against ${baseline.startMs} ms without a transcription",
                interrupted.startMs <= baseline.startMs + START_MARGIN_MS,
            )
            // No gaps: the same amount of audio as the baseline, no dropouts.
            assertEquals("dropouts: ${interrupted.meeting.dropouts}", emptyList<Any>(), interrupted.meeting.dropouts)
            val difference = abs(interrupted.meeting.audio.durationMs - baseline.meeting.audio.durationMs)
            assertTrue(
                "recorded ${interrupted.meeting.audio.durationMs} ms against ${baseline.meeting.audio.durationMs} ms without a transcription",
                difference <= DURATION_TOLERANCE_MS,
            )

            // While recording no transcription can start, and nothing resumes by itself afterwards.
            assertEquals(TranscriptionState.Idle, controller.state.value)
            assertNull(env.app.jobRegistry.current(JobKind.TRANSCRIPTION))
        }
    }

    @Test
    fun aTranscriptionIsRefusedWhileARecordingRuns() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val meeting = env.buildMeeting(repeat = 1)
            val controller = env.app.recordingController
            assertTrue(runBlocking { controller.start("Refusal", env.tree.uri) })
            try {
                waitUntil("audio arrives", 20_000) { controller.state.value.phase == Phase.RECORDING && controller.state.value.framesAppended > 0 }
                assertEquals(
                    StartResult.RecordingInProgress,
                    runBlocking { env.app.transcriptionController.start(TranscriptionRequest(meeting.folderName, env.modelId, "en", Mode.START)) },
                )
            } finally {
                runBlocking { controller.stop() }
                waitUntil("idle after stop", 20_000) { controller.state.value.phase == Phase.IDLE }
            }
            assertNotNull(env.readMetadata(meeting))
            assertNull("a refused start leaves no job", env.readMetadata(meeting).transcription)
        }
    }

    private companion object {
        const val START_MARGIN_MS = 1_500L
        const val DURATION_TOLERANCE_MS = 500L
    }
}
