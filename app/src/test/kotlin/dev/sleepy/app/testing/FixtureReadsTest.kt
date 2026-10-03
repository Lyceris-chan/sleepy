package dev.sleepy.app.testing

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * How the fixture readers treat an absent fixture and one that is present but wrong.
 *
 * The reference APKs are build outputs outside the repository. On a machine without them a reader
 * reports the test as skipped, which JUnit records as a skip rather than a failure, so the suite
 * passes on a clean runner and the skipped count shows what did not run. A file that is present
 * and cannot be read is a different situation: it fails, because a skip there would hide a broken
 * fixture.
 */
class FixtureReadsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun anAbsentFixtureIsReportedAsSkipped() {
        val absent = File(tempFolder.root, "absent.apk")
        val thrown = runCatching { dexEntries(absent) }.exceptionOrNull()
        assertTrue(
            "an absent fixture must raise the assumption JUnit reports as a skip, not " +
                "${thrown?.javaClass?.name}",
            thrown is AssumptionViolatedException
        )
    }

    @Test
    fun aPresentFixtureThatCannotBeReadFailsRatherThanSkipping() {
        val wrong = File.createTempFile("fixture", ".apk").apply {
            writeText("this is not a ZIP archive")
            deleteOnExit()
        }
        val thrown = runCatching { dexEntries(wrong) }.exceptionOrNull()
        assertNotNull("a present fixture that cannot be read must fail the test", thrown)
        assertFalse(
            "a present fixture that cannot be read must not be reported as a skip",
            thrown is AssumptionViolatedException
        )
    }
}
