package app.meetingrecorder.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.meetingrecorder.R
import app.meetingrecorder.core.channels.SourceOption
import app.meetingrecorder.recording.localizedSourceName

/** The channel layout a source would produce, as the label shown to the user. */
@Composable
fun channelLabel(channels: Int): String =
    stringResource(if (channels >= 2) R.string.source_stereo else R.string.source_mono)

/**
 * The available microphones with the one a new recording uses selected. A plugged-in external
 * microphone is preselected and marked as suggested; the user can pick another one (FR-006, FR-007).
 */
@Composable
fun SourcePicker(
    state: SourceUiState,
    onSelect: (SourceOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(R.string.source_title), style = MaterialTheme.typography.titleMedium)
        state.options.forEach { option ->
            val selected = option.id == state.selected?.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, onClick = { onSelect(option) }, role = Role.RadioButton)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = localizedSourceName(context, option.info.name), style = MaterialTheme.typography.bodyLarge)
                    val suffix = if (option.id == state.suggestedId) " · ${stringResource(R.string.source_suggested)}" else ""
                    Text(
                        text = channelLabel(option.recordedChannels) + suffix,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
