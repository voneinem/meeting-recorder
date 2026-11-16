package app.meetingrecorder.ui.playback

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.meetingrecorder.R
import app.meetingrecorder.core.time.Timeline

/**
 * Plays back one meeting: play and pause, a seek bar with the position, and the photos. Selecting a
 * photo jumps to its position on the timeline and shows it large (FR-028, FR-029).
 */
@Composable
fun PlaybackScreen(
    onBack: () -> Unit,
    viewModel: PlaybackViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text(text = stringResource(R.string.action_back)) }
        when {
            state.notFound -> Text(text = stringResource(R.string.playback_not_found))
            state.loading -> Unit
            else -> PlaybackContent(state, viewModel)
        }
    }

    val selected = state.selectedPhoto
    val bitmap = state.selectedBitmap
    if (selected != null) {
        PhotoViewer(
            atMs = selected.atMs,
            bitmap = bitmap,
            onClose = viewModel::closePhoto,
        )
    }
}

@Composable
private fun PlaybackContent(state: PlaybackUiState, viewModel: PlaybackViewModel) {
    Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
    if (state.playbackFailed) {
        Text(text = stringResource(R.string.playback_failed), color = MaterialTheme.colorScheme.error)
    }
    val player = state.player
    val positionDescription = stringResource(R.string.playback_position_description)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationMs.coerceAtLeast(1)
    Slider(
        value = dragging ?: player.positionMs.toFloat().coerceAtMost(duration.toFloat()),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { viewModel.seekTo(it.toLong()) }
            dragging = null
        },
        valueRange = 0f..duration.toFloat(),
        enabled = player.ready,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = positionDescription },
    )
    val shown = (dragging?.toLong() ?: player.positionMs).coerceAtLeast(0)
    Text(
        text = stringResource(R.string.playback_position, Timeline.format(shown), Timeline.format(player.durationMs)),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = viewModel::playPause, enabled = player.ready, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(if (player.playing) R.string.playback_pause else R.string.playback_play))
    }

    Text(text = stringResource(R.string.playback_photos), style = MaterialTheme.typography.titleMedium)
    if (state.photos.isEmpty()) {
        Text(text = stringResource(R.string.playback_no_photos))
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.photos, key = { it.path }) { photo ->
                PhotoThumbnail(photo = photo, onClick = { viewModel.selectPhoto(photo) })
            }
        }
    }
}

@Composable
private fun PhotoThumbnail(photo: PhotoItem, onClick: () -> Unit) {
    val time = Timeline.format(photo.atMs)
    val description = stringResource(R.string.playback_photo_description, time)
    Card(onClick = onClick) {
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(THUMBNAIL_SIZE), contentAlignment = Alignment.Center) {
                photo.thumbnail?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = description,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(THUMBNAIL_SIZE),
                    )
                }
            }
            Text(text = time, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PhotoViewer(atMs: Long, bitmap: android.graphics.Bitmap?, onClose: () -> Unit) {
    val time = Timeline.format(atMs)
    val description = stringResource(R.string.playback_photo_description, time)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = time, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text(text = stringResource(R.string.playback_photo_close)) }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth().clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = description,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

private val THUMBNAIL_SIZE = 112.dp
