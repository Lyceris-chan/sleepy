package dev.sleepy.app.ui.theme

import androidx.compose.ui.graphics.Color

// M3 Expressive palette for sleepy, all derived from one purple seed so both color schemes keep
// the same identity. Dark values are listed first, then the light counterparts.

val SLEEPY_PURPLE_LIGHT     = Color(0xFFD6BAFF)
val SLEEPY_PURPLE_DARK      = Color(0xFF56119E)
val SLEEPY_PURPLE_CONTAINER = Color(0xFF38006B)

// Dark scheme.
val SLEEPY_SURFACE                        = Color(0xFF13111A)
val SLEEPY_SURFACE_VARIANT                = Color(0xFF201C2B)
val SLEEPY_ON_SURFACE                     = Color(0xFFECE6F4)
val SLEEPY_ON_SURFACE_VARIANT             = Color(0xFFCBC4D8)
val SLEEPY_SURFACE_CONTAINER_LOWEST_DARK  = Color(0xFF0D0B12)
val SLEEPY_SURFACE_CONTAINER_LOW_DARK     = Color(0xFF1A1723)
val SLEEPY_SURFACE_CONTAINER_DARK         = Color(0xFF201C2B)
val SLEEPY_SURFACE_CONTAINER_HIGH_DARK    = Color(0xFF2A2536)
val SLEEPY_SURFACE_CONTAINER_HIGHEST_DARK = Color(0xFF352F42)
// The outline is the border of components that carry no fill of their own—an unchecked
// Switch, an OutlinedTextField—so it is a non-text component, and WCAG 2.2 asks 3:1 of it
// against whatever it is drawn on (SC 1.4.11). WCAG 3.0 measures the same pair with APCA, whose
// non-text guidance sets the floor at |Lc| 45. The previous 0xFF8A8599 measured 5.25:1 but
// Lc -37.9 on SLEEPY_SURFACE, and 0xFF35313F measured Lc -3.7, so neither border met both
// requirements. These are the same hue family raised until both formulas pass on every surface
// in the scheme; OutlineContrastTest computes both and asserts both.
val SLEEPY_OUTLINE = Color(0xFFAFACB9)
// The divider tone is the lower-contrast of the two while it clears the same non-text floor: it
// reads at Lc -46.7 or better on every surface, compared with -52.6 for the preceding outline.
val SLEEPY_OUTLINE_VARIANT = Color(0xFFA4A1B0)

// Light scheme. Same hue with inverted tones: the accents are darkened and the surfaces
// lightened so text keeps its contrast when the system is not in dark mode.
val SLEEPY_LIGHT_PRIMARY                   = Color(0xFF6E3FBF)
val SLEEPY_LIGHT_ON_PRIMARY                = Color(0xFFFFFFFF)
val SLEEPY_LIGHT_PRIMARY_CONTAINER         = Color(0xFFEADDFF)
val SLEEPY_LIGHT_ON_PRIMARY_CONTAINER      = Color(0xFF21005D)
val SLEEPY_LIGHT_SURFACE                   = Color(0xFFFDF7FF)
val SLEEPY_LIGHT_ON_SURFACE                = Color(0xFF1C1B20)
val SLEEPY_LIGHT_SURFACE_VARIANT           = Color(0xFFE7E0EC)
val SLEEPY_LIGHT_ON_SURFACE_VARIANT        = Color(0xFF49454F)
val SLEEPY_LIGHT_SURFACE_CONTAINER_LOWEST  = Color(0xFFFFFFFF)
val SLEEPY_LIGHT_SURFACE_CONTAINER_LOW     = Color(0xFFF7F2FA)
val SLEEPY_LIGHT_SURFACE_CONTAINER         = Color(0xFFF1ECF5)
val SLEEPY_LIGHT_SURFACE_CONTAINER_HIGH    = Color(0xFFEBE6EF)
val SLEEPY_LIGHT_SURFACE_CONTAINER_HIGHEST = Color(0xFFE6E1E9)
val SLEEPY_LIGHT_OUTLINE                   = Color(0xFF7A757F)
// The divider tone. The Material 3 default (0xFFCAC4D0) measured Lc +13.9 and 1.32:1 on the
// darkest light surface, so a divider drawn with it was as invisible as the dark value the audit
// condemned. This tone keeps the hue family, stays lighter than the outline, and clears both the
// 3:1 of SC 1.4.11 and the audit's |Lc| 45 non-text floor on every light surface in the scheme.
// OutlineContrastTest computes both.
val SLEEPY_LIGHT_OUTLINE_VARIANT           = Color(0xFF817C8A)
val SLEEPY_LIGHT_ERROR                     = Color(0xFFB3261E)
val SLEEPY_LIGHT_ON_ERROR                  = Color(0xFFFFFFFF)

// Status accents. The dark values do not meet the contrast floor on the light surfaces, so each
// has a light counterpart; each is paired with an icon and a word in the UI.
//
// SLEEPY_SUCCESS is also label text (an audit row's verdict, for example), so it has to clear the
// APCA body-text floor of |Lc| 60 and not only the 4.5:1 that WCAG 2.2 asks of text. The previous
// 0xFF4EBA6F measured 7.64:1 but Lc -53.5 on SLEEPY_SURFACE and -49.1 on the lightest container,
// and the following green measures Lc -61.7 or better on every surface in the scheme.
val SLEEPY_SUCCESS       = Color(0xFF82CE99)
val SLEEPY_WARNING       = Color(0xFFFFB74D)
val SLEEPY_ERROR         = Color(0xFFFFB4AB)
val SLEEPY_ON_ERROR      = Color(0xFF690005)
val SLEEPY_LIGHT_SUCCESS = Color(0xFF146C2E)
val SLEEPY_LIGHT_WARNING = Color(0xFF7A5A00)

// The error-container pair, which carries the failure card. That card draws its heading and body
// in the scheme's primary and onSurfaceVariant colors rather than in onErrorContainer, and the
// Material 3 default dark container (0xFF8C1D18) left those at Lc -58.5 and -58.9, just under the
// audit's |Lc| 60 text floor. The dark container is deepened until both clear the floor with
// margin; the light pair is the Material 3 default, which clears every floor already.
// OutlineContrastTest computes both pairs.
val SLEEPY_ERROR_CONTAINER      = Color(0xFF7D1914)
val SLEEPY_ON_ERROR_CONTAINER   = Color(0xFFF9DEDC)
val SLEEPY_LIGHT_ERROR_CONTAINER    = Color(0xFFF9DEDC)
val SLEEPY_LIGHT_ON_ERROR_CONTAINER = Color(0xFF410E0B)
