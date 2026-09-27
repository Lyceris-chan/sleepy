package dev.sleepy.app.model

/**
 * What was actually checked about the produced APK.
 *
 * Every field here is the result of a real check run against the finished artefact.
 * [sourceIntegrityVerified] is deliberately nullable: `null` means the source published
 * no hash to compare against, which is different from a hash that failed to match.
 * [zipalignPassed] is nullable for the same reason: `null` means the archive's central
 * directory could not be read, so no entry was measured — which is a different thing from
 * a directory that was read and found clean.
 * [v1SignatureValid] is nullable for the third version of that reason: `null` means JAR
 * signing does not apply to this build at all, so there was no verdict to reach. The
 * signature is still written — see [dev.sleepy.app.engine.ApkVerifier] for why the scheme
 * cannot be checked above API 23 and why that is not a failure.
 */
data class VerificationReport(
    /**
     * Whether the JAR signature verified, `null` when the build's `minSdkVersion` puts it
     * below the API level at which Android stopped honouring JAR signing — a question that
     * does not apply, not one answered "no".
     */
    val v1SignatureValid: Boolean?,
    val v2SignatureValid: Boolean,
    val v3SignatureValid: Boolean,
    val zipalignPassed: Boolean?,
    val sourceIntegrityVerified: Boolean?,
    val mergedNativeLibraries: Int,
    /** Files merged in from the configuration splits' `res/` trees. */
    val mergedResourceFiles: Int,
    val outputBytes: Long,
    val patchesApplied: List<String>,
    val patchesSkipped: List<String>,
    val patchesFailed: List<String>
)
