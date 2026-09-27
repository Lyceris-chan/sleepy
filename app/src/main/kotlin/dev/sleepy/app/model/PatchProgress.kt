package dev.sleepy.app.model

import android.net.Uri

sealed interface PatchProgress {
    data object Idle : PatchProgress
    data class Downloading(val percent: Int, val bytesReceived: Long, val bytesTotal: Long) : PatchProgress
    data class Decoding(val step: String) : PatchProgress
    data class Patching(val step: String, val current: Int, val total: Int) : PatchProgress
    data class Assembling(val step: String) : PatchProgress
    data class Signing(val step: String) : PatchProgress
    data class Done(val outputUri: Uri, val sha256: String, val report: VerificationReport) : PatchProgress
    data class Failed(val message: String, val detail: String? = null) : PatchProgress
}
