package app.meetingrecorder.storage

import androidx.core.net.toUri
import android.content.Context
import android.net.Uri
import app.meetingrecorder.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of checking the storage folder before the app or a recording relies on it. */
sealed interface StorageStatus {
    /** No folder has been chosen yet. */
    data object NoFolder : StorageStatus

    /** The permission to the chosen folder was revoked or the folder was removed. */
    data object PermissionLost : StorageStatus

    /** The folder exists but cannot be used (for example the provider rejects random access). */
    data class Unusable(val reason: String) : StorageStatus

    data class Ok(val treeUri: Uri) : StorageStatus
}

/**
 * Checks that the persisted permission is still granted and that the folder passes the probe
 * (spec edge case "Storage folder unavailable", FR-002). Run on app launch and before every recording.
 */
class StorageGuard(private val context: Context, private val preferences: AppPreferences) {

    suspend fun check(): StorageStatus = withContext(Dispatchers.IO) {
        val saved = preferences.storageTreeUri ?: return@withContext StorageStatus.NoFolder
        val treeUri = saved.toUri()
        val storage = SafMeetingStorage(context, treeUri)
        if (!storage.hasPersistedPermission()) return@withContext StorageStatus.PermissionLost
        val probe = storage.probe()
        if (probe.isFailure) {
            StorageStatus.Unusable(probe.exceptionOrNull()?.message.orEmpty())
        } else {
            StorageStatus.Ok(treeUri)
        }
    }
}
