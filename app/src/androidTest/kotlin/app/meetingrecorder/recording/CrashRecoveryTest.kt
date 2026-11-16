package app.meetingrecorder.recording

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.meetingrecorder.MainActivity
import app.meetingrecorder.TestTree
import app.meetingrecorder.core.json.MeetingValidator
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.wav.WavHeader
import app.meetingrecorder.grantRecordingPermissions
import app.meetingrecorder.readOnlyMeeting
import app.meetingrecorder.targetContext
import app.meetingrecorder.waitUntil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Kills the recording process in the middle of a recording while the test process keeps running, then
 * runs the recovery: the meeting must come back as `recovered` with a valid header and no more than
 * about two seconds of audio missing (FR-024, FR-025, SC-003).
 */
@RunWith(AndroidJUnit4::class)
class CrashRecoveryTest {

    @Test
    fun aKilledRecordingProcessIsRepairedWithAllItsAudio() {
        grantRecordingPermissions()
        TestTree(targetContext, "crash-recovery-test").use { tree ->
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = RecordingController(targetContext)
                assertTrue("start failed", runBlocking { controller.start("Crash test", tree.uri) })
                waitUntil("recording with audio") { controller.state.value.phase == Phase.RECORDING && controller.state.value.framesAppended > 0 }
                Thread.sleep(6_000)

                val framesBeforeKill = controller.state.value.framesAppended
                killRecordingProcess()
                waitUntil("the controller notices the lost service") { controller.state.value.phase == Phase.IDLE }

                // What the crash left behind: a meeting that still says "recording" and has no final duration.
                val crashed = readOnlyMeeting(tree)
                assertEquals(MeetingStatus.RECORDING, crashed.meeting.status)
                assertEquals(0L, crashed.meeting.audio.durationFrames)

                val outcomes = RecoveryRunner(targetContext, tree.uri).run(activeFolder = null)
                assertTrue("not recovered: $outcomes", outcomes.single().recovered)

                val recovered = readOnlyMeeting(tree)
                assertEquals(MeetingStatus.RECOVERED, recovered.meeting.status)
                assertEquals("meeting.json violations", emptyList<MeetingValidator.Violation>(), MeetingValidator.validate(recovered.meeting))
                assertTrue("CLAUDE.md is missing: ${recovered.children}", "CLAUDE.md" in recovered.children)

                // The header now describes the whole file, and the duration comes from the audio that is there.
                val frames = (recovered.wavBytes - WavHeader.SIZE) / recovered.wav.blockAlign
                assertEquals(frames, recovered.meeting.audio.durationFrames)
                assertEquals(recovered.wavBytes - WavHeader.SIZE, recovered.wav.declaredDataBytes)
                val sampleRate = recovered.wav.sampleRate
                assertTrue(
                    "recovered $frames frames but $framesBeforeKill were recorded before the kill",
                    abs(frames - framesBeforeKill) <= 2L * sampleRate,
                )
            }
        }
    }

    private fun killRecordingProcess() {
        val manager = targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pid = manager.runningAppProcesses.single { it.processName == "${targetContext.packageName}:recording" }.pid
        android.os.Process.killProcess(pid)
    }
}
