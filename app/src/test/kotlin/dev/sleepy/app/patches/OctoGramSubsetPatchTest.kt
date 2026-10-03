package dev.sleepy.app.patches

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.DexFile
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.SelectivePatchGenerator
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.testing.ReferenceApks
import dev.sleepy.app.testing.dexEntries
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A subset of the OctoGram edits, applied to the real 3.6.1 APK and checked by opcode.
 *
 * The methods the selection names must change, and the methods the same sets hold but the run did
 * not select must not. Comparison is by opcode with encoding widths folded together, because a
 * full DEX reassembly may re-encode an instruction into its wider form.
 */
class OctoGramSubsetPatchTest {

    private val apkFile = ReferenceApks.octoGram361Arm64

    /** The twelve methods of `cn8` that write the log, by name. */
    private val emitters = listOf("a", "b", "d", "e", "f", "g", "h", "k", "m", "n", "o", "p")

    /** The five methods that ship a log off the device. */
    private val uploaders = listOf(
        "org/telegram/ui/PremiumPreviewFragment" to "A3",
        "org/telegram/ui/PremiumPreviewFragment" to "B3",
        "org/telegram/ui/PremiumPreviewFragment" to "C3",
        "r44" to "R0",
        "a28" to "b3"
    )

    /**
     * Strings only `yb3.g()` loads, each occurring once in the whole APK.
     *
     * They are what the stubbed body is checked against: with that body replaced, the constants it
     * loaded have no other reader anywhere, and the assembler writes no string the code does not
     * use.
     */
    private val crashBodyStrings = listOf(
        "init: Initializing Crashlytics",
        "init: Crashlytics initialized",
        "createNotificationChannel: Notification channel created"
    )

    /**
     * The APK's DEX entries.
     *
     * The fixture is a build output outside the repository, so a machine without it reports
     * these tests as skipped rather than passing them without having read anything.
     */
    private fun readDexEntries(): Map<String, ByteArray> {
        val entries = dexEntries(apkFile)
        assertEquals("the fixture is the 3.6.1 build", 4, entries.size)
        return entries
    }

