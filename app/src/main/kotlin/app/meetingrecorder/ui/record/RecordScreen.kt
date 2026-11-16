package app.meetingrecorder.ui.record

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.camera.view.PreviewView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.meetingrecorder.R
import app.meetingrecorder.core.time.Timeline
import app.meetingrecorder.camera.CameraController
import app.meetingrecorder.permissions.CameraPermission
import app.meetingrecorder.permissions.CameraPermissionCard
import app.meetingrecorder.permissions.PermissionRationale
import app.meetingrecorder.permissions.RecordPermissions
import app.meetingrecorder.recording.Phase
import app.meetingrecorder.recording.localizedSourceName
import app.meetingrecorder.ui.components.LevelMeterBar

/**
 * The recording screen (FR-001, FR-004, FR-005, FR-007): title and Start while idle; elapsed time,
 * level meter per channel, Pause/Resume and Stop while recording.
 */
@Composable
fun RecordScreen(
    onOpenList: () -> Unit,
    onStorageProblem: () -> Unit,
    onFinished: () -> Unit,
    viewModel: RecordViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val starting by viewModel.starting.collectAsStateWithLifecycle()
    val storageProblem by viewModel.storageProblem.collectAsStateWithLifecycle()
    val photoBusy by viewModel.photoBusy.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()

    var showRationale by remember { mutableStateOf(false) }
    var microphoneDenied by remember { mutableStateOf(false) }
    var previousPhase by remember { mutableStateOf(state.phase) }
    var cameraGranted by remember { mutableStateOf(CameraPermission.granted(context)) }
    var cameraDenied by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        cameraDenied = !granted
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (RecordPermissions.microphoneGranted(context)) {
            microphoneDenied = false
            showRationale = false
            viewModel.start()
        } else {
            microphoneDenied = true
        }
    }

    LaunchedEffect(storageProblem) {
        if (storageProblem) {
            viewModel.storageProblemHandled()
            onStorageProblem()
        }
    }
    LaunchedEffect(state.phase) {
        if (previousPhase != Phase.IDLE && state.phase == Phase.IDLE) onFinished()
        previousPhase = state.phase
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        notice?.let { current ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val text = when (current) {
                        is Notice.Text -> stringResource(current.message, *current.args.toTypedArray())
                        is Notice.Plural -> pluralStringResource(current.message, current.quantity, current.quantity)
                    }
                    Text(text = text)
                    TextButton(onClick = viewModel::dismissNotice) { Text(text = stringResource(R.string.action_ok)) }
                }
            }
        }

        if (state.phase == Phase.IDLE) {
            if (showRationale) {
                PermissionRationale(
                    microphoneDenied = microphoneDenied,
                    onContinue = { permissionLauncher.launch(RecordPermissions.required()) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                )
            } else {
                IdleContent(
                    sources = sources,
                    onSelectSource = viewModel::selectSource,
                    title = title,
                    starting = starting,
                    onTitleChange = viewModel::onTitleChange,
                    onStart = {
                        if (RecordPermissions.microphoneGranted(context)) viewModel.start() else showRationale = true
                    },
                    onOpenList = onOpenList,
                )
            }
        } else {
            ActiveContent(
                phase = state.phase,
                elapsedMs = state.elapsedMs,
                levels = state.levels,
                activeSourceName = state.activeSourceName,
                photoCount = state.photoCount,
                photoBusy = photoBusy,
                camera = viewModel.camera,
                cameraGranted = cameraGranted,
                cameraDenied = cameraDenied,
                onAllowCamera = { cameraLauncher.launch(android.Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                onTakePhoto = viewModel::takePhoto,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onStop = viewModel::stop,
            )
        }
    }
}

@Composable
private fun IdleContent(
    sources: SourceUiState,
    onSelectSource: (app.meetingrecorder.core.channels.SourceOption) -> Unit,
    title: String,
    starting: Boolean,
    onTitleChange: (String) -> Unit,
    onStart: () -> Unit,
    onOpenList: () -> Unit,
) {
    OutlinedTextField(
        value = title,
        onValueChange = onTitleChange,
        label = { Text(text = stringResource(R.string.record_title_label)) },
        placeholder = { Text(text = stringResource(R.string.record_title_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SourcePicker(state = sources, onSelect = onSelectSource)
    Button(onClick = onStart, enabled = !starting, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.record_start))
    }
    TextButton(onClick = onOpenList, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.record_open_list))
    }
}

@Composable
private fun ActiveContent(
    phase: Phase,
    elapsedMs: Long,
    levels: List<app.meetingrecorder.core.level.ChannelLevel>,
    activeSourceName: String,
    photoCount: Int,
    photoBusy: Boolean,
    camera: CameraController,
    cameraGranted: Boolean,
    cameraDenied: Boolean,
    onAllowCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onTakePhoto: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val stateLabel = when (phase) {
        Phase.RECORDING -> R.string.record_state_recording
        Phase.PAUSED -> R.string.record_state_paused
        Phase.SUSPENDED -> R.string.record_state_suspended
        else -> R.string.record_state_saving
    }
    val elapsedDescription = stringResource(R.string.record_elapsed_description)
    Text(text = stringResource(stateLabel), style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Text(
        text = Timeline.format(elapsedMs),
        style = MaterialTheme.typography.displayLarge,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = elapsedDescription },
        textAlign = TextAlign.Center,
    )
    if (activeSourceName.isNotEmpty()) {
        Text(
            text = stringResource(R.string.source_active, localizedSourceName(LocalContext.current, activeSourceName), channelLabel(levels.size)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
    levels.forEachIndexed { index, level ->
        val description = if (levels.size == 1) {
            stringResource(R.string.level_meter_description_mono)
        } else {
            stringResource(R.string.level_meter_description, index + 1, levels.size)
        }
        val label = if (levels.size == 2) stringResource(if (index == 0) R.string.meter_left else R.string.meter_right) else null
        LevelMeterBar(level = level, description = description, label = label)
    }
    val controlsEnabled = phase != Phase.FINALIZING
    if (cameraGranted) {
        CameraPreview(camera)
        Button(onClick = onTakePhoto, enabled = controlsEnabled && !photoBusy, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (photoCount > 0) stringResource(R.string.photo_button_count, photoCount) else stringResource(R.string.photo_button),
            )
        }
    } else {
        CameraPermissionCard(denied = cameraDenied, onAllow = onAllowCamera, onOpenSettings = onOpenSettings)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (phase == Phase.RECORDING) {
            OutlinedButton(onClick = onPause, enabled = controlsEnabled, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.record_pause))
            }
        } else {
            OutlinedButton(onClick = onResume, enabled = controlsEnabled, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.action_resume))
            }
        }
        Button(onClick = onStop, enabled = controlsEnabled, modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.action_stop))
        }
    }
}

/**
 * The live camera view, in the portrait shape of the photo so the framing shown is the framing saved.
 * The camera is released when this leaves the composition or the screen stops.
 */
@Composable
private fun CameraPreview(camera: CameraController) {
    val owner = LocalLifecycleOwner.current
    val description = stringResource(R.string.photo_preview_description)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                // COMPATIBLE renders into a texture view, so the preview scrolls, clips and layers like
                // other content instead of drawing on top of the timer.
                PreviewView(context).also {
                    it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    it.scaleType = PreviewView.ScaleType.FIT_CENTER
                    camera.bind(owner, it)
                }
            },
            onRelease = { camera.unbind() },
            modifier = Modifier
                .height(PREVIEW_HEIGHT)
                .aspectRatio(PREVIEW_ASPECT_RATIO)
                .clipToBounds()
                .semantics { contentDescription = description },
        )
    }
}

private val PREVIEW_HEIGHT = 300.dp
private const val PREVIEW_ASPECT_RATIO = 3f / 4f
