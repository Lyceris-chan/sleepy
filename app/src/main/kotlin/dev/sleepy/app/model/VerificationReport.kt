package dev.sleepy.app.model

/**
 * What was checked about the produced APK.
 *
 * Every field is the result of a check run against the finished artifact.
 * [sourceIntegrityVerified] is deliberately nullable: `null` means the source published no hash to
 * compare against, which is different from a hash that failed to match. [zipalignPassed] is
 * nullable for the same reason: `null` means the archive's central directory could not be read, so
 * no entry was measured, which is different from a directory that was read and found clean.
 * [v1SignatureValid] is nullable for the third version of that reason: `null` means JAR signing
 * does not apply to this build at all, so there was no verdict to reach. The signature is still
 * written—see [dev.sleepy.app.engine.ApkVerifier] for why the scheme cannot be checked later than
 * API 23 and why that is not a failure.
 */
data class VerificationReport(
    /**
     * The outcome of the step log, so the result screen can distinguish a complete run from one
     * that finished with degradations and from one whose file must not be used. See
     * [BuildOutcome.of] for how the steps determine it.
     */
    val outcome: BuildOutcome,
    /**
     * Whether the JAR signature check passed, `null` when the build's `minSdkVersion` puts it earlier than
     * the API level at which Android stopped honoring JAR signing—a question that does not
     * apply, not one answered "no".
     */
    val v1SignatureValid: Boolean?,
    /** Whether the APK Signature Scheme v2 signature check passed. */
    val v2SignatureValid: Boolean,
    /** Whether the APK Signature Scheme v3 signature check passed. */
    val v3SignatureValid: Boolean,
    /** Whether every entry is aligned, or null when the central directory could not be read. */
    val zipalignPassed: Boolean?,
    /**
     * Whether the source matched the published SHA-256, or null when the source publishes no
     * hash.
     */
    val sourceIntegrityVerified: Boolean?,
    /** The size of the finished APK in bytes. */
    val outputBytes: Long,
    /** The titles of the steps that reported OK. */
    val patchesApplied: List<String>,
    /** The titles of the steps that reported SKIP. */
    val patchesSkipped: List<String>,
    /** The titles of the steps that reported FAIL. */
    val patchesFailed: List<String>
)
