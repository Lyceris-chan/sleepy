package dev.sleepy.app.model

enum class StepStatus {
    OK,
    SKIP,
    FAIL
}

data class StepResult(
    val label: String,
    val status: StepStatus,
    val detail: String? = null
)
