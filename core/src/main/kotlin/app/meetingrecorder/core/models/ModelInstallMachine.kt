package app.meetingrecorder.core.models

/** State of one model id (data-model.md, "ModelInstallJob"). */
public sealed interface ModelInstallState {
    public data object NotInstalled : ModelInstallState

    /** A download or an import is running. Only a download can pause; an import is cancelled instead. */
    public data class Transferring(val kind: TransferKind, val bytesDone: Long, val total: Long) : ModelInstallState

    /** An interrupted download whose partial files are kept so that it can resume. */
    public data class Paused(val bytesDone: Long, val total: Long) : ModelInstallState

    public data object Verifying : ModelInstallState

    public data object Installed : ModelInstallState

    public data class Failed(val reason: InstallFailure) : ModelInstallState
}

public enum class TransferKind { DOWNLOAD, IMPORT }

public enum class InstallFailure { CHECKSUM }

public sealed interface InstallEvent {
    public data class Start(val kind: TransferKind, val total: Long) : InstallEvent

    public data class Progress(val bytesDone: Long) : InstallEvent

    /** All bytes are there; the hash is checked next. */
    public data object SizeReached : InstallEvent

    public data object HashOk : InstallEvent

    public data object HashMismatch : InstallEvent

    /** The user cancelled. */
    public data object Cancel : InstallEvent

    /** The network was lost, the process was killed or a recording started. */
    public data object Interrupted : InstallEvent

    public data object Resume : InstallEvent

    public data object DeletePartial : InstallEvent

    /** The user acknowledged a failure. */
    public data object Dismiss : InstallEvent

    public data class Delete(val inUse: Boolean) : InstallEvent
}

public enum class InstallEffect {
    /** Delete `<id>.part` and `<id>.part.json`. */
    DELETE_PARTIAL,

    /** Leave `<id>.part` and `<id>.part.json` for a later resume. */
    KEEP_PARTIAL,

    /** Move `.part` to `.bin` and write the sidecar. */
    WRITE_SIDECAR,

    /** Delete `<id>.bin`, `<id>.json` and any partial files. */
    DELETE_INSTALLED,
}

/** [rejected] is true when the event is not allowed in the state: nothing changes. */
public data class InstallTransition(
    val state: ModelInstallState,
    val effects: List<InstallEffect> = emptyList(),
    val rejected: Boolean = false,
)

/** The transitions of data-model.md as a pure reducer; the caller carries out the effects. */
public object ModelInstallMachine {

    /** One install job at a time: none of the [others] may be transferring or verifying. */
    public fun mayStart(others: Collection<ModelInstallState>): Boolean =
        others.none { it is ModelInstallState.Transferring || it == ModelInstallState.Verifying }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    public fun reduce(state: ModelInstallState, event: InstallEvent): InstallTransition = when (state) {
        ModelInstallState.NotInstalled, is ModelInstallState.Failed -> when (event) {
            is InstallEvent.Start -> InstallTransition(ModelInstallState.Transferring(event.kind, 0, event.total))
            InstallEvent.Dismiss -> if (state is ModelInstallState.Failed) InstallTransition(ModelInstallState.NotInstalled) else rejected(state)
            else -> rejected(state)
        }
        is ModelInstallState.Transferring -> when (event) {
            is InstallEvent.Progress ->
                if (event.bytesDone in state.bytesDone..state.total) InstallTransition(state.copy(bytesDone = event.bytesDone)) else rejected(state)
            InstallEvent.SizeReached ->
                if (state.bytesDone == state.total) InstallTransition(ModelInstallState.Verifying) else rejected(state)
            InstallEvent.Cancel -> InstallTransition(ModelInstallState.NotInstalled, listOf(InstallEffect.DELETE_PARTIAL))
            InstallEvent.Interrupted -> when (state.kind) {
                TransferKind.DOWNLOAD ->
                    InstallTransition(ModelInstallState.Paused(state.bytesDone, state.total), listOf(InstallEffect.KEEP_PARTIAL))
                TransferKind.IMPORT -> InstallTransition(ModelInstallState.NotInstalled, listOf(InstallEffect.DELETE_PARTIAL))
            }
            else -> rejected(state)
        }
        is ModelInstallState.Paused -> when (event) {
            InstallEvent.Resume -> InstallTransition(ModelInstallState.Transferring(TransferKind.DOWNLOAD, state.bytesDone, state.total))
            InstallEvent.DeletePartial, InstallEvent.Cancel ->
                InstallTransition(ModelInstallState.NotInstalled, listOf(InstallEffect.DELETE_PARTIAL))
            else -> rejected(state)
        }
        ModelInstallState.Verifying -> when (event) {
            InstallEvent.HashOk -> InstallTransition(ModelInstallState.Installed, listOf(InstallEffect.WRITE_SIDECAR))
            InstallEvent.HashMismatch ->
                InstallTransition(ModelInstallState.Failed(InstallFailure.CHECKSUM), listOf(InstallEffect.DELETE_PARTIAL))
            else -> rejected(state)
        }
        ModelInstallState.Installed -> when (event) {
            is InstallEvent.Delete ->
                if (event.inUse) rejected(state) else InstallTransition(ModelInstallState.NotInstalled, listOf(InstallEffect.DELETE_INSTALLED))
            else -> rejected(state)
        }
    }

    private fun rejected(state: ModelInstallState) = InstallTransition(state, rejected = true)
}
