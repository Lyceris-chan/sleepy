package dev.sleepy.app.model

import android.net.Uri

/**
 * Phases of a patch run, in the order the pipeline enters them.
 *
 * Each phase carries a user-facing [PatchProgress.step] description; the matching explanation is
 * on the [StepResult] entries, so the progress screen can show the reason next to the action
 * instead of only naming it.
 */
sealed interface PatchProgress {
    /** No run is in progress. */
    data object Idle : PatchProgress

    /** A download in progress, with the bytes received so far. */
    data class Downloading(
        val percent: Int,
        val bytesReceived: Long,
        val bytesTotal: Long,
        val step: String = "Downloading the original APK"
    ) : PatchProgress

    /** The downloaded archive is being read. */
    data class Decoding(val step: String) : PatchProgress

    /** Merging the app bundle's configuration splits (native libraries) into the base APK. */
    data class MergingSplits(
        val step: String,
        val librariesMerged: Int,
        val abis: List<String>
    ) : PatchProgress

    /** Patches are being applied, out of [total]. */
    data class Patching(
        val step: String,
        val current: Int,
        val total: Int,
        val explanation: String? = null
    ) : PatchProgress

    /** The patched files are being repackaged into an APK. */
    data class Assembling(val step: String) : PatchProgress

    /** The assembled APK is being signed, and then its signatures checked. */
    data class Signing(val step: String) : PatchProgress

    /** The run finished and produced [outputUri]. */
    data class Done(
        val outputUri: Uri,
        val sha256: String,
        val report: VerificationReport
    ) : PatchProgress

    /** The run stopped with an error, with [detail] when the error records one. */
    data class Failed(val message: String, val detail: String? = null) : PatchProgress
}
