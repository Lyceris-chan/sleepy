package dev.sleepy.app.engine

import dev.sleepy.app.model.StepStatus
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nulling a Hermes bundle's Sentry DSN: the string is replaced in place and the footer recomputed.
 *
 * A patched bundle is loadable only while its trailing SHA-1 covers the file as it now is, so the
 * edit has to rewrite the DSN and leave a footer that matches.
 */
class HermesSentryDsnTest {

    @Test
    fun pureKotlinHermesSentryDsnNulling() {
        val dummyUrl =
            "https://abc123def456.ingest.sentry.io/api/12345/envelope/?sentry_version=7&" +
                "sentry_key=0123456789abcdef0123456789abcdef&" +
                "sentry_client=sentry.javascript.react-native"
        val prefix = "var __DEV__=false;DSN=\""
        val suffix = "\";run();"
        val fullContent = prefix + dummyUrl + suffix
        val contentBytes = fullContent.toByteArray(Charsets.ISO_8859_1)

        // Append 20 dummy bytes for Hermes SHA-1 footer
        val md = MessageDigest.getInstance("SHA-1")
        val initialFooter = md.digest(contentBytes)
        val bundleWithFooter = contentBytes + initialFooter

        val (patchedBundle, result) = HermesPatcher.nullifySentryDsn(bundleWithFooter)
        assertNotNull("Result should not be null", result)
        assertEquals(StepStatus.OK, result!!.status)
        assertEquals(bundleWithFooter.size, patchedBundle.size)

        val patchedString = String(patchedBundle, Charsets.ISO_8859_1)
        assertTrue("Sentry domain must be gone", !patchedString.contains("ingest.sentry.io"))
        assertTrue(
            "Nulled DSN prefix must be present",
            patchedString.contains("https://0.0.0.0/000")
        )

        // Verify recomputed SHA-1 footer
        val payloadLength = patchedBundle.size - 20
        val expectedSha1 =
            MessageDigest.getInstance("SHA-1").digest(patchedBundle.copyOfRange(0, payloadLength))
        val actualSha1 = patchedBundle.copyOfRange(payloadLength, patchedBundle.size)
        assertTrue("Footer must match SHA-1 of payload", expectedSha1.contentEquals(actualSha1))
        println("Pure Kotlin Hermes Sentry DSN nulling & SHA-1 footer verification passed!")
    }
}
