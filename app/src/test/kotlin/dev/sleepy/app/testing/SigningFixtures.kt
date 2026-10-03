package dev.sleepy.app.testing

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals

/**
 * Fixtures for the APK signing tests: a self-signed certificate, and a minimal APK carrying a real
 * binary manifest.
 *
 * apksig signs with a certificate and a private key, and the JDK has no public API that mints a
 * certificate: every route that does requires BouncyCastle or a `keytool` run, and neither belongs
 * in a unit test. What is needed is small—a v3 certificate with a name, a validity window and a
 * public key, and no extensions at all—so it is encoded in DER here, the way a platform keystore
 * encodes the certificate it mints. The manifest is written as real binary XML because apksig
 * reads `minSdkVersion` out of it through the same resource-map resolution the platform uses.
 */

/** A self-signed certificate for [keyPair], valid from 2020 to 2030. */
fun selfSignedCertificate(keyPair: KeyPair): X509Certificate {
    val ecdsa = keyPair.public.algorithm == "EC"
    val algorithm = if (ecdsa) ECDSA_WITH_SHA256 else rsaWithSha256Algorithm
    val name = derSeq(derSet(derSeq(derOid(OID_COMMON_NAME), derUtf8("sleepy test"))))
    val tbsCertificate = derSeq(
        derContextTagged(0xA0, derInteger(2)), // version: v3
        derInteger(1),                         // serialNumber
        algorithm,
        name,                                  // issuer
        derSeq(derUtcTime("200101000000Z"), derUtcTime("300101000000Z")),
        name,                                  // subject
        keyPair.public.encoded
    )
    val signatureBytes = Signature.getInstance(
        if (ecdsa) "SHA256withECDSA" else "SHA256withRSA"
    ).run {
        initSign(keyPair.private)
        update(tbsCertificate)
        sign()
    }
    val encoded = derSeq(tbsCertificate, algorithm, derBitString(signatureBytes))

    val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(encoded.inputStream()) as X509Certificate
    assertEquals(
        "the certificate must certify the key that signs with it",
        keyPair.public,
        certificate.publicKey
    )
    return certificate
}

/**
 * An APK of one binary manifest declaring `minSdkVersion` [minSdkVersion], plus one entry.
 *
 * The entry exists so the archive is an APK rather than a bare manifest, which is what the signer
 * expects to read.
 */
fun minimalApk(minSdkVersion: Int): File {
    val apk = File.createTempFile("sleepy-sign-fixture-", ".apk")
    apk.deleteOnExit()
    ZipOutputStream(apk.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
        zip.write(manifestWithMinSdk(minSdkVersion))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("classes.dex"))
        zip.write(ByteArray(64) { it.toByte() })
        zip.closeEntry()
    }
    return apk
}

/**
 * `<manifest><uses-sdk android:minSdkVersion="[minSdkVersion]"/></manifest>` as binary XML.
 *
 * The resource map is indexed by string-pool index: the platform resolves an attribute name by
 * looking its string index up in this array, so `minSdkVersion`'s resource ID has to sit at the
 * index the string pool gives it, which is 2 here.
 */
fun manifestWithMinSdk(minSdkVersion: Int): ByteArray {
    val strings = listOf("manifest", "uses-sdk", "minSdkVersion", ANDROID_NAMESPACE)
    val namespaceIndex = 3

    val pool = stringPool(strings)
    val map = resourceMap(intArrayOf(0, 0, ATTR_MIN_SDK_VERSION, 0))
    // ns, name, rawValue, dataType (0x10 = TYPE_INT_DEC), data
    val minSdkAttribute = intArrayOf(namespaceIndex, 2, -1, 0x10, minSdkVersion)
    val document = listOf(
        startElement(0, -1, emptyList()),
        startElement(1, -1, listOf(minSdkAttribute)),
        endElement(1, -1),
        endElement(0, -1)
    )

    val body = ByteArrayOutputStream()
    body.write(pool)
    body.write(map)
    document.forEach { body.write(it) }
    val bodyBytes = body.toByteArray()

    val root = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0x0003)            // RES_XML_TYPE
        .putShort(8)                 // headerSize
        .putInt(8 + bodyBytes.size)
        .array()
    return root + bodyBytes
}

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
    for (offset in offsets) {
        buf.putInt(offset)
    }
    buf.put(data.toByteArray())
    return buf.array()
}

private fun writeUtf8Length(out: OutputStream, length: Int) {
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
    for (id in ids) {
        buf.putInt(id)
    }
    return buf.array()
}

private fun startElement(
    nameIndex: Int,
    namespaceIndex: Int,
    attributes: List<IntArray>
): ByteArray {
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

// --- DER, for the certificate fixture -------------------------------------------------------

private fun der(tag: Int, content: ByteArray): ByteArray =
    byteArrayOf(tag.toByte()) + derLength(content.size) + content

private fun derLength(size: Int): ByteArray = when {
    size < 0x80 -> byteArrayOf(size.toByte())
    size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
    else -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
}

private fun concatenated(parts: Array<out ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    for (part in parts) {
        out.write(part)
    }
    return out.toByteArray()
}

private fun derSeq(vararg parts: ByteArray) = der(0x30, concatenated(parts))
private fun derSet(vararg parts: ByteArray) = der(0x31, concatenated(parts))
private fun derInteger(value: Int) = der(0x02, byteArrayOf(value.toByte()))
private fun derContextTagged(tag: Int, content: ByteArray) = der(tag, content)
private fun derUtf8(text: String) = der(0x0C, text.toByteArray(Charsets.UTF_8))
private fun derUtcTime(text: String) = der(0x17, text.toByteArray(Charsets.US_ASCII))
private fun derOid(encoded: ByteArray) = der(0x06, encoded)
private fun derBitString(bytes: ByteArray) = der(0x03, byteArrayOf(0) + bytes)

/** `android:minSdkVersion`, as the platform's resource map names it. */
private const val ATTR_MIN_SDK_VERSION = 0x0101020c

private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

/** DER for the OID 2.5.4.3, `id-at-commonName`. */
private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

/**
 * DER for the OID 1.2.840.10045.4.3.2, `ecdsa-with-SHA256`, with no parameters field:
 * RFC 5758 defines the identifier as the OID alone, unlike the RSA identifiers, which carry an
 * explicit NULL.
 */
private val ECDSA_WITH_SHA256 = byteArrayOf(
    0x30, 0x0A, 0x06, 0x08,
    0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02
)

/** DER for the OID 1.2.840.113549.1.1.11, `sha256WithRSAEncryption`. */
private val rsaWithSha256Algorithm = derSeq(
    derOid(
        byteArrayOf(
            0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B
        )
    )
)
