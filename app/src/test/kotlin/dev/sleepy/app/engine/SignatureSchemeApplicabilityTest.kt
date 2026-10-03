package dev.sleepy.app.engine

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import dev.sleepy.app.testing.minimalApk
import dev.sleepy.app.testing.selfSignedCertificate
import dev.sleepy.app.ui.screens.signatureStatusText
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported meaning of a JAR signature on a build that cannot use one.
 *
 * The platform reads APK Signature Scheme v1 only below API 24, and the verifier follows the same
 * rule: a JAR signature on a build declaring a higher minSdkVersion is not read, and that must be
 * reported as not applicable rather than invalid. The tests sign minimal APKs declaring three
 * minSdkVersions and pin which verdict each one produces.
 */
class SignatureSchemeApplicabilityTest {

    @Test
    fun aJarSignatureOnABuildThatCannotUseItIsReportedAsNotApplicable() {
        val signed = signedApkDeclaring(minSdkVersion = 24)

        // The precondition: the signature is there. Any explanation of the verdict below depends
        // on a file that carries it.
        assertTrue("apksig must have written the JAR signature files", hasJarSignatureFiles(signed))

        val result = ApkVerifier.verify(signed)

        assertNull(
            "a scheme the platform will never read must be reported as having no verdict, " +
                "not as a failure",
            result.v1SignatureValid
        )
        val reason = result.v1NotApplicableReason
        assertNotNull("an unexplained null is no better than a wrong answer", reason)
        assertTrue("the reason must name the declared version: $reason", reason!!.contains("24"))
        assertTrue(
            "nothing about this is an error: ${result.signatureErrors}",
            result.signatureErrors.isEmpty()
        )

        // And the schemes that do apply to this build are unaffected.
        assertEquals(true, result.v2SignatureValid)
        assertEquals(true, result.v3SignatureValid)
    }

    /**
     * The other side of the same rule, on a build that predates API 24. The signing is
     * identical—same code, same key, same three schemes—so the only thing that differs is
     * the version the APK declares, which is exactly what the verdict has to turn on.
     */
    @Test
    fun theSameSignatureOnABuildThatDoesUseItIsReportedAsValid() {
        val signed = signedApkDeclaring(minSdkVersion = 23)
        assertTrue("apksig must have written the JAR signature files", hasJarSignatureFiles(signed))

        val result = ApkVerifier.verify(signed)

        assertEquals(
            "below API 24 the platform reads the JAR signature, so it is checked and must verify",
            true,
            result.v1SignatureValid
        )
        assertNull(
            "a verdict was reached, so there is nothing to explain",
            result.v1NotApplicableReason
        )
    }

    /**
     * The unknown branch must not hide real failures, and this is the case the other side of the
     * rule describes: an APK that declares a `minSdkVersion` below 24—where JAR signing is
     * genuinely required—and carries no JAR signature at all. The signing is wrong there, so
     * the verdict has to be `false` and the reason has to be stated.
     *
     * A signature that is present but corrupt produces the same verdict: flipping one byte of a
     * signed entry's data measures as `false` too. That case is not asserted here because
     * apksig 9.4.1 records no error text for it, so an assertion on an empty explanation only
     * asserts that the library records nothing.
     */
    @Test
    fun anApkThatNeedsAJarSignatureAndHasNoneIsReportedAsInvalid() {
        val signed = signedApkDeclaring(minSdkVersion = 23)
        val stripped = File.createTempFile("sleepy-no-v1-", ".apk")
        try {
            copyWithoutSignatureFiles(signed, stripped)

            val result = ApkVerifier.verify(stripped)

            assertEquals(
                "a scheme that was required and missing must read as invalid, " +
                    "not as not applicable",
                false,
                result.v1SignatureValid
            )
            assertNull(
                "a verdict was reached, so there is nothing to explain",
                result.v1NotApplicableReason
            )
            assertFalse(
                "the failure has to be stated: ${result.signatureErrors}",
                result.signatureErrors.isEmpty()
            )
        } finally {
            stripped.delete()
        }
    }

    /** The wording of the three verdicts, only one of which is not a pass or a fail. */
    @Test
    fun aSchemeThatCannotApplyIsNeverRenderedAsAFailure() {
        assertEquals("Valid", signatureStatusText(true))
        assertEquals("Not valid", signatureStatusText(false))
        assertEquals("Not applicable", signatureStatusText(null))
    }

    // --- fixture -------------------------------------------------------------------------

    /**
     * An APK declaring [minSdkVersion], signed with v1, v2 and v3 exactly as the patcher signs.
     *
     * The two `minSdkVersion`s above are the two sides of API 24: 23 is the last level whose
     * platform reads a JAR signature, 24 the first that does not.
     */
    private fun signedApkDeclaring(minSdkVersion: Int): File {
        val apk = minimalApk(minSdkVersion)

        val keyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "sleepy-test",
            KeyConfig.Jca(keyPair.private),
            listOf(selfSignedCertificate(keyPair))
        ).build()

        val signed = File.createTempFile(apk.name.removeSuffix(".apk") + "-signed-", ".apk")
        signed.deleteOnExit()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(apk)
            .setOutputApk(signed)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        return signed
    }

    /** Whether the archive carries the three entries a JAR signature is made of. */
    private fun hasJarSignatureFiles(apk: File): Boolean = ZipFile(apk).use { zip ->
        zip.getEntry("META-INF/MANIFEST.MF") != null &&
            zip.entries().asSequence().any {
                it.name.startsWith("META-INF/") && it.name.endsWith(".SF")
            } &&
            zip.entries().asSequence().any {
                it.name.startsWith("META-INF/") && it.name.endsWith(".RSA")
            }
    }

    /** Copies [apk] to [out] without its JAR signature, leaving everything else as it was. */
    private fun copyWithoutSignatureFiles(apk: File, out: File) {
        ZipFile(apk).use { zip ->
            ZipOutputStream(out.outputStream()).use { zos ->
                for (entry in zip.entries()) {
                    if (entry.name.startsWith("META-INF/")) continue
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(zip.getInputStream(entry).readBytes())
                    zos.closeEntry()
                }
            }
        }
    }
}
