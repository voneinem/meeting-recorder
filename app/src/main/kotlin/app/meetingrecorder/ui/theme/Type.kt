package app.meetingrecorder.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

private val base = Typography()

/**
 * The system font with Material's sizes and a calm hierarchy: titles, headlines and labels in medium weight, body
 * text regular. No font file is bundled or loaded (FR-008).
 */
internal val AppTypography = Typography(
    displayLarge = base.displayLarge,
    displayMedium = base.displayMedium,
    displaySmall = base.displaySmall,
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Medium),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Medium),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontWeight = FontWeight.Normal),
    bodyMedium = base.bodyMedium.copy(fontWeight = FontWeight.Normal),
    bodySmall = base.bodySmall.copy(fontWeight = FontWeight.Normal),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium),
)
