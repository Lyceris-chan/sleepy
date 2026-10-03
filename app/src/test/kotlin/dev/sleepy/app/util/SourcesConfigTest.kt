package dev.sleepy.app.util

import dev.sleepy.app.model.ApkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser against the shapes `sources.json` uses.
 *
 * A split entry pairs a URL with the integrity data published for that URL, so the pipeline knows
 * which hash and size belong to which split. A split listed as a plain URL stays parseable as one
 * the configuration publishes no integrity data for.
 */
class SourcesConfigTest {

    private fun document(splits: String, apkType: String = "split_base") = """
        {
          "schema_version": 2,
          "sources": [
            {
              "id": "example",
              "display_name": "Example",
              "package_name": "com.example",
              "version_name": "1.2.3",
              "version_code": 10203,
              "url": "https://example.test/base.apk",
              "apk_type": "$apkType",
              "sha256_expected": null,
              "description": "An example source.",
              "changelog_url": "",
              "patch_ids": [],
              "splits": $splits
            }
          ]
        }
    """.trimIndent()

    @Test
    fun aSplitCarriesTheHashAndSizeItsUrlPublishes() {
        val sources = SourcesConfig.parseJson(
            document(
                """
                [
                  {
                    "url": "https://example.test/config.arm64_v8a",
                    "sha256_expected": "abc123",
                    "size_bytes": 73842363
                  }
                ]
                """.trimIndent()
            )
        )

        val split = sources.single().splits.single()
        assertEquals("https://example.test/config.arm64_v8a", split.url)
        assertEquals("abc123", split.sha256Expected)
        assertEquals(73842363L, split.sizeBytes)
    }

    @Test
    fun aSplitListedAsAPlainUrlParsesWithoutIntegrityData() {
        val sources = SourcesConfig.parseJson(
            document("""[ "https://example.test/config.de" ]""")
        )

        val split = sources.single().splits.single()
        assertEquals("https://example.test/config.de", split.url)
        assertNull(split.sha256Expected)
        assertNull(split.sizeBytes)
    }

    @Test
    fun aSourceWithNoSplitsParsesWithAnEmptyList() {
        val sources = SourcesConfig.parseJson(document("[]", apkType = "universal"))

        assertEquals(ApkType.UNIVERSAL, sources.single().apkType)
        assertTrue(sources.single().splits.isEmpty())
    }
}
