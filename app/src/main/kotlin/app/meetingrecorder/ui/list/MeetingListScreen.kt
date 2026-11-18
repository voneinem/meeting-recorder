package app.meetingrecorder.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.meetingrecorder.R
import app.meetingrecorder.core.model.MeetingStatus
import app.meetingrecorder.core.time.Timeline
import app.meetingrecorder.core.transcription.TranscriptionStatus
import app.meetingrecorder.storage.MeetingEntry
import app.meetingrecorder.ui.transcription.StartTranscriptionDialog
import app.meetingrecorder.ui.transcription.StatusChip
import app.meetingrecorder.ui.transcription.startMessage
import java.text.DateFormat
import java.util.Date

/** The basic meeting list (FR-026): title, start and duration, newest first. */
@Composable
fun MeetingListScreen(
    onOpenMeeting: (String) -> Unit,
    onNewRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    viewModel: MeetingListViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var renaming by remember { mutableStateOf<MeetingEntry?>(null) }
    var deleting by remember { mutableStateOf<MeetingEntry?>(null) }
    var transcribing by remember { mutableStateOf<MeetingEntry?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.list_title), style = MaterialTheme.typography.headlineMedium)
        state.message?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(message))
                    TextButton(onClick = viewModel::messageShown) { Text(text = stringResource(R.string.action_ok)) }
                }
            }
        }
        if (!state.loading && state.meetings.isEmpty()) {
            Text(text = stringResource(R.string.list_empty), modifier = Modifier.weight(1f))
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.meetings, key = { it.folderName }) { entry ->
                    MeetingRow(
                        entry = entry,
                        editable = entry.folderName != state.activeFolder,
                        onOpen = { onOpenMeeting(entry.folderName) },
                        onRename = { renaming = entry },
                        onDelete = { deleting = entry },
                        onTranscribe = { transcribing = entry },
                        percent = state.transcribingPercent.takeIf { state.transcribing == entry.folderName },
                        onStatusAction = { mode -> viewModel.restart(entry, mode) },
                    )
                }
            }
        }
        Button(onClick = onNewRecording, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.list_new_recording))
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.list_settings))
        }
    }

    renaming?.let { entry ->
        RenameDialog(
            current = entry.title,
            onConfirm = { title ->
                viewModel.rename(entry, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    transcribing?.let { entry ->
        StartTranscriptionDialog(
            folderName = entry.folderName,
            replacing = entry.transcription == TranscriptionStatus.Done,
            onDismiss = { transcribing = null },
            onOpenModels = onOpenModels,
            onResult = { result -> startMessage(context, result)?.let(viewModel::showMessage) },
        )
    }
    deleting?.let { entry ->
        DeleteDialog(
            title = entry.title,
            warnRunning = viewModel.deleteNeedsWarning(entry),
            onConfirm = {
                viewModel.delete(entry)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val valid = MeetingListViewModel.isValidTitle(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.list_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(text = stringResource(R.string.list_rename_label)) },
                singleLine = true,
                isError = !valid,
                supportingText = if (valid) null else ({ Text(text = stringResource(R.string.list_rename_error)) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = valid) { Text(text = stringResource(R.string.action_rename)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun DeleteDialog(title: String, warnRunning: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.list_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.list_delete_message, title))
                if (warnRunning) Text(text = stringResource(R.string.meeting_delete_running_warning), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun MeetingRow(
    entry: MeetingEntry,
    editable: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTranscribe: () -> Unit,
    percent: Int?,
    onStatusAction: (app.meetingrecorder.transcription.Mode) -> Unit,
) {
    val started = remember(entry.startedAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.startedAtMs))
    }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = entry.title, style = MaterialTheme.typography.titleMedium)
            Text(text = started, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Start))
            entry.durationMs?.let {
                Text(text = stringResource(R.string.list_duration, Timeline.format(it)), style = MaterialTheme.typography.bodyMedium)
            }
            StatusChip(entry, percent, onStatusAction)
            when {
                !entry.metadataReadable -> Text(text = stringResource(R.string.list_unreadable), color = MaterialTheme.colorScheme.error)
                entry.status == MeetingStatus.RECOVERED -> Text(text = stringResource(R.string.list_recovered))
            }
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entry.metadataReadable) {
                        TextButton(onClick = onRename) { Text(text = stringResource(R.string.action_rename)) }
                        TextButton(onClick = onTranscribe) {
                            Text(text = stringResource(if (entry.transcription == TranscriptionStatus.Done) R.string.transcribe_again else R.string.transcribe_action))
                        }
                    }
                    TextButton(onClick = onDelete) { Text(text = stringResource(R.string.action_delete)) }
                }
            }
        }
    }
}
