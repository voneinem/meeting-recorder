package app.meetingrecorder.recording

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.meetingrecorder.MainActivity
import app.meetingrecorder.TestTree
import app.meetingrecorder.core.json.MeetingValidator
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.grantRecordingPermissions
import app.meetingrecorder.readOnlyMeeting
import app.meetingrecorder.targetContext
import app.meetingrecorder.waitUntil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records through the real foreground service (own process): about 5 s, pause 2 s, resume, stop. The
 * saved meeting must be valid and the paused time must not be in the audio (FR-005, FR-007, SC-010).
 */
@RunWith(AndroidJUnit4::class)
class RecordingServiceTest {

    @Test
    fun recordsPausesResumesAndSavesAValidMeeting() {
        grantRecordingPermissions()
        TestTree(targetContext, "recording-service-test").use { tree ->
            // A visible activity keeps the app in the foreground, as it is when a user starts a recording.
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = RecordingController(targetContext)
                val wallStart = SystemClock.elapsedRealtime()

                assertTrue("start failed", runBlocking { controller.start("Instrumented test", tree.uri) })
                waitUntil("recording with audio") { controller.state.value.phase == Phase.RECORDING && controller.state.value.framesAppended > 0 }
                Thread.sleep(5_000)

                runBlocking { controller.pause() }
                waitUntil("paused") { controller.state.value.phase == Phase.PAUSED }
                val framesAtPause = controller.state.value.framesAppended
                Thread.sleep(2_000)
                assertEquals("no audio may be recorded while paused", framesAtPause, controller.state.value.framesAppended)

                runBlocking { controller.resume() }
                waitUntil("recording again") { controller.state.value.phase == Phase.RECORDING }
                Thread.sleep(1_500)

                runBlocking { controller.stop() }
                waitUntil("idle after stop", timeoutMs = 20_000) { controller.state.value.phase == Phase.IDLE }
                val wallMs = SystemClock.elapsedRealtime() - wallStart

                val onDisk = readOnlyMeeting(tree)
                assertTrue("missing files in ${onDisk.children}", onDisk.children.containsAll(listOf("audio.wav", "meeting.json", "CLAUDE.md", "photos")))
                assertEquals("meeting.json violations", emptyList<MeetingValidator.Violation>(), MeetingValidator.validate(onDisk.meeting))
                assertEquals(MeetingStatus.COMPLETE, onDisk.meeting.status)
                assertEquals("Instrumented test", onDisk.meeting.title)

                // The WAV is parseable and its length matches the frames in meeting.json.
                val frames = (onDisk.wavBytes - 44) / onDisk.wav.blockAlign
                assertEquals(onDisk.meeting.audio.durationFrames, frames)
                assertEquals(onDisk.meeting.audio.sampleRate, onDisk.wav.sampleRate)

                // About 6.5 s were recorded in a run that took at least 2 s longer: the pause is not in the audio.
                val recordedMs = onDisk.meeting.audio.durationMs
                assertTrue("recorded $recordedMs ms is out of range", recordedMs in 5_500..8_500)
                assertTrue("pause not excluded: recorded $recordedMs ms of $wallMs ms", recordedMs <= wallMs - 1_500)
            }
        }
    }
}
