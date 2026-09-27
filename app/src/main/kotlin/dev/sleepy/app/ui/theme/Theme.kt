package dev.sleepy.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = SleepyPurpleLight,
    onPrimary = SleepyPurpleDark,
    primaryContainer = SleepyPurpleContainer,
    onPrimaryContainer = SleepyPurpleLight,
    surface = SleepySurface,
    onSurface = SleepyOnSurface,
    surfaceVariant = SleepySurfaceVariant,
    onSurfaceVariant = SleepyOnSurfaceVariant,
    error = SleepyError,
    onError = SleepyOnError
)

private val LightColors = darkColorScheme(
    // We default to modern deep theme for consistent dark aesthetic
    primary = SleepyPurple,
    onPrimary = Color.White,
    primaryContainer = SleepyPurpleContainer,
    onPrimaryContainer = SleepyPurpleLight,
    surface = SleepySurface,
    onSurface = SleepyOnSurface,
    surfaceVariant = SleepySurfaceVariant,
    onSurfaceVariant = SleepyOnSurfaceVariant,
    error = SleepyError,
    onError = SleepyOnError
)

@Composable
fun SleepyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SleepyTypography,
        shapes = SleepyShapes,
        content = content
    )
}
