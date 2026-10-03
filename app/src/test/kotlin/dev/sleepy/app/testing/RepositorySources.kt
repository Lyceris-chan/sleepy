package dev.sleepy.app.testing

import java.io.File

/**
 * Locates repository files from a unit test's working directory.
 *
 * Gradle runs these tests with the module directory as `user.dir`, while a runner started from
 * the repository root sees a different base. Every lookup walks upward for the first path that
 * exists, so a test does not depend on where it was started.
 */

/** The directory the tests were started in. */
fun workingDirectory(): File = File(
    requireNotNull(System.getProperty("user.dir")) {
        "user.dir is not set, so repository files cannot be looked for"
    }
)

/** The file at [relativePath], found upward from the working directory. */
fun sourceFile(relativePath: String): File {
    val file = generateSequence(workingDirectory()) { it.parentFile }
        .map { File(it, relativePath) }
        .firstOrNull { it.isFile }
    return requireNotNull(file) { "$relativePath was not found above ${workingDirectory()}" }
}

/** The text of the file at [relativePath], found upward from the working directory. */
fun source(relativePath: String): String = sourceFile(relativePath).readText()

/** Every Kotlin source under the app's `ui` package. */
fun uiSources(): List<File> {
    val relative = "app/src/main/kotlin/dev/sleepy/app/ui"
    val root = generateSequence(workingDirectory()) { it.parentFile }
        .map { File(it, relative) }
        .firstOrNull { it.isDirectory }
    requireNotNull(root) { "$relative was not found above ${workingDirectory()}" }
    return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
