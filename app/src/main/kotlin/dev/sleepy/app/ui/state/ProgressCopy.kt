package dev.sleepy.app.ui.state

import dev.sleepy.app.model.PatchProgress

/**
 * What the progress screen shows for the current phase, and what a screen reader announces.
 *
 * The copy is built here rather than inside the composable so the pacing rule can be checked on the
 * JVM: [announcement] carries only the two lines that change at a phase boundary, while [detail]
 * carries the values that move continuously—a byte count, a step number, a merge count. A live
 * region placed on the announcement therefore announces each phase once, not each percent.
 *
 * The two fields are separate for that reason: folding [detail] into [announcement] makes the
 * screen announce every progress tick.
 */
internal data class PhaseCopy(
    val title: String,
    val why: String,
    val detail: String?,
    val announcement: String
)

/**
 * The copy for [progress], with the continuously changing values kept out of the announcement.
 *
 * [PhaseCopy.announcement] is `title` and `why` joined, which is what the live region announces;
 * the two are constant for the length of a phase except where a phase genuinely reaches a new
 * stage, such as the merge count rising from zero.
 */
internal fun phaseCopy(progress: PatchProgress): PhaseCopy {
    val title = when (val p = progress) {
        is PatchProgress.Downloading -> "Downloading the app"
        is PatchProgress.Decoding -> "Opening the package"
        is PatchProgress.MergingSplits -> "Adding the missing native libraries"
        is PatchProgress.Patching -> p.step
        is PatchProgress.Assembling -> "Rebuilding the APK"
        is PatchProgress.Signing -> "Signing the result"
        is PatchProgress.Done -> "Finished"
        is PatchProgress.Failed -> "Something went wrong"
        PatchProgress.Idle -> "Getting ready"
    }

    val why = when (val p = progress) {
        is PatchProgress.Downloading ->
            "Fetching the untouched original so " + "every change can be traced."
        is PatchProgress.Decoding ->
            "Reading the package in memory. " + "The file on disk is never modified."
        is PatchProgress.MergingSplits ->
            if (p.librariesMerged > 0) {
                "This build ships its native code separately. " +
                    "Putting it back is what stops the app crashing on launch."
            } else {
                "Checking the extra pieces this build was split into."
            }
        is PatchProgress.Patching -> p.explanation ?: "Applying the changes you selected."
        is PatchProgress.Assembling ->
            "Putting the modified files back and " + "re-aligning the archive."
        is PatchProgress.Signing ->
            "Android refuses to install an unsigned app, " +
                "and the result is verified afterward."
        is PatchProgress.Done -> "The patched app is ready to install."
        is PatchProgress.Failed -> p.message
        PatchProgress.Idle -> "Preparing the patching engine."
    }

    val detail: String? = when (val p = progress) {
        is PatchProgress.Downloading -> {
            val received = p.bytesReceived / (1024 * 1024.0)
            val total = p.bytesTotal / (1024 * 1024.0)
            if (p.bytesTotal > 0) {
                "%.1f of %.1f MB".format(received, total)
            } else {
                "%.1f MB so far".format(received)
            }
        }
        is PatchProgress.MergingSplits ->
            if (p.librariesMerged > 0) {
                "${p.librariesMerged} libraries for ${p.abis.joinToString(", ")}"
            } else {
                null
            }
        is PatchProgress.Patching ->
            if (p.total > 0) "Step ${(p.current + 1).coerceAtMost(p.total)} of ${p.total}" else null
        is PatchProgress.Failed -> p.detail?.lineSequence()?.take(3)?.joinToString("\n")
        else -> null
    }

    return PhaseCopy(
        title = title,
        why = why,
        detail = detail,
        announcement = "$title. $why"
    )
}

/**
 * The label of the button that opens the result screen, or null while a run is still going.
 *
 * A finished run and a failed one both end on the result screen, and the label states which of the
 * two the reader is about to see.
 */
internal fun resultActionLabel(progress: PatchProgress): String? = when (progress) {
    is PatchProgress.Done -> "View the result"
    is PatchProgress.Failed -> "See what failed"
    else -> null
}

/** The card that replaces the phase card after the run is stopped from the progress screen. */
internal data class StoppedCopy(
    val title: String,
    val why: String,
    val action: String
)

internal val STOPPED_COPY = StoppedCopy(
    title = "Patching stopped",
    why = "Nothing was installed. The app you started with is untouched.",
    action = "Back to the apps"
)

/** How the step list reaches a newly appended row. */
internal enum class ScrollMotion { ANIMATED, IMMEDIATE }

/**
 * The motion to scroll with: animation only where the platform reports that animations are enabled.
 *
 * A reader who turns animations off in the system settings asks for no motion, and an auto-scroll
 * is the kind of motion that setting controls. The platform reports the setting through
 * [android.animation.ValueAnimator.areAnimatorsEnabled], which the screen reads; the mapping is
 * here so it can be checked without a device.
 */
internal fun scrollMotionFor(animationsEnabled: Boolean): ScrollMotion =
    if (animationsEnabled) ScrollMotion.ANIMATED else ScrollMotion.IMMEDIATE

/**
 * The index of the newest step to reveal, or null when the log is empty and there is nothing to
 * scroll to.
 */
internal fun newestStepIndex(stepCount: Int): Int? = if (stepCount <= 0) null else stepCount - 1
