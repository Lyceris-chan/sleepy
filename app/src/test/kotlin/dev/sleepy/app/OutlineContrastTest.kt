package dev.sleepy.app

import androidx.compose.ui.graphics.Color
import dev.sleepy.app.ui.theme.SleepyLightOutline
import dev.sleepy.app.ui.theme.SleepyLightSurface
import dev.sleepy.app.ui.theme.SleepyLightSurfaceContainer
import dev.sleepy.app.ui.theme.SleepyLightSurfaceContainerHigh
import dev.sleepy.app.ui.theme.SleepyLightSurfaceContainerHighest
import dev.sleepy.app.ui.theme.SleepyLightSurfaceContainerLow
import dev.sleepy.app.ui.theme.SleepyLightSurfaceContainerLowest
import dev.sleepy.app.ui.theme.SleepyLightSurfaceVariant
import dev.sleepy.app.ui.theme.SleepyOutline
import dev.sleepy.app.ui.theme.SleepySurface
import dev.sleepy.app.ui.theme.SleepySurfaceContainerDark
import dev.sleepy.app.ui.theme.SleepySurfaceContainerHighDark
import dev.sleepy.app.ui.theme.SleepySurfaceContainerHighestDark
import dev.sleepy.app.ui.theme.SleepySurfaceContainerLowDark
import dev.sleepy.app.ui.theme.SleepySurfaceContainerLowestDark
import dev.sleepy.app.ui.theme.SleepySurfaceVariant
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The outline colour is legible against the surfaces it is drawn on.
 *
 * `colorScheme.outline` is what M3 borders an unchecked `Switch` and an `OutlinedTextField`
 * with — controls that carry no fill, so the outline is the *only* thing marking where they are.
 * WCAG 2.2's SC 1.4.11 asks 3:1 of a non-text component against its surroundings, and the dark
 * scheme's outline measured 2.01:1 on `SleepySurface` and 1.78:1 on the container tones: to a
 * user with low vision, an invisible control.
 *
 * The ratios are computed here rather than quoted, so the number in a comment cannot drift away
 * from the colours it describes. Both schemes are checked: only the dark one changed, and the
 * light one is now pinned too, since the same edit to a surface would break it silently.
 */
class OutlineContrastTest {

    @Test
    fun theDarkOutlineIsLegibleOnEverySurfaceOfTheDarkScheme() {
        assertAtLeast3To1(SleepyOutline, SleepySurface, "the dark outline on SleepySurface")
        assertAtLeast3To1(SleepyOutline, SleepySurfaceVariant, "the dark outline on SleepySurfaceVariant")
        assertAtLeast3To1(SleepyOutline, SleepySurfaceContainerLowestDark, "on the lowest container")
        assertAtLeast3To1(SleepyOutline, SleepySurfaceContainerLowDark, "on a low container")
        assertAtLeast3To1(SleepyOutline, SleepySurfaceContainerDark, "on a container")
        assertAtLeast3To1(SleepyOutline, SleepySurfaceContainerHighDark, "on a high container")
        assertAtLeast3To1(SleepyOutline, SleepySurfaceContainerHighestDark, "on the highest container")
    }

    @Test
    fun theLightOutlineIsLegibleOnEverySurfaceOfTheLightScheme() {
        assertAtLeast3To1(SleepyLightOutline, SleepyLightSurface, "the light outline on SleepyLightSurface")
        assertAtLeast3To1(SleepyLightOutline, SleepyLightSurfaceVariant, "on SleepyLightSurfaceVariant")
        assertAtLeast3To1(SleepyLightOutline, SleepyLightSurfaceContainerLowest, "on the lowest container")
        assertAtLeast3To1(SleepyLightOutline, SleepyLightSurfaceContainerLow, "on a low container")
        assertAtLeast3To1(SleepyLightOutline, SleepyLightSurfaceContainer, "on a container")
        assertAtLeast3To1(SleepyLightOutline, SleepyLightSurfaceContainerHigh, "on a high container")
        assertAtLeast3To1(SleepyLightOutline, SleepyLightSurfaceContainerHighest, "on the highest container")
    }

    private fun assertAtLeast3To1(foreground: Color, background: Color, what: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$what measures ${"%.2f".format(ratio)}:1, under the 3:1 WCAG 2.2 asks of a non-text component",
            ratio >= 3.0
        )
    }

    /** WCAG 2.2's contrast ratio: relative luminance, lighter over darker, 0.05 added to both. */
    private fun contrastRatio(a: Color, b: Color): Double {
        val luminanceA = relativeLuminance(a)
        val luminanceB = relativeLuminance(b)
        return (max(luminanceA, luminanceB) + 0.05) / (min(luminanceA, luminanceB) + 0.05)
    }

    /** WCAG 2.2's relative luminance for sRGB, which is where these colours are defined. */
    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
