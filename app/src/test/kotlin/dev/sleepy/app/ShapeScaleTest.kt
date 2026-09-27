package dev.sleepy.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.sleepy.app.ui.theme.SleepyShapes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shape scale uses Material 3's token names, so it has to use Material 3's token values.
 *
 * The names are not the app's own vocabulary: `CardDefaults.shape` is `shapes.medium`,
 * `AlertDialogDefaults.shape` is `shapes.extraLarge`, `ChipDefaults.shape` is `shapes.small` and
 * `OutlinedTextFieldDefaults.shape` is `shapes.extraSmall`. A scale that renames its steps —
 * 6/12/18/26/34 dp, which is what these were — therefore changes components that were never
 * asked to change, and every one of those changes is invisible at the call site, because the
 * component reads the token rather than naming a corner.
 */
class ShapeScaleTest {

    @Test
    fun theShapeScaleIsTheMaterialTokensAtTheirOwnValues() {
        assertEquals("extraSmall is M3's 4 dp token", RoundedCornerShape(4.dp), SleepyShapes.extraSmall)
        assertEquals("small is M3's 8 dp token", RoundedCornerShape(8.dp), SleepyShapes.small)
        assertEquals("medium is M3's 12 dp token", RoundedCornerShape(12.dp), SleepyShapes.medium)
        assertEquals("large is M3's 16 dp token", RoundedCornerShape(16.dp), SleepyShapes.large)
        assertEquals("extraLarge is M3's 28 dp token", RoundedCornerShape(28.dp), SleepyShapes.extraLarge)
    }
}
