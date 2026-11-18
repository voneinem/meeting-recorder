package app.meetingrecorder.transcription

import android.net.TrafficStats
import android.os.Build
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.MeetingRecorderApp
import app.meetingrecorder.TestTree
import app.meetingrecorder.core.folder.MeetingFolderLayout
import app.meetingrecorder.core.json.MeetingJson
import app.meetingrecorder.core.json.MeetingValidator
import app.meetingrecorder.core.model.AudioInfo
import app.meetingrecorder.core.model.ChannelLayout
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.model.Source
import app.meetingrecorder.core.model.SourceType
import app.meetingrecorder.core.transcript.Transcript
import app.meetingrecorder.core.transcript.TranscriptJson
import app.meetingrecorder.core.transcript.TranscriptValidator
import app.meetingrecorder.core.wav.WavHeader
import app.meetingrecorder.models.ImportListener
import app.meetingrecorder.models.ImportResult
import app.meetingrecorder.models.ImportSource
import app.meetingrecorder.models.ModelImporter
import app.meetingrecorder.storage.SafMeetingStorage
import app.meetingrecorder.targetContext
import app.meetingrecorder.waitUntil
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs a real transcription through WorkManager, the worker and the real engine with the tiny model, on a
 * meeting built from `jfk.wav` in the debug document tree (T134).
 *
 * Needs the files pushed first (the tests are skipped without them):
 *
 *     adb push ggml-tiny-q5_1.bin /data/local/tmp/
 *     adb push jfk.wav /data/local/tmp/
 *
 * Shared set-up and the emulator-only guard: [TranscriptionTestEnvironment].
 */
class TranscriptionWorkerTest {

    private var environment: TranscriptionTestEnvironment? = null
    private val env get() = checkNotNull(environment)

    @Before
    fun setUp() {
        environment = TranscriptionTestEnvironment.create("transcription-worker")
    }

    @After
    fun tearDown() {
        environment?.close()
    }

    @Test
    fun aTranscriptionCommitsAValidTranscriptWithoutTouchingTheAudioOrTheNetwork() {
        val meeting = env.buildMeeting(repeat = 1)
        val audioBefore = env.sha256(meeting.audioUri)
        val uid = android.os.Process.myUid()
        val trafficBefore = traffic(uid)

        val started = runBlocking { env.app.transcriptionController.start(TranscriptionRequest(meeting.folderName, env.modelId, "en", Mode.START)) }
        assertEquals(StartResult.Started, started)
        waitUntil("the job finishes", timeoutMs = 240_000) { env.app.transcriptionController.state.value == TranscriptionState.Idle }

        val metadata = env.readMetadata(meeting)
        val state = "meeting.json: ${env.storage.readText(meeting.metadataUri)}"
        val block = metadata.transcription
        assertNotNull("no transcription block. $state", block?.transcript)
        assertNull("a job block is left. $state", block?.job)
        assertEquals("en", block?.transcript?.language)
        assertTrue((block?.transcript?.segmentCount ?: 0) > 0)

        // transcript.json decodes, validates and contains the known sentence.
        val names = env.storage.listChildren(meeting.folderUri).map { it.name }
        assertTrue(names.toString(), MeetingFolderLayout.TRANSCRIPT_JSON_FILE in names)
        val transcriptText = checkNotNull(env.storage.readText(env.storage.findChild(meeting.folderUri, MeetingFolderLayout.TRANSCRIPT_JSON_FILE)!!.uri))
        val decoded = TranscriptJson.decode(transcriptText) as TranscriptJson.DecodeResult.Success
        val transcript: Transcript = decoded.transcript
        assertEquals(emptyList<TranscriptValidator.Violation>(), TranscriptValidator.validate(transcript))
        val spoken = transcript.segments.joinToString(" ") { it.text }.lowercase()
        assertTrue(spoken, spoken.contains("ask not what your country can do for you"))

        // transcript.md and the CLAUDE.md section exist.
        val markdown = env.storage.readText(env.storage.findChild(meeting.folderUri, MeetingFolderLayout.TRANSCRIPT_MD_FILE)!!.uri).orEmpty()
        assertTrue("transcript.md is empty", markdown.isNotBlank())
        val guide = env.storage.readText(env.storage.findChild(meeting.folderUri, MeetingFolderLayout.GUIDE_FILE)!!.uri).orEmpty()
        assertTrue("CLAUDE.md has no transcript section", guide.contains("## Transcript"))

        // Nothing left over, the audio is bit-identical, and no byte went over the network (SC-001).
        assertTrue(names.toString(), names.none { it.endsWith(".part") || it == MeetingFolderLayout.TRANSCRIPT_WORK_FILE })
        assertEquals("audio.wav changed", audioBefore, env.sha256(meeting.audioUri))
        assertEquals("network traffic during the run", trafficBefore, traffic(uid))
    }

