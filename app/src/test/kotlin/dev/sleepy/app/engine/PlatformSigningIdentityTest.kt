package dev.sleepy.app.engine

import dev.sleepy.app.testing.minimalApk
import dev.sleepy.app.testing.selfSignedCertificate
import dev.sleepy.app.testing.source
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signing identity comes from the platform keystore and produces a verifiable APK.
 *
 * The app holds no key file and no password: the platform generates the key, stores it, and
 * performs the signature, so the identity a run signs with is whatever the platform returns, and
 * a device without a platform key fails the run rather than falling back to a file key. A JVM
 * test cannot reach the platform keystore, so the keystore side is represented by a key pair and
 * a self-signed certificate built in the test, and the source-level checks cover the wiring that
 * selects the keystore.
 */
class PlatformSigningIdentityTest {

    @Test
    fun theIdentityIsTheOneThePlatformKeystoreReturns() {
        val platform = platformStyleIdentity()

        val identity = ApkSignerHelper.signingIdentity { platform }

        assertEquals(platform.privateKey, identity.privateKey)
        assertEquals(platform.certificate, identity.certificate)
    }

    /**
     * Signing needs a key, and this app has one source for it. A file-backed keystore is the second
     * source, and its private key is readable by anything that can read the app's storage
     * directory, so a missing platform key fails the run instead of falling back to a file.
     */
    @Test
    fun aDeviceWithoutAPlatformKeyFailsTheRunRatherThanSigningWithAFileKey() {
        val failure = try {
            ApkSignerHelper.signingIdentity { null }
            null
        } catch (e: Exception) {
            e
        }

        assertTrue("a missing platform key has to stop the run", failure is IllegalStateException)
        val message = failure?.message.orEmpty()
        assertTrue("the failure has to name what happened: $message", message.contains("keystore"))
        assertTrue("the failure has to name why it matters: $message", message.contains("unsigned"))
    }

    /**
     * The platform's key type is what the signer is built for, so it is the one that has to produce
     * an APK that verifies. The fixture declares `minSdkVersion` 23, below API 24, which makes
     * apksig read and verify the JAR signature as well as v2 and v3, including the JAR signature of
     * an EC key.
     */
    @Test
    fun anEcIdentitySignsAnApkThatVerifies() {
        val signed = signFixtureWith(platformStyleIdentity())
        try {
            // A JAR signature names its block file after the algorithm of the key that made it, so
            // an EC key writes .EC where an RSA key writes .RSA.
            assertTrue(
                "the signer must write a JAR signature with the EC key",
                hasJarSignatureFiles(signed, ".EC")
            )

            val result = ApkVerifier.verify(signed)

            assertEquals(
                "the JAR signature must verify: ${result.signatureErrors}",
                true,
                result.v1SignatureValid
            )
            assertEquals("v2 must verify: ${result.signatureErrors}", true, result.v2SignatureValid)
            assertEquals("v3 must verify: ${result.signatureErrors}", true, result.v3SignatureValid)
            assertTrue(
                "nothing about this signature is wrong: ${result.signatureErrors}",
                result.signatureErrors.isEmpty()
            )
        } finally {
            signed.delete()
        }
    }

    /**
     * The signing path keeps no key material, and the keystore it gets one from is the platform's.
     *
     * These are the properties a JVM test cannot exercise—the Android keystore is a device
     * service—so they are read off the source: the key is generated in the Android keystore for
     * signing with SHA-256, a secure element is asked for first and the TEE is the fallback, and
     * nothing in the file writes key bytes or a password anywhere.
     */
    @Test
    fun theKeyIsGeneratedInThePlatformKeystoreAndNothingIsWrittenToDisk() {
        val source = source(SIGNER_PATH)

        assertTrue(
            "the key has to come from the Android keystore",
            source.contains("AndroidKeyStore")
        )
        assertTrue("the key has to be an EC key", source.contains("KeyProperties.KEY_ALGORITHM_EC"))
        assertTrue("the curve has to be P-256", source.contains("ECGenParameterSpec(EC_CURVE)"))
        assertTrue(
            "the key is generated for signing",
            source.contains("KeyProperties.PURPOSE_SIGN")
        )
        assertTrue(
            "the key is generated for SHA-256 signatures",
            source.contains("KeyProperties.DIGEST_SHA256")
        )
        assertTrue(
            "a secure element is the first place to ask for the key",
            source.contains("setIsStrongBoxBacked(true)")
        )
        assertTrue(
            "the TEE is the fallback when the secure element refuses",
            source.contains("generatePlatformKey(strongBox = false)")
        )
        assertTrue(
            "sign() must sign with the platform key",
            source.contains("signingIdentity { platformIdentity(context) }")
        )

        assertFalse("a PKCS#12 keystore holds an exportable private key", source.contains("PKCS12"))
        assertFalse("no password is stored for the key", source.contains("keyStorePassword"))
        assertFalse("no keystore file is opened", source.contains("FileInputStream"))
        assertFalse("no keystore file is opened", source.contains("inputStream"))
        assertTrue(
            "the keystore an earlier version wrote has to be named as unread rather than left " +
                "unexplained",
            source.contains("not read and not migrated")
        )
        assertFalse("sun.security.x509 is blocked on device", source.contains("sun.security"))
        assertFalse(
            "nothing on the signing path writes key material to disk",
            source.contains("FileOutputStream")
        )
        assertFalse(
            "nothing on the signing path writes key material to disk",
            source.contains("writeText")
        )
        assertFalse(
            "nothing on the signing path writes key material to disk",
            source.contains("writeBytes")
        )
    }

    // --- fixtures -------------------------------------------------------------------------

    private fun signFixtureWith(identity: ApkSignerHelper.SigningIdentity): File {
        val apk = minimalApk(minSdkVersion = 23)
        val signed = File.createTempFile("sleepy-signed-", ".apk")
        signed.deleteOnExit()
        try {
            return ApkSignerHelper.signWith(identity, apk, signed)
        } finally {
            apk.delete()
        }
    }

    /**
     * The key pair and certificate that a platform keystore returns: an EC P-256 key and a
     * self-signed SHA-256 ECDSA certificate over it. The platform generates both in the
     * key-generation call; this builds the same pair in the JVM so the path the signer takes on a
     * device can be exercised here.
     */
    private fun platformStyleIdentity(): ApkSignerHelper.SigningIdentity {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return ApkSignerHelper.SigningIdentity(
            privateKey = keyPair.private,
            certificate = selfSignedCertificate(keyPair)
        )
    }

    /** Whether the archive carries a JAR signature whose block file is named [blockExtension]. */
    private fun hasJarSignatureFiles(apk: File, blockExtension: String): Boolean =
        ZipFile(apk).use { zip ->
            zip.getEntry("META-INF/MANIFEST.MF") != null &&
                zip.entries().asSequence().any {
                    it.name.startsWith("META-INF/") && it.name.endsWith(".SF")
                } &&
                zip.entries().asSequence().any {
                    it.name.startsWith("META-INF/") && it.name.endsWith(blockExtension)
                }
        }

    private companion object {
        const val SIGNER_PATH = "app/src/main/kotlin/dev/sleepy/app/engine/ApkSignerHelper.kt"
    }
}
