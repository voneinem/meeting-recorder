package app.meetingrecorder.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Fallback palettes for devices without dynamic color (before Android 12). */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF1F3A5F),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF8A1C1C),
    onSecondary = Color(0xFFFFFFFF),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF0B2545),
    secondary = Color(0xFFFFB4AB),
    onSecondary = Color(0xFF561E19),
)
