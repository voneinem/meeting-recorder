package app.meetingrecorder.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

private fun Int.c() = Color(this)

/**
 * The Material 3 color scheme of one appearance, built from [Palette] (research R2). Every role is set on
 * purpose, so none keeps Material's baseline purple tones (`ColorSchemeRolesTest` checks it). The roles that
 * the app does not use (the fixed roles, scrim, surface tint) still come from palette tokens.
 */
private fun scheme(p: Palette.Set, opposite: Palette.Set): ColorScheme {
    // Every role is passed by name, so the order of Material's parameters cannot matter. The factory only
    // supplies defaults for roles that are not passed; there are none, so the light factory serves both appearances.
    return lightColorScheme(
        primary = p.green.c(),
        onPrimary = p.onGreen.c(),
        primaryContainer = p.mint.c(),
        onPrimaryContainer = p.greenStrong.c(),
        inversePrimary = opposite.green.c(),
        secondary = p.blue.c(),
        onSecondary = p.onGreen.c(),
        secondaryContainer = p.blueSoft.c(),
        onSecondaryContainer = p.blue.c(),
        tertiary = p.amber.c(),
        onTertiary = p.onGreen.c(),
        tertiaryContainer = p.amberSoft.c(),
        onTertiaryContainer = p.amber.c(),
        background = p.paper.c(),
        onBackground = p.ink.c(),
        surface = p.surface.c(),
        onSurface = p.ink.c(),
        surfaceVariant = p.surfaceQuiet.c(),
        onSurfaceVariant = p.inkSoft.c(),
        surfaceTint = p.green.c(),
        inverseSurface = p.ink.c(),
        inverseOnSurface = p.paper.c(),
        error = p.rose.c(),
        onError = p.onRose.c(),
        errorContainer = p.roseSoft.c(),
        onErrorContainer = p.onRoseSoft.c(),
        outline = p.outlineControl.c(),
        outlineVariant = p.line.c(),
        scrim = p.ink.c(),
        surfaceBright = p.surface.c(),
        surfaceDim = p.paper.c(),
        surfaceContainer = p.surfaceQuiet.c(),
        surfaceContainerHigh = p.surfaceQuiet.c(),
        surfaceContainerHighest = p.surfaceQuiet.c(),
        surfaceContainerLow = p.surface.c(),
        surfaceContainerLowest = p.surface.c(),
        primaryFixed = p.mint.c(),
        primaryFixedDim = p.green.c(),
        onPrimaryFixed = p.greenStrong.c(),
        onPrimaryFixedVariant = p.greenStrong.c(),
        secondaryFixed = p.blueSoft.c(),
        secondaryFixedDim = p.blue.c(),
        onSecondaryFixed = p.blue.c(),
        onSecondaryFixedVariant = p.blue.c(),
        tertiaryFixed = p.amberSoft.c(),
        tertiaryFixedDim = p.amber.c(),
        onTertiaryFixed = p.amber.c(),
        onTertiaryFixedVariant = p.amber.c(),
    )
}

internal val LightColorScheme: ColorScheme = scheme(Palette.Light, Palette.Dark)
internal val DarkColorScheme: ColorScheme = scheme(Palette.Dark, Palette.Light)

/** One status: the text or accent color, its soft container and the text color on that container. */
@Immutable
class StatusRole(val foreground: Color, val container: Color, val onContainer: Color)

/**
 * The status colors that Material 3 has no slot for. [error] mirrors the Material error roles so that screens can
 * pick every status from one place. A status is always shown together with its text (theme contract rule 4).
 */
@Immutable
class StatusColors(
    val success: StatusRole,
    val information: StatusRole,
    val warning: StatusRole,
    val error: StatusRole,
)

private fun statusColors(p: Palette.Set) = StatusColors(
    success = StatusRole(p.green.c(), p.mint.c(), p.greenStrong.c()),
    information = StatusRole(p.blue.c(), p.blueSoft.c(), p.blue.c()),
    warning = StatusRole(p.amber.c(), p.amberSoft.c(), p.amber.c()),
    error = StatusRole(p.rose.c(), p.roseSoft.c(), p.onRoseSoft.c()),
)

internal val LightStatusColors: StatusColors = statusColors(Palette.Light)
internal val DarkStatusColors: StatusColors = statusColors(Palette.Dark)
