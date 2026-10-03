package dev.sleepy.app.model

/**
 * The outcome of a finished run's step log.
 *
 * A step that reported SKIP is not a failure: it records that the build made the step
 * unnecessary, and it records the reason. Only [StepStatus.FAIL] changes the outcome, and only a
 * failure marked [StepResult.failureIsFatal] makes the produced file unusable.
 */
enum class BuildOutcome {
    /** Every step reported OK or SKIP, so the run produced the requested result. */
    Success,

    /**
     * The finished file is installable, but at least one requested change did not apply.
     *
     * A patch whose class is absent, or whose anchor did not match, produces this outcome: the
     * archive is still the build it started from, plus whatever did apply.
     */
    Incomplete,

    /**
     * The finished file does not hold the requested result and must not be offered as one.
     *
     * A signature that does not verify, an archive that cannot be aligned, or a clone whose
     * package name does not read back the same from the manifest and the resource table produces
     * this outcome.
     */
    Failed;

    companion object {
        /**
         * The outcome of [steps].
         *
         * A fatal failure takes precedence over a non-fatal one, so a run that produced an
         * unusable file is [Failed] regardless of the non-fatal failures beside it.
         */
        fun of(steps: List<StepResult>): BuildOutcome {
            val failures = steps.filter { it.status == StepStatus.FAIL }
            return when {
                failures.any { it.failureIsFatal } -> Failed
                failures.isNotEmpty() -> Incomplete
                else -> Success
            }
        }
    }
}
