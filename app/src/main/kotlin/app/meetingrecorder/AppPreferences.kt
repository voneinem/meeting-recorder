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

    /** Language offered when starting a transcription: `auto`, `de` or `en`. */
    var transcriptionDefaultLanguage: String
        get() = prefs.getString(KEY_TRANSCRIPTION_LANGUAGE, LANGUAGE_AUTO) ?: LANGUAGE_AUTO
        set(value) = prefs.edit { putString(KEY_TRANSCRIPTION_LANGUAGE, value) }

    /** The installed model preselected for a transcription, or null when the user chose none. */
    var transcriptionDefaultModelId: String?
        get() = prefs.getString(KEY_TRANSCRIPTION_DEFAULT_MODEL, null)
        set(value) = prefs.edit { putString(KEY_TRANSCRIPTION_DEFAULT_MODEL, value) }

    /** The model used for the most recent transcription, or null. */
    var transcriptionLastModelId: String?
        get() = prefs.getString(KEY_TRANSCRIPTION_LAST_MODEL, null)
        set(value) = prefs.edit { putString(KEY_TRANSCRIPTION_LAST_MODEL, value) }

    /** Whether the one-time hint about disabled notifications was shown (FR-019). */
    var transcriptionNotificationHintShown: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_HINT_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION_HINT_SHOWN, value) }

    companion object {
        const val LANGUAGE_AUTO = "auto"
        private const val FILE_NAME = "meeting_recorder_prefs"
        private const val KEY_STORAGE_TREE_URI = "storage_tree_uri"
        private const val KEY_CONSENT_ACKNOWLEDGED = "consent_acknowledged"
        private const val KEY_TRANSCRIPTION_LANGUAGE = "transcription.defaultLanguage"
        private const val KEY_TRANSCRIPTION_DEFAULT_MODEL = "transcription.defaultModelId"
        private const val KEY_TRANSCRIPTION_LAST_MODEL = "transcription.lastModelId"
        private const val KEY_NOTIFICATION_HINT_SHOWN = "transcription.notificationHintShown"
    }
}
