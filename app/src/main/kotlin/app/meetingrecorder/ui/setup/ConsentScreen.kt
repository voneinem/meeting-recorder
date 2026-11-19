package app.meetingrecorder.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.meetingrecorder.R
import app.meetingrecorder.ui.components.AppButton

/** The recording-consent notice (FR-003). It stays until the user acknowledges it explicitly. */
@Composable
fun ConsentScreen(onAcknowledged: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.consent_title), style = MaterialTheme.typography.headlineMedium)
        Text(text = stringResource(R.string.consent_text), style = MaterialTheme.typography.bodyLarge)
        AppButton(onClick = onAcknowledged, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.consent_acknowledge))
        }
    }
}
