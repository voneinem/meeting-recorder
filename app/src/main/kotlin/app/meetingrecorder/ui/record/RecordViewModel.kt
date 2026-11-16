package app.meetingrecorder.ui.record

import android.app.Application
import android.text.format.Formatter
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.MeetingRecorderApp
import app.meetingrecorder.R
import app.meetingrecorder.camera.CameraController
import app.meetingrecorder.camera.PhotoResult
import app.meetingrecorder.camera.PhotoSaver
import app.meetingrecorder.camera.PhotoTaker
import app.meetingrecorder.core.channels.SourceOption
import app.meetingrecorder.core.channels.SourceSelector
import app.meetingrecorder.recording.RecordingController
import app.meetingrecorder.recording.SourceMonitor
import app.meetingrecorder.recording.localizedSourceName
import app.meetingrecorder.recording.RecordingEvent
import app.meetingrecorder.recording.RecordingSession
import app.meetingrecorder.recording.RecordingState
import app.meetingrecorder.storage.StorageGuard
import app.meetingrecorder.storage.StorageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A message for the user: a string or plurals resource with its arguments. */
sealed interface Notice {
    data class Text(@StringRes val message: Int, val args: List<Any> = emptyList()) : Notice
    data class Plural(@PluralsRes val message: Int, val quantity: Int) : Notice
}

/** What the source picker shows: the choices, the one a new recording would use, and the suggested one. */
data class SourceUiState(
    val options: List<SourceOption> = emptyList(),
    val selected: SourceOption? = null,
    val suggestedId: Int? = null,
)

class RecordViewModel(application: Application) : AndroidViewModel(application) {
    private val controller: RecordingController = (application as MeetingRecorderApp).recordingController
    private val guard = StorageGuard(application, AppPreferences(application))

    val state: StateFlow<RecordingState> = controller.state

    /** The camera bound to the record screen; photos are taken through it. */
    val camera = CameraController(application)
    private val photoTaker = PhotoTaker(PhotoSaver(application, controller), camera)

    private val _photoBusy = MutableStateFlow(false)

    /** True while a photo is being captured and saved; one photo at a time keeps positions in order. */
    val photoBusy: StateFlow<Boolean> = _photoBusy.asStateFlow()

    private val sourceMonitor = SourceMonitor(application)
    private val selectedSourceId = MutableStateFlow<Int?>(null)

    /** The microphones to choose from. The external one is preselected when it is plugged in (FR-006, FR-007). */
    val sources: StateFlow<SourceUiState> = combine(sourceMonitor.options, selectedSourceId) { options, selectedId ->
        SourceUiState(
            options = options,
            selected = SourceSelector.resolve(selectedId, options),
            suggestedId = SourceSelector.suggested(options)?.id,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SourceUiState())

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    private val _storageProblem = MutableStateFlow(false)

    /** True after a start attempt found the storage folder unusable; the screen then opens the setup. */
    val storageProblem: StateFlow<Boolean> = _storageProblem.asStateFlow()

    init {
        sourceMonitor.start()
        controller.attachIfRunning()
        viewModelScope.launch {
            controller.events.collect { event ->
                _notice.value = event.toNotice(
                    formatBytes = { Formatter.formatShortFileSize(getApplication(), it) },
                    sourceName = { localizedSourceName(getApplication(), it) },
                )
            }
        }
    }

    fun selectSource(option: SourceOption) {
        selectedSourceId.value = option.id
    }

    fun onTitleChange(value: String) {
        _title.value = value
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun storageProblemHandled() {
        _storageProblem.value = false
    }

    /** Checks the storage folder, then starts a meeting with the entered title (blank means the default). */
    fun start() {
        if (_starting.value) return
        _starting.value = true
        viewModelScope.launch {
            try {
                when (val status = guard.check()) {
                    is StorageStatus.Ok -> {
                        _notice.value = null
                        val started = controller.start(_title.value.trim().ifEmpty { null }, status.treeUri, sources.value.selected?.id ?: RecordingController.BUILT_IN_DEVICE_ID)
                        if (started) _title.value = ""
                    }
                    else -> _storageProblem.value = true
                }
            } finally {
                _starting.value = false
            }
        }
    }

    /** Takes a photo at the current point of the recording; a failure never interrupts the recording. */
    fun takePhoto() {
        if (_photoBusy.value) return
        _photoBusy.value = true
        viewModelScope.launch {
            try {
                if (photoTaker.take() is PhotoResult.Failed) _notice.value = Notice.Text(R.string.photo_failed)
            } finally {
                _photoBusy.value = false
            }
        }
    }

    fun pause() {
        viewModelScope.launch { controller.pause() }
    }

    fun resume() {
        viewModelScope.launch { controller.resume() }
    }

    fun stop() {
        viewModelScope.launch { controller.stop() }
    }

    override fun onCleared() {
        sourceMonitor.stop()
        camera.unbind()
    }
}
