package app.meetingrecorder.ui.setup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.storage.SafMeetingStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SetupUiState {
    data object Idle : SetupUiState
    data object Checking : SetupUiState

    /** The chosen folder cannot be used; [reason] is the technical cause. */
    data class Unusable(val reason: String) : SetupUiState

    /** The folder was accepted and saved. */
    data object Done : SetupUiState
}

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val _state = MutableStateFlow<SetupUiState>(SetupUiState.Idle)
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun onFolderPicked(treeUri: Uri?) {
        if (treeUri == null) return
        _state.value = SetupUiState.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { accept(treeUri) }
            _state.value = result.fold(
                onSuccess = {
                    preferences.storageTreeUri = treeUri.toString()
                    SetupUiState.Done
                },
                onFailure = { SetupUiState.Unusable(it.message.orEmpty()) },
            )
        }
    }

    private fun accept(treeUri: Uri): Result<Unit> = runCatching {
        val resolver = getApplication<Application>().contentResolver
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        SafMeetingStorage(getApplication(), treeUri).probe().getOrThrow()
    }
}
