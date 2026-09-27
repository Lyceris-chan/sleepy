package dev.sleepy.app

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import dev.sleepy.app.engine.ApkVerifier
import dev.sleepy.app.ui.screens.signatureStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * What "Signature v1 (JAR)" means when the scheme cannot apply to the build at all.
 *
 * JAR signing exists for platforms that predate APK Signature Scheme v2. From API 24 on the
 * platform reads v2, v3 and the APK Signing Block instead, and apksig's verifier follows the
 * same rule: asked about an APK whose own `minSdkVersion` reaches 24, it does not run the JAR
 * verifier at all and answers `isVerifiedUsingV1Scheme = false`. Read as a bare Boolean that
 * turned a signature which is present, intact and simply unread into a reported failure on
 * every APK this patcher produces — the Discord target declares `minSdkVersion` 24.
 *
 * These tests sign the way the patcher signs, at three declared `minSdkVersion`s, and pin that
 * the verdict says "not valid" only where a JAR signature was actually read and rejected.
 *
 * The fixture is built here rather than borrowed from a device or an SDK: a minimal APK whose
 * binary manifest declares the version under test, signed with a key generated in the test.
 */
class SignatureSchemeApplicabilityTest {

    @Test
    fun aJarSignatureOnABuildThatCannotUseItIsReportedAsNotApplicable() {
        val signed = signedApkDeclaring(minSdkVersion = 24)

        // The precondition is the whole point: the signature is there. Any explanation of the
        // verdict below has to start from a file that really carries it.
        assertTrue("apksig must have written the JAR signature files", hasJarSignatureFiles(signed))

        val result = ApkVerifier.verify(signed)

        assertNull(
            "a scheme the platform will never read must be reported as having no verdict, not as a failure",
            result.v1SignatureValid
        )
        val reason = result.v1NotApplicableReason
        assertNotNull("an unexplained null is no better than a wrong answer", reason)
        assertTrue("the reason must name the declared version: $reason", reason!!.contains("24"))
        assertTrue("nothing about this is an error: ${result.signatureErrors}", result.signatureErrors.isEmpty())

        // And the schemes that do apply to this build are unaffected.
        assertEquals(true, result.v2SignatureValid)
        assertEquals(true, result.v3SignatureValid)
    }

    /**
     * The other side of the same rule, on a build that predates API 24. The signing is
     * identical — same code, same key, same three schemes — so the only thing that differs is
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
        assertNull("a verdict was reached, so there is nothing to explain", result.v1NotApplicableReason)
    }

    /**
     * The unknown branch must not swallow real failures, and this is the case the other side of
     * the rule describes: an APK that declares a `minSdkVersion` below 24 — where JAR signing is
     * genuinely required — and carries no JAR signature at all. The signing really is wrong
     * there, so the verdict has to be `false` and the reason has to be stated.
     *
     * A signature that is present but corrupt lands in the same place: flipping one byte of a
     * signed entry's data measures as `false` too. That case is not asserted here because
     * apksig 9.4.1 records no error text for it, and an assertion on an empty explanation would
     * only be testing the library's silence.
     */
    @Test
    fun anApkThatNeedsAJarSignatureAndHasNoneIsReportedAsInvalid() {
        val signed = signedApkDeclaring(minSdkVersion = 23)
        val stripped = File.createTempFile("sleepy-no-v1-", ".apk")
        try {
            copyWithoutSignatureFiles(signed, stripped)

            val result = ApkVerifier.verify(stripped)

            assertEquals(
                "a scheme that was required and missing must read as invalid, not as not applicable",
                false,
                result.v1SignatureValid
            )
            assertNull("a verdict was reached, so there is nothing to explain", result.v1NotApplicableReason)
            assertFalse("the failure has to be stated: ${result.signatureErrors}", result.signatureErrors.isEmpty())
        } finally {
            stripped.delete()
        }
    }

