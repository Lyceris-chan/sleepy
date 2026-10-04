package dev.sleepy.app.patches

import dev.sleepy.app.engine.BinaryXmlModifier
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.engine.PatchVersionGate
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.SelectivePatchGenerator
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.testing.ReferenceApks
import dev.sleepy.app.testing.dexEntries
import dev.sleepy.app.testing.manifestOf
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The OctoGram patch payloads on the shipped 3.6.1 build, and their refusal on the older 3.6.0
 * build.
 *
 * The 3.6.1 build receives every edit the sets generate, and the package rename is applied to its
 * manifest. The 3.6.0 build is the local fixture for a release no source offers: it identifies no
 * supported version, so the gate refuses every tagged edit and only the untagged Firebase
 * registrars resolve by class name.
 */
class OctoGramPayloadIntegrationTest {

    @Test
    fun octoGram361DynamicResolutionAndSurgicalPatching() = runBlocking {
        val apkFile = ReferenceApks.octoGram361Arm64
        // An absent fixture is a skipped test, not a passing one: a return from the body reports
        // a pass instead, and this file's coverage is absent from CI.
        assumeTrue("${apkFile.path} is not on this machine", apkFile.exists())

        println("Reading OctoGram 3.6.1 APK (size: ${apkFile.length()} bytes)...")
        // 1. Extract DEX entries
        val dexEntries = dexEntries(apkFile)
        assertEquals(4, dexEntries.size)

        // 2. Build class-to-DEX index
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        assertEquals("classes.dex", classToDex["Lcom/google/firebase/abt/component/AbtRegistrar;"])
        assertEquals("classes3.dex", classToDex["Lorg/telegram/ui/e6;"])
        assertEquals("classes3.dex", classToDex["La28;"])
        assertEquals("classes3.dex", classToDex["Lgpd;"])
        assertEquals("classes3.dex", classToDex["Ls04;"])
        assertEquals("classes3.dex", classToDex["Lj6d;"])
        assertEquals("classes3.dex", classToDex["Lorg/telegram/ui/ActionBar/ActionBarLayout;"])
        assertEquals("classes3.dex", classToDex["Lt6;"])
        assertEquals("classes3.dex", classToDex["Lcn8;"])
        assertEquals("classes.dex", classToDex["Lit/octogram/android/unsorted/OctoConfig;"])

        println("Class-to-DEX index verified successfully across 4 DEX files!")

        // 3. Filter and resolve every OctoGram edit for this APK, through the path the pipeline
        // takes: a selection naming every item of every set, passed to each set's own generator.
        // A set resolves its own patches from the selection, so this also checks that the item
        // table and the edit groups line up for a build this app offers.
        val detected = PatchVersionGate.detectOctoGramVersion(classToDex)
        assertEquals(
            "the fixture must be the build the manifest's source registers",
            OctoGramPatches.REGISTERED_BUILD,
            detected
        )
        val selection =
            PatchSelection.fromSavedIds(OctoGramPatches.ALL.map { it.id }, PatchItemCatalog)
        val target = TargetApk(classToDex, dexEntries)
        val patchesToApply = mutableListOf<SmaliPatch>()
        for (patchSet in OctoGramPatches.ALL) {
            val generated =
                (patchSet.generator as SelectivePatchGenerator).generate(target, selection)
            assertNull("${patchSet.id} has nothing to apply", generated.skipReason)
            val matching = generated.patches.filter { patch ->
                if (!PatchVersionGate.admits(patch, detected)) return@filter false
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]
                actualDex != null && dexEntries.containsKey(actualDex)
            }
            assertEquals(
                "every edit of ${patchSet.id} must resolve on this build",
                generated.patches.size,
                matching.size
            )
            matching.forEach { patch ->
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]!!
                patchesToApply.add(patch.copy(dexName = actualDex))
            }
        }
        assertEquals("the catalog is forty-three edits", 43, patchesToApply.size)

        println("Total 1-to-1 patch edits queued: ${patchesToApply.size}")

        // 4. Test surgical patching on classes.dex: the four Firebase registrars and the
        // external-browser default.
        val classesDexPatches = patchesToApply.filter { it.dexName == "classes.dex" }
        assertEquals(
            "the four Firebase registrars and the external-browser default",
            5,
            classesDexPatches.size
        )
        val (patchedClassesDex, classesDexResults) = DexProcessor.patchDexSurgically(
            dexBytes = dexEntries["classes.dex"]!!,
            patches = classesDexPatches
        )
        assertTrue("classes.dex output size must be positive", patchedClassesDex.isNotEmpty())
        classesDexResults.forEach {
            assertEquals("classes.dex patch must succeed: ${it.label}", StepStatus.OK, it.status)
        }
        println("classes.dex: all ${classesDexResults.size} patches applied with status OK!")

        // 5. Test surgical patching on classes3.dex
        val dex3Patches = patchesToApply.filter { it.dexName == "classes3.dex" }
        assertEquals(
            "thirty-eight edits, the other five being the Firebase registrars and the external-browser " +
                "default in classes.dex",
            38,
            dex3Patches.size
        )
        // The classes the crash reporter and the premium rows live in, named so that an edit that
        // stops resolving shows up as a missing class here.
        listOf("yb3.smali", "org/telegram/ui/ProfileActivity.smali", "teb.smali").forEach { path ->
            assertTrue(
                "$path must be among the edits this build gets",
                dex3Patches.any { it.smaliPath == path }
            )
        }
        val (patchedClasses3Dex, dex3Results) = DexProcessor.patchDexSurgically(
            dexBytes = dexEntries["classes3.dex"]!!,
            patches = dex3Patches
        )
        assertTrue("classes3.dex output size must be positive", patchedClasses3Dex.isNotEmpty())
        dex3Results.forEach {
            assertEquals("classes3.dex patch must succeed: ${it.label}", StepStatus.OK, it.status)
        }
        println("classes3.dex: all ${dex3Results.size} patches applied with status OK!")
    }

    @Test
    fun olderOctoGramBuildIsRefusedBecauseItsVersionIsNotIdentified() = runBlocking {
        val apkFile = ReferenceApks.octoGramArm64
        assumeTrue("${apkFile.path} is not on this machine", apkFile.exists())

        println("Reading OctoGram 3.6.0 APK (size: ${apkFile.length()} bytes)...")
        // 1. Extract DEX entries
        val dexEntries = dexEntries(apkFile)

        // 2. Build class-to-DEX index
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        assertEquals("classes4.dex", classToDex["Lorg/telegram/ui/o;"])
        assertEquals("classes3.dex", classToDex["Lorg/telegram/messenger/m0;"])
        assertEquals("classes3.dex", classToDex["Ly5l;"])
        assertEquals("classes4.dex", classToDex["Lbe6;"])
        assertEquals("classes3.dex", classToDex["Lhxk;"])
        assertEquals("classes3.dex", classToDex["Lcom/google/firebase/abt/component/AbtRegistrar;"])

        println("3.6.0 Class-to-DEX index verified successfully!")

        // 3. The build must stay unidentified. The classes asserted above are the ones an older
        // detection matched, and no source offers that release now, so none of them may name a
        // version.
        val detected = PatchVersionGate.detectOctoGramVersion(classToDex)
        assertNull(
            "the 3.6.0 classes must not identify a build this app patches",
            detected
        )

        // 4. Every tagged set is refused with a reason, and the untagged Firebase registrars are
        // the only edits whose classes resolve. A generator with no selection to honor produces
        // its whole set, which is what this asks for from each set before applying the gate.
        val target = TargetApk(classToDex, dexEntries)
        val patchesToApply = mutableListOf<SmaliPatch>()
        var refusedSets = 0
        for (patchSet in OctoGramPatches.ALL) {
            val generated = requireNotNull(patchSet.generator) { "${patchSet.id} has no generator" }
                .generate(target)
            assertNull("${patchSet.id} has nothing to apply", generated.skipReason)
            val matching = generated.patches.filter { patch ->
                if (!PatchVersionGate.admits(patch, detected)) return@filter false
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]
                actualDex != null && dexEntries.containsKey(actualDex)
            }
            if (matching.isEmpty() && generated.patches.isNotEmpty()) {
                val reason = PatchVersionGate.refusalReason(generated.patches, detected)
                assertNotNull("${patchSet.id} was refused without a reason", reason)
                assertTrue(
                    "${patchSet.id} must say the version was not identified: $reason",
                    reason!!.contains("could not be identified")
                )
                refusedSets++
            }
            assertEquals(
                "only the one version-less registrar is left of ${patchSet.id} here",
                if (patchSet.id.startsWith("octogram_firebase")) 1 else 0,
                matching.size
            )
            matching.forEach { patch ->
                val desc = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
                val actualDex = patch.dexName ?: classToDex[desc]!!
                patchesToApply.add(patch.copy(dexName = actualDex))
            }
        }
        assertEquals(
            "the twelve sets written for the registered build are refused on this one",
            12,
            refusedSets
        )
        assertEquals(
            "the four version-less Firebase registrars are the whole of what a 3.6.0 build gets",
            4,
            patchesToApply.size
        )
        val dexName = patchesToApply.first().dexName!!
        val patchedDexEntries = patchesToApply.filter { it.dexName == dexName }
        assertEquals("and they all live in the same DEX here", 4, patchedDexEntries.size)

        val (patchedDex, results) =
            DexProcessor.patchDexSurgically(dexEntries[dexName]!!, patchedDexEntries)
        assertTrue(patchedDex.isNotEmpty())
        results.forEach { assertEquals(StepStatus.OK, it.status) }
        println("3.6.0 $dexName: all ${results.size} version-less patches applied OK!")
    }

    @Test
    fun binaryXmlPackageRename() {
        val apkFile = ReferenceApks.octoGram361Arm64
        assumeTrue("${apkFile.path} is not on this machine", apkFile.exists())

        val entry = ZipFile(apkFile).use { it.getEntry("AndroidManifest.xml") }
        assertNotNull(entry)
        val origBytes = manifestOf(apkFile)

        val modifiedBytes = BinaryXmlModifier.modifyPackageName(
            manifestBytes = origBytes,
            oldPackageName = "it.octogram.android",
            newPackageName = "it.octogram.android.sleepy"
        )

        val targetUtf8 = "it.octogram.android.sleepy".toByteArray(Charsets.UTF_8)
        val targetUtf16 = "it.octogram.android.sleepy".toByteArray(Charsets.UTF_16LE)
        val found = (0 until modifiedBytes.size - targetUtf8.size).any { i ->
            targetUtf8.indices.all { j -> modifiedBytes[i + j] == targetUtf8[j] }
        } || (0 until modifiedBytes.size - targetUtf16.size).any { i ->
            targetUtf16.indices.all { j -> modifiedBytes[i + j] == targetUtf16[j] }
        }

        assertTrue("Modified manifest must contain new package name", found)
        println("Binary XML Package renaming verified! Output size: ${modifiedBytes.size} bytes")
    }
}
