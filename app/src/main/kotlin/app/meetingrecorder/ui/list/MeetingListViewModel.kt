package app.meetingrecorder.ui.list

import androidx.core.net.toUri
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.MeetingRecorderApp
import app.meetingrecorder.R
import app.meetingrecorder.core.model.MeetingMetadata
import app.meetingrecorder.storage.MeetingEntry
import app.meetingrecorder.storage.MeetingRepository
import app.meetingrecorder.storage.SafMeetingStorage
import app.meetingrecorder.transcription.DeleteGuard
import app.meetingrecorder.transcription.Mode
import app.meetingrecorder.transcription.StartResult
import app.meetingrecorder.transcription.TranscriptionRequest
import app.meetingrecorder.transcription.TranscriptionState
import app.meetingrecorder.ui.transcription.startResultMessage
import app.meetingrecorder.transcription.DeleteResult
import app.meetingrecorder.transcription.RunOutcome
import app.meetingrecorder.transcription.TranscriptionEvent
import app.meetingrecorder.transcription.TranscriptionNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MeetingListUiState(
    val meetings: List<MeetingEntry> = emptyList(),
    val loading: Boolean = true,
    /** Folder name of the meeting that is being recorded right now; it can be neither renamed nor deleted. */
    val activeFolder: String? = null,
    /** A message for the user, for example after a failed rename. */
    val message: Int? = null,
    /** The meeting being transcribed right now and its progress in percent, for the live status chip. */
    val transcribing: String? = null,
    val transcribingPercent: Int? = null,
)

class MeetingListViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val _state = MutableStateFlow(MeetingListUiState())
    val state: StateFlow<MeetingListUiState> = _state.asStateFlow()

    init {
        // Keep the active recording's folder out of reach of rename and delete.
        viewModelScope.launch {
            (application as MeetingRecorderApp).recordingController.state.collect { recording ->
                _state.value = _state.value.copy(activeFolder = recording.meetingFolder)
            }
        }
        viewModelScope.launch {
            (application as MeetingRecorderApp).transcriptionController.state.collect { job ->
                val active = job as? TranscriptionState.Active
                _state.value = _state.value.copy(
                    transcribing = active?.folderName,
                    transcribingPercent = active?.let { (it.progress * PERCENT).toInt().coerceIn(0, PERCENT) },
                )
            }
        }
        // A finished, failed or cancelled transcription changes the list and may need a message.
        viewModelScope.launch {
            (application as MeetingRecorderApp).transcriptionController.events.collect { event ->
                when (event) {
                    is TranscriptionEvent.Cancelled -> _state.value = _state.value.copy(message = R.string.transcription_cancelled)
                    is TranscriptionEvent.Failed -> failureReason(event.outcome)?.let {
                        _state.value = _state.value.copy(message = TranscriptionNotification.reasonText(it))
                    }
                    is TranscriptionEvent.Finished -> Unit
                }
                refresh()
            }
        }
        // A recovery run at app start can change statuses and durations: reload when it finishes.
        viewModelScope.launch {
            (application as MeetingRecorderApp).recoveryRuns.collect { refresh() }
        }
    }

    /** Reloads the list from the storage folder (also picks up folders added from outside the app). */
    fun refresh() {
        val saved = preferences.storageTreeUri ?: return
        viewModelScope.launch {
            val meetings = withContext(Dispatchers.IO) {
                runCatching { repository(saved.toUri()).list() }
                    .getOrDefault(emptyList())
            }
            _state.value = _state.value.copy(meetings = meetings, loading = false)
        }
    }

    /** Renames a meeting: only the title in `meeting.json` changes (FR-027). */
    fun rename(entry: MeetingEntry, newTitle: String) {
        if (!isValidTitle(newTitle) || entry.folderName == _state.value.activeFolder) return
        change(entry, R.string.list_rename_failed) { it.rename(entry.folderName, newTitle) }
    }

    /**
     * Deletes the whole meeting folder; the caller has asked for confirmation (FR-030). A running transcription
     * of the meeting is cancelled first and the delete waits for it to stop (FR-020).
     */
    fun delete(entry: MeetingEntry) {
        if (entry.folderName == _state.value.activeFolder) return
        val saved = preferences.storageTreeUri ?: return
        val app = getApplication<Application>() as MeetingRecorderApp
        viewModelScope.launch {
            val guard = DeleteGuard(app.transcriptionController) { folder ->
                runCatching { repository(saved.toUri()).delete(folder) }.getOrDefault(false)
            }
            val result = withContext(Dispatchers.IO) { guard.delete(entry.folderName) }
            when (result) {
                DeleteResult.Deleted -> Unit
                DeleteResult.StopTimedOut -> _state.value = _state.value.copy(message = R.string.meeting_delete_still_running)
                DeleteResult.Failed -> _state.value = _state.value.copy(message = R.string.list_delete_failed)
            }
            refresh()
        }
    }

    /** True when the delete confirmation must warn that a running transcription will be cancelled. */
    fun deleteNeedsWarning(entry: MeetingEntry): Boolean =
        (getApplication<Application>() as MeetingRecorderApp).transcriptionController.isRunningFor(entry.folderName)

    /** Shows [message] in the list (for example the answer to a refused transcription start). */
    fun showMessage(@androidx.annotation.StringRes message: Int) {
        _state.value = _state.value.copy(message = message)
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }

    private fun change(entry: MeetingEntry, failure: Int, action: (MeetingRepository) -> Boolean) {
        val saved = preferences.storageTreeUri ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { action(repository(saved.toUri())) }.getOrDefault(false)
            }
            if (!ok) _state.value = _state.value.copy(message = failure)
            refresh()
        }
    }

    private fun repository(tree: Uri): MeetingRepository {
        val app = getApplication<Application>() as MeetingRecorderApp
        return MeetingRepository(SafMeetingStorage(app, tree)) { folder -> app.transcriptionController.isRunningFor(folder) }
    }

    /** "Resume", "Start over" or "Retry" on a failed row: the same model and language as the failed job. */
    fun restart(entry: MeetingEntry, mode: Mode) {
        val modelId = entry.jobModelId ?: return
        val app = getApplication<Application>() as MeetingRecorderApp
        viewModelScope.launch {
            val result = app.transcriptionController.start(
                TranscriptionRequest(entry.folderName, modelId, entry.jobLanguage ?: AppPreferences.LANGUAGE_AUTO, mode),
            )
            startResultMessage(result)?.takeIf { result != StartResult.Started }?.let { _state.value = _state.value.copy(message = it) }
            refresh()
        }
    }

    private fun failureReason(outcome: RunOutcome): app.meetingrecorder.core.model.FailureReason? = when (outcome) {
        is RunOutcome.Failed -> outcome.reason
        is RunOutcome.Stopped -> outcome.reason
        else -> null
    }

    companion object {
        private const val PERCENT = 100

        /** A title is valid when it has 1 to 200 characters after trimming. */
        fun isValidTitle(title: String): Boolean = title.trim().length in 1..MeetingMetadata.MAX_TITLE_LENGTH
    }
}
