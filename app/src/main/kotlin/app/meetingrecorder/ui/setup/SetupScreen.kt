package app.meetingrecorder.ui.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.meetingrecorder.R

/** Opens the system folder picker with the flags needed to keep write access across restarts. */
class OpenStorageTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
}

/**
 * First-launch screen (FR-002): explains why a folder is needed, shows the privacy notice about
 * sync services, lets the user choose the folder and rejects folders that cannot be used.
 */
@Composable
fun SetupScreen(
    folderWasLost: Boolean,
    onDone: () -> Unit,
    viewModel: SetupViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(OpenStorageTree(), viewModel::onFolderPicked)

    LaunchedEffect(state) {
        if (state == SetupUiState.Done) onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        if (folderWasLost) {
            Text(text = stringResource(R.string.setup_error_lost), color = MaterialTheme.colorScheme.error)
        }
        Text(text = stringResource(R.string.setup_explanation))
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.setup_privacy_notice), modifier = Modifier.padding(16.dp))
        }
        (state as? SetupUiState.Unusable)?.let {
            Text(
                text = stringResource(R.string.setup_error_unusable, it.reason),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (state == SetupUiState.Checking) {
            CircularProgressIndicator()
        } else {
            Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.setup_choose_folder))
            }
        }
    }
}
