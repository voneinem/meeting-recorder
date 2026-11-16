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
import app.meetingrecorder.recording.RecordingController
import app.meetingrecorder.recording.RecordingEvent
import app.meetingrecorder.recording.RecordingSession
import app.meetingrecorder.recording.RecordingState
import app.meetingrecorder.storage.StorageGuard
import app.meetingrecorder.storage.StorageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A message for the user: a string or plurals resource with its arguments. */
sealed interface Notice {
    data class Text(@StringRes val message: Int, val args: List<Any> = emptyList()) : Notice
    data class Plural(@PluralsRes val message: Int, val quantity: Int) : Notice
}

class RecordViewModel(application: Application) : AndroidViewModel(application) {
    private val controller: RecordingController = (application as MeetingRecorderApp).recordingController
    private val guard = StorageGuard(application, AppPreferences(application))

    val state: StateFlow<RecordingState> = controller.state

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
        controller.attachIfRunning()
        viewModelScope.launch { controller.events.collect { _notice.value = it.toNotice() } }
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
                        val started = controller.start(_title.value.trim().ifEmpty { null }, status.treeUri)
                        if (started) _title.value = ""
                    }
                    else -> _storageProblem.value = true
                }
            } finally {
                _starting.value = false
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

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
    }

    private fun RecordingEvent.toNotice(): Notice? = when (this) {
        is RecordingEvent.Error -> when (code) {
            "no_microphone_permission" -> Notice.Text(R.string.record_error_no_microphone)
            "start_failed" -> Notice.Text(R.string.record_error_start_failed, listOf(detail.orEmpty()))
            "nothing_recorded" -> Notice.Text(R.string.record_error_nothing_recorded)
            "storage_too_small" -> Notice.Text(R.string.record_error_storage_too_small)
            else -> Notice.Text(R.string.record_error_generic, listOf(code))
        }
        is RecordingEvent.AudioDropped -> Notice.Text(R.string.record_notice_dropped)
        is RecordingEvent.Suspended -> when (reason) {
            RecordingSession.REASON_MICROPHONE_TAKEN -> Notice.Text(R.string.record_notice_suspended_taken)
            RecordingSession.REASON_READ_ERROR -> Notice.Text(R.string.record_notice_suspended_read_error)
            else -> Notice.Text(R.string.record_notice_suspended, listOf(reason))
        }
        is RecordingEvent.StorageLow ->
            Notice.Text(R.string.storage_low, listOf(Formatter.formatShortFileSize(getApplication(), bytesLeft)))
        RecordingEvent.StorageFull -> Notice.Text(R.string.storage_full)
        is RecordingEvent.LimitApproaching -> {
            val minutes = ((secondsLeft + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt()
            Notice.Plural(R.plurals.limit_approaching, minutes)
        }
        RecordingEvent.LimitReached -> Notice.Text(R.string.limit_reached)
        else -> null
    }
}
