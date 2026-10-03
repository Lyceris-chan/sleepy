package dev.sleepy.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.sleepy.app.ui.theme.SLEEPY_SHAPES
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shape scale uses the Material 3 token names with their Material 3 values.
 *
 * Components resolve tokens by name—`CardDefaults.shape` is `shapes.medium`,
 * `AlertDialogDefaults.shape` is `shapes.extraLarge`—so assigning a non-Material value to a
 * token silently changes every component that reads it. Each step is pinned to its 4/8/12/16/28
 * dp value.
 */
class ShapeScaleTest {

    @Test
    fun theShapeScaleIsTheMaterialTokensAtTheirOwnValues() {
        assertEquals(
            "extraSmall is M3's 4 dp token",
            RoundedCornerShape(4.dp),
            SLEEPY_SHAPES.extraSmall
        )
        assertEquals("small is M3's 8 dp token", RoundedCornerShape(8.dp), SLEEPY_SHAPES.small)
        assertEquals("medium is M3's 12 dp token", RoundedCornerShape(12.dp), SLEEPY_SHAPES.medium)
        assertEquals("large is M3's 16 dp token", RoundedCornerShape(16.dp), SLEEPY_SHAPES.large)
        assertEquals(
            "extraLarge is M3's 28 dp token",
            RoundedCornerShape(28.dp),
            SLEEPY_SHAPES.extraLarge
        )
    }
}
