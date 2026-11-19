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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import app.meetingrecorder.storage.TranscriptLoad
import app.meetingrecorder.ui.transcription.JobBanner
import app.meetingrecorder.ui.transcription.StartTranscriptionDialog
import app.meetingrecorder.ui.transcription.transcriptSection
import app.meetingrecorder.ui.transcription.startMessage
import app.meetingrecorder.ui.components.AppCard
import app.meetingrecorder.ui.components.AppButton
import app.meetingrecorder.ui.components.AppOutlinedButton
import app.meetingrecorder.ui.components.AppTextButton

/**
 * Plays back one meeting: play and pause, a seek bar with the position, and the photos. Selecting a
 * photo jumps to its position on the timeline and shows it large (FR-028, FR-029).
 */
@Composable
fun PlaybackScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    viewModel: PlaybackViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }

    // One lazy list: the controls and photos first, then the transcript rows (a long transcript has thousands).
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "back") { AppTextButton(onClick = onBack) { Text(text = stringResource(R.string.action_back)) } }
        when {
            state.notFound -> item(key = "not-found") { Text(text = stringResource(R.string.playback_not_found)) }
            state.loading -> Unit
            else -> {
                item(key = "player") {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { PlaybackContent(state, viewModel) }
                }
                state.message?.let { message ->
                    item(key = "message") {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = stringResource(message), modifier = Modifier.weight(1f))
                                AppTextButton(onClick = viewModel::messageShown) { Text(text = stringResource(R.string.action_ok)) }
                            }
                        }
                    }
                }
                item(key = "transcribe") { TranscribeEntry(viewModel.folder, replacing = state.transcript is TranscriptLoad.Valid, onOpenModels) }
                transcriptSection(
                    state = state,
                    onSeek = viewModel::seekAndPlay,
                    onPhoto = viewModel::selectPhotoByPath,
                    banner = {
                        JobBanner(
                            hint = state.resolved.hint,
                            hasTranscript = state.transcript is TranscriptLoad.Valid,
                            modelInstalled = state.modelInstalled,
                            busy = state.jobPercent != null,
                            onResume = viewModel::resumeJob,
                            onStartOver = viewModel::startOverJob,
                            onDismiss = viewModel::dismissJob,
                        )
                    },
                )
            }
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

/** The "Transcribe" button of the detail screen; it opens the start dialog and shows the answer to a refused start. */
@Composable
private fun TranscribeEntry(folderName: String, replacing: Boolean, onOpenModels: () -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    AppOutlinedButton(onClick = { dialog = true }, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(if (replacing) R.string.transcribe_again else R.string.transcribe_action))
    }
    notice?.let { Text(text = stringResource(it), color = MaterialTheme.colorScheme.error) }
    if (dialog) {
        StartTranscriptionDialog(
            folderName = folderName,
            replacing = replacing,
            onDismiss = { dialog = false },
            onOpenModels = onOpenModels,
            onResult = { result -> notice = startMessage(context, result) },
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
        // The unplayed part of the track must be visible against the page (3:1): the default pale tint is not.
        colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outline),
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
    AppButton(onClick = viewModel::playPause, enabled = player.ready, modifier = Modifier.fillMaxWidth()) {
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
    AppCard(onClick = onClick) {
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
        AppCard(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = time, style = MaterialTheme.typography.titleLarge)
                    AppTextButton(onClick = onClose) { Text(text = stringResource(R.string.playback_photo_close)) }
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
