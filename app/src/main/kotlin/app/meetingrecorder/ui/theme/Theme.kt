package app.meetingrecorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * The status colors of the current appearance (success, information, warning, error), each with a container and
 * the text color on it. Read them here, never as hex values (theme contract rule 1).
 */
val MaterialTheme.statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

/**
 * The one theme of the app: the fixed palette in a light and a dark appearance, chosen by the device setting and
 * by nothing else. There is no wallpaper-based (dynamic) color: the look must be the same on every phone.
 */
@Composable
fun MeetingRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme
    val status = if (darkTheme) DarkStatusColors else LightStatusColors
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = AppTypography, content = content)
    }
}
