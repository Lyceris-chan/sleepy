package dev.sleepy.app.model

data class VerificationReport(
    val v1SignatureValid: Boolean,
    val v2SignatureValid: Boolean,
    val v3SignatureValid: Boolean,
    val zipalignPassed: Boolean,
    val patchesApplied: List<String>,
    val patchesFailed: List<String>
)
