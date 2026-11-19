package app.meetingrecorder.ui.transcription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.meetingrecorder.R
import app.meetingrecorder.core.transcription.TranscriptionStatus
import app.meetingrecorder.storage.MeetingEntry
import app.meetingrecorder.transcription.Mode
import app.meetingrecorder.ui.theme.statusColors
import app.meetingrecorder.ui.components.AppTextButton

/**
 * The transcription status of a meeting in the list: nothing for none, the percentage while it runs, done, or
 * failed with the row actions that fit ("Resume" when the work file can continue, "Start over", "Retry").
 * [percent] is the live progress of the running job, or null when it is not known.
 */
@Composable
fun StatusChip(entry: MeetingEntry, percent: Int?, onAction: (Mode) -> Unit, modifier: Modifier = Modifier) {
    val status = entry.transcription
    if (status == TranscriptionStatus.None) return
    val label = when (status) {
        TranscriptionStatus.InProgress -> percent?.let { stringResource(R.string.status_in_progress, it) } ?: stringResource(R.string.status_in_progress_unknown)
        TranscriptionStatus.Done -> stringResource(R.string.status_done)
        is TranscriptionStatus.Failed -> stringResource(R.string.status_failed)
        TranscriptionStatus.None -> return
    }
    val description = stringResource(R.string.status_description, label)
    // In progress is information, done is success, failed is an error; the label text always says which (FR-006).
    val role = when (status) {
        TranscriptionStatus.InProgress -> MaterialTheme.statusColors.information
        TranscriptionStatus.Done -> MaterialTheme.statusColors.success
        is TranscriptionStatus.Failed -> MaterialTheme.statusColors.error
        TranscriptionStatus.None -> return
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = {},
            label = { Text(text = label) },
            shape = CircleShape,
            colors = AssistChipDefaults.assistChipColors(containerColor = role.container, labelColor = role.onContainer),
            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = role.foreground),
            modifier = Modifier.semantics { contentDescription = description },
        )
        if (status is TranscriptionStatus.Failed && entry.jobModelId != null) {
            if (entry.resumable) AppTextButton(onClick = { onAction(Mode.RESUME) }) { Text(text = stringResource(R.string.status_action_resume)) }
            AppTextButton(onClick = { onAction(Mode.RESTART) }) {
                Text(text = stringResource(if (entry.resumable) R.string.status_action_start_over else R.string.status_action_retry))
            }
        }
    }
}
