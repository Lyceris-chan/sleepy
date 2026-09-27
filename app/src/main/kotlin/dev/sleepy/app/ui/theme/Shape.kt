package dev.sleepy.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The Material 3 shape scale, at the values Material 3 gives those names.
 *
 * 4, 8, 12, 16 and 28 dp are not arbitrary roundings-up of each other: they are the tokens, and
 * components that do not name a shape of their own look them up by name. `CardDefaults.shape` is
 * `shapes.medium`, `AlertDialogDefaults.shape` is `shapes.extraLarge`, `ChipDefaults.shape` is
 * `shapes.small` and `OutlinedTextFieldDefaults.shape` is `shapes.extraSmall` — so a scale that
 * borrows the names and changes the values does not only restyle sleepy's own corners, it
 * restyles every one of those components too.
 *
 * It did: these were 6/12/18/26/34 dp, which drew dialogs and the cards that follow them at
 * 34 dp instead of 28, cards at 26 instead of 16, chips and text fields at 12 instead of 8, and
 * the text field's 6 dp extraSmall rather than M3's 4.
 */
val SleepyShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(12.dp),
    large      = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
