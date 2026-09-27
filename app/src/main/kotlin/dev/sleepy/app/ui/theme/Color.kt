package dev.sleepy.app.ui.theme

import androidx.compose.ui.graphics.Color

// M3 Expressive palette for sleepy, all derived from one purple seed so both colour schemes
// keep the same identity. Dark values are listed first, then the light counterparts.

// Purple seed.
val SleepyPurple          = Color(0xFF9B5DE5)
val SleepyPurpleLight     = Color(0xFFD6BAFF)
val SleepyPurpleDark      = Color(0xFF56119E)
val SleepyPurpleContainer = Color(0xFF38006B)

// Dark scheme.
val SleepySurface          = Color(0xFF13111A)
val SleepySurfaceVariant   = Color(0xFF201C2B)
val SleepyOnSurface        = Color(0xFFECE6F4)
val SleepyOnSurfaceVariant = Color(0xFFCBC4D8)
val SleepySurfaceContainerLowestDark  = Color(0xFF0D0B12)
val SleepySurfaceContainerLowDark     = Color(0xFF1A1723)
val SleepySurfaceContainerDark        = Color(0xFF201C2B)
val SleepySurfaceContainerHighDark    = Color(0xFF2A2536)
val SleepySurfaceContainerHighestDark = Color(0xFF352F42)
val SleepyOutline          = Color(0xFF4A4456)
val SleepyOutlineVariant   = Color(0xFF35313F)

// Light scheme. Same hue, flipped tones: the accents are darkened and the surfaces lightened
// so text keeps its contrast when the system is not in dark mode.
val SleepyLightPrimary             = Color(0xFF6E3FBF)
val SleepyLightOnPrimary           = Color(0xFFFFFFFF)
val SleepyLightPrimaryContainer    = Color(0xFFEADDFF)
val SleepyLightOnPrimaryContainer  = Color(0xFF21005D)
val SleepyLightSurface             = Color(0xFFFDF7FF)
val SleepyLightOnSurface           = Color(0xFF1C1B20)
val SleepyLightSurfaceVariant      = Color(0xFFE7E0EC)
val SleepyLightOnSurfaceVariant    = Color(0xFF49454F)
val SleepyLightSurfaceContainerLowest  = Color(0xFFFFFFFF)
val SleepyLightSurfaceContainerLow     = Color(0xFFF7F2FA)
val SleepyLightSurfaceContainer        = Color(0xFFF1ECF5)
val SleepyLightSurfaceContainerHigh    = Color(0xFFEBE6EF)
val SleepyLightSurfaceContainerHighest = Color(0xFFE6E1E9)
val SleepyLightOutline             = Color(0xFF7A757F)
val SleepyLightOutlineVariant      = Color(0xFFCAC4D0)
val SleepyLightError               = Color(0xFFB3261E)
val SleepyLightOnError             = Color(0xFFFFFFFF)

// Status accents. The dark values are unreadable on the light surfaces, so each has a light
// counterpart; both are always paired with an icon and a word in the UI.
val SleepySuccess      = Color(0xFF4EBA6F)
val SleepyWarning      = Color(0xFFFFB74D)
val SleepyError        = Color(0xFFFFB4AB)
val SleepyOnError      = Color(0xFF690005)
val SleepyLightSuccess = Color(0xFF146C2E)
val SleepyLightWarning = Color(0xFF7A5A00)
