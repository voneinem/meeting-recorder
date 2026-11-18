package app.meetingrecorder.ui.settings

import androidx.core.net.toUri
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import app.meetingrecorder.MeetingRecorderApp
import app.meetingrecorder.core.models.InstalledModel
import app.meetingrecorder.core.models.ModelOrigin
import app.meetingrecorder.models.ModelStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.meetingrecorder.AppPreferences
import app.meetingrecorder.R

/** Settings and about (T054): the consent notice again, the storage folder, version, license, privacy. */
@Composable
fun SettingsScreen(preferences: AppPreferences, onChangeFolder: () -> Unit, onOpenModels: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val folder = remember { preferences.storageTreeUri?.let { it.toUri().lastPathSegment }.orEmpty() }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    var language by remember { mutableStateOf(preferences.transcriptionDefaultLanguage) }
    var defaultModel by remember { mutableStateOf(preferences.transcriptionDefaultModelId) }
    val installed by produceState(emptyList<InstalledModel>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { (context.applicationContext as MeetingRecorderApp).modelRepository.installed() }.getOrDefault(emptyList())
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

        Text(text = stringResource(R.string.settings_consent_heading), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.consent_text))

        Text(text = stringResource(R.string.settings_storage_heading), style = MaterialTheme.typography.titleMedium)
        Text(text = folder)
        OutlinedButton(onClick = onChangeFolder, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.settings_change_folder))
        }

        Text(text = stringResource(R.string.settings_transcription_heading), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.settings_models_entry))
        }
        Text(text = stringResource(R.string.settings_models_entry_summary), style = MaterialTheme.typography.bodySmall)
        Text(text = stringResource(R.string.settings_default_language), style = MaterialTheme.typography.labelLarge)
        listOf(
            AppPreferences.LANGUAGE_AUTO to R.string.settings_language_auto,
            "de" to R.string.settings_language_de,
            "en" to R.string.settings_language_en,
        ).forEach { (code, label) ->
            ChoiceRow(text = stringResource(label), selected = language == code) {
                language = code
                preferences.transcriptionDefaultLanguage = code
            }
        }
        Text(text = stringResource(R.string.settings_default_model), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(text = stringResource(R.string.settings_default_model_none), selected = defaultModel == null) {
            defaultModel = null
            preferences.transcriptionDefaultModelId = null
        }
        installed.forEach { model ->
            val name = modelName(model)
            ChoiceRow(text = name, selected = defaultModel == model.id) {
                defaultModel = model.id
                preferences.transcriptionDefaultModelId = model.id
            }
        }

        Text(text = stringResource(R.string.settings_permissions_heading), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.settings_permissions_internet))
        Text(text = stringResource(R.string.settings_permissions_awake))
        Text(text = stringResource(R.string.settings_permissions_background))

        Text(text = stringResource(R.string.settings_about_heading), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.settings_version, version))
        Text(text = stringResource(R.string.settings_license))
        Text(text = stringResource(R.string.settings_privacy))

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = text, modifier = Modifier.padding(start = 12.dp))
    }
}

/** The display name of an installed model: a catalog name resource, or the imported file's name. */
@Composable
private fun modelName(model: InstalledModel): String {
    val resource = ModelStrings.res(model.displayName)
    return if (resource != null) {
        stringResource(resource)
    } else if (model.origin == ModelOrigin.IMPORTED) {
        stringResource(R.string.model_imported_name, model.displayName)
    } else {
        model.displayName
    }
}
