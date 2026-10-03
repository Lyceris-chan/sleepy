package dev.sleepy.app.engine

import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.patches.OctoGramPatches

/**
 * Selects the patches that may run against a target APK, from the app version its classes
 * identify.
 *
 * A patch written against obfuscated class names belongs to one release of one app: R8 gives
 * those classes new names on every release, so the patch carries a version tag. An untagged patch
 * targets a class whose name does not change, such as a Firebase registrar, and the class being
 * present identifies it.
 *
 * [detectOctoGramVersion] recognizes the OctoGram build that `sources.json` registers by one
 * class that build carries. A build it does not recognize is unidentified. [admits] returns false
 * for every tagged patch on an unidentified build, and [refusalReason] supplies the reason the
 * step log states. A tagged patch whose class name happens to resolve on another release can edit
 * an unrelated method, which is worse than a patch that does nothing.
 */
object PatchVersionGate {

    /**
     * The class that identifies the OctoGram build [OctoGramPatches.REGISTERED_BUILD] names.
     *
     * The class holds the channel chat-list code that the sponsored-rows patch edits, and it
     * carries a name that R8 produced for that build alone. The presence of the class is the
     * signal; nothing else about the build is consulted.
     */
    private const val OCTOGRAM_REGISTERED_MARKER = "Lorg/telegram/ui/e6;"

    /**
     * The OctoGram version that [classToDexIndex] identifies, or null when it identifies none.
     *
     * Only the registered build is recognized. A build of any other release carries different
     * obfuscated class names, so naming one of them here would name a build none of the OctoGram
     * patches run on.
     */
    fun detectOctoGramVersion(classToDexIndex: Map<String, String>): String? =
        if (classToDexIndex.containsKey(OCTOGRAM_REGISTERED_MARKER)) {
            OctoGramPatches.REGISTERED_BUILD
        } else {
            null
        }

    /**
     * Whether [patch] may run against a build whose detected version is [detectedVersion].
     *
     * An untagged patch applies to every build. A tagged patch applies only when
     * [detectedVersion] equals its tag, and a null [detectedVersion] equals no tag, so an
     * unidentified build receives no tagged patch.
     */
    fun admits(patch: SmaliPatch, detectedVersion: String?): Boolean =
        patch.versionTag == null || patch.versionTag == detectedVersion

    /**
     * The reason to report when a set's [candidates] all stayed out of the build, or null when
     * version gating is not what kept them out.
     *
     * A caller asks this after a set matched nothing. The result distinguishes the two ways a
     * version tag can cause that: the build is unidentified, or it is identified as a version
     * other than the one the tag names. A null result means no tag was involved, and the caller
     * reports that the classes are absent instead.
     */
    fun refusalReason(candidates: List<SmaliPatch>, detectedVersion: String?): String? {
        val tagged = candidates.filter { it.versionTag != null }
        return when {
            tagged.isEmpty() -> null
            detectedVersion == null ->
                "This build's app version could not be identified, so patches written for a " +
                    "specific release were not attempted."
            tagged.any { it.versionTag != detectedVersion } ->
                "Written for a different app version, so it was not attempted on this build."
            else -> null
        }
    }
}
