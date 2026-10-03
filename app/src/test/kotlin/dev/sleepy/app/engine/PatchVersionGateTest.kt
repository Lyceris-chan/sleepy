package dev.sleepy.app.engine

import dev.sleepy.app.model.SmaliPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version gate that decides which patches a target APK may receive.
 *
 * A patch tagged for a release names obfuscated classes from that release, so the gate applies it
 * only to a build identified as that release. These tests hold the two refusals apart and keep the
 * untagged path open: the Discord patches and the OctoGram Firebase registrar edits carry no tag
 * and still apply to a build whose OctoGram version is unknown.
 */
class PatchVersionGateTest {

    private fun tagged(version: String) =
        SmaliPatch(smaliPath = "org/telegram/ui/e6.smali", versionTag = version)

    private fun untagged() = SmaliPatch(
        smaliPath = "com/google/firebase/abt/component/AbtRegistrar.smali"
    )

    /** The class map of the OctoGram build the manifest's source registers. */
    private val registeredBuild = mapOf("Lorg/telegram/ui/e6;" to "classes3.dex")

    /**
     * The classes the OctoGram 3.6.0 build carries. The same three entries once identified that
     * build, and no source offers it now, so they must identify nothing.
     */
    private val olderBuild = mapOf(
        "Lorg/telegram/ui/o;" to "classes4.dex",
        "Lorg/telegram/messenger/m0;" to "classes3.dex",
        "Ly5l;" to "classes3.dex",
        "Lhxk;" to "classes3.dex"
    )

    @Test
    fun theRegisteredBuildIsIdentified() {
        assertEquals("3.6.1", PatchVersionGate.detectOctoGramVersion(registeredBuild))
    }

    @Test
    fun theOlderBuildIsNotIdentified() {
        assertNull(
            "3.6.0 is not a build this app patches, so its classes must not name a version",
            PatchVersionGate.detectOctoGramVersion(olderBuild)
        )
    }

    @Test
    fun anUnidentifiedBuildReceivesNoTaggedPatch() {
        assertFalse(
            "a patch written for one release may not run on a build whose version is unknown",
            PatchVersionGate.admits(tagged("3.6.1"), detectedVersion = null)
        )
    }

    @Test
    fun anUntaggedPatchRunsOnAnUnidentifiedBuild() {
        assertTrue(
            "a patch with no version tag is resolved by class name on every build",
            PatchVersionGate.admits(untagged(), detectedVersion = null)
        )
    }

    @Test
    fun aTagThatNamesAnotherVersionIsRefused() {
        assertFalse(PatchVersionGate.admits(tagged("3.6.1"), detectedVersion = "3.6.2"))
        assertTrue(PatchVersionGate.admits(tagged("3.6.1"), detectedVersion = "3.6.1"))
    }

    @Test
    fun theRefusalForAnUnidentifiedBuildNamesTheMissingVersion() {
        val reason = PatchVersionGate.refusalReason(listOf(tagged("3.6.1")), detectedVersion = null)
        assertNotNull("a refused set must state a reason", reason)
        assertTrue(
            "the reason has to say the version was not identified: $reason",
            reason!!.contains("could not be identified")
        )
    }

    @Test
    fun theRefusalForAnIdentifiedBuildStillNamesTheVersionDifference() {
        assertEquals(
            "Written for a different app version, so it was not attempted on this build.",
            PatchVersionGate.refusalReason(listOf(tagged("3.6.1")), detectedVersion = "3.6.2")
        )
    }

    @Test
    fun anUntaggedSetHasNoVersionRefusal() {
        assertNull(
            "with no tag involved, the caller reports that the classes are absent instead",
            PatchVersionGate.refusalReason(listOf(untagged()), detectedVersion = null)
        )
    }
}
