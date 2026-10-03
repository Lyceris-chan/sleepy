package dev.sleepy.app.model

import dev.sleepy.app.ui.screens.resultPresentation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outcome a run's step log produces, and the action the result screen offers for it.
 *
 * A run that failed a step must not report success or offer its file; a skipped step is not a
 * failure; a fatal failure outranks a degradation; and only a run that did not fully succeed
 * offers a retry. The step log is the single input to all of it.
 */
class BuildOutcomeTest {

    @Test
    fun everyStepThatLandedIsASuccess() {
        assertEquals(
            BuildOutcome.Success,
            BuildOutcome.of(
                listOf(
                    StepResult("Rebuilt the APK", StepStatus.OK),
                    StepResult("Signed the APK", StepStatus.OK)
                )
            )
        )
        assertEquals(
            "a run with no steps has nothing that failed",
            BuildOutcome.Success,
            BuildOutcome.of(emptyList())
        )
    }

    /**
     * A SKIP records that the build made the step unnecessary and carries the reason; it is not a
     * failure, and a run whose only non-OK steps were skipped is a complete run.
     */
    @Test
    fun aSkippedStepIsNotAFailure() {
        assertEquals(
            BuildOutcome.Success,
            BuildOutcome.of(
                listOf(
                    StepResult("Rebuilt the APK", StepStatus.OK),
                    StepResult("JavaScript patches skipped", StepStatus.SKIP, "no bundle to patch")
                )
            )
        )
    }

    /**
     * A failure that leaves the file usable is a degradation: the run is not a success, and the
     * file is still offered under an outcome that records that some changes did not land.
     */
    @Test
    fun aFailedPatchThatChangedNothingLeavesTheRunIncomplete() {
        val log = listOf(
            StepResult("Rebuilt the APK", StepStatus.OK),
            StepResult(
                "target/MainActivity.smali",
                StepStatus.FAIL,
                "anchor not found",
                failureIsFatal = false
            )
        )
        assertEquals(BuildOutcome.Incomplete, BuildOutcome.of(log))

        val presentation = resultPresentation(BuildOutcome.Incomplete)
        assertTrue(
            "the file is still a build of the source, so it is still offered",
            presentation.offersArtifact
        )
        assertFalse(
            "a run with a failed step must not read as a success",
            presentation.headline == "Build Successful"
        )
    }

    /**
     * A failure that leaves the file unusable—a rename that did not read back, a signature that
     * does not verify, an archive that cannot be aligned—ends the run as a failure.
     */
    @Test
    fun aFatalFailureFailsTheRun() {
        val log = listOf(
            StepResult("Rebuilt the APK", StepStatus.OK),
            StepResult(
                "Left the resource table's package as it was",
                StepStatus.FAIL,
                "the name is too long"
            )
        )
        assertEquals(BuildOutcome.Failed, BuildOutcome.of(log))

        val presentation = resultPresentation(BuildOutcome.Failed)
        assertFalse("a failed run offers no file", presentation.offersArtifact)
        assertEquals("Build Failed", presentation.headline)
    }

    /** A fatal failure outranks a degradation, regardless of how many degradations accompany it. */
    @Test
    fun aFatalFailureOutranksADegradation() {
        val log = listOf(
            StepResult(
                "main/Thing.smali",
                StepStatus.FAIL,
                "anchor not found",
                failureIsFatal = false
            ),
            StepResult(
                "Checked the signatures on the finished APK",
                StepStatus.FAIL,
                "v2 not valid"
            )
        )
        assertEquals(BuildOutcome.Failed, BuildOutcome.of(log))
    }

    /** A run that did not finish has no outcome to show, and its screen presents a failure. */
    @Test
    fun noOutcomeIsPresentedAsAFailure() {
        val presentation = resultPresentation(null)
        assertEquals("Build Failed", presentation.headline)
        assertFalse(presentation.offersArtifact)
    }

    /**
     * A run that failed a step can be run again, so its screen offers the retry; a run whose steps
     * all landed cannot produce a different result from the same selection, so it does not.
     */
    @Test
    fun onlyARunThatDidNotFullySucceedOffersARetry() {
        assertTrue(resultPresentation(BuildOutcome.Failed).offersRetry)
        assertTrue(resultPresentation(BuildOutcome.Incomplete).offersRetry)
        assertTrue(
            "a run that did not finish can be run again",
            resultPresentation(null).offersRetry
        )
        assertFalse(resultPresentation(BuildOutcome.Success).offersRetry)
    }

    /**
     * The pipeline applies the classification before it reports Done.
     *
     * `PatchingPipeline` takes a `Context`, which a unit test on the JVM cannot supply, so the
     * gate is checked in its source: the source that has to call [BuildOutcome.of] and has to
     * take the failure branch before it builds the report. This fails if the gate is removed,
     * which is the state that allowed a run with a failed step to report success.
     */
    @Test
    fun thePipelineGatesCompletionOnTheStepLog() {
        val source = pipelineSource()
        val gate = source.indexOf("BuildOutcome.of(_stepLog.value)")
        val guard = source.indexOf("if (outcome == BuildOutcome.Failed)")
        val done = source.indexOf("PatchProgress.Done(")

        assertTrue("the pipeline does not read the step log's outcome", gate >= 0)
        assertTrue(
            "the pipeline reports Done without a failure branch before it",
            guard in (gate + 1) until done
        )
        assertTrue(
            "the failure branch does not report the failure",
            source.indexOf("PatchProgress.Failed(", guard) in guard until done
        )
        assertTrue(
            "the failure branch does not delete the file it refuses to offer",
            source.indexOf("outputFile.delete()", guard) in guard until done
        )
    }

    /** `PatchingPipeline.kt`, found upward from the working directory the tests run in. */
    private fun pipelineSource(): String {
        val workingDirectory = requireNotNull(System.getProperty("user.dir")) {
            "user.dir is not set, so PatchingPipeline.kt cannot be looked for from the working " +
                "directory"
        }
        val relative = "app/src/main/kotlin/dev/sleepy/app/engine/PatchingPipeline.kt"
        val source = generateSequence(File(workingDirectory)) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.isFile }
        return requireNotNull(source) {
            "$relative was not found above $workingDirectory"
        }.readText()
    }
}
