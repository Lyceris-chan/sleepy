package dev.sleepy.app.model

/**
 * What was actually checked about the produced APK.
 *
 * Every field here is the result of a real check run against the finished artefact.
 * [sourceIntegrityVerified] is deliberately nullable: `null` means the source published
 * no hash to compare against, which is different from a hash that failed to match.
 */
data class VerificationReport(
    val v1SignatureValid: Boolean,
    val v2SignatureValid: Boolean,
    val v3SignatureValid: Boolean,
    val zipalignPassed: Boolean,
    val sourceIntegrityVerified: Boolean?,
    val mergedNativeLibraries: Int,
    val outputBytes: Long,
    val patchesApplied: List<String>,
    val patchesSkipped: List<String>,
    val patchesFailed: List<String>
)
