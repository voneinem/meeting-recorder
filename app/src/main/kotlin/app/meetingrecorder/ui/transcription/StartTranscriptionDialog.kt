package app.meetingrecorder.ui.transcription

import android.app.ActivityManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.MeetingRecorderApp
import app.meetingrecorder.R
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.models.ModelCatalog
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.core.transcription.StartDefaults
import app.meetingrecorder.models.ModelStrings
import app.meetingrecorder.transcription.Mode
import app.meetingrecorder.transcription.StartResult
import app.meetingrecorder.transcription.TranscriptionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.meetingrecorder.ui.components.AppTextButton
import app.meetingrecorder.ui.theme.statusColors

/** The user-facing message for the answer to a start: a confirmation, or the reason for the refusal. */
@StringRes
fun startResultMessage(result: StartResult): Int? = when (result) {
    StartResult.Started -> R.string.transcribe_started
    StartResult.RecordingInProgress -> R.string.transcribe_refused_recording
    StartResult.AnotherJobRunning -> R.string.transcribe_refused_busy
    StartResult.ModelNotInstalled -> R.string.transcribe_no_model
    StartResult.ResumeNotPossible -> R.string.transcribe_refused_resume
    StartResult.MeetingNotFound -> R.string.transcribe_refused_not_found
    StartResult.MeetingStillRecording -> R.string.transcribe_refused_still_recording
    StartResult.ReplaceNeedsConfirmation -> R.string.transcribe_refused_confirm
}

/**
 * The message after a start. The first time a transcription starts while notifications are off, the one-time
 * hint (FR-019) replaces the confirmation: the job still runs and the progress is shown in the app.
 */
@StringRes
fun startMessage(context: android.content.Context, result: StartResult): Int? {
    if (result == StartResult.Started && notificationsOff(context)) {
        val preferences = AppPreferences(context)
        if (!preferences.transcriptionNotificationHintShown) {
            preferences.transcriptionNotificationHintShown = true
            return R.string.transcription_notification_hint
        }
    }
    return startResultMessage(result)
}

private fun notificationsOff(context: android.content.Context): Boolean =
    !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

/**
 * Starts a transcription (FR-012 to FR-014): the model (installed models with their size) and the language
 * (Automatic, German, English) are preselected from the settings through [StartDefaults.preselect], with
 * inline notices when the model is not suited to the language or needs a lot of memory. With no model
 * installed a message and a button to the model settings replace the dialog.
 */
@Composable
fun StartTranscriptionDialog(
    folderName: String,
    /** True when the meeting already has a transcript: the dialog then warns that it will be replaced (FR-033). */
    replacing: Boolean,
    onDismiss: () -> Unit,
    onOpenModels: () -> Unit,
    onResult: (StartResult) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MeetingRecorderApp
    val preferences = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()
    val installed by produceState<List<InstalledModel>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { app.modelRepository.installed() }.getOrDefault(emptyList()) }
    }
    val models = installed
    if (models == null) return
    if (models.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(R.string.transcribe_title)) },
            text = { Text(text = stringResource(R.string.transcribe_no_model)) },
            confirmButton = { AppTextButton(onClick = { onDismiss(); onOpenModels() }) { Text(text = stringResource(R.string.transcribe_open_models)) } },
            dismissButton = { AppTextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) } },
        )
        return
    }

    var modelId by remember {
        mutableStateOf(
            StartDefaults.preselect(models.map { it.id }, preferences.transcriptionDefaultModelId, preferences.transcriptionLastModelId)
                ?: models.first().id,
        )
    }
    var language by remember { mutableStateOf(preferences.transcriptionDefaultLanguage) }
    var starting by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.id == modelId } ?: models.first()
    val languageWarning = StartDefaults.languageWarning(selected.languages, language)
    val totalMemory = remember {
        ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    }
    val approxMemoryMb = ModelCatalog.find(selected.id)?.approxMemoryMb
    val memoryWarning = approxMemoryMb != null && StartDefaults.memoryWarning(totalMemory, approxMemoryMb)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(if (replacing) R.string.transcribe_again else R.string.transcribe_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (replacing) Text(text = stringResource(R.string.transcribe_again_warning), color = MaterialTheme.statusColors.warning.foreground)
                Text(text = stringResource(R.string.transcribe_model_label), style = MaterialTheme.typography.labelLarge)
                models.forEach { model ->
                    Choice(text = modelLabel(model, android.text.format.Formatter.formatShortFileSize(context, model.sizeBytes)), selected = model.id == selected.id) {
                        modelId = model.id
                    }
                }
                Text(text = stringResource(R.string.transcribe_language_label), style = MaterialTheme.typography.labelLarge)
                listOf(
                    AppPreferences.LANGUAGE_AUTO to R.string.settings_language_auto,
                    "de" to R.string.settings_language_de,
                    "en" to R.string.settings_language_en,
                ).forEach { (code, label) -> Choice(text = stringResource(label), selected = language == code) { language = code } }
                if (languageWarning) Text(text = stringResource(R.string.transcribe_warning_language), color = MaterialTheme.statusColors.warning.foreground)
                if (memoryWarning) Text(text = stringResource(R.string.transcribe_warning_memory), color = MaterialTheme.statusColors.warning.foreground)
            }
        },
        confirmButton = {
            AppTextButton(
                enabled = !starting,
                onClick = {
                    starting = true
                    // The dialog must stay in the composition until the start has returned: leaving it cancels this
                    // scope, and a start cancelled before it ran leaves no job and no message (found on the phone, S13).
                    scope.launch {
                        val result = app.transcriptionController.start(TranscriptionRequest(folderName, selected.id, language, Mode.START, replaceExisting = replacing))
                        onResult(result)
                        onDismiss()
                    }
                },
            ) { Text(text = stringResource(R.string.transcribe_start)) }
        },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun modelLabel(model: InstalledModel, size: String): String {
    val resource = ModelStrings.res(model.displayName)
    val name = when {
        resource != null -> stringResource(resource)
        model.origin == ModelOrigin.IMPORTED -> stringResource(R.string.model_imported_name, model.displayName)
        else -> model.displayName
    }
    return "$name ($size)"
}

@Composable
private fun Choice(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = text, modifier = Modifier.padding(start = 12.dp))
    }
}
