package app.meetingrecorder.recording

/** Commands and system signals that move the recording session between phases. */
enum class Command { START, PAUSE, RESUME, STOP, SUSPEND, FINALIZED, PHOTO }

/**
 * The legal transitions of contracts/recording-service.md. Pure Kotlin without Android imports; the
 * session asks it before acting, so a command in a disallowed phase never corrupts state.
 */
class RecordingStateMachine(initial: Phase = Phase.IDLE) {

    var phase: Phase = initial
        private set

    sealed interface Result {
        data class Moved(val from: Phase, val to: Phase) : Result

        /** The command is allowed in the current phase and does not change it (for example a photo). */
        data object Allowed : Result

        data class Rejected(val command: Command, val phase: Phase) : Result
    }

    /** Applies [command]; the phase only changes when the result is [Result.Moved]. */
    fun apply(command: Command): Result {
        val target = when (command) {
            Command.START -> if (phase == Phase.IDLE) Phase.RECORDING else null
            Command.PAUSE -> if (phase == Phase.RECORDING) Phase.PAUSED else null
            Command.SUSPEND -> if (phase == Phase.RECORDING) Phase.SUSPENDED else null
            Command.RESUME -> if (phase == Phase.PAUSED || phase == Phase.SUSPENDED) Phase.RECORDING else null
            Command.STOP -> if (phase in ACTIVE) Phase.FINALIZING else null
            Command.FINALIZED -> if (phase == Phase.FINALIZING) Phase.IDLE else null
            Command.PHOTO -> return if (phase in ACTIVE) Result.Allowed else Result.Rejected(command, phase)
        } ?: return Result.Rejected(command, phase)
        val from = phase
        phase = target
        return Result.Moved(from, target)
    }

    private companion object {
        val ACTIVE = setOf(Phase.RECORDING, Phase.PAUSED, Phase.SUSPENDED)
    }
}
