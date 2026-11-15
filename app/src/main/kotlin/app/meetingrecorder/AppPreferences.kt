package app.meetingrecorder

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * App-local conveniences only (data-model.md, "App preferences"). Deleting them never affects any
 * meeting folder: the folders are the source of truth.
 */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The persisted Storage Access Framework tree of the storage folder, or null before setup. */
    var storageTreeUri: String?
        get() = prefs.getString(KEY_STORAGE_TREE_URI, null)
        set(value) = prefs.edit { putString(KEY_STORAGE_TREE_URI, value) }

    /** Whether the user acknowledged that all participants must consent to being recorded. */
    var consentAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_CONSENT_ACKNOWLEDGED, false)
        set(value) = prefs.edit { putBoolean(KEY_CONSENT_ACKNOWLEDGED, value) }

    private companion object {
        const val FILE_NAME = "meeting_recorder_prefs"
        const val KEY_STORAGE_TREE_URI = "storage_tree_uri"
        const val KEY_CONSENT_ACKNOWLEDGED = "consent_acknowledged"
    }
}
