package dev.sleepy.app.ui

import androidx.compose.ui.graphics.Color
import dev.sleepy.app.ui.theme.SLEEPY_ERROR_CONTAINER
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_ERROR_CONTAINER
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_ON_ERROR_CONTAINER
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_ON_SURFACE_VARIANT
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_OUTLINE
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_OUTLINE_VARIANT
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_PRIMARY
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_SUCCESS
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_SURFACE
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_SURFACE_CONTAINER
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_SURFACE_CONTAINER_HIGH
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_SURFACE_CONTAINER_HIGHEST
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_SURFACE_CONTAINER_LOW
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_SURFACE_CONTAINER_LOWEST
import dev.sleepy.app.ui.theme.SLEEPY_LIGHT_SURFACE_VARIANT
import dev.sleepy.app.ui.theme.SLEEPY_ON_ERROR_CONTAINER
import dev.sleepy.app.ui.theme.SLEEPY_ON_SURFACE_VARIANT
import dev.sleepy.app.ui.theme.SLEEPY_OUTLINE
import dev.sleepy.app.ui.theme.SLEEPY_OUTLINE_VARIANT
import dev.sleepy.app.ui.theme.SLEEPY_PURPLE_LIGHT
import dev.sleepy.app.ui.theme.SLEEPY_SUCCESS
import dev.sleepy.app.ui.theme.SLEEPY_SURFACE
import dev.sleepy.app.ui.theme.SLEEPY_SURFACE_CONTAINER_DARK
import dev.sleepy.app.ui.theme.SLEEPY_SURFACE_CONTAINER_HIGH_DARK
import dev.sleepy.app.ui.theme.SLEEPY_SURFACE_CONTAINER_HIGHEST_DARK
import dev.sleepy.app.ui.theme.SLEEPY_SURFACE_CONTAINER_LOW_DARK
import dev.sleepy.app.ui.theme.SLEEPY_SURFACE_CONTAINER_LOWEST_DARK
import dev.sleepy.app.ui.theme.SLEEPY_SURFACE_VARIANT
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast of the outline, divider, and accent colors against the surfaces they are drawn on.
 *
 * `colorScheme.outline` is the only boundary of an unchecked `Switch` and an `OutlinedTextField`,
 * so it clears the non-text contrast floor on every surface of both schemes; the success accent
 * carries body-size label text and clears the body-text floor. The ratios are computed here, and
 * the APCA reference vectors pin the implementation to published measurements.
 */
class OutlineContrastTest {

    @Test
    fun theDarkOutlineIsLegibleOnEverySurfaceOfTheDarkScheme() {
        assertAtLeast3To1(SLEEPY_OUTLINE, SLEEPY_SURFACE, "the dark outline on SLEEPY_SURFACE")
        assertAtLeast3To1(
            SLEEPY_OUTLINE,
            SLEEPY_SURFACE_VARIANT,
            "the dark outline on SLEEPY_SURFACE_VARIANT"
        )
        assertAtLeast3To1(
            SLEEPY_OUTLINE,
            SLEEPY_SURFACE_CONTAINER_LOWEST_DARK,
            "on the lowest container"
        )
        assertAtLeast3To1(SLEEPY_OUTLINE, SLEEPY_SURFACE_CONTAINER_LOW_DARK, "on a low container")
        assertAtLeast3To1(SLEEPY_OUTLINE, SLEEPY_SURFACE_CONTAINER_DARK, "on a container")
        assertAtLeast3To1(SLEEPY_OUTLINE, SLEEPY_SURFACE_CONTAINER_HIGH_DARK, "on a high container")
        assertAtLeast3To1(
            SLEEPY_OUTLINE,
            SLEEPY_SURFACE_CONTAINER_HIGHEST_DARK,
            "on the highest container"
        )
    }

