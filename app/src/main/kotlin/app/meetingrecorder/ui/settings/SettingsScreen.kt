package app.meetingrecorder.ui.settings

import androidx.core.net.toUri
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
fun SettingsScreen(preferences: AppPreferences, onChangeFolder: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val folder = remember { preferences.storageTreeUri?.let { it.toUri().lastPathSegment }.orEmpty() }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
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

        Text(text = stringResource(R.string.settings_about_heading), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.settings_version, version))
        Text(text = stringResource(R.string.settings_license))
        Text(text = stringResource(R.string.settings_privacy))

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.action_back))
        }
    }
}
