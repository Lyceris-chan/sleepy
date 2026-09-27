package dev.sleepy.app.model

import android.net.Uri

/**
 * Phases of a patch run, in the order the pipeline enters them.
 *
 * Each phase carries a user-facing [PatchProgress.step] description; the matching
 * "why" text lives on the [StepResult] entries so the progress screen can show the
 * reasoning next to the action instead of only naming it.
 */
sealed interface PatchProgress {
    data object Idle : PatchProgress

    data class Downloading(
        val percent: Int,
        val bytesReceived: Long,
        val bytesTotal: Long,
        val step: String = "Downloading the original APK"
    ) : PatchProgress

    data class Decoding(val step: String) : PatchProgress

    /** Merging App Bundle configuration splits (native libraries) into the base APK. */
    data class MergingSplits(
        val step: String,
        val librariesMerged: Int,
        val abis: List<String>
    ) : PatchProgress

    data class Patching(
        val step: String,
        val current: Int,
        val total: Int,
        val explanation: String? = null
    ) : PatchProgress

    data class Assembling(val step: String) : PatchProgress
    data class Signing(val step: String) : PatchProgress

    data class Done(
        val outputUri: Uri,
        val sha256: String,
        val report: VerificationReport
    ) : PatchProgress

    data class Failed(val message: String, val detail: String? = null) : PatchProgress
}