    @Test
    fun theLightOutlineIsLegibleOnEverySurfaceOfTheLightScheme() {
        assertAtLeast3To1(
            SLEEPY_LIGHT_OUTLINE,
            SLEEPY_LIGHT_SURFACE,
            "the light outline on SLEEPY_LIGHT_SURFACE"
        )
        assertAtLeast3To1(
            SLEEPY_LIGHT_OUTLINE,
            SLEEPY_LIGHT_SURFACE_VARIANT,
            "on SLEEPY_LIGHT_SURFACE_VARIANT"
        )
        assertAtLeast3To1(
            SLEEPY_LIGHT_OUTLINE,
            SLEEPY_LIGHT_SURFACE_CONTAINER_LOWEST,
            "on the lowest container"
        )
        assertAtLeast3To1(SLEEPY_LIGHT_OUTLINE, SLEEPY_LIGHT_SURFACE_CONTAINER_LOW, "on a low container")
        assertAtLeast3To1(SLEEPY_LIGHT_OUTLINE, SLEEPY_LIGHT_SURFACE_CONTAINER, "on a container")
        assertAtLeast3To1(
            SLEEPY_LIGHT_OUTLINE,
            SLEEPY_LIGHT_SURFACE_CONTAINER_HIGH,
            "on a high container"
        )
        assertAtLeast3To1(
            SLEEPY_LIGHT_OUTLINE,
            SLEEPY_LIGHT_SURFACE_CONTAINER_HIGHEST,
            "on the highest container"
        )
    }

    @Test
    fun theApcaReferenceVectorsMatchThePublishedMeasurements() {
        // #8A8599 and #4EBA6F are the pre-correction values the audit measured; #CBC4D8,
        // #7A757F and #146C2E are palette colors the audit measured with the algorithm below.
        assertLc(+106.0, Color(0xFF000000), Color(0xFFFFFFFF), "#000000 on #FFFFFF")
        assertLc(-107.9, Color(0xFFFFFFFF), Color(0xFF000000), "#FFFFFF on #000000")
        assertLc(+63.1, Color(0xFF888888), Color(0xFFFFFFFF), "#888888 on #FFFFFF")
        assertLc(-37.9, Color(0xFF8A8599), Color(0xFF13111A), "#8A8599 on #13111A")
        assertLc(-53.5, Color(0xFF4EBA6F), Color(0xFF13111A), "#4EBA6F on #13111A")
        assertLc(-72.1, Color(0xFFCBC4D8), Color(0xFF13111A), "#CBC4D8 on #13111A")
        assertLc(+67.5, Color(0xFF7A757F), Color(0xFFFDF7FF), "#7A757F on #FDF7FF")
        assertLc(+78.3, Color(0xFF146C2E), Color(0xFFFDF7FF), "#146C2E on #FDF7FF")
        // #35313F, the pre-correction divider, falls inside the 0.1.9 low-contrast clamp, which
        // returns 0.0 where the audit reported -3.7, so 0.0 is the value the 0.1.9 rule produces.
        assertLc(0.0, Color(0xFF35313F), Color(0xFF13111A), "#35313F on #13111A")
    }

    @Test
    fun theDarkOutlineAndDividerClearTheApcaNonTextFloorOnEveryDarkSurface() {
        val outlines = listOf(
            "SLEEPY_OUTLINE" to SLEEPY_OUTLINE,
            "SLEEPY_OUTLINE_VARIANT" to SLEEPY_OUTLINE_VARIANT
        )
        for ((name, outline) in outlines) {
            for ((surfaceName, surface) in darkSurfaces) {
                assertAtLeastLc(45.0, outline, surface, "$name on $surfaceName")
            }
        }
    }

    @Test
    fun theDarkSuccessAccentClearsTheApcaBodyTextFloorOnEveryDarkSurface() {
        for ((surfaceName, surface) in darkSurfaces) {
            assertAtLeastLc(60.0, SLEEPY_SUCCESS, surface, "SLEEPY_SUCCESS on $surfaceName")
        }
    }

