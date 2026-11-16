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
                runCatching { MeetingRepository(SafMeetingStorage(getApplication(), saved.toUri())).list() }
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

    /** Deletes the whole meeting folder; the caller has asked for confirmation (FR-030). */
    fun delete(entry: MeetingEntry) {
        if (entry.folderName == _state.value.activeFolder) return
        change(entry, R.string.list_delete_failed) { it.delete(entry.folderName) }
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }

    private fun change(entry: MeetingEntry, failure: Int, action: (MeetingRepository) -> Boolean) {
        val saved = preferences.storageTreeUri ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { action(MeetingRepository(SafMeetingStorage(getApplication(), saved.toUri()))) }.getOrDefault(false)
            }
            if (!ok) _state.value = _state.value.copy(message = failure)
            refresh()
        }
    }

    companion object {
        /** A title is valid when it has 1 to 200 characters after trimming. */
        fun isValidTitle(title: String): Boolean = title.trim().length in 1..MeetingMetadata.MAX_TITLE_LENGTH
    }
}
