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
 * `surfaceVariant`, so a nested card, dialog or sheet is one step in the surface tone instead of
 * a flat rectangle of the same color.
 */
private val DARK_COLORS = darkColorScheme(
    primary = SLEEPY_PURPLE_LIGHT,
    onPrimary = SLEEPY_PURPLE_DARK,
    primaryContainer = SLEEPY_PURPLE_CONTAINER,
    onPrimaryContainer = SLEEPY_PURPLE_LIGHT,
    background = SLEEPY_SURFACE,
    onBackground = SLEEPY_ON_SURFACE,
    surface = SLEEPY_SURFACE,
    onSurface = SLEEPY_ON_SURFACE,
    surfaceVariant = SLEEPY_SURFACE_VARIANT,
    onSurfaceVariant = SLEEPY_ON_SURFACE_VARIANT,
    surfaceContainerLowest = SLEEPY_SURFACE_CONTAINER_LOWEST_DARK,
    surfaceContainerLow = SLEEPY_SURFACE_CONTAINER_LOW_DARK,
    surfaceContainer = SLEEPY_SURFACE_CONTAINER_DARK,
    surfaceContainerHigh = SLEEPY_SURFACE_CONTAINER_HIGH_DARK,
    surfaceContainerHighest = SLEEPY_SURFACE_CONTAINER_HIGHEST_DARK,
    outline = SLEEPY_OUTLINE,
    outlineVariant = SLEEPY_OUTLINE_VARIANT,
    error = SLEEPY_ERROR,
    onError = SLEEPY_ON_ERROR,
    errorContainer = SLEEPY_ERROR_CONTAINER,
    onErrorContainer = SLEEPY_ON_ERROR_CONTAINER
)

/**
 * The light scheme, built with `lightColorScheme`. It keeps the purple seed and inverts every
 * tone, so it is a light theme rather than the dark palette with a light label.
 */
private val LIGHT_COLORS = lightColorScheme(
    primary = SLEEPY_LIGHT_PRIMARY,
    onPrimary = SLEEPY_LIGHT_ON_PRIMARY,
    primaryContainer = SLEEPY_LIGHT_PRIMARY_CONTAINER,
    onPrimaryContainer = SLEEPY_LIGHT_ON_PRIMARY_CONTAINER,
    background = SLEEPY_LIGHT_SURFACE,
    onBackground = SLEEPY_LIGHT_ON_SURFACE,
    surface = SLEEPY_LIGHT_SURFACE,
    onSurface = SLEEPY_LIGHT_ON_SURFACE,
    surfaceVariant = SLEEPY_LIGHT_SURFACE_VARIANT,
    onSurfaceVariant = SLEEPY_LIGHT_ON_SURFACE_VARIANT,
    surfaceContainerLowest = SLEEPY_LIGHT_SURFACE_CONTAINER_LOWEST,
    surfaceContainerLow = SLEEPY_LIGHT_SURFACE_CONTAINER_LOW,
    surfaceContainer = SLEEPY_LIGHT_SURFACE_CONTAINER,
    surfaceContainerHigh = SLEEPY_LIGHT_SURFACE_CONTAINER_HIGH,
    surfaceContainerHighest = SLEEPY_LIGHT_SURFACE_CONTAINER_HIGHEST,
    outline = SLEEPY_LIGHT_OUTLINE,
    outlineVariant = SLEEPY_LIGHT_OUTLINE_VARIANT,
    error = SLEEPY_LIGHT_ERROR,
    onError = SLEEPY_LIGHT_ON_ERROR,
    errorContainer = SLEEPY_LIGHT_ERROR_CONTAINER,
    onErrorContainer = SLEEPY_LIGHT_ON_ERROR_CONTAINER
)

/**
 * Audit accents that fall outside the tonal palette.
 *
 * Status in this app is carried by an icon and a word as well as a color, so these are emphasis
 * only and are not the sole signal.
 */
@Immutable
data class SleepyStatusColors(
    val success: Color,
    val warning: Color
)

private val DARK_STATUS_COLORS = SleepyStatusColors(success = SLEEPY_SUCCESS, warning = SLEEPY_WARNING)

private val LIGHT_STATUS_COLORS = SleepyStatusColors(
    success = SLEEPY_LIGHT_SUCCESS,
    warning = SLEEPY_LIGHT_WARNING
)

private val LocalStatusColors = staticCompositionLocalOf { DARK_STATUS_COLORS }

/** Audit accents for the active color scheme, read as `MaterialTheme.statusColors`. */
val MaterialTheme.statusColors: SleepyStatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

/**
 * Applies the sleepy design system.
 *
 * The status bar and navigation bar icon colors follow the system theme through the edge-to-edge
 * setup in the host activity.
 *
 * @param darkTheme If true, builds the dark scheme. If false, builds the light scheme. Defaults
 *   to the system setting.
 * @param dynamicColor If true, derives colors from the device wallpaper on Android 12 and later.
 *   If false, or on earlier versions, uses the fixed sleepy scheme. Defaults to true.
 * @param content The composable content that is drawn inside the theme.
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
        darkTheme -> DARK_COLORS
        else -> LIGHT_COLORS
    }
    val statusColors = if (darkTheme) DARK_STATUS_COLORS else LIGHT_STATUS_COLORS

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SLEEPY_TYPOGRAPHY,
            shapes = SLEEPY_SHAPES,
            content = content
        )
    }
}
