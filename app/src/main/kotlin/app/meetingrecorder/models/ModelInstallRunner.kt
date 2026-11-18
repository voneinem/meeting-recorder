package app.meetingrecorder.models

import app.meetingrecorder.core.models.InstallEvent
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelInstallState
import app.meetingrecorder.core.models.ModelKind
import app.meetingrecorder.core.models.TransferKind
import app.meetingrecorder.models.download.DownloadControl
import app.meetingrecorder.models.download.DownloadFailure
import app.meetingrecorder.models.download.DownloadListener
import app.meetingrecorder.models.download.DownloadResult
import app.meetingrecorder.models.download.Stop

/** One-shot notices about an install that did not succeed; the UI turns them into localized messages. */
sealed interface ModelError {
    data object Checksum : ModelError
    data object Network : ModelError
    data object UnexpectedAddress : ModelError
    data object FileChanged : ModelError
    data class NotEnoughSpace(val missingBytes: Long) : ModelError
    data object NotAModel : ModelError
    data object ImportFailed : ModelError
    data object AlreadyInstalled : ModelError
    data object InUse : ModelError
    data object RecordingInProgress : ModelError
    data object Unknown : ModelError
}

sealed interface InstallOutcome {
    data class Installed(val id: String) : InstallOutcome
    data object Cancelled : InstallOutcome

    /** The download stopped and keeps its partial files. [connectionLost] is false when it was paused on purpose. */
    data class Paused(val connectionLost: Boolean) : InstallOutcome

    data class Failed(val error: ModelError) : InstallOutcome
}

/**
 * Carries out one install and keeps the [ModelStateStore] in step: a download (the voice-activity companion
 * first, when the catalog entry names one and it is missing) or an import. It holds no Android types, so the
 * `ModelInstallWorker` stays a thin shell around it and the JVM tests cover every outcome.
 */
class ModelInstallRunner(
    private val store: ModelStateStore,
    private val repository: ModelRepository,
    private val catalog: () -> List<ModelCatalogEntry>,
    private val download: (ModelCatalogEntry, displayName: String, DownloadListener) -> DownloadResult,
    private val importModel: (ImportSource, ImportListener) -> ImportResult,
    private val displayName: (ModelCatalogEntry) -> String,
) {
    /** Downloads [id]. [control] stops it from outside; [onProgress] gets the combined bytes of all files. */
    fun runDownload(id: String, control: DownloadControl, onProgress: (bytesDone: Long, total: Long) -> Unit): InstallOutcome {
        val entry = catalog().firstOrNull { it.id == id && it.kind == ModelKind.SPEECH } ?: return InstallOutcome.Failed(ModelError.Unknown)
        val resuming = store.stateOf(id) is ModelInstallState.Paused
        val companion = entry.vadCompanionId
            ?.let { companionId -> catalog().firstOrNull { it.id == companionId } }
            ?.takeIf { repository.find(it.id) == null }
        val companionBytes = if (resuming) 0L else companion?.sizeBytes ?: 0L
        val total = (store.stateOf(id) as? ModelInstallState.Paused)?.total ?: (entry.sizeBytes + companionBytes)

        store.apply(id, if (resuming) InstallEvent.Resume else InstallEvent.Start(TransferKind.DOWNLOAD, total))
        fun report(done: Long) {
            store.apply(id, InstallEvent.Progress(done.coerceAtMost(total)))
            onProgress(done.coerceAtMost(total), total)
        }
        fun listener(offset: Long, size: Long) = object : DownloadListener {
            override val control = control
            override fun onProgress(bytesDone: Long, total: Long) = report(offset + bytesDone.coerceAtMost(size))
        }

        var offset = 0L
        if (companion != null) {
            when (val result = download(companion, displayName(companion), listener(0, companion.sizeBytes))) {
                is DownloadResult.Installed -> offset = if (resuming) 0 else companion.sizeBytes
                else -> return stopped(id, result, control)
            }
        }
        return when (val result = download(entry, displayName(entry), listener(offset, entry.sizeBytes))) {
            is DownloadResult.Installed -> {
                report(total)
                store.apply(id, InstallEvent.SizeReached)
                store.apply(id, InstallEvent.HashOk)
                InstallOutcome.Installed(id)
            }
            else -> stopped(id, result, control)
        }
    }

    /** Imports [source]. A stop request, whatever its reason, cancels: an import cannot pause. */
    fun runImport(source: ImportSource, control: DownloadControl, onProgress: (bytesDone: Long, total: Long) -> Unit): InstallOutcome {
        val listener = object : ImportListener {
            override fun onProgress(bytesDone: Long, total: Long) = onProgress(bytesDone, total)
            override fun isCancelled(): Boolean = control.requested != Stop.NONE
        }
        val outcome = when (val result = importModel(source, listener)) {
            is ImportResult.Installed -> InstallOutcome.Installed(result.model.id)
            is ImportResult.AlreadyInstalled -> InstallOutcome.Failed(ModelError.AlreadyInstalled)
            ImportResult.Rejected -> InstallOutcome.Failed(ModelError.NotAModel)
            is ImportResult.NotEnoughSpace -> InstallOutcome.Failed(ModelError.NotEnoughSpace(result.missingBytes))
            ImportResult.Cancelled -> InstallOutcome.Cancelled
            is ImportResult.Failed -> InstallOutcome.Failed(ModelError.ImportFailed)
        }
        store.reload()
        return outcome
    }

    /** Maps a download that did not install to the state events and the outcome. */
    private fun stopped(id: String, result: DownloadResult, control: DownloadControl): InstallOutcome = when (result) {
        DownloadResult.Cancelled -> {
            store.apply(id, InstallEvent.Cancel)
            InstallOutcome.Cancelled
        }
        is DownloadResult.Interrupted -> {
            store.apply(id, InstallEvent.Interrupted)
            InstallOutcome.Paused(connectionLost = control.requested != Stop.PAUSE)
        }
        is DownloadResult.Failed -> when (result.reason) {
            DownloadFailure.CHECKSUM_MISMATCH -> {
                val state = store.stateOf(id) as? ModelInstallState.Transferring
                if (state != null) store.apply(id, InstallEvent.Progress(state.total))
                store.apply(id, InstallEvent.SizeReached)
                store.apply(id, InstallEvent.HashMismatch)
                InstallOutcome.Failed(ModelError.Checksum)
            }
            else -> {
                store.apply(id, InstallEvent.Cancel)
                InstallOutcome.Failed(
                    when (result.reason) {
                        DownloadFailure.UNEXPECTED_ADDRESS, DownloadFailure.TOO_MANY_REDIRECTS -> ModelError.UnexpectedAddress
                        DownloadFailure.FILE_CHANGED_ON_SERVER -> ModelError.FileChanged
                        else -> ModelError.Network
                    },
                )
            }
        }
        is DownloadResult.Installed -> error("an installed result is not a stop")
    }
}
