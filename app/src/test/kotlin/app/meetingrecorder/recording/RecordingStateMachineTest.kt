package app.meetingrecorder.recording

import app.meetingrecorder.recording.RecordingStateMachine.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RecordingStateMachineTest {

    private fun machine(phase: Phase) = RecordingStateMachine(phase)

    private fun assertMoves(from: Phase, command: Command, to: Phase) {
        val machine = machine(from)
        assertEquals(Result.Moved(from, to), machine.apply(command), "$command in $from")
        assertEquals(to, machine.phase)
    }

    private fun assertRejected(from: Phase, command: Command) {
        val machine = machine(from)
        assertEquals(Result.Rejected(command, from), machine.apply(command), "$command in $from")
        assertEquals(from, machine.phase, "phase must not change when $command is rejected in $from")
    }

    @Test
    fun `a recording goes through start pause resume stop and finalize`() {
        val machine = RecordingStateMachine()
        assertIs<Result.Moved>(machine.apply(Command.START))
        assertEquals(Phase.RECORDING, machine.phase)
        assertIs<Result.Moved>(machine.apply(Command.PAUSE))
        assertEquals(Phase.PAUSED, machine.phase)
        assertIs<Result.Moved>(machine.apply(Command.RESUME))
        assertEquals(Phase.RECORDING, machine.phase)
        assertIs<Result.Moved>(machine.apply(Command.STOP))
        assertEquals(Phase.FINALIZING, machine.phase)
        assertIs<Result.Moved>(machine.apply(Command.FINALIZED))
        assertEquals(Phase.IDLE, machine.phase)
    }

    @Test
    fun `allowed transitions`() {
        assertMoves(Phase.IDLE, Command.START, Phase.RECORDING)
        assertMoves(Phase.RECORDING, Command.PAUSE, Phase.PAUSED)
        assertMoves(Phase.RECORDING, Command.SUSPEND, Phase.SUSPENDED)
        assertMoves(Phase.PAUSED, Command.RESUME, Phase.RECORDING)
        assertMoves(Phase.SUSPENDED, Command.RESUME, Phase.RECORDING)
        assertMoves(Phase.RECORDING, Command.STOP, Phase.FINALIZING)
        assertMoves(Phase.PAUSED, Command.STOP, Phase.FINALIZING)
        assertMoves(Phase.SUSPENDED, Command.STOP, Phase.FINALIZING)
        assertMoves(Phase.FINALIZING, Command.FINALIZED, Phase.IDLE)
    }

    @Test
    fun `disallowed commands are rejected and change nothing`() {
        assertRejected(Phase.IDLE, Command.PAUSE)
        assertRejected(Phase.IDLE, Command.RESUME)
        assertRejected(Phase.IDLE, Command.STOP)
        assertRejected(Phase.IDLE, Command.SUSPEND)
        assertRejected(Phase.IDLE, Command.FINALIZED)
        assertRejected(Phase.RECORDING, Command.START)
        assertRejected(Phase.RECORDING, Command.RESUME)
        assertRejected(Phase.RECORDING, Command.FINALIZED)
        assertRejected(Phase.PAUSED, Command.START)
        assertRejected(Phase.PAUSED, Command.PAUSE)
        assertRejected(Phase.PAUSED, Command.SUSPEND)
        assertRejected(Phase.SUSPENDED, Command.PAUSE)
        assertRejected(Phase.SUSPENDED, Command.SUSPEND)
        assertRejected(Phase.FINALIZING, Command.START)
        assertRejected(Phase.FINALIZING, Command.PAUSE)
        assertRejected(Phase.FINALIZING, Command.RESUME)
        assertRejected(Phase.FINALIZING, Command.STOP)
    }

    @Test
    fun `photos are allowed while recording paused or suspended only`() {
        for (phase in listOf(Phase.RECORDING, Phase.PAUSED, Phase.SUSPENDED)) {
            val machine = machine(phase)
            assertEquals(Result.Allowed, machine.apply(Command.PHOTO))
            assertEquals(phase, machine.phase)
        }
        assertRejected(Phase.IDLE, Command.PHOTO)
        assertRejected(Phase.FINALIZING, Command.PHOTO)
    }

    @Test
    fun `elapsed time is derived from the frame counter`() {
        assertEquals(0, RecordingState().elapsedMs)
        assertEquals(1_000, RecordingState(framesAppended = 48_000).elapsedMs)
        assertEquals(1_500, RecordingState(framesAppended = 72_000).elapsedMs)
        assertEquals(1_000, RecordingState(framesAppended = 44_100, sampleRate = 44_100).elapsedMs)
    }
}
