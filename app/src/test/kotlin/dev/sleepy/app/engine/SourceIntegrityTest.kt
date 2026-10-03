package dev.sleepy.app.engine

import dev.sleepy.app.model.SplitSource
import dev.sleepy.app.util.HashUtils
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The verdict the pipeline records for a source download and for each configuration split.
 *
 * The flag comes from a comparison against the bytes that arrived, never from the presence of a
 * hash field: a source that publishes no hash reports null, a published hash that matches reports
 * true, and a published hash that does not match reports false. A true value is therefore only
 * reachable when a comparison ran and agreed.
 */
class SourceIntegrityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** A payload carrying the ZIP magic number that [dev.sleepy.app.util.Downloader] requires. */
    private val payload = "PK\u0003\u0004 sleepy source payload".toByteArray(Charsets.ISO_8859_1)

    /** A file holding [payload], and the `file://` URL that serves it. */
    private fun servedFixture(): Pair<File, String> {
        val file = File(tempFolder.root, "source.apk").apply { writeBytes(payload) }
        return file to "file://${file.absolutePath}"
    }

    @Test
    fun aSourceThatPublishesNoHashReportsNoVerdict() = runBlocking {
        val (_, url) = servedFixture()
        val destination = File(tempFolder.root, "download.apk")

        val integrity = fetchSourceApk(url, expectedSha256 = null, destination = destination) { _, _ -> }

        assertNull("no published hash means no comparison, so the verdict is null", integrity.verified)
        assertEquals(payload.size.toLong(), integrity.sizeBytes)
        assertTrue("a source with no published hash still delivers its bytes", destination.isFile)
    }

    @Test
    fun aPublishedHashThatMatchesTheBytesReportsTrue() = runBlocking {
        val (_, url) = servedFixture()
        val destination = File(tempFolder.root, "download.apk")

        val integrity = fetchSourceApk(
            url,
            expectedSha256 = HashUtils.sha256Hex(payload),
            destination = destination
        ) { _, _ -> }

        assertEquals(true, integrity.verified)
        assertTrue("a matching download is delivered", destination.isFile)
    }

    @Test
    fun aPublishedHashThatDoesNotMatchTheBytesReportsFalseRatherThanPassing() = runBlocking {
        val (_, url) = servedFixture()
        val destination = File(tempFolder.root, "download.apk")
        val otherBuild = HashUtils.sha256Hex("a different build".toByteArray())

        val integrity = fetchSourceApk(url, expectedSha256 = otherBuild, destination = destination) { _, _ -> }

        assertEquals(
            "a published hash that does not match the downloaded bytes must report false",
            false,
            integrity.verified
        )
        assertFalse(
            "a download that failed its integrity check must not be written for patching",
            destination.exists()
        )
    }

    /** A split record for the fixture URL, with the integrity data its source publishes. */
    private fun split(url: String, sha256: String? = null, sizeBytes: Long? = null) =
        SplitSource(url = url, sha256Expected = sha256, sizeBytes = sizeBytes)

    @Test
    fun aSplitThatMatchesItsPublishedHashAndSizeIsDelivered() = runBlocking {
        val (_, url) = servedFixture()
        val destination = File(tempFolder.root, "split.apk")

        val integrity = fetchSplitApk(
            split(url, sha256 = HashUtils.sha256Hex(payload), sizeBytes = payload.size.toLong()),
            destination
        )

        assertEquals(true, integrity.verified)
        assertEquals(true, integrity.sizeMatches)
        assertTrue("a matching split is delivered for merging", destination.isFile)
    }

    @Test
    fun aSplitThatDoesNotMatchItsPublishedHashIsNotDelivered() = runBlocking {
        val (_, url) = servedFixture()
        val destination = File(tempFolder.root, "split.apk")
        val otherSplit = HashUtils.sha256Hex("a different split".toByteArray())

        val integrity = fetchSplitApk(split(url, sha256 = otherSplit), destination)

        assertEquals(false, integrity.verified)
        assertFalse(
            "a split that failed its integrity check must not be written for merging",
            destination.exists()
        )
    }

    @Test
    fun aSplitWhoseSizeDiffersFromThePublishedSizeIsNotDelivered() = runBlocking {
        val (_, url) = servedFixture()
        val destination = File(tempFolder.root, "split.apk")

        val integrity = fetchSplitApk(
            split(url, sizeBytes = payload.size + 1L),
            destination
        )

        assertEquals(false, integrity.sizeMatches)
        assertNull("no hash was published, so no hash verdict exists", integrity.verified)
        assertFalse(
            "a split whose size differs from the published size must not be written for merging",
            destination.exists()
        )
    }

    @Test
    fun aSplitWithNoPublishedIntegrityDataIsDeliveredUnchecked() = runBlocking {
        val (_, url) = servedFixture()
        val destination = File(tempFolder.root, "split.apk")

        val integrity = fetchSplitApk(split(url), destination)

        assertNull(integrity.verified)
        assertNull(integrity.sizeMatches)
        assertTrue("a split with no published hash still delivers its bytes", destination.isFile)
    }
}
