package app.meetingrecorder.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.meetingrecorder.R

/** The runtime permissions of the recording feature (Principle VI: only what a feature needs). */
object RecordPermissions {
    /** Microphone is required; notifications (Android 13 and later) only make the recording visible. */
    fun required(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun microphoneGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/** The camera permission is optional: only photos need it (Principle VI, FR-031). */
object CameraPermission {
    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

/**
 * Explains why the camera is needed before the system dialog is shown. After a denial it says that
 * only photos are affected and offers to retry or to open the app settings.
 */
@Composable
fun CameraPermissionCard(
    denied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.photo_camera_reason))
        if (denied) {
            Text(text = stringResource(R.string.photo_camera_denied), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.permission_retry))
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.permission_open_settings))
            }
        } else {
            OutlinedButton(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.photo_camera_allow))
            }
        }
    }
}

/**
 * Explains why each permission is needed before the system dialog is shown (FR-031). When the
 * microphone was denied it says so and offers to retry or to open the app settings.
 */
@Composable
fun PermissionRationale(
    microphoneDenied: Boolean,
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(R.string.permission_title), style = MaterialTheme.typography.titleLarge)
        Text(text = stringResource(R.string.permission_microphone_reason))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Text(text = stringResource(R.string.permission_notification_reason))
        }
        if (microphoneDenied) {
            Text(text = stringResource(R.string.permission_microphone_denied), color = MaterialTheme.colorScheme.error)
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.permission_retry))
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.permission_open_settings))
            }
        } else {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.permission_continue))
            }
        }
    }
}
