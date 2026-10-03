package dev.sleepy.app.testing

/**
 * Every `run:` block of [yaml], as the text the runner executes.
 *
 * A workflow test reads step bodies as script text, so it needs the body of each `run:` key
 * rather than the file as one string. The body is every following line indented deeper than the
 * key; a `run:` whose value sits on the same line is returned as that value.
 */
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

/** A `run:` key, its indentation, and whatever the same line holds after the colon. */
private val RUN_KEY = Regex("""^(\s*)run:\s*(.*)$""")
