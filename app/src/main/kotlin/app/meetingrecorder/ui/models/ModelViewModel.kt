package app.meetingrecorder.ui.models

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.meetingrecorder.MeetingRecorderApp
import app.meetingrecorder.R
import app.meetingrecorder.core.models.InstallFailure
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.models.ModelCatalogEntry
import app.meetingrecorder.core.models.ModelInstallState
import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.models.DeleteResult
import app.meetingrecorder.models.ModelError
import app.meetingrecorder.models.ModelEvent
import app.meetingrecorder.models.ModelStartResult
import app.meetingrecorder.models.ModelStrings
import app.meetingrecorder.recording.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A row of the model list: a catalog entry, an installed catalog model or an imported one. */
data class ModelRow(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val languages: ModelLanguages,
    val license: String?,
    val description: String?,
    val recommended: Boolean,
    val imported: Boolean,
    val state: ModelInstallState,
    val entry: ModelCatalogEntry?,
)

/** A message for the user: a string resource and its optional text argument. */
data class UiMessage(@StringRes val resource: Int, val argument: String? = null)

data class ModelUiState(
    val rows: List<ModelRow> = emptyList(),
    val importPercent: Int? = null,
    /** True while a recording runs: download, resume and import are disabled (FR-017). */
    val recording: Boolean = false,
    val message: UiMessage? = null,
    /** The model whose download the user is asked to confirm; nothing starts before they do (FR-003). */
    val confirmDownload: ModelRow? = null,
    val confirmDelete: ModelRow? = null,
)

class ModelViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeetingRecorderApp
    private val controller get() = app.modelController
    private val _state = MutableStateFlow(ModelUiState())
    val state: StateFlow<ModelUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(controller.states, controller.importPercent, app.recordingController.state) { states, importing, recording ->
                Triple(states, importing, recording.phase != Phase.IDLE)
            }.collect { (states, importing, recording) ->
                val rows = withContext(Dispatchers.IO) { rowsFor(states) }
                _state.value = _state.value.copy(rows = rows, importPercent = importing, recording = recording)
            }
        }
        viewModelScope.launch {
            controller.events.collect { event ->
                when (event) {
                    is ModelEvent.Failed -> show(messageFor(event.error))
                    is ModelEvent.Installed, is ModelEvent.Deleted -> Unit
                }
            }
        }
    }

    private fun rowsFor(states: Map<String, ModelInstallState>): List<ModelRow> {
        val installed = app.modelRepository.installed().associateBy { it.id }
        val offered = controller.offered()
        val catalogRows = offered.map { entry ->
            ModelRow(
                id = entry.id,
                name = resourceText(entry.displayNameKey) ?: entry.id,
                sizeBytes = entry.sizeBytes,
                languages = entry.languages,
                license = entry.license,
                description = resourceText(entry.descriptionKey),
                recommended = entry.recommended,
                imported = false,
                state = states[entry.id] ?: ModelInstallState.NotInstalled,
                entry = entry,
            )
        }
        val offeredIds = offered.map { it.id }.toSet()
        val importedRows = installed.values.filter { it.id !in offeredIds }.sortedBy { it.id }.map(::importedRow)
        return catalogRows + importedRows
    }

    private fun importedRow(model: InstalledModel) = ModelRow(
        id = model.id,
        name = getApplication<Application>().getString(R.string.model_imported_name, resourceText(model.displayName) ?: model.displayName),
        sizeBytes = model.sizeBytes,
        languages = model.languages,
        license = null,
        description = null,
        recommended = false,
        imported = true,
        state = ModelInstallState.Installed,
        entry = null,
    )

    private fun resourceText(key: String): String? = ModelStrings.res(key)?.let { getApplication<Application>().getString(it) }

    fun askDownload(row: ModelRow) {
        _state.value = _state.value.copy(confirmDownload = row)
    }

    fun dismissDialogs() {
        _state.value = _state.value.copy(confirmDownload = null, confirmDelete = null)
    }

    /** The user pressed Download in the confirmation. */
    fun confirmDownload() {
        val row = _state.value.confirmDownload ?: return
        dismissDialogs()
        viewModelScope.launch {
            val paused = row.state is ModelInstallState.Paused
            show(startMessage(if (paused) controller.resume(row.id) else controller.download(row.id)))
        }
    }

    fun resume(row: ModelRow) {
        viewModelScope.launch { show(startMessage(controller.resume(row.id))) }
    }

    fun cancel(row: ModelRow) = controller.cancel(row.id)

    fun cancelImport() = controller.cancelImport()

    fun dismissFailure(row: ModelRow) = controller.dismiss(row.id)

    fun import(uri: Uri) {
        viewModelScope.launch { show(startMessage(controller.import(uri))) }
    }

    fun askDelete(row: ModelRow) {
        _state.value = _state.value.copy(confirmDelete = row)
    }

    fun confirmDelete() {
        val row = _state.value.confirmDelete ?: return
        dismissDialogs()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { controller.delete(row.id) }
            if (result == DeleteResult.IN_USE) show(UiMessage(R.string.model_in_use))
        }
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }

    private fun show(message: UiMessage?) {
        if (message != null) _state.value = _state.value.copy(message = message)
    }

    private fun startMessage(result: ModelStartResult): UiMessage? = when (result) {
        ModelStartResult.Started -> null
        ModelStartResult.RecordingInProgress -> UiMessage(R.string.model_recording_in_progress)
        ModelStartResult.AnotherInstallRunning -> null // the running install is visible in the list
        ModelStartResult.UnknownModel -> UiMessage(R.string.model_error_network)
        is ModelStartResult.NotEnoughSpace -> UiMessage(R.string.model_error_space, formatSize(result.missingBytes))
    }

    private fun messageFor(error: ModelError): UiMessage = when (error) {
        ModelError.Checksum -> UiMessage(R.string.model_error_checksum)
        ModelError.Network, ModelError.Unknown -> UiMessage(R.string.model_error_network)
        ModelError.UnexpectedAddress -> UiMessage(R.string.model_error_address)
        ModelError.FileChanged -> UiMessage(R.string.model_error_changed)
        is ModelError.NotEnoughSpace -> UiMessage(R.string.model_error_space, formatSize(error.missingBytes))
        ModelError.NotAModel -> UiMessage(R.string.model_error_not_model)
        ModelError.ImportFailed -> UiMessage(R.string.model_error_import_failed)
        ModelError.AlreadyInstalled -> UiMessage(R.string.model_already_installed)
        ModelError.InUse -> UiMessage(R.string.model_in_use)
        ModelError.RecordingInProgress -> UiMessage(R.string.model_recording_in_progress)
    }

    private fun formatSize(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(getApplication(), bytes)

    companion object {
        fun failureText(failure: InstallFailure): Int = when (failure) {
            InstallFailure.CHECKSUM -> R.string.model_error_checksum
        }
    }
}
