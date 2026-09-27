package dev.sleepy.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The version this build reports, against the one the changelog says is the newest release.
 *
 * The settings screen shows what it is told rather than a version written into the screen, and the
 * build script reads that version from the changelog's newest released heading, so the three cannot
 * disagree — but only while the reading keeps working. That is what this checks: a heading the
 * pattern does not match, or a version bumped in one place and not the other, fails here rather
 * than shipping a build that calls itself something no release ever was.
 *
 * The `Unreleased` heading is skipped on purpose. It names a version nobody can install, and a
 * build reporting it would be claiming to be a release that does not exist yet.
 */
class AppVersionTest {

    /**
     * The directory these tests run in, named so that the lookup below has a value to start from.
     *
     * `System.getProperty` hands back a platform type and `File`'s constructor takes a non-null
     * `String`, so the two cannot be joined without either asserting it here or leaving the
     * compiler to decide.
     */
    private val workingDirectory: String = requireNotNull(System.getProperty("user.dir")) {
        "user.dir is not set, so CHANGELOG.md cannot be looked for from the working directory"
    }

    /**
     * The newest released version in `CHANGELOG.md`, e.g. `1.4.0` from `## [1.4.0] - 2026-09-27`.
     *
     * The file is looked for upwards from the working directory, which is the module directory when
     * Gradle runs these tests and the repository directory when something else runs them.
     */
    private fun newestReleasedVersion(): String {
        val changelog = generateSequence(File(workingDirectory)) { it.parentFile }
            .map { File(it, "CHANGELOG.md") }
            .firstOrNull { it.isFile }
        requireNotNull(changelog) {
            "CHANGELOG.md was not found above $workingDirectory, so the version this build " +
                "reports cannot be checked against the newest release"
        }

        return Regex("^## \\[(\\d+\\.\\d+\\.\\d+)\\]", RegexOption.MULTILINE)
            .find(changelog.readText())
            ?.groupValues?.get(1)
            ?: error("${changelog.path} has no released version heading (## [x.y.z])")
    }

    /** What the settings screen shows is the newest release the changelog lists. */
    @Test
    fun theVersionTheBuildReportsIsTheNewestReleaseInTheChangelog() {
        assertEquals(newestReleasedVersion(), BuildConfig.VERSION_NAME)
    }

    /**
     * The install number orders releases the way the version does.
     *
     * Android upgrades by this number and ignores the name, so two releases agreeing on it is an
     * update that is refused while looking like a newer version. The build script derives it from
     * the same string the name comes from, and this is the check that it still does.
     */
    @Test
    fun theVersionCodeOrdersReleasesByTheVersionTheyReport() {
        val (major, minor, patch) = BuildConfig.VERSION_NAME.split(".").map { it.toInt() }
        assertEquals(major * 10_000 + minor * 100 + patch, BuildConfig.VERSION_CODE)
    }
}
