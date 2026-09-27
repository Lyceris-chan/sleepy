package dev.sleepy.app.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.security.auth.x500.X500Principal

object SelfSignedCertBuilder {

    /**
     * Pure Java/Kotlin minimal X.509 v3 self-signed certificate generator using standard DER encoding.
     */
    fun buildCertificate(keyPair: KeyPair, principal: X500Principal): X509Certificate {
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 3650L * 24 * 60 * 60 * 1000) // 10 years
        val serialNumber = BigInteger(64, SecureRandom())

        val tbsCertificate = encodeTBS(keyPair.public, principal, serialNumber, notBefore, notAfter)

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbsCertificate)
        val signature = signer.sign()

        val certBytes = encodeSequence(
            tbsCertificate,
            encodeAlgorithmId("1.2.840.113549.1.1.11"), // sha256WithRSAEncryption
            encodeBitString(signature)
        )

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
    }

    private fun encodeTBS(
        publicKey: PublicKey,
        principal: X500Principal,
        serialNumber: BigInteger,
        notBefore: Date,
        notAfter: Date
    ): ByteArray {
        val version = byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02) // v3 [0] EXPLICIT INTEGER 2
        val serial = encodeInteger(serialNumber)
        val sigAlg = encodeAlgorithmId("1.2.840.113549.1.1.11")
        val issuer = principal.encoded
        val validity = encodeSequence(encodeTime(notBefore), encodeTime(notAfter))
        val subject = principal.encoded
        val subjectPublicKeyInfo = publicKey.encoded

        return encodeSequence(
            version,
            serial,
            sigAlg,
            issuer,
            validity,
            subject,
            subjectPublicKeyInfo
        )
    }

    private fun encodeSequence(vararg elements: ByteArray): ByteArray {
        val totalLength = elements.sumOf { it.size }
        val baos = ByteArrayOutputStream()
        baos.write(0x30) // SEQUENCE tag
        writeLength(baos, totalLength)
        elements.forEach { baos.write(it) }
        return baos.toByteArray()
    }

    private fun writeLength(baos: ByteArrayOutputStream, length: Int) {
        if (length < 128) {
            baos.write(length)
        } else if (length < 256) {
            baos.write(0x81)
            baos.write(length)
        } else if (length < 65536) {
            baos.write(0x82)
            baos.write(length shr 8)
            baos.write(length and 0xFF)
        } else {
            baos.write(0x83)
            baos.write(length shr 16)
            baos.write((length shr 8) and 0xFF)
            baos.write(length and 0xFF)
        }
    }

    private fun encodeInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val baos = ByteArrayOutputStream()
        baos.write(0x02) // INTEGER tag
        writeLength(baos, bytes.size)
        baos.write(bytes)
        return baos.toByteArray()
    }

    private fun encodeAlgorithmId(oid: String): ByteArray {
        // DER encode OID sequence: 30 0D 06 09 2A 86 48 86 F7 0D 01 01 0B 05 00
        return byteArrayOf(
            0x30, 0x0D,
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B,
            0x05, 0x00 // NULL parameter
        )
    }

    private fun encodeTime(date: Date): ByteArray {
        val sdf = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val bytes = sdf.format(date).toByteArray(Charsets.US_ASCII)
        val baos = ByteArrayOutputStream()
        baos.write(0x17) // UTCTime tag
        writeLength(baos, bytes.size)
        baos.write(bytes)
        return baos.toByteArray()
    }

    private fun encodeBitString(bytes: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(0x03) // BIT STRING tag
        writeLength(baos, bytes.size + 1)
        baos.write(0x00) // unused bits = 0
        baos.write(bytes)
        return baos.toByteArray()
    }
}
