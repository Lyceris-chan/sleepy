package dev.sleepy.app.model

/** The result a pipeline step reports. */
enum class StepStatus {
    /** The step completed. */
    OK,

    /** The build made the step unnecessary, so it did not run. */
    SKIP,

    /** The step did not produce its result. */
    FAIL
}

/**
 * One entry in the step log: what a step did, and what it found.
 *
 * @property title The step's name in the log.
 * @property explanation Why the step ran, or why it was skipped, or null when it records none.
 * @property technicalTarget The class, method, or file the step acted on.
 * @property status The result the step reported.
 * @property detail Extra information the step recorded, or null when it recorded none.
 * @property failureIsFatal Whether a [StepStatus.FAIL] on this step leaves the produced file
 *   unusable. Defaults to true: a step that reports failure blocks the build unless it explicitly
 *   marks the failure as a degradation. Steps that patch a class the target APK need not contain
 *   set this to false, because the file they leave behind is still a valid build of what they
 *   started from.
 */
data class StepResult(
    val title: String,
    val explanation: String? = null,
    val technicalTarget: String? = null,
    val status: StepStatus,
    val detail: String? = null,
    val failureIsFatal: Boolean = true
) {
    val label: String get() = title

    /** Creates a result whose title and technical target are both [label]. */
    constructor(
        label: String,
        status: StepStatus,
        detail: String? = null,
        failureIsFatal: Boolean = true
    ) : this(
        title = label,
        explanation = null,
        technicalTarget = label,
        status = status,
        detail = detail,
        failureIsFatal = failureIsFatal
    )
}
