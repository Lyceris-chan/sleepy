package dev.sleepy.app.engine

import android.content.Context
import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import javax.security.auth.x500.X500Principal

object ApkSignerHelper {

    private const val KEYSTORE_NAME = "sleepy_internal.p12"
    private const val KEY_ALIAS = "sleepy"
    private val KEY_PASSWORD = "sleepy_password_2026".toCharArray()

    /**
     * Signs [inputApk] into [outputApk] and leaves it there.
     *
     * Both ends are files because apksig streams the archive from one to the other: the signed
     * result is never a `ByteArray`. Handing the signer bytes instead would put a full copy of
     * the APK on the heap at the exact moment the heap is already holding everything that went
     * into it, and then write it out again — a 131 MB archive copied twice for nothing.
     *
     * @return the signed file, which is [outputApk], so the caller can chain off the result.
     */
    suspend fun sign(context: Context, inputApk: File, outputApk: File): File =
        withContext(Dispatchers.IO) {
            val keyStoreFile = File(context.filesDir, KEYSTORE_NAME)
            val keyStore = KeyStore.getInstance("PKCS12")

            if (!keyStoreFile.exists()) {
                keyStore.load(null, KEY_PASSWORD)
                val keyPair = generateKeyPair()
                val cert = generateCertificate(keyPair)
                keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, KEY_PASSWORD, arrayOf(cert))
                FileOutputStream(keyStoreFile).use { fos ->
                    keyStore.store(fos, KEY_PASSWORD)
                }
            } else {
                keyStoreFile.inputStream().use { fis ->
                    keyStore.load(fis, KEY_PASSWORD)
                }
            }

            val privateKey = keyStore.getKey(KEY_ALIAS, KEY_PASSWORD) as PrivateKey
            val certificate = keyStore.getCertificate(KEY_ALIAS) as X509Certificate

            val signerConfig = ApkSigner.SignerConfig.Builder(
                "sleepy",
                KeyConfig.Jca(privateKey),
                listOf(certificate)
            ).build()

            // A stale file would be signed around rather than replaced, so the destination
            // starts from nothing either way.
            if (outputApk.exists() && !outputApk.delete()) {
                throw IllegalStateException("Could not replace ${outputApk.absolutePath}")
            }

            val apkSigner = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()

            apkSigner.sign()
            outputApk
        }

    private fun generateKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        return keyGen.generateKeyPair()
    }

    private fun generateCertificate(keyPair: KeyPair): X509Certificate {
        // Construct a self-signed X.509 certificate using Sun/Java security provider or reflection
        val principal = X500Principal("CN=sleepy, OU=Personal Patch, O=sleepy, C=US")
        
        try {
            // Android platform has internal X509CertInfo / AndroidCA
            val certClass = Class.forName("sun.security.x509.X509CertInfo")
            val certImplClass = Class.forName("sun.security.x509.X509CertImpl")
            val valClass = Class.forName("sun.security.x509.CertificateValidity")
            val nameClass = Class.forName("sun.security.x509.X500Name")
            val algClass = Class.forName("sun.security.x509.AlgorithmId")
            val keyClass = Class.forName("sun.security.x509.CertificateX509Key")
            val snClass = Class.forName("sun.security.x509.CertificateSerialNumber")
            val verClass = Class.forName("sun.security.x509.CertificateVersion")

            val info = certClass.getConstructor().newInstance()
            val from = Date()
            val to = Date(from.time + 30L * 365 * 24 * 60 * 60 * 1000)
            val interval = valClass.getConstructor(Date::class.java, Date::class.java).newInstance(from, to)
            val sn = snClass.getConstructor(BigInteger::class.java).newInstance(BigInteger(64, SecureRandom()))
            val owner = nameClass.getConstructor(String::class.java).newInstance("CN=sleepy, OU=Personal Patch, O=sleepy, C=US")

            certClass.getMethod("set", String::class.java, Any::class.java).apply {
                invoke(info, "validity", interval)
                invoke(info, "serialNumber", sn)
                invoke(info, "subject", owner)
                invoke(info, "issuer", owner)
                invoke(info, "key", keyClass.getConstructor(PublicKey::class.java).newInstance(keyPair.public))
                invoke(info, "version", verClass.getConstructor(Int::class.javaPrimitiveType).newInstance(2))
                val algo = algClass.getMethod("get", String::class.java).invoke(null, "SHA256withRSA")
                invoke(info, "algorithmID", algClass.getConstructor(algClass).newInstance(algo))
            }

            val cert = certImplClass.getConstructor(certClass).newInstance(info) as X509Certificate
            certImplClass.getMethod("sign", PrivateKey::class.java, String::class.java).invoke(cert, keyPair.private, "SHA256withRSA")
            return cert
        } catch (e: Throwable) {
            // Fallback: Dynamic self-signed X.509 DER structure construction
            return SelfSignedCertBuilder.buildCertificate(keyPair, principal)
        }
    }
}
