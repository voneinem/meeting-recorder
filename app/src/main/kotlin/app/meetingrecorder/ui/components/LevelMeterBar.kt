package app.meetingrecorder.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.meetingrecorder.core.level.ChannelLevel
import app.meetingrecorder.core.level.LevelMeter

/** A horizontal bar for the level of one channel: empty at the floor value, full at 0 dBFS. */
@Composable
fun LevelMeterBar(level: ChannelLevel, description: String, modifier: Modifier = Modifier) {
    val fraction = ((level.peakDb - LevelMeter.FLOOR_DB) / -LevelMeter.FLOOR_DB).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .semantics { contentDescription = description },
    )
}
