package app.meetingrecorder.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.meetingrecorder.ui.theme.statusColors

/** The meaning of a card: plain, a warning (needs attention, nothing lost) or an error (failed). */
enum class CardTone { Neutral, Warning, Error }

/**
 * The one card style of the app (research R6): a flat surface with a thin line and the modest corner radius. A
 * status card is asked for with [tone]; the card sets its content color itself, because a status container is not a
 * Material role and the default content color would not match it. No screen sets card colors, elevation or border.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.Neutral,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = cardColors(tone),
        elevation = flat(),
        border = border(tone),
        content = content,
    )
}

/** A card that can be clicked. */
@Composable
fun AppCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.Neutral,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = cardColors(tone),
        elevation = flat(),
        border = border(tone),
        content = content,
    )
}

@Composable
private fun cardColors(tone: CardTone): CardColors {
    val scheme = MaterialTheme.colorScheme
    val status = MaterialTheme.statusColors
    return when (tone) {
        CardTone.Neutral -> CardDefaults.cardColors(containerColor = scheme.surface, contentColor = scheme.onSurface)
        CardTone.Warning -> CardDefaults.cardColors(containerColor = status.warning.container, contentColor = status.warning.onContainer)
        CardTone.Error -> CardDefaults.cardColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer)
    }
}

@Composable
private fun border(tone: CardTone): BorderStroke {
    val color = when (tone) {
        CardTone.Neutral -> MaterialTheme.colorScheme.outlineVariant
        CardTone.Warning -> MaterialTheme.statusColors.warning.foreground
        CardTone.Error -> MaterialTheme.colorScheme.error
    }
    return BorderStroke(1.dp, color)
}

@Composable
private fun flat(): CardElevation = CardDefaults.cardElevation(
    defaultElevation = 0.dp,
    pressedElevation = 0.dp,
    focusedElevation = 0.dp,
    hoveredElevation = 0.dp,
    draggedElevation = 0.dp,
    disabledElevation = 0.dp,
)
