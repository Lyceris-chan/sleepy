package dev.sleepy.app.ui

import dev.sleepy.app.testing.source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Settings screen states about the app.
 *
 * The screen presents security and accessibility properties as facts, so each claim has to match
 * what the code does. A conformance claim the project has not established, such as "OWASP Mobile
 * M5 strict TLS" or "WCAG 2.2 AA accessibility", tells a reader the app was audited in ways it
 * was not.
 */
class SettingsClaimsTest {

    private val screen = source("app/src/main/kotlin/dev/sleepy/app/ui/screens/SettingsScreen.kt")

    @Test
    fun theScreenMakesNoConformanceClaimTheProjectHasNotEstablished() {
        assertFalse(
            "the screen must not claim OWASP Mobile M5 strict TLS: the app does not pin " +
                "certificates, and M5 is a retired Mobile Top 10 label",
            screen.contains("OWASP Mobile M5")
        )
        assertFalse(
            "the screen must not claim WCAG 2.2 AA conformance while the SC 1.4.4, SC 1.4.11 " +
                "and SC 3.2.6 gaps from the audit remain open",
            screen.contains("WCAG 2.2 AA")
        )
    }

    @Test
    fun theScreenStatesTheTransportPropertyThatHolds() {
        assertTrue(
            "the transport row must state the HTTPS-only rule the downloader enforces",
            screen.contains("Every download URL must use HTTPS")
        )
        assertTrue(
            "the transport row must say that certificates are not pinned",
            screen.contains("does not pin certificates")
        )
    }
}
