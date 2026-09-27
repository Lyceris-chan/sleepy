package dev.sleepy.app.model

enum class StepStatus {
    OK,
    SKIP,
    FAIL
}

data class StepResult(
    val title: String,
    val explanation: String? = null,
    val technicalTarget: String? = null,
    val status: StepStatus,
    val detail: String? = null
) {
    val label: String get() = title

    constructor(label: String, status: StepStatus, detail: String? = null) : this(
        title = label,
        explanation = null,
        technicalTarget = label,
        status = status,
        detail = detail
    )
}
