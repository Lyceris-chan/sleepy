package dev.sleepy.app.build

import dev.sleepy.app.testing.runBlocks
import dev.sleepy.app.testing.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source hash workflow holds write permission only where it opens the pull request, and
 * every action it runs is pinned to a commit.
 *
 * The job that downloads and hashes has no reason to change the repository, so a compromise in
 * a download step must not find a token that can push. The write permission belongs to the job
 * that commits the refreshed manifest to a branch and opens the request, and to no other.
 */
class SourceHashWorkflowTest {

    @Test
    fun noStepBodyCarriesAnInterpolatedValue() {
        val blocks = runBlocks(workflow())
        assertTrue("the workflow has no run blocks, so this test checked nothing", blocks.size >= 3)

        for ((index, block) in blocks.withIndex()) {
            assertFalse(
                "run block ${index + 1} interpolates a value into script text: " +
                    "${block.lineSequence().first()}",
                block.contains("\${{")
            )
        }
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
        assertTrue(
            "opening a pull request needs the pull-requests permission",
            workflow.contains("      pull-requests: write")
        )
    }

    @Test
    fun everyActionIsPinnedToACommit() {
        val uses = workflow().lines().map { it.trim() }.filter { it.startsWith("uses:") }
        assertTrue("the workflow uses no actions, so this test checked nothing", uses.size >= 3)

        for (line in uses) {
            assertTrue(
                "action is not pinned to a commit SHA: $line",
                Regex("""uses:\s+\S+@[0-9a-f]{40}(\s*#.*)?""").matches(line)
            )
        }
    }

    @Test
    fun theWorkflowRefreshesBothCopiesOfTheManifest() {
        val workflow = workflow()

        assertTrue(
            "the workflow has to run the hash refresh script",
            workflow.contains("update_source_hashes.py")
        )
        assertTrue(workflow.contains("sources.json"))
        assertTrue(workflow.contains("app/src/main/assets/sources.json"))
    }

    private fun workflow(): String = source(".github/workflows/source-hashes.yml")
}
