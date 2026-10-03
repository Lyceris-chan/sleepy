package dev.sleepy.app.ui

import dev.sleepy.app.testing.source
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state a screen keeps across re-entry and rotation, and the retry after a failed run.
 *
 * Re-entering the selection screen with the same source must not rebuild the selection, the
 * expanded sets and the saved confirmation must survive a rotation, and a stopped or failed run
 * must leave the screen in a state the user can act on.
 */
class ScreenStateRestorationTest {

    /**
     * Coming back to the selection screen calls `selectSource` again with the same id; the call
     * must return before it reseeds the selection, or every choice made on that screen is lost.
     */
    @Test
    fun reenteringTheSameSourceKeepsTheSelection() {
        val source = source("app/src/main/kotlin/dev/sleepy/app/viewmodel/PatchViewModel.kt")
        assertTrue(
            "selectSource rebuilds the selection for a source that is already selected",
            source.contains("if (_selectedSource.value?.id == sourceId) return")
        )
        val guard = source.indexOf("if (_selectedSource.value?.id == sourceId) return")
        val reseed = source.indexOf("_selection.value = patchSelection.with(")
        assertTrue("the guard does not precede the reseed", guard in 0 until reseed)
    }

    /** The expanded sets are a screen state, so a rotation keeps them. */
    @Test
    fun theExpandedSetsSurviveARotation() {
        val source = source("app/src/main/kotlin/dev/sleepy/app/ui/screens/PatchSelectScreen.kt")
        assertTrue(
            "the expanded set ids are not saved",
            source.contains("""var expandedSetIds by rememberSaveable(""")
        )
        assertTrue("the saved set has no saver", source.contains("stateSaver = listSaver("))
    }

    /** The confirmation that names the saved file is a screen state, so a rotation keeps it. */
    @Test
    fun theSavedConfirmationSurvivesARotation() {
        val source = source("app/src/main/kotlin/dev/sleepy/app/ui/screens/ResultScreen.kt")
        assertTrue(
            "the saved confirmation is not saved",
            source.contains("var savedMessage by rememberSaveable")
        )
    }

    /** A stopped run must not leave the progress screen on "Getting ready". */
    @Test
    fun aStoppedRunIsRememberedAsStopped() {
        val source = source("app/src/main/kotlin/dev/sleepy/app/viewmodel/PatchViewModel.kt")
        assertTrue(
            "the ViewModel has no stopped flag",
            source.contains("val stopped: StateFlow<Boolean>")
        )
        val cancel = source.indexOf("fun cancel()")
        assertTrue("the cancel call does not mark the run stopped", cancel >= 0)
        assertTrue(
            "the stopped flag is not set when the run is canceled",
            source.indexOf("_stopped.value = true", cancel) in cancel until cancel + 200
        )
        val start = source.indexOf("fun startPatch()")
        assertTrue(
            "the stopped flag is not cleared when a run starts",
            source.indexOf("_stopped.value = false", start) in start..(source.length)
        )
    }

    /** A failed run offers the retry, and the retry starts a new run on the same selection. */
    @Test
    fun aFailedRunOffersARetry() {
        val screen = source("app/src/main/kotlin/dev/sleepy/app/ui/screens/ResultScreen.kt")
        assertTrue("the screen reads no retry offer", screen.contains("presentation.offersRetry"))
        assertTrue("the retry button is missing", screen.contains("""Text("Try again""""))

        val navigation =
            source("app/src/main/kotlin/dev/sleepy/app/ui/navigation/SleepyNavGraph.kt")
        val retry = navigation.indexOf("onRetry = {")
        assertTrue("the navigation does not wire the retry", retry >= 0)
        val start = navigation.indexOf("patchViewModel.startPatch()", retry)
        val navigate = navigation.indexOf("navController.navigate(Screen.Progress.route)", retry)
        assertTrue("the retry does not start a run", start in (retry + 1) until navigate)
    }

    /** The file at [relative], found upward from the working directory the tests run in. */
}