    /** The wording of the three verdicts, of which only one of the three is not a pass or a fail. */
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
        val apk = File.createTempFile("sleepy-minsdk-$minSdkVersion-", ".apk")
        apk.deleteOnExit()
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifestWithMinSdk(minSdkVersion))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(64) { it.toByte() })
            zip.closeEntry()
        }

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
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
            zip.entries().asSequence().any { it.name.startsWith("META-INF/") && it.name.endsWith(".SF") } &&
            zip.entries().asSequence().any { it.name.startsWith("META-INF/") && it.name.endsWith(".RSA") }
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

    // --- a self-signed certificate, written out as DER ------------------------------------
    //
    // apksig signs with a certificate and a private key, and the JDK has no public API that
    // mints a certificate: every route that does wants BouncyCastle or a `keytool` run, and
    // neither belongs in a unit test. What is needed is small — a v3 certificate with a name,
    // a validity window and a public key, and no extensions at all — so it is encoded here.

    /**
     * A self-signed certificate for [keyPair], valid from 2020 to 2030.
     *
     * The public key is embedded in the form the JDK already hands out (`PublicKey.encoded` is
     * a complete DER `SubjectPublicKeyInfo`), and the signature is over the encoded
     * `TBSCertificate`, which is what makes it verifiable rather than merely well-formed.
     */
    private fun selfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val algorithm = sha256WithRsaAlgorithm
        val name = seq(setOf(seq(oid(OID_COMMON_NAME), utf8("sleepy test"))))
        val tbsCertificate = seq(
            contextTagged(0xA0, integer(2)), // version: v3
            integer(1),                      // serialNumber
            algorithm,
            name,                            // issuer
            seq(utcTime("200101000000Z"), utcTime("300101000000Z")),
            name,                            // subject
            keyPair.public.encoded
        )
        val signatureBytes = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbsCertificate)
            sign()
        }
        val encoded = seq(tbsCertificate, algorithm, bitString(signatureBytes))

        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(encoded.inputStream()) as X509Certificate
        assertEquals(
            "the certificate must certify the key that signs with it",
            keyPair.public,
            certificate.publicKey
        )
        return certificate
    }

    private fun der(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    private fun derLength(size: Int): ByteArray = when {
        size < 0x80 -> byteArrayOf(size.toByte())
        size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
        else -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
    }

    private fun concatenated(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = der(0x30, concatenated(parts))
    private fun setOf(vararg parts: ByteArray) = der(0x31, concatenated(parts))
    private fun integer(value: Int) = der(0x02, byteArrayOf(value.toByte()))
    private fun contextTagged(tag: Int, content: ByteArray) = der(tag, content)
    private fun utf8(text: String) = der(0x0C, text.toByteArray(Charsets.UTF_8))
    private fun utcTime(text: String) = der(0x17, text.toByteArray(Charsets.US_ASCII))
    private fun oid(encoded: ByteArray) = der(0x06, encoded)
    private fun bitString(bytes: ByteArray) = der(0x03, byteArrayOf(0) + bytes)

    // --- a minimal binary AndroidManifest.xml --------------------------------------------
    //
    // The verdict turns on the declared minSdkVersion, so the fixture manifest has to carry a
    // real one, which means a real binary-XML document: apksig reads the attribute through the
    // same resource-map resolution the platform uses, and a hand-waved manifest simply throws.

    private fun stringPool(strings: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for ((index, text) in strings.withIndex()) {
            offsets[index] = data.size()
            val utf8 = text.toByteArray(Charsets.UTF_8)
            writeUtf8Length(data, text.length)
            writeUtf8Length(data, utf8.size)
            data.write(utf8)
            data.write(0)
        }
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val size = stringsStart + data.size()

        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0001)             // RES_STRING_POOL_TYPE
        buf.putShort(headerSize.toShort())
        buf.putInt(size)
        buf.putInt(strings.size)
        buf.putInt(0)                    // styleCount
        buf.putInt(0x00000100)           // UTF8_FLAG
        buf.putInt(stringsStart)
        buf.putInt(0)                    // stylesStart
        for (offset in offsets) buf.putInt(offset)
        buf.put(data.toByteArray())
        return buf.array()
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) {
            out.write(length)
        } else {
            out.write(((length ushr 8) and 0x7F) or 0x80)
            out.write(length and 0xFF)
        }
    }

    private fun resourceMap(ids: IntArray): ByteArray {
        val buf = ByteBuffer.allocate(8 + ids.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0180)             // RES_XML_RESOURCE_MAP_TYPE
        buf.putShort(8)
        buf.putInt(8 + ids.size * 4)
        for (id in ids) buf.putInt(id)
        return buf.array()
    }

    private fun startElement(nameIndex: Int, namespaceIndex: Int, attributes: List<IntArray>): ByteArray {
        val size = 16 + 20 + attributes.size * 20
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0102)             // RES_XML_START_ELEMENT_TYPE
        buf.putShort(16)                 // headerSize
        buf.putInt(size)
        buf.putInt(1)                    // lineNumber
        buf.putInt(-1)                   // comment
        buf.putInt(namespaceIndex)
        buf.putInt(nameIndex)
        buf.putShort(20)                 // attributeStart
        buf.putShort(20)                 // attributeSize
        buf.putShort(attributes.size.toShort())
        buf.putShort(0)                  // idIndex
        buf.putShort(0)                  // classIndex
        buf.putShort(0)                  // styleIndex
        for (attribute in attributes) {
            // ns string index, name string index, rawValue, Res_value size, res0, dataType, data
            buf.putInt(attribute[0])
            buf.putInt(attribute[1])
            buf.putInt(attribute[2])
            buf.putShort(8)
            buf.put(0)
            buf.put(attribute[3].toByte())
            buf.putInt(attribute[4])
        }
        return buf.array()
    }

    private fun endElement(nameIndex: Int, namespaceIndex: Int): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0103)             // RES_XML_END_ELEMENT_TYPE
        buf.putShort(16)
        buf.putInt(24)
        buf.putInt(1)                    // lineNumber
        buf.putInt(-1)                   // comment
        buf.putInt(namespaceIndex)
        buf.putInt(nameIndex)
        return buf.array()
    }

    /**
     * A binary manifest holding `<manifest><uses-sdk android:minSdkVersion="[minSdkVersion]"/>`
     * and nothing else.
     *
     * The resource map is indexed by string-pool index rather than being a list of the IDs the
     * document uses: the platform resolves an attribute name by looking its string index up in
     * this array, so `minSdkVersion`'s ID has to sit at `minSdkVersion`'s index. Index 2 is
     * where the string pool puts it.
     */
    private fun manifestWithMinSdk(minSdkVersion: Int): ByteArray {
        val strings = listOf("manifest", "uses-sdk", "minSdkVersion", ANDROID_NAMESPACE)
        val manifestIndex = 0
        val usesSdkIndex = 1
        val minSdkIndex = 2
        val namespaceIndex = 3

        val pool = stringPool(strings)
        val map = resourceMap(intArrayOf(0, 0, ATTR_MIN_SDK_VERSION, 0))
        // ns, name, rawValue, dataType (0x10 = TYPE_INT_DEC), data
        val minSdkAttribute = intArrayOf(namespaceIndex, minSdkIndex, -1, 0x10, minSdkVersion)
        val document = listOf(
            startElement(manifestIndex, -1, emptyList()),
            startElement(usesSdkIndex, -1, listOf(minSdkAttribute)),
            endElement(usesSdkIndex, -1),
            endElement(manifestIndex, -1)
        )

        val body = concatenated((listOf(pool, map) + document).toTypedArray())
        val root = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0003)            // RES_XML_TYPE
            .putShort(8)                 // headerSize
            .putInt(8 + body.size)
            .array()
        return root + body
    }

    /** DER for the OID 1.2.840.113549.1.1.11, `sha256WithRSAEncryption`. */
    private val sha256WithRsaAlgorithm = seq(
        oid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B))
    )

    private companion object {
        /** `android:minSdkVersion`, as the platform's resource map names it. */
        const val ATTR_MIN_SDK_VERSION = 0x0101020c

        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        /** DER for the OID 2.5.4.3, `id-at-commonName`. */
        val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)
    }
}