    @Test
    fun aSubsetOfOctoGramEditsChangesItsOwnMethodsAndNothingElse() = runBlocking {
        val dexEntries = readDexEntries()
        val target = TargetApk(DexProcessor.buildClassToDexIndex(dexEntries), dexEntries)
        val classes3 = dexEntries.getValue("classes3.dex")

        // The crash reporter and the log itself, and deliberately not the uploaders: they are the
        // set's other item, and staying unapplied is half of what this test asserts.
        val selection = PatchSelection.ofKeys(
            OctoGramPatchItems.itemKeyOf(OctoGramPatches.CRASH_REPORTER.id, "startup"),
            OctoGramPatchItems.itemKeyOf(OctoGramPatches.OCTO_LOGGER.id, "emitters")
        )
        val patches = OctoGramPatchItems.patches(OctoGramPatches.CRASH_REPORTER.id, selection) +
            OctoGramPatchItems.patches(OctoGramPatches.OCTO_LOGGER.id, selection)
        assertEquals(
            "the subset is the crash reporter and the twelve emitters, and nothing else",
            emitters.size + 1,
            patches.size
        )

        // A set the selection does not name generates nothing at all, with a reason—the narrowing
        // the pipeline relies on before it ever reaches a set.
        val premium = OctoGramPatches.PREMIUM_SETTINGS.generator as SelectivePatchGenerator
        val unselected = premium.generate(target, selection)
        assertTrue(
            "a set no item of which is selected must generate no patches",
            unselected.patches.isEmpty()
        )
        assertNotNull(
            "and it must say why rather than report an empty success",
            unselected.skipReason
        )

        // What must not move this run: the uploaders, the row builder and, in the class being
        // edited, a method the crash patch does not name.
        val untouched = uploaders +
            ("org/telegram/ui/ProfileActivity" to "yd") +
            ("yb3" to "h")

        val before = DexView(classes3)
        assertEquals(
            "the fixture has to be the build these entries were derived from",
            emitters.size,
            emitters.count { before.opcodes("cn8", it) != null }
        )
        crashBodyStrings.forEach { needle ->
            assertTrue(
                "$needle is not in the unpatched DEX, so its absence would prove nothing",
                before.hasAscii(needle)
            )
        }
        val untouchedBefore = opcodesOf(before, untouched)

        val (patched, results) =
            DexProcessor.patchDexSurgically(dexBytes = classes3, patches = patches)
        assertTrue("classes3.dex produced no output", patched.isNotEmpty())
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} failed: ${it.detail}", StepStatus.OK, it.status)
        }

        val after = DexView(patched)
        emitters.forEach { name ->
            assertEquals(
                "cn8.$name must be left as a body that only returns",
                listOf(RETURN_VOID),
                after.opcodes("cn8", name)
            )
        }
        assertEquals(
            "yb3.g must be left as a body that only returns",
            listOf(RETURN_VOID),
            after.opcodes("yb3", "g")
        )
        crashBodyStrings.forEach { needle ->
            assertTrue(
                "the crash reporter's own text is still in the patched DEX, so its body is not " +
                    "gone: $needle",
                !after.hasAscii(needle)
            )
        }
        // The class is edited rather than deleted, which is why the body is stubbed instead of the
        // handler's only entry point being removed: everything else in `yb3` is still there.
        assertNotNull("yb3 must still be in the DEX", after.opcodes("yb3", "h"))

        assertEquals(
            "nothing this run did not select may change",
            untouchedBefore,
            opcodesOf(after, untouched)
        )
    }

    @Test
    fun thePremiumRowsEditFlipsExactlyTheThreeBranchesAndTouchesNothingElse() = runBlocking {
        val dexEntries = readDexEntries()
        val classes3 = dexEntries.getValue("classes3.dex")

        val selection = PatchSelection.ofKeys(
            OctoGramPatchItems.itemKeyOf(OctoGramPatches.PREMIUM_SETTINGS.id, "premiumRows")
        )
        val patches = OctoGramPatchItems.patches(OctoGramPatches.PREMIUM_SETTINGS.id, selection)
        assertEquals("the premium rows are one switch over three edits", 3, patches.size)

        // What this run must leave alone: the log's methods and the crash reporter, neither of
        // which it selected.
        val untouched = uploaders + emitters.map { "cn8" to it } + ("yb3" to "g")

        val before = DexView(classes3)
        val rowBuilder = "org/telegram/ui/ProfileActivity"
        val beforeCounts = opcodeCounts(before.opcodes(rowBuilder, "yd"), "ProfileActivity.yd()")
        // The pre-state the delta below is measured against. `yd()` is full of checks over the same
        // register—ninety-two `if-nez` and thirteen `if-gez`—which is why each anchor carries
        // the lines around its branch rather than the branch alone: an anchor that matched one of
        // these other sites moves these counts by more than the delta allows, and fails there
        // rather than in a review.
        assertEquals(
            "the branch instructions of ProfileActivity.yd() before anything is applied",
            mapOf("if-nez" to 92, "if-gez" to 13, "goto" to 43),
            beforeCounts.filterKeys { it in BRANCHES }
        )
        val untouchedBefore = opcodesOf(before, untouched)

        val (patched, results) =
            DexProcessor.patchDexSurgically(dexBytes = classes3, patches = patches)
        assertTrue("classes3.dex produced no output", patched.isNotEmpty())
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} failed: ${it.detail}", StepStatus.OK, it.status)
        }

        val after = DexView(patched)
        val afterCounts = opcodeCounts(after.opcodes(rowBuilder, "yd"), "ProfileActivity.yd()")

        // The whole of what the three edits do: two guarded row inserts and the sections-row check
        // become jumps. Stated as a difference rather than as three expectations, so an edit that
        // changed anything else in the method—or a fourth edit that was not selected—fails
        // here.
        val delta = (afterCounts.keys + beforeCounts.keys)
            .associateWith { opcode -> (afterCounts[opcode] ?: 0) - (beforeCounts[opcode] ?: 0) }
            .filterValues { it != 0 }
        assertEquals(
            "hiding the premium rows replaces three branches with jumps, and changes nothing else",
            mapOf(GOTO to 3, "if-nez" to -2, "if-gez" to -1),
            delta
        )

        assertEquals(
            "nothing this run did not select may change",
            untouchedBefore,
            opcodesOf(after, untouched)
        )
    }

    /** One method's opcodes per name, as `Class.method`, for comparing two runs. */
    private fun opcodesOf(
        view: DexView,
        methods: List<Pair<String, String>>
    ): Map<String, List<String>?> =
        methods.associate { (className, method) ->
            "$className.$method" to view.opcodes(className, method)
        }

    /** Opcodes by count, for comparing one method across two runs. */
    private fun opcodeCounts(opcodes: List<String>?, method: String): Map<String, Int> =
        requireNotNull(opcodes) { "$method is not in this DEX" }.groupingBy { it }.eachCount()

    private companion object {
        /**
         * The opcodes these tests name, spelled as dexlib2 reports them.
         *
         * `Opcode.name` is the smali mnemonic the assembler takes, not an uppercase constant name:
         * `return-void`, not `RETURN_VOID`. The encoding width rides along as a `/16`, `/32`,
         * `/jumbo` or `/range` suffix, which [DexView.opcodes] folds away before comparing, so
         * `goto` covers `goto` and `goto/16` alike—the same spelling the patches' anchors use.
         */
        const val RETURN_VOID = "return-void"
        const val GOTO = "goto"

        /**
         * The three branch mnemonics this build's premium-row edits move, as dexlib2 spells
         * them.
         */
        val BRANCHES = setOf("if-nez", "if-gez", GOTO)
    }
}

/**
 * One DEX as dexlib2 reads it, with the two questions these tests ask of it: what instructions a
 * method holds, and whether a string is anywhere in it.
 *
 * The file is written to disk because that is how dexlib2 opens a DEX, and methods are matched by
 * name alone: every method compared here has a name of its own within its class, and a name that
 * does not resolve returns null, which the assertions then report as a missing method rather than
 * passing without a report.
 */
private class DexView(bytes: ByteArray) {

    private val file: File = File.createTempFile("octoSubset", ".dex").apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    private val dex: DexFile = DexFileFactory.loadDexFile(file, Opcodes.forApi(28))

    /** The file as it stands, read once and only if a string is looked for. */
    private val raw: ByteArray by lazy { file.readBytes() }

    /** The method's opcodes in order, with the encoding widths folded together, or null. */
    fun opcodes(className: String, methodName: String): List<String>? {
        val method = dex.classes
            .firstOrNull { it.type == "L$className;" }
            ?.methods
            ?.firstOrNull { it.name == methodName }
            ?: return null
        return method.implementation?.instructions?.map { it.opcode.name.substringBefore('/') }
    }

    /**
     * Whether [text] appears anywhere in the DEX's bytes, as its own bytes.
     *
     * A string constant's text is stored in the DEX's string pool as MUTF-8, which is what these
     * needles are byte for byte—and searching the pool rather than the code catches a string that
     * is still referenced from somewhere this test does not follow.
     */
    fun hasAscii(text: String): Boolean {
        val needle = text.toByteArray(Charsets.ISO_8859_1)
        return (0..raw.size - needle.size).any { start ->
            needle.indices.all { offset -> raw[start + offset] == needle[offset] }
        }
    }
}