    @Test
    fun theLightOutlineClearsTheApcaNonTextFloorOnEveryLightSurface() {
        for ((surfaceName, surface) in lightSurfaces) {
            assertAtLeastLc(45.0, SLEEPY_LIGHT_OUTLINE, surface, "SLEEPY_LIGHT_OUTLINE on $surfaceName")
        }
    }

    @Test
    fun theLightOutlineVariantIsLegibleOnEverySurfaceOfTheLightScheme() {
        for ((surfaceName, surface) in lightSurfaces) {
            assertAtLeast3To1(
                SLEEPY_LIGHT_OUTLINE_VARIANT,
                surface,
                "SLEEPY_LIGHT_OUTLINE_VARIANT on $surfaceName"
            )
        }
    }

    @Test
    fun theLightOutlineVariantClearsTheApcaNonTextFloorOnEveryLightSurface() {
        for ((surfaceName, surface) in lightSurfaces) {
            assertAtLeastLc(
                45.0,
                SLEEPY_LIGHT_OUTLINE_VARIANT,
                surface,
                "SLEEPY_LIGHT_OUTLINE_VARIANT on $surfaceName"
            )
        }
    }

    /**
     * The failure card's text, in both schemes.
     *
     * The card fills with `errorContainer` and draws its heading in `primary` and its body in
     * `onSurfaceVariant` as well as the failure note in `onErrorContainer`, so all three pairs
     * have to clear the audit's body-text floor.
     */
    @Test
    fun theErrorContainerTextClearsTheApcaBodyTextFloorOnBothSchemes() {
        val darkPairs = listOf(
            "primary on the dark error container" to (SLEEPY_PURPLE_LIGHT to SLEEPY_ERROR_CONTAINER),
            "onSurfaceVariant on the dark error container" to
                (SLEEPY_ON_SURFACE_VARIANT to SLEEPY_ERROR_CONTAINER),
            "onErrorContainer on the dark error container" to
                (SLEEPY_ON_ERROR_CONTAINER to SLEEPY_ERROR_CONTAINER)
        )
        val lightPairs = listOf(
            "primary on the light error container" to
                (SLEEPY_LIGHT_PRIMARY to SLEEPY_LIGHT_ERROR_CONTAINER),
            "onSurfaceVariant on the light error container" to
                (SLEEPY_LIGHT_ON_SURFACE_VARIANT to SLEEPY_LIGHT_ERROR_CONTAINER),
            "onErrorContainer on the light error container" to
                (SLEEPY_LIGHT_ON_ERROR_CONTAINER to SLEEPY_LIGHT_ERROR_CONTAINER)
        )
        for ((what, pair) in darkPairs + lightPairs) {
            val (text, container) = pair
            assertAtLeastLc(60.0, text, container, what)
            assertAtLeast4Point5To1(text, container, what)
        }
    }

