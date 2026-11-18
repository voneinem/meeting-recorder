package app.meetingrecorder.recording

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class RecordingProbeTest {
    private val state = MutableStateFlow(RecordingState())
    private var attachCalls = 0

    private fun probe(alive: Boolean, timeoutMs: Long = 150, onAttach: () -> Unit = {}) = RecordingProbe(
        isProcessAlive = { alive },
        attach = {
            attachCalls++
            onAttach()
        },
        state = state,
        timeoutMs = timeoutMs,
    )

    @Test
    fun aDeadRecordingProcessGivesFalseAtOnceWithoutAttaching() = runBlocking {
        val started = System.nanoTime()

        assertFalse(probe(alive = false, timeoutMs = 10_000).isRecording())

        assertEquals(0, attachCalls)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000, "must not wait")
    }

    @Test
    fun anAliveProcessWithARecordingPhaseGivesTrueAndTheFolder() = runBlocking {
        state.value = RecordingState(phase = Phase.RECORDING, meetingFolder = "2026-11-16_10-00-00")
        val probe = probe(alive = true)

        assertTrue(probe.isRecording())
        assertEquals("2026-11-16_10-00-00", probe.activeFolder())
        assertEquals(2, attachCalls) // every question attaches again, the service may have changed
    }

    @Test
    fun everyNonIdlePhaseCountsAsRecording() = runBlocking {
        for (phase in listOf(Phase.RECORDING, Phase.PAUSED, Phase.SUSPENDED, Phase.FINALIZING)) {
            state.value = RecordingState(phase = phase, meetingFolder = "m")
            assertTrue(probe(alive = true).isRecording(), "phase $phase")
        }
    }

    @Test
    fun anAliveProcessThatStaysIdleGivesFalseAfterTheTimeout() = runBlocking {
        val started = System.nanoTime()

        assertFalse(probe(alive = true, timeoutMs = 150).isRecording())

        assertEquals(1, attachCalls)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 100, "waited for the timeout")
    }

    @Test
    fun aRecreatedUiProcessSeesTheRecordingThatArrivesAfterAttach() = runBlocking {
        // The controller starts at IDLE and learns the real phase only after it has bound to the service.
        val probe = probe(alive = true, timeoutMs = 2_000) {
            thread {
                Thread.sleep(50)
                state.value = RecordingState(phase = Phase.RECORDING, meetingFolder = "2026-11-16_10-00-00")
            }
        }

        assertTrue(probe.isRecording())
    }

    @Test
    fun theFolderIsNullWhenNothingIsRecording() = runBlocking {
        assertNull(probe(alive = false).activeFolder())
        assertNull(probe(alive = true, timeoutMs = 50).activeFolder())
    }

    @Test
    fun theProbeIsUsableAsTheRecordingCheckPort() = runBlocking {
        val check: RecordingCheck = probe(alive = false)
        assertFalse(check.isRecording())
    }
}
