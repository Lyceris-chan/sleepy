package dev.sleepy.app.build

import dev.sleepy.app.BuildConfig
import dev.sleepy.app.testing.workingDirectory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The version the build reports agrees with the newest release in the changelog.
 *
 * The build reads the version from the changelog's newest released heading, and the settings
 * screen shows what the build reports. The tests pin that both agree and that the version code
 * orders releases the way the version name does.
 */
class AppVersionTest {

    /**
     * The newest released version in `CHANGELOG.md`, e.g. `1.4.0` from `## [1.4.0] - 2026-09-27`.
     *
     * The lookup walks upward from the working directory, which is the module directory when
     * Gradle runs these tests and the repository directory when something else runs them.
     */
    private fun newestReleasedVersion(): String {
        val changelog = generateSequence(workingDirectory()) { it.parentFile }
            .map { File(it, "CHANGELOG.md") }
            .firstOrNull { it.isFile }
        requireNotNull(changelog) {
            "CHANGELOG.md was not found above ${workingDirectory()}, so the version this build " +
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
     * Android upgrades by this number and ignores the name, so two releases agreeing on it produce
     * an update that Android rejects while it looks like a newer version. The build script derives
     * it from the same string the name comes from, and this test checks that the derivation still
     * holds.
     */
    @Test
    fun theVersionCodeOrdersReleasesByTheVersionTheyReport() {
        val (major, minor, patch) = BuildConfig.VERSION_NAME.split(".").map { it.toInt() }
        assertEquals(major * 10_000 + minor * 100 + patch, BuildConfig.VERSION_CODE)
    }
}
