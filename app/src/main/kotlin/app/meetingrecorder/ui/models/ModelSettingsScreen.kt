package app.meetingrecorder.ui.models

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.meetingrecorder.R
import app.meetingrecorder.core.models.ModelInstallState
import app.meetingrecorder.core.models.ModelLanguages
import app.meetingrecorder.core.models.TransferKind

/**
 * The model list (FR-001 to FR-011): name, size, languages, note, license and state of every model, a
 * download confirmation that starts nothing before "Download" (FR-003), progress with cancel (FR-004),
 * resume, import from a file and delete. Import and delete work without a network.
 */
@Composable
fun ModelSettingsScreen(onBack: () -> Unit, viewModel: ModelViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.models_title), style = MaterialTheme.typography.headlineMedium)
        Text(text = stringResource(R.string.models_intro))
        Text(text = stringResource(R.string.models_network_note), style = MaterialTheme.typography.bodySmall)

        state.message?.let { message ->
            val text = message.argument?.let { stringResource(message.resource, it) } ?: stringResource(message.resource)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = text, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::messageShown) { Text(text = stringResource(R.string.action_ok)) }
                }
            }
        }
        if (state.recording) Text(text = stringResource(R.string.model_recording_in_progress), color = MaterialTheme.colorScheme.error)

        if (state.rows.isEmpty()) Text(text = stringResource(R.string.models_empty))
        state.rows.forEach { row ->
            ModelCard(row, state.recording, viewModel, Formatter.formatShortFileSize(context, row.sizeBytes))
        }

        val importing = state.importPercent
        if (importing != null) {
            Text(text = stringResource(R.string.model_state_importing, importing))
            LinearProgressIndicator(progress = { importing / PERCENT }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = viewModel::cancelImport, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.action_cancel))
            }
        } else {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.recording, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.model_action_import))
            }
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(text = stringResource(R.string.action_back)) }
    }

    state.confirmDownload?.let { row ->
        val entry = row.entry
        AlertDialog(
            onDismissRequest = viewModel::dismissDialogs,
            title = { Text(text = stringResource(R.string.model_download_title, row.name)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.model_download_message,
                        row.name,
                        Formatter.formatShortFileSize(context, row.sizeBytes),
                        entry?.sourceName.orEmpty(),
                        entry?.license.orEmpty(),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmDownload) { Text(text = stringResource(R.string.model_action_download)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialogs) { Text(text = stringResource(R.string.action_cancel)) } },
        )
    }
    state.confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDialogs,
            title = { Text(text = stringResource(R.string.model_delete_title)) },
            text = { Text(text = stringResource(R.string.model_delete_message, row.name)) },
            confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text(text = stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialogs) { Text(text = stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ModelCard(row: ModelRow, recording: Boolean, viewModel: ModelViewModel, size: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = row.name, style = MaterialTheme.typography.titleMedium)
            if (row.recommended) Text(text = stringResource(R.string.model_recommended), style = MaterialTheme.typography.labelMedium)
            row.description?.let { Text(text = it) }
            Text(text = stringResource(R.string.model_size, size))
            Text(
                text = stringResource(
                    if (row.languages == ModelLanguages.MULTILINGUAL) R.string.model_languages_multilingual else R.string.model_languages_english,
                ),
            )
            row.license?.let { Text(text = stringResource(R.string.model_license, it)) }
            ModelActions(row, recording, viewModel)
        }
    }
}

@Composable
private fun ModelActions(row: ModelRow, recording: Boolean, viewModel: ModelViewModel) {
    when (val state = row.state) {
        ModelInstallState.NotInstalled -> Button(
            onClick = { viewModel.askDownload(row) },
            enabled = !recording && row.entry != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(text = stringResource(R.string.model_action_download)) }
        is ModelInstallState.Transferring -> {
            val percent = percentOf(state.bytesDone, state.total)
            Text(text = stringResource(R.string.model_state_downloading, percent))
            LinearProgressIndicator(progress = { percent / PERCENT }, modifier = Modifier.fillMaxWidth())
            if (state.kind == TransferKind.DOWNLOAD) {
                OutlinedButton(onClick = { viewModel.cancel(row) }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        }
        is ModelInstallState.Paused -> {
            Text(text = stringResource(R.string.model_state_paused, percentOf(state.bytesDone, state.total)))
            Button(onClick = { viewModel.resume(row) }, enabled = !recording, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.model_action_resume_download))
            }
            OutlinedButton(onClick = { viewModel.cancel(row) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.model_action_delete_partial))
            }
        }
        ModelInstallState.Verifying -> Text(text = stringResource(R.string.model_state_verifying))
        ModelInstallState.Installed -> {
            Text(text = stringResource(R.string.model_state_installed), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { viewModel.askDelete(row) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.action_delete))
            }
        }
        is ModelInstallState.Failed -> {
            Text(text = stringResource(ModelViewModel.failureText(state.reason)), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { viewModel.dismissFailure(row) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.action_ok))
            }
        }
    }
}

private const val PERCENT = 100f

private fun percentOf(done: Long, total: Long): Int = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