    /**
     * The values the corrected colors measure, pinned so a change to either one is visible here
     * rather than only in a re-audit.
     */
    @Test
    fun theCorrectedDividerAndErrorContainerMeasureTheirRecordedValues() {
        assertLc(
            +51.1,
            SLEEPY_LIGHT_OUTLINE_VARIANT,
            SLEEPY_LIGHT_SURFACE_CONTAINER_HIGHEST,
            "SLEEPY_LIGHT_OUTLINE_VARIANT on the highest light container"
        )
        assertLc(
            +64.1,
            SLEEPY_LIGHT_OUTLINE_VARIANT,
            SLEEPY_LIGHT_SURFACE,
            "SLEEPY_LIGHT_OUTLINE_VARIANT on SLEEPY_LIGHT_SURFACE"
        )
        assertLc(
            -61.4,
            SLEEPY_PURPLE_LIGHT,
            SLEEPY_ERROR_CONTAINER,
            "primary on SLEEPY_ERROR_CONTAINER"
        )
        assertLc(
            -61.8,
            SLEEPY_ON_SURFACE_VARIANT,
            SLEEPY_ERROR_CONTAINER,
            "onSurfaceVariant on SLEEPY_ERROR_CONTAINER"
        )
        assertLc(
            -79.4,
            SLEEPY_ON_ERROR_CONTAINER,
            SLEEPY_ERROR_CONTAINER,
            "onErrorContainer on SLEEPY_ERROR_CONTAINER"
        )
        assertLc(
            +66.6,
            SLEEPY_LIGHT_PRIMARY,
            SLEEPY_LIGHT_ERROR_CONTAINER,
            "primary on SLEEPY_LIGHT_ERROR_CONTAINER"
        )
        assertLc(
            +75.7,
            SLEEPY_LIGHT_ON_SURFACE_VARIANT,
            SLEEPY_LIGHT_ERROR_CONTAINER,
            "onSurfaceVariant on SLEEPY_LIGHT_ERROR_CONTAINER"
        )
        assertLc(
            +86.7,
            SLEEPY_LIGHT_ON_ERROR_CONTAINER,
            SLEEPY_LIGHT_ERROR_CONTAINER,
            "onErrorContainer on SLEEPY_LIGHT_ERROR_CONTAINER"
        )
    }

    @Test
    fun theLightSuccessAccentClearsTheApcaBodyTextFloorOnEveryLightSurface() {
        for ((surfaceName, surface) in lightSurfaces) {
            assertAtLeastLc(60.0, SLEEPY_LIGHT_SUCCESS, surface, "SLEEPY_LIGHT_SUCCESS on $surfaceName")
        }
    }

