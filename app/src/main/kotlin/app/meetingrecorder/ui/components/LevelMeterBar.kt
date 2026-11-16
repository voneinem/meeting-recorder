package app.meetingrecorder.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.meetingrecorder.core.level.ChannelLevel
import app.meetingrecorder.core.level.LevelMeter

/** A horizontal bar for the level of one channel: empty at the floor value, full at 0 dBFS. */
@Composable
fun LevelMeterBar(level: ChannelLevel, description: String, modifier: Modifier = Modifier, label: String? = null) {
    val fraction = ((level.peakDb - LevelMeter.FLOOR_DB) / -LevelMeter.FLOOR_DB).coerceIn(0f, 1f)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(LABEL_WIDTH))
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .semantics { contentDescription = description },
        )
    }
}

private val LABEL_WIDTH = 64.dp
