package app.meetingrecorder.ui.list

import androidx.core.net.toUri
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.MeetingRecorderApp
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
)

class MeetingListViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val _state = MutableStateFlow(MeetingListUiState())
    val state: StateFlow<MeetingListUiState> = _state.asStateFlow()

    init {
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
            _state.value = MeetingListUiState(meetings = meetings, loading = false)
        }
    }
}