    @Test
    fun cancellingLeavesTheMeetingAsBefore() {
        val meeting = env.buildMeeting(repeat = 6)
        val audioBefore = env.sha256(meeting.audioUri)
        val before = env.readMetadata(meeting)

        val started = runBlocking { env.app.transcriptionController.start(TranscriptionRequest(meeting.folderName, env.modelId, "en", Mode.START)) }
        assertEquals(StartResult.Started, started)
        waitUntil("the job is decoding", timeoutMs = 120_000) {
            (env.app.transcriptionController.state.value as? TranscriptionState.Active)?.phase == ActivePhase.RUNNING
        }
        val cancelledAt = android.os.SystemClock.elapsedRealtime()
        env.app.transcriptionController.cancel()
        waitUntil("the job stops", timeoutMs = 30_000) { env.app.transcriptionController.state.value == TranscriptionState.Idle }
        val stoppedAfterMs = android.os.SystemClock.elapsedRealtime() - cancelledAt
        assertTrue("a user cancel took $stoppedAfterMs ms, the limit is 5000 ms (FR-004)", stoppedAfterMs <= 5_000)
        assertNull("the job is still registered", env.app.jobRegistry.current(app.meetingrecorder.work.JobKind.TRANSCRIPTION))

        val names = env.storage.listChildren(meeting.folderUri).map { it.name }
        assertTrue("the job completed before the cancel: $names", MeetingFolderLayout.TRANSCRIPT_JSON_FILE !in names)
        assertTrue(names.toString(), names.none { it.startsWith("transcript") || it.endsWith(".part") })
        assertEquals("meeting.json changed", before, env.readMetadata(meeting))
        assertEquals("audio.wav changed", audioBefore, env.sha256(meeting.audioUri))
    }

    /**
     * Android 15 stops a `mediaProcessing` foreground service after 6 hours (research R-20, item 4). The platform's
     * `device_config` knob shortens that to 20 s, so the real timeout path can be exercised: WorkManager's service
     * receives `onTimeout`, stops the worker with `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`, and the job must end as
     * `failed / interrupted / resumable` with its work file kept (never discarded as a user cancel).
     */
    @Test
    fun aForegroundServiceTimeoutInterruptsTheJobAndKeepsItsWorkFile() {
        assumeTrue("the timeout exists from Android 15", android.os.Build.VERSION.SDK_INT >= 35)
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = automation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).readBytes().decodeToString() }
        val key = "media_processing_fgs_timeout_duration"
        shell("device_config put activity_manager $key 20000")
        try {
            val meeting = env.buildMeeting(repeat = 30)
            val started = runBlocking { env.app.transcriptionController.start(TranscriptionRequest(meeting.folderName, env.modelId, "en", Mode.START)) }
            assertEquals(StartResult.Started, started)
            waitUntil("the job is decoding", timeoutMs = 120_000) {
                (env.app.transcriptionController.state.value as? TranscriptionState.Active)?.phase == ActivePhase.RUNNING
            }
            waitUntil("the timeout stops the job", timeoutMs = 120_000) { env.app.transcriptionController.state.value == TranscriptionState.Idle }

            val job = checkNotNull(env.readMetadata(meeting).transcription?.job) { "no job block: ${env.storage.readText(meeting.metadataUri)}" }
            assertEquals(app.meetingrecorder.core.model.JobState.FAILED, job.state)
            assertEquals(app.meetingrecorder.core.model.FailureReason.INTERRUPTED, job.failureReason)
            assertTrue("the job must be resumable", job.resumable)
            val names = env.storage.listChildren(meeting.folderUri).map { it.name }
            assertTrue("the work file is kept: $names", MeetingFolderLayout.TRANSCRIPT_WORK_FILE in names)
            assertTrue("no transcript was committed: $names", MeetingFolderLayout.TRANSCRIPT_JSON_FILE !in names)

            // WorkManager keeps a stopped job and reschedules it for about 10 s later. A new start by the user right away
            // must still run (it was once dropped, leaving the job registered without a worker).
            shell("device_config delete activity_manager $key")
            val resumed = runBlocking { env.app.transcriptionController.start(TranscriptionRequest(meeting.folderName, env.modelId, "en", Mode.RESTART)) }
            assertEquals(StartResult.Started, resumed)
            waitUntil("the resumed job is decoding", timeoutMs = 60_000) {
                (env.app.transcriptionController.state.value as? TranscriptionState.Active)?.phase == ActivePhase.RUNNING
            }
        } finally {
            shell("device_config delete activity_manager $key")
        }
    }

    /** Bytes sent plus received by this app's uid; the platform reports UNSUPPORTED (-1) on some builds. */
    private fun traffic(uid: Int): Long {
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        assumeTrue("per-uid traffic statistics are not supported", rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong())
        return rx + tx
    }
}
