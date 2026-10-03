package dev.sleepy.app.ui

import dev.sleepy.app.testing.source
import dev.sleepy.app.testing.uiSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interactive targets keep a component-enforced minimum size and stay clear of the keyboard.
 *
 * The Material components the screens use enforce a 48 dp interactive minimum; a raw
 * `pointerInput` target bypasses it, so none may exist. The selection screen's bottom bar
 * consumes the keyboard inset so the start button is not drawn under the keyboard.
 */
class TouchTargetsTest {

    /** The bottom bar moves above the keyboard instead of being covered by it. */
    @Test
    fun theStartButtonKeepsOutOfTheKeyboardsWay() {
        val screen = source("app/src/main/kotlin/dev/sleepy/app/ui/screens/PatchSelectScreen.kt")
        assertTrue(
            "the selection screen does not consume the keyboard inset",
            screen.contains(".imePadding()")
        )
    }

    /**
     * The screens do not build their own pointer targets. Every interactive element is a Material
     * component—Button, IconButton, TextButton, Switch, or a row with clickable or toggleable—
     * and those carry a 48 dp minimum interactive size. A raw `pointerInput` target bypasses that
     * minimum, so the check is that none exists.
     */
    @Test
    fun everyInteractiveTargetIsAMaterialComponent() {
        val offenders = uiSources()
            .filter { it.readText().contains("pointerInput(") }
            .map { it.name }
        assertTrue(
            "a raw pointer target bypasses the component minimum: $offenders",
            offenders.isEmpty()
        )
    }
}
