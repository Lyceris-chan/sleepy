package dev.sleepy.app.build

import dev.sleepy.app.testing.sourceFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest a build bundles and the integrity data it publishes.
 *
 * The app downloads what `sources.json` names and verifies it against the hash recorded for the
 * same URL, so a source whose base APK or split carries no hash is one the app cannot check. The
 * file exists twice, at the repository root and in the app's assets, and a build copies one over
 * the other: a change that reaches only one of them ships hashes the app does not read.
 */
class SourcesManifestTest {

    private companion object {
        /** A SHA-256 as the manifest writes it: lowercase hexadecimal, 64 characters. */
        val SHA256 = Regex("[0-9a-f]{64}")
    }

    private fun parsed(): JSONObject = JSONObject(sourceFile("sources.json").readText())

    @Test
    fun theBundledManifestIsThePublishedOne() {
        val published = sourceFile("sources.json").readBytes()
        val bundled = sourceFile("app/src/main/assets/sources.json").readBytes()

        assertTrue(
            "the two copies of sources.json must be byte-identical, or the app verifies " +
                "against hashes the repository does not publish",
            published.contentEquals(bundled)
        )
    }

    @Test
    fun theManifestUsesTheSchemaTheParserReads() {
        assertEquals(2, parsed().getInt("schema_version"))
    }

    @Test
    fun everySourcePublishesAHashForItsBaseApk() {
        val sources = parsed().getJSONArray("sources")

        for (index in 0 until sources.length()) {
            val source = sources.getJSONObject(index)
            val id = source.getString("id")
            assertTrue(
                "$id publishes no base APK hash",
                source.optString("sha256_expected").matches(SHA256)
            )
        }
    }

    @Test
    fun everySplitRecordsTheHashAndSizeItsUrlServes() {
        val sources = parsed().getJSONArray("sources")

        for (index in 0 until sources.length()) {
            val source = sources.getJSONObject(index)
            val id = source.getString("id")
            val splits = source.getJSONArray("splits")

            for (position in 0 until splits.length()) {
                val split = splits.getJSONObject(position)
                val url = split.getString("url")
                assertTrue(
                    "$id split ${position + 1} has a blank URL",
                    url.isNotBlank()
                )
                assertTrue(
                    "$id split ${position + 1} ($url) publishes no hash",
                    split.optString("sha256_expected").matches(SHA256)
                )
                assertTrue(
                    "$id split ${position + 1} ($url) publishes no size",
                    split.getLong("size_bytes") > 0
                )
            }
        }
    }
}
