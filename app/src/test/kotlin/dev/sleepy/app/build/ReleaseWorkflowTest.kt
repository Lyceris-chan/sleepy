package dev.sleepy.app.build

import dev.sleepy.app.testing.runBlocks
import dev.sleepy.app.testing.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release workflow passes values to its scripts as environment data, runs the build and the
 * tests side by side, and lets only the publishing job write.
 *
 * A `run:` block is executed as script, so a workflow input or a git ref spliced into one becomes
 * script the runner executes. The tests check that no step body interpolates a value and that the
 * tag reaches the signing job's script through `env:`.
 *
 * The job graph is checked too. The build must not wait for the test result—waiting would put the
 * suite on the critical path—while the release must wait for both, because a build that succeeds
 * while the tests fail must publish nothing. The job that holds the signing key and the job that
 * publishes are separate, and only the publishing one may write.
 */
class ReleaseWorkflowTest {

    @Test
    fun noStepBodyCarriesAnInterpolatedValue() {
        val blocks = runBlocks(workflow())
        assertTrue("the workflow has no run blocks, so this test checked nothing", blocks.size >= 4)

        for ((index, block) in blocks.withIndex()) {
            assertFalse(
                "run block ${index + 1} interpolates a value into script text: " +
                    "${block.lineSequence().first()}",
                block.contains("\${{")
            )
        }
    }

    @Test
    fun theTagReachesTheScriptAsAnEnvironmentValue() {
        val workflow = workflow()

        assertTrue(
            "the tag input has to be handed over as a value",
            workflow.contains("INPUT_TAG: \${{ github.event.inputs.tag }}")
        )
        assertTrue(
            "the ref name is the fallback",
            workflow.contains("REF_NAME: \${{ github.ref_name }}")
        )
        assertTrue(
            "the script has to read the environment, quoted",
            workflow.contains("TAG=\"\${INPUT_TAG:-\$REF_NAME}\"")
        )
    }

    @Test
    fun theWorkflowItselfHoldsNoWritePermission() {
        val header = workflow().lines().takeWhile { !it.startsWith("jobs:") }.joinToString("\n")

        assertTrue(
            "the top level has to grant read access",
            header.contains("permissions:\n  contents: read")
        )
        assertFalse(
            "no write permission may be granted for the whole workflow",
            Regex("(?m)^\\s*(contents|pull-requests):\\s*write").containsMatchIn(header)
        )
    }

    @Test
    fun onlyThePublishingJobCanWrite() {
        val workflow = workflow()

        assertEquals(
            "exactly one job grants contents: write",
            1,
            Regex("contents: write").findAll(workflow).count()
        )
        assertTrue(
            "the write permission has to sit in a job-level permissions block",
            workflow.contains("      contents: write")
        )
    }

    @Test
    fun theBuildRunsBesideTheTestsAndTheReleaseWaitsForBoth() {
        val workflow = workflow()
        val buildJob = workflow.substringAfter("\n  build:").substringBefore("\n  publish:")

        assertFalse(
            "the build job must not wait for the test job: that is the serialisation this " +
                "workflow exists to avoid",
            buildJob.contains("needs:")
        )
        assertTrue(
            "publishing has to be gated on the tests as well as the build",
            workflow.contains("needs: [test, build]")
        )
    }

    @Test
    fun thePublishingJobInstallsNoBuildToolchain() {
        val publishJob = workflow().substringAfter("\n  publish:")

        assertFalse(
            "publishing downloads an artifact and calls the release action; a JDK here is " +
                "a second setup paid for nothing",
            publishJob.contains("setup-java")
        )
        assertFalse(
            "publishing needs no Android SDK: nothing it runs uses one",
            publishJob.contains("setup-android")
        )
    }

    // --- the workflow as text ------------------------------------------------------------------

    private fun workflow(): String = source(".github/workflows/release.yml")
}
