package app.meetingrecorder.ui.transcription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.meetingrecorder.R
import app.meetingrecorder.core.model.FailureReason
import app.meetingrecorder.core.transcription.DetailHint
import app.meetingrecorder.transcription.TranscriptionNotification

/**
 * The banner of a job that did not finish: interrupted, stopped because a recording started, or failed, with
 * the explanation of the reason and **Resume** (when the work file can continue), **Start over** and
 * **Dismiss**. When a committed transcript exists the banner says that the earlier transcript is unchanged
 * (the list still says done). A job that stopped because its model is gone offers only "Start over".
 */
@Composable
fun JobBanner(
    hint: DetailHint,
    hasTranscript: Boolean,
    modelInstalled: Boolean,
    busy: Boolean,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onDismiss: () -> Unit,
) {
    val failed = hint as? DetailHint.JobFailed ?: return
    val interrupted = failed.reason == FailureReason.INTERRUPTED || failed.reason == FailureReason.RECORDING_STARTED
    val modelGone = failed.reason == FailureReason.MODEL_MISSING || (!modelInstalled && failed.resumable)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(if (interrupted) R.string.banner_interrupted_title else R.string.banner_failed_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(text = stringResource(TranscriptionNotification.reasonText(failed.reason)))
            if (modelGone) Text(text = stringResource(R.string.banner_model_gone))
            if (hasTranscript) Text(text = stringResource(R.string.banner_retranscription_note))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed.resumable && !modelGone) {
                    TextButton(onClick = onResume, enabled = !busy) { Text(text = stringResource(R.string.status_action_resume)) }
                }
                TextButton(onClick = onStartOver, enabled = !busy) { Text(text = stringResource(R.string.status_action_start_over)) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(text = stringResource(R.string.action_dismiss)) }
            }
        }
    }
}
