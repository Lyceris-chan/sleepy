package dev.sleepy.app.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Signs the finished APK with a key the platform holds for this installation.
 *
 * The key is an EC P-256 key in the Android keystore. The TEE generates it and signs with it, or a
 * secure element does on a device that advertises StrongBox. The platform returns no copy of the
 * key, so the signing path writes no key material and no password to the filesystem. The
 * certificate that goes with the key is issued by the keystore in the same call.
 *
 * A device whose keystore cannot produce a key fails the run. This app does not fall back to a key
 * stored in its own storage directory, because Android requires an APK to be signed and a key on
 * disk is readable by anything that reads the app's storage.
 *
 * A `sleepy_internal.p12` keystore written by an earlier version of this app, and the cleartext
 * `sleepy_internal.p12.password` file beside it, are not read and not migrated. The key inside them
 * cannot be moved into the platform keystore, and a run that used them signs with a software key
 * while the platform key stays unused. Signing therefore produces a new identity: an app patched by
 * an earlier version of this patcher is reinstalled instead of updated, because Android does not
 * install an update signed by a different key.
 */
object ApkSignerHelper {

    private const val PLATFORM_KEY_ALIAS = "sleepy"

    private const val ANDROID_KEYSTORE_NAME = "AndroidKeyStore"

    /** The curve of [PLATFORM_KEY_ALIAS], which the TEE and StrongBox both implement. */
    private const val EC_CURVE = "secp256r1"

    private const val CERTIFICATE_SUBJECT = "CN=sleepy, OU=Personal Patch, O=sleepy, C=US"

    private const val CERTIFICATE_VALIDITY_MILLIS = 3650L * 24 * 60 * 60 * 1000

    /** The key and certificate a run signs with. */
    internal class SigningIdentity(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    /**
     * Signs [inputApk] into [outputApk].
     *
     * Both ends are files because apksig streams the archive from one to the other: the signed
     * result is not held as a `ByteArray`. Passing bytes to the signer instead puts a full copy of
     * the APK on the heap at the moment the heap already holds everything that went into it, and
     * then writes it out again—a 131 MB archive copied twice.
     *
     * The key does not leave the platform keystore: apksig takes the JCA [PrivateKey] the keystore
     * returns and signs through it, so no private key bytes pass through this app's memory.
     *
     * @return the signed file, which is [outputApk], so a caller can pass the result to the next
     *     step.
     */
    suspend fun sign(context: Context, inputApk: File, outputApk: File): File =
        withContext(Dispatchers.IO) {
            signWith(signingIdentity { platformIdentity(context) }, inputApk, outputApk)
        }

    /**
     * The identity this installation signs with, or a failure that names why there is none.
     *
     * [platform] returns null when the platform has no key to offer, which is the only case in
     * which this function throws. There is deliberately no second key source: the file-backed
     * keystore an earlier version used holds an exportable private key in the app's storage
     * directory, and a run that falls back to it signs with a key that anything able to read that
     * directory can copy.
     */
    internal fun signingIdentity(platform: () -> SigningIdentity?): SigningIdentity =
        platform() ?: throw IllegalStateException(
            "This device's keystore would not provide a signing key, and Android does not " +
                "install an unsigned APK."
        )

    /**
     * Signs [inputApk] into [outputApk] with [identity], using all three signature schemes.
     *
     * The destination is deleted first, so that the signer replaces a stale file rather than
     * signing around it.
     */
    internal fun signWith(identity: SigningIdentity, inputApk: File, outputApk: File): File {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "sleepy",
            KeyConfig.Jca(identity.privateKey),
            listOf(identity.certificate)
        ).build()

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
        return outputApk
    }

    /**
     * A key from the platform's Android keystore, generated on first use, or null when the
     * platform cannot provide one.
     *
     * The key is EC P-256 and its certificate is issued by the same call: [KeyGenParameterSpec]
     * carries the subject, the serial number and the validity window, and the keystore returns a
     * self-signed certificate for the key it created. The private key is not readable and not
     * exportable, so there is no password to store and no key copy to protect with one.
     *
     * A device that advertises a secure element is asked for one first, which moves the key off the
     * CPU; a request the device cannot satisfy falls back to the TEE. A device that has neither, or
     * one whose keystore rejects every request, returns null.
     */
    internal fun platformIdentity(context: Context): SigningIdentity? = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_NAME).apply { load(null) }
        if (!keyStore.containsAlias(PLATFORM_KEY_ALIAS)) {
            generatePlatformKey(context)
        }
        SigningIdentity(
            privateKey = keyStore.getKey(PLATFORM_KEY_ALIAS, null) as PrivateKey,
            certificate = keyStore.getCertificate(PLATFORM_KEY_ALIAS) as X509Certificate
        )
    } catch (e: Exception) {
        // Any failure here means the device has no usable platform key. The caller reports that as
        // a failed run; the next run asks the platform again.
        null
    }

    /**
     * Generates [PLATFORM_KEY_ALIAS] in the Android keystore, in a secure element when the device
     * advertises one and in the TEE otherwise.
     */
    private fun generatePlatformKey(context: Context) {
        if (isStrongBoxAvailable(context)) {
            try {
                generatePlatformKey(strongBox = true)
                return
            } catch (e: Exception) {
                // A device can advertise a secure element and still reject a key request for it:
                // the hardware can be busy, or can already hold its maximum number of keys. The TEE
                // is the next place to ask, so the request is made again there.
            }
        }
        generatePlatformKey(strongBox = false)
    }

    /**
     * Whether the device advertises a secure element for keystore keys.
     *
     * StrongBox exists from API 28, so an older platform returns false without a call.
     */
    private fun isStrongBoxAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun generatePlatformKey(strongBox: Boolean) {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE_NAME
        )
        val spec = KeyGenParameterSpec.Builder(PLATFORM_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal(CERTIFICATE_SUBJECT))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()))
            .setCertificateNotBefore(Date())
            .setCertificateNotAfter(Date(System.currentTimeMillis() + CERTIFICATE_VALIDITY_MILLIS))
        // Guarded here rather than in the caller: setIsStrongBoxBacked exists from API 28, and the
        // parameter is only true on a device that advertises the feature.
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            spec.setIsStrongBoxBacked(true)
        }
        generator.initialize(spec.build())
        generator.generateKeyPair()
    }
}
