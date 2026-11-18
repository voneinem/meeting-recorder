package app.meetingrecorder.ui.transcription

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.meetingrecorder.R
import app.meetingrecorder.core.time.Timeline
import app.meetingrecorder.storage.TranscriptLoad
import app.meetingrecorder.ui.playback.PhotoItem
import app.meetingrecorder.ui.playback.PlaybackUiState
import app.meetingrecorder.ui.playback.TranscriptRow

/**
 * The transcript of a meeting as lazy list items: segment rows (the start time and the text, announced as a
 * button that plays from there) with the photos placed between them by the shared placement rule. A meeting
 * without a transcript shows the offer to transcribe or the current state, never an empty area (FR-032). The
 * transcript is not editable and the current segment is not highlighted (out of scope).
 */
fun LazyListScope.transcriptSection(
    state: PlaybackUiState,
    onSeek: (Long) -> Unit,
    onPhoto: (String) -> Unit,
    banner: @Composable () -> Unit,
) {
    item(key = "transcript-heading") {
        Text(text = stringResource(R.string.transcript_heading), style = MaterialTheme.typography.titleMedium)
    }
    item(key = "transcript-banner") { banner() }
    state.jobPercent?.let { percent ->
        item(key = "transcript-progress") { Text(text = stringResource(R.string.status_in_progress, percent)) }
    }
    when (val transcript = state.transcript) {
        TranscriptLoad.Missing -> if (state.jobPercent == null) {
            item(key = "transcript-none") { Text(text = stringResource(R.string.transcript_none)) }
        }
        TranscriptLoad.Unreadable -> item(key = "transcript-unreadable") {
            Text(text = stringResource(R.string.transcript_unreadable), color = MaterialTheme.colorScheme.error)
        }
        is TranscriptLoad.Valid -> if (transcript.transcript.segments.isEmpty()) {
            item(key = "transcript-empty") { Text(text = stringResource(R.string.transcript_empty)) }
        } else {
            val thumbnails = state.photos.associateBy { it.path }
            items(state.transcriptRows, key = { row -> row.key() }) { row ->
                when (row) {
                    is TranscriptRow.Segment -> SegmentRow(row, onSeek)
                    is TranscriptRow.Photo -> PhotoRow(row, thumbnails[row.path], onPhoto)
                }
            }
        }
    }
}

/** A stable key per row: the segment id or the photo path. */
private fun TranscriptRow.key(): String = when (this) {
    is TranscriptRow.Segment -> "s$id"
    is TranscriptRow.Photo -> "p$path"
}

@Composable
private fun SegmentRow(row: TranscriptRow.Segment, onSeek: (Long) -> Unit) {
    val time = Timeline.format(row.startMs)
    val description = stringResource(R.string.transcript_segment_description, time, row.text)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onSeek(row.startMs) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = time, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(text = row.text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PhotoRow(row: TranscriptRow.Photo, photo: PhotoItem?, onPhoto: (String) -> Unit) {
    val time = Timeline.format(row.atMs)
    val description = stringResource(R.string.transcript_photo_description, row.number, time)
    Card(onClick = { onPhoto(row.path) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            photo?.thumbnail?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(PHOTO_SIZE))
            }
            Text(text = stringResource(R.string.transcript_photo_label, row.number, time), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private val PHOTO_SIZE = 72.dp
