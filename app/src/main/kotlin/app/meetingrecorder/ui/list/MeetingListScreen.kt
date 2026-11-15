package app.meetingrecorder.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.meetingrecorder.storage.MeetingEntry
import java.text.DateFormat
import java.util.Date

/** The basic meeting list (FR-026): title, start and duration, newest first. */
@Composable
fun MeetingListScreen(
    onNewRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MeetingListViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.list_title), style = MaterialTheme.typography.headlineMedium)
        if (!state.loading && state.meetings.isEmpty()) {
            Text(text = stringResource(R.string.list_empty), modifier = Modifier.weight(1f))
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.meetings, key = { it.folderName }) { MeetingRow(it) }
            }
        }
        Button(onClick = onNewRecording, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.list_new_recording))
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.list_settings))
        }
    }
}

@Composable
private fun MeetingRow(entry: MeetingEntry) {
    val started = remember(entry.startedAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.startedAtMs))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = entry.title, style = MaterialTheme.typography.titleMedium)
            Text(text = started, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Start))
            entry.durationMs?.let {
                Text(text = stringResource(R.string.list_duration, Timeline.format(it)), style = MaterialTheme.typography.bodyMedium)
            }
            when {
                !entry.metadataReadable -> Text(text = stringResource(R.string.list_unreadable), color = MaterialTheme.colorScheme.error)
                entry.status == MeetingStatus.RECOVERED -> Text(text = stringResource(R.string.list_recovered))
            }
        }
    }
}
