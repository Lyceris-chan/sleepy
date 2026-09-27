package dev.sleepy.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The dark scheme. Layered surfaces use the `surfaceContainer*` roles rather than
 * `surfaceVariant`, so a nested card, dialog or sheet is a step on the tonal ladder instead of
 * a flat rectangle of the same colour.
 */
private val DarkColors = darkColorScheme(
    primary = SleepyPurpleLight,
    onPrimary = SleepyPurpleDark,
    primaryContainer = SleepyPurpleContainer,
    onPrimaryContainer = SleepyPurpleLight,
    background = SleepySurface,
    onBackground = SleepyOnSurface,
    surface = SleepySurface,
    onSurface = SleepyOnSurface,
    surfaceVariant = SleepySurfaceVariant,
    onSurfaceVariant = SleepyOnSurfaceVariant,
    surfaceContainerLowest = SleepySurfaceContainerLowestDark,
    surfaceContainerLow = SleepySurfaceContainerLowDark,
    surfaceContainer = SleepySurfaceContainerDark,
    surfaceContainerHigh = SleepySurfaceContainerHighDark,
    surfaceContainerHighest = SleepySurfaceContainerHighestDark,
    outline = SleepyOutline,
    outlineVariant = SleepyOutlineVariant,
    error = SleepyError,
    onError = SleepyOnError
)

/**
 * The light scheme, built with `lightColorScheme`. It keeps the purple seed but flips every
 * tone, so it is a genuine light theme rather than the dark palette under a light label.
 */
private val LightColors = lightColorScheme(
    primary = SleepyLightPrimary,
    onPrimary = SleepyLightOnPrimary,
    primaryContainer = SleepyLightPrimaryContainer,
    onPrimaryContainer = SleepyLightOnPrimaryContainer,
    background = SleepyLightSurface,
    onBackground = SleepyLightOnSurface,
    surface = SleepyLightSurface,
    onSurface = SleepyLightOnSurface,
    surfaceVariant = SleepyLightSurfaceVariant,
    onSurfaceVariant = SleepyLightOnSurfaceVariant,
    surfaceContainerLowest = SleepyLightSurfaceContainerLowest,
    surfaceContainerLow = SleepyLightSurfaceContainerLow,
    surfaceContainer = SleepyLightSurfaceContainer,
    surfaceContainerHigh = SleepyLightSurfaceContainerHigh,
    surfaceContainerHighest = SleepyLightSurfaceContainerHighest,
    outline = SleepyLightOutline,
    outlineVariant = SleepyLightOutlineVariant,
    error = SleepyLightError,
    onError = SleepyLightOnError
)

/**
 * Audit accents that fall outside the tonal palette.
 *
 * Status in this app is always carried by an icon and a word as well as a colour, so these are
 * emphasis only — they are never the sole signal.
 */
@Immutable
data class SleepyStatusColors(
    val success: Color,
    val warning: Color
)

private val DarkStatusColors = SleepyStatusColors(success = SleepySuccess, warning = SleepyWarning)

private val LightStatusColors = SleepyStatusColors(
    success = SleepyLightSuccess,
    warning = SleepyLightWarning
)

private val LocalStatusColors = staticCompositionLocalOf { DarkStatusColors }

/** Audit accents for the active colour scheme, read as `MaterialTheme.statusColors`. */
val MaterialTheme.statusColors: SleepyStatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

/**
 * Applies the sleepy design system.
 *
 * @param darkTheme whether to build the dark scheme; defaults to the system setting.
 * @param dynamicColor whether to derive colours from the device wallpaper on Android 12+.
 * The status bar and navigation bar icon colours follow the same flag through the
 * edge-to-edge setup in the host activity.
 */
@Composable
fun SleepyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SleepyTypography,
            shapes = SleepyShapes,
            content = content
        )
    }
}
