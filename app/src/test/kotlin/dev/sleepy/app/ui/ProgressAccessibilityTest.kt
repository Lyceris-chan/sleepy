package dev.sleepy.app.ui

import dev.sleepy.app.model.PatchProgress
import dev.sleepy.app.testing.source
import dev.sleepy.app.ui.state.ScrollMotion
import dev.sleepy.app.ui.state.newestStepIndex
import dev.sleepy.app.ui.state.phaseCopy
import dev.sleepy.app.ui.state.resultActionLabel
import dev.sleepy.app.ui.state.scrollMotionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the progress screen announces during a run and what it offers when the run ends.
 *
 * The live region announces a phase once: values that change on every tick stay out of the
 * announcement. The run never navigates on its own; a finished or failed run offers its result,
 * and a stopped run offers a way back to the app list.
 */
class ProgressAccessibilityTest {

    @Test
    fun aPhaseAnnouncesItsTitleAndReason() {
        val copy = phaseCopy(
            PatchProgress.Downloading(percent = 10, bytesReceived = 1024, bytesTotal = 100 * 1024)
        )
        assertEquals("Downloading the app", copy.title)
        assertEquals(
            "Downloading the app. Fetching the untouched original so every change can be traced.",
            copy.announcement
        )
    }

    /**
     * The live region announces on a stage change, not on a progress tick: two updates inside the
     * same phase share an announcement while their visible detail differs.
     */
    @Test
    fun progressWithinAPhaseKeepsTheSameAnnouncement() {
        val early = phaseCopy(
            PatchProgress.Downloading(percent = 1, bytesReceived = 0, bytesTotal = 2 * 1024 * 1024)
        )
        val late = phaseCopy(
            PatchProgress.Downloading(
                percent = 99,
                bytesReceived = 2 * 1024 * 1024,
                bytesTotal = 2 * 1024 * 1024
            )
        )
        assertEquals(early.announcement, late.announcement)
        assertNotEquals(early.detail, late.detail)

        val firstStep = phaseCopy(
            PatchProgress.Patching("Patching the manifest", current = 0, total = 10)
        )
        val lastStep = phaseCopy(
            PatchProgress.Patching("Patching the manifest", current = 9, total = 10)
        )
        assertEquals(firstStep.announcement, lastStep.announcement)
        assertNotEquals(firstStep.detail, lastStep.detail)
    }

    @Test
    fun aNewStepOrANewPhaseChangesTheAnnouncement() {
        val first = phaseCopy(PatchProgress.Patching("First patch", current = 0, total = 2))
        val second = phaseCopy(PatchProgress.Patching("Second patch", current = 1, total = 2))
        assertNotEquals(first.announcement, second.announcement)

        val decoding = phaseCopy(PatchProgress.Decoding("Reading the APK in memory"))
        val assembling = phaseCopy(PatchProgress.Assembling("Rebuilding the APK package"))
        assertNotEquals(decoding.announcement, assembling.announcement)

        // The merge phase reaches a new stage once libraries actually merge, which changes the
        // announcement.
        val checking = phaseCopy(
            PatchProgress.MergingSplits("Merging", librariesMerged = 0, abis = emptyList())
        )
        val merging = phaseCopy(
            PatchProgress.MergingSplits(
                "Merging",
                librariesMerged = 3,
                abis = listOf("arm64-v8a")
            )
        )
        assertNotEquals(checking.announcement, merging.announcement)
    }

    @Test
    fun everyPhaseReportsANonBlankAnnouncement() {
        val states = listOf(
            PatchProgress.Idle,
            PatchProgress.Downloading(percent = 0, bytesReceived = 0, bytesTotal = 0),
            PatchProgress.Decoding("Reading"),
            PatchProgress.MergingSplits("Merging", 0, emptyList()),
            PatchProgress.Patching("Patching", 0, 1),
            PatchProgress.Assembling("Rebuilding"),
            PatchProgress.Signing("Signing"),
            PatchProgress.Failed("Something broke", detail = "line one\nline two")
        )
        states.forEach { state ->
            val copy = phaseCopy(state)
            assertTrue(
                "${state::class.simpleName} has no announcement",
                copy.announcement.isNotBlank()
            )
            assertTrue(
                "${state::class.simpleName} announces a bare title",
                copy.announcement.contains(". ")
            )
        }

        // A failure is announced with its reason, not with a generic heading alone.
        val failed = phaseCopy(PatchProgress.Failed("The download did not finish."))
        assertEquals("Something went wrong. The download did not finish.", failed.announcement)
    }