    private fun assertAtLeast3To1(foreground: Color, background: Color, what: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$what measures ${"%.2f".format(ratio)}:1, " +
                "under the 3:1 WCAG 2.2 asks of a non-text component",
            ratio >= 3.0
        )
    }

    private fun assertAtLeast4Point5To1(foreground: Color, background: Color, what: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$what measures ${"%.2f".format(ratio)}:1, " +
                "under the 4.5:1 WCAG 2.2 asks of body text",
            ratio >= 4.5
        )
    }

    /** WCAG 2.2's contrast ratio: relative luminance, lighter over darker, 0.05 added to both. */
    private fun contrastRatio(a: Color, b: Color): Double {
        val luminanceA = relativeLuminance(a)
        val luminanceB = relativeLuminance(b)
        return (max(luminanceA, luminanceB) + 0.05) / (min(luminanceA, luminanceB) + 0.05)
    }

    /** WCAG 2.2's relative luminance for sRGB, which is where these colors are defined. */
    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) +
            0.0722 * linear(color.blue)
    }

    /** The dark surfaces a component is drawn on, from the base tone to the highest container. */
    private val darkSurfaces = listOf(
        "SLEEPY_SURFACE" to SLEEPY_SURFACE,
        "SLEEPY_SURFACE_VARIANT" to SLEEPY_SURFACE_VARIANT,
        "SLEEPY_SURFACE_CONTAINER_LOWEST_DARK" to SLEEPY_SURFACE_CONTAINER_LOWEST_DARK,
        "SLEEPY_SURFACE_CONTAINER_LOW_DARK" to SLEEPY_SURFACE_CONTAINER_LOW_DARK,
        "SLEEPY_SURFACE_CONTAINER_HIGH_DARK" to SLEEPY_SURFACE_CONTAINER_HIGH_DARK,
        "SLEEPY_SURFACE_CONTAINER_HIGHEST_DARK" to SLEEPY_SURFACE_CONTAINER_HIGHEST_DARK
    )

    /** The light surfaces a component is drawn on, from the base tone to the highest container. */
    private val lightSurfaces = listOf(
        "SLEEPY_LIGHT_SURFACE" to SLEEPY_LIGHT_SURFACE,
        "SLEEPY_LIGHT_SURFACE_CONTAINER_LOWEST" to SLEEPY_LIGHT_SURFACE_CONTAINER_LOWEST,
        "SLEEPY_LIGHT_SURFACE_CONTAINER_LOW" to SLEEPY_LIGHT_SURFACE_CONTAINER_LOW,
        "SLEEPY_LIGHT_SURFACE_CONTAINER" to SLEEPY_LIGHT_SURFACE_CONTAINER,
        "SLEEPY_LIGHT_SURFACE_CONTAINER_HIGH" to SLEEPY_LIGHT_SURFACE_CONTAINER_HIGH,
        "SLEEPY_LIGHT_SURFACE_CONTAINER_HIGHEST" to SLEEPY_LIGHT_SURFACE_CONTAINER_HIGHEST
    )

    /** Compares an Lc value with a published measurement, within 0.1 Lc. */
    private fun assertLc(expected: Double, text: Color, background: Color, what: String) {
        val measured = apcaLc(text, background)
        assertEquals(
            "$what measures ${"%.2f".format(measured)} Lc, not the expected " +
                "${"%.2f".format(expected)} Lc",
            expected,
            measured,
            0.1
        )
    }

    /** Compares the magnitude of an Lc value with an APCA floor. */
    private fun assertAtLeastLc(floor: Double, foreground: Color, background: Color, what: String) {
        val lc = apcaLc(foreground, background)
        assertTrue(
            "$what measures ${"%.2f".format(lc)} Lc, under the |Lc| $floor floor of " +
                "WCAG 3.0's draft",
            abs(lc) >= floor
        )
    }

    /**
     * APCA-W3 0.1.9 contrast, reported in Lc. The sign carries the polarity: a negative Lc is
     * light text on a dark background, a positive Lc is dark text on a light background.
     */
    private fun apcaLc(text: Color, background: Color): Double {
        val blockThreshold = 0.022
        val blockClamp = 1.414
        val normalBackgroundPower = 0.56
        val normalTextPower = 0.57
        val reverseBackgroundPower = 0.65
        val reverseTextPower = 0.62
        val blackOnWhiteScale = 1.14
        val whiteOnBlackScale = 1.14
        val blackOnWhiteOffset = 0.027
        val whiteOnBlackOffset = 0.027
        val minimumDifference = 0.0005
        val lowContrastClip = 0.1

        // APCA-W3's black level clamp: a value at or below the threshold gains a power-curve
        // offset.
        fun softClamp(y: Double): Double =
            if (y > blockThreshold) y else y + (blockThreshold - y).pow(blockClamp)

        val textY = softClamp(sRgbToY(text))
        val backgroundY = softClamp(sRgbToY(background))
        if (abs(backgroundY - textY) < minimumDifference) return 0.0

        val sapc: Double
        val clipped: Double
        if (backgroundY > textY) {
            sapc = (backgroundY.pow(normalBackgroundPower) - textY.pow(normalTextPower)) *
                blackOnWhiteScale
            clipped = if (sapc < lowContrastClip) 0.0 else sapc - blackOnWhiteOffset
        } else {
            sapc = (backgroundY.pow(reverseBackgroundPower) - textY.pow(reverseTextPower)) *
                whiteOnBlackScale
            clipped = if (sapc > -lowContrastClip) 0.0 else sapc + whiteOnBlackOffset
        }
        return clipped * 100.0
    }

    /** APCA-W3's sRGB luminance: each 8-bit channel over 255, linearized with a 2.4 power. */
    private fun sRgbToY(color: Color): Double =
        0.2126729 * linearChannel(color.red) +
            0.7151522 * linearChannel(color.green) +
            0.0721750 * linearChannel(color.blue)

    private fun linearChannel(channel: Float): Double = channelFraction(channel).pow(2.4)

    /** The channel as a 0..1 fraction of its 8-bit value, which is how the tables define it. */
    private fun channelFraction(channel: Float): Double = (channel * 255.0f).roundToInt() / 255.0
}
