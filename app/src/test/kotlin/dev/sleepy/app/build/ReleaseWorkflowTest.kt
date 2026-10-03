package dev.sleepy.app.build

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

    /** Every `run:` block of [yaml], as the text the runner executes. */
    internal fun runBlocks(yaml: String): List<String> {
        val blocks = mutableListOf<String>()
        val lines = yaml.lines()
        var index = 0
        while (index < lines.size) {
            val match = RUN_KEY.matchEntire(lines[index])
            if (match == null) {
                index++
                continue
            }
            val indent = match.groupValues[1].length
            val inline = match.groupValues[2]
            index++

            val body = mutableListOf<String>()
            while (index < lines.size) {
                val line = lines[index]
                val indentation = line.takeWhile { it == ' ' }.length
                if (line.isNotBlank() && indentation <= indent) break
                body.add(line)
                index++
            }

            if (body.isEmpty() && inline.isNotEmpty() && inline != "|" && inline != ">") {
                blocks.add(inline)
            } else {
                blocks.add(body.joinToString("\n") { it.trimStart() })
            }
        }
        return blocks
    }
    private companion object {
        /** A `run:` key, its indentation, and whatever the same line holds after the colon. */
        val RUN_KEY = Regex("""^(\s*)run:\s*(.*)$""")
    }
}