    /**
     * A finished run opens the result screen and a failed one opens the failure report; a run that
     * is still going offers neither. `PatchProgress.Done` carries a `Uri`, which the JVM stub
     * cannot build, so the Done branch is pinned in the source that maps it.
     */
    @Test
    fun onlyAFinishedOrFailedRunOffersItsResult() {
        assertEquals("See what failed", resultActionLabel(PatchProgress.Failed("boom")))
        assertNull(resultActionLabel(PatchProgress.Idle))
        assertNull(resultActionLabel(PatchProgress.Decoding("Reading")))
        assertNull(resultActionLabel(PatchProgress.Signing("Signing")))

        val source = source("app/src/main/kotlin/dev/sleepy/app/ui/state/ProgressCopy.kt")
        assertTrue(
            "a finished run does not offer its result",
            source.contains("""is PatchProgress.Done -> "View the result"""")
        )
    }

    @Test
    fun theAutoScrollFollowsTheAnimationSetting() {
        assertEquals(ScrollMotion.ANIMATED, scrollMotionFor(animationsEnabled = true))
        assertEquals(ScrollMotion.IMMEDIATE, scrollMotionFor(animationsEnabled = false))

        // The screen reads the platform setting, so the mapping above is what the list follows.
        val source = source("app/src/main/kotlin/dev/sleepy/app/ui/screens/ProgressScreen.kt")
        assertTrue(
            "the auto-scroll does not read the animation setting",
            source.contains("scrollMotionFor(ValueAnimator.areAnimatorsEnabled())")
        )
        assertTrue(
            "the auto-scroll does not jump when animations are off",
            source.contains("ScrollMotion.IMMEDIATE -> listState.scrollToItem(target)")
        )
    }

    @Test
    fun theNewestStepIsTheOnlyScrollTarget() {
        assertNull(newestStepIndex(0))
        assertEquals(0, newestStepIndex(1))
        assertEquals(4, newestStepIndex(5))
    }

    /**
     * The live region wraps the title and the reason and nothing else, so the detail line and the
     * progress bar sit outside it and their updates are not announced.
     */
    @Test
    fun thePhaseCardCarriesAPoliteLiveRegionAroundTheAnnouncementOnly() {
        val source = source("app/src/main/kotlin/dev/sleepy/app/ui/screens/ProgressScreen.kt")
        val live = source.indexOf("liveRegion = LiveRegionMode.Polite")
        assertTrue("the phase card declares no live region", live >= 0)
        assertTrue(
            "the live region does not carry the announcement",
            source.indexOf("contentDescription = copy.announcement") in live until live + 300
        )
        val detail = source.indexOf("copy.detail.orEmpty()")
        val progressBar = source.indexOf("LinearProgressIndicator(", live)
        assertTrue("the detail line is not outside the live region", detail > live)
        assertTrue("the progress bar is not outside the live region", progressBar > live)
    }

    /** A run must not leave the screen on its own after a fixed wait. */
    @Test
    fun theRunNeverNavigatesByItself() {
        val source = source("app/src/main/kotlin/dev/sleepy/app/ui/screens/ProgressScreen.kt")
        assertFalse(
            "the screen still waits to navigate on its own",
            source.contains("delay(600)")
        )
        assertFalse(
            "the screen still finishes without being asked",
            source.contains("LaunchedEffect(progress)")
        )
        assertTrue(
            "the finished run does not offer its result",
            source.contains("resultActionLabel(progress)")
        )
    }

    /** After Stop, the screen shows the stopped card and a button back to the app list. */
    @Test
    fun aStoppedRunHasAWayOut() {
        val source = source("app/src/main/kotlin/dev/sleepy/app/ui/screens/ProgressScreen.kt")
        assertTrue(
            "a stopped run falls through to the phase card",
            source.contains("stopped && progress is PatchProgress.Idle")
        )
        assertTrue("the stopped card has no exit", source.contains("StoppedCard(onExit = onExit)"))

        val navigation =
            source("app/src/main/kotlin/dev/sleepy/app/ui/navigation/SleepyNavGraph.kt")
        assertTrue("the navigation does not wire the exit", navigation.contains("onExit = {"))
    }

    @Test
    fun theStepListHeadingIsAHeading() {
        val source = source("app/src/main/kotlin/dev/sleepy/app/ui/screens/ProgressScreen.kt")
        val title = source.indexOf("""text = "What changed"""")
        assertTrue("the step list has no title", title >= 0)
        assertTrue(
            "the step list title is not a heading",
            source.indexOf("semantics { heading() }", title) in title until title + 400
        )
    }

    /** The file at [relative], found upward from the working directory the tests run in. */
}
