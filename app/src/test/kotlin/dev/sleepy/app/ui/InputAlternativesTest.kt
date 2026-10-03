package dev.sleepy.app.ui

import dev.sleepy.app.testing.source
import dev.sleepy.app.testing.uiSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Input never requires a drag, credentials, or entering a value the app already knows.
 *
 * The UI has no drag-only interaction and no credential field, and the clone name is prefilled
 * from the source. Each check reads the source that would have to change for the property to
 * break.
 */
class InputAlternativesTest {

    /** The app has no dragging movement: no gesture-only path exists. */
    @Test
    fun nothingInTheUiDependsOnDragging() {
        val dragTokens = listOf(
            "detectDragGestures",
            "detectHorizontalDragGestures",
            "detectVerticalDragGestures",
            "AnchoredDraggable",
            "swipeable(",
            "rememberSwipeToDismissBoxState"
        )
        val offenders = uiSources().flatMap { file ->
            dragTokens.filter { token -> file.readText().contains(token) }
                .map { "${file.name}: $it" }
        }
        assertTrue("a drag-only interaction was added: $offenders", offenders.isEmpty())
    }

    /** The app asks for no authentication, so no cognitive function test is required. */
    @Test
    fun theAppAsksForNoCredentials() {
        val credentialTokens = listOf(
            "PasswordVisualTransformation",
            "KeyboardType.Password",
            "KeyboardType.Email",
            "AutofillType",
            "Credentials"
        )
        val offenders = uiSources().flatMap { file ->
            credentialTokens.filter { token -> file.readText().contains(token) }
                .map { "${file.name}: $it" }
        }
        assertTrue("a credential field was added: $offenders", offenders.isEmpty())
    }

    /** The clone name is prefilled from the source rather than requested again. */
    @Test
    fun theCloneNameIsPrefilled() {
        val viewModel = source("app/src/main/kotlin/dev/sleepy/app/viewmodel/PatchViewModel.kt")
        assertTrue(
            "the clone name is not prefilled from the source",
            viewModel.contains("\${it.packageName}.sleepy")
        )
        assertTrue(
            "switching clone mode on does not fill a blank name",
            viewModel.contains("setCloneMode") &&
                viewModel.contains("if (enabled && _customPackageName.value.isBlank())")
        )
    }
}
