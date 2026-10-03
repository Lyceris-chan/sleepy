package dev.sleepy.app.build

import dev.sleepy.app.testing.runBlocks
import dev.sleepy.app.testing.source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release workflow passes values to its scripts as environment data.
 *
 * A `run:` block is executed as script, so a workflow input or a git ref spliced into one becomes
 * script the runner executes. The tests check that no step body interpolates a value and that the
 * tag reaches the signing job's script through `env:`.
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

    // --- the workflow as text ------------------------------------------------------------------

    private fun workflow(): String = source(".github/workflows/release.yml")
}
