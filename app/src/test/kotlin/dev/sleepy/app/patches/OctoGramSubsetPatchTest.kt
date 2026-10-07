package dev.sleepy.app.patches

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.SelectivePatchGenerator
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.testing.ComparisonApks
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
 * full DEX reassembly may re-encode an instruction into its wider form; where an edit moves an
 * operand and no opcode—the external-browser default passes a different register—the operand is
 * what the test compares.
 */
class OctoGramSubsetPatchTest {

    private val apkFile = ComparisonApks.octoGram361Arm64

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
    fun thePremiumRowsEditFlipsExactlyTheFourBranchesAndTouchesNothingElse() = runBlocking {
        val dexEntries = readDexEntries()
        val classes3 = dexEntries.getValue("classes3.dex")

        val selection = PatchSelection.ofKeys(
            OctoGramPatchItems.itemKeyOf(OctoGramPatches.PREMIUM_SETTINGS.id, "premiumRows")
        )
        val patches = OctoGramPatchItems.patches(OctoGramPatches.PREMIUM_SETTINGS.id, selection)
        assertEquals("the Telegram Premium row is one switch over four edits", 4, patches.size)

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

        // The row on the app's own Settings screen is the fourth edit, in a class of its own. It is
        // the same shape: one guarded insert becomes a jump, and the rest of the row builder—which
        // builds every other settings row—is untouched.
        val settings = "teb"
        val settingsBefore = opcodeCounts(before.opcodes(settings, "c3"), "teb.c3()")
        val settingsAfter = opcodeCounts(after.opcodes(settings, "c3"), "teb.c3()")
        val settingsDelta = (settingsAfter.keys + settingsBefore.keys)
            .associateWith { opcode -> (settingsAfter[opcode] ?: 0) - (settingsBefore[opcode] ?: 0) }
            .filterValues { it != 0 }
        assertEquals(
            "hiding the Telegram Premium row in Settings replaces one branch with a jump, and " +
                "changes nothing else in the class that builds every row there",
            mapOf(GOTO to 1, "if-nez" to -1),
            settingsDelta
        )

        assertEquals(
            "nothing this run did not select may change",
            untouchedBefore,
            opcodesOf(after, untouched)
        )
    }

    /**
     * The external-browser default, checked where [DexView.opcodes] cannot see it.
     *
     * The edit changes which register one call reads and no instruction, so the opcode stream is
     * identical before and after and the register has to be read from the encoding. The registers
     * the two Boolean constants are read into are checked too, so the test asserts what the moved
     * register holds rather than only its number.
     */
    @Test
    fun theExternalBrowserDefaultMovesTheCallOntoTheRegisterHoldingTrue() = runBlocking {
        val dexEntries = readDexEntries()
        val externalBrowser = OctoGramPatches.EXTERNAL_BROWSER
        val selection = PatchSelection.ofKeys(
            OctoGramPatchItems.itemKeyOf(externalBrowser.id, "defaultOn")
        )
        val patches = OctoGramPatchItems.patches(externalBrowser.id, selection)
        assertEquals("the setting's default is one edit", 1, patches.size)

        val className = "it/octogram/android/unsorted/OctoConfig"
        val before = DexView(dexEntries.getValue("classes.dex"))
        val beforeSteps = requireNotNull(before.steps(className, "<init>")) {
            "$className.<init> is not in the fixture"
        }
        val beforeInvoke = defaultInvoke(beforeSteps)
        assertEquals(
            "the fixture registers the default from the register that holds Boolean.FALSE",
            4,
            beforeInvoke.register
        )
        assertEquals(
            "and Boolean.FALSE is what that register was read from",
            BOOLEAN_FALSE,
            fieldReadInto(beforeSteps, beforeInvoke.index, beforeInvoke.register)
        )
        assertEquals(
            "while the register holding Boolean.TRUE is defined as well",
            BOOLEAN_TRUE,
            fieldReadInto(beforeSteps, beforeInvoke.index, 3)
        )

        val (patched, results) =
            DexProcessor.patchDexSurgically(dexBytes = dexEntries.getValue("classes.dex"), patches = patches)
        assertTrue("classes.dex produced no output", patched.isNotEmpty())
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} failed: ${it.detail}", StepStatus.OK, it.status)
        }

        val after = DexView(patched)
        assertEquals(
            "the edit moves a register operand and changes no instruction around it",
            before.opcodes(className, "<init>"),
            after.opcodes(className, "<init>")
        )
        val afterInvoke = defaultInvoke(requireNotNull(after.steps(className, "<init>")))
        assertEquals(
            "the default is now the register that holds Boolean.TRUE, so a fresh install ships " +
                "the setting on",
            3,
            afterInvoke.register
        )
        assertEquals(
            "and Boolean.TRUE is still what that register holds at the call",
            BOOLEAN_TRUE,
            fieldReadInto(
                requireNotNull(after.steps(className, "<init>")),
                afterInvoke.index,
                afterInvoke.register
            )
        )
        assertEquals(
            "nothing else in the class changed",
            before.opcodes(className, "g"),
            after.opcodes(className, "g")
        )
    }

    /**
     * The three business edits: the profile row's branch and the two command slugs become jumps
     * at the sites the recorded change set names, and the rest of each method keeps its
     * instruction counts.
     */
    @Test
    fun theBusinessEditsTurnExactlyFourBranchesIntoJumps() = runBlocking {
        val dexEntries = readDexEntries()
        val business = OctoGramPatches.HIDE_BUSINESS
        val selection = PatchSelection.ofKeys(
            OctoGramPatchItems.itemKeyOf(business.id, "rowAndCommands")
        )
        val patches = OctoGramPatchItems.patches(business.id, selection)
        assertEquals("the two rows and the two commands are one item", 4, patches.size)

        val classes3 = dexEntries.getValue("classes3.dex")
        val rowBuilder = "org/telegram/ui/ProfileActivity"
        val before = DexView(classes3)
        val beforeRow = opcodeCounts(before.opcodes(rowBuilder, "yd"), "ProfileActivity.yd()")
        assertEquals(
            "the branch instructions of ProfileActivity.yd() before anything is applied",
            mapOf(IF_NEZ to 92, "if-gez" to 13, GOTO to 43),
            beforeRow.filterKeys { it in BRANCHES }
        )
        val beforeSlugs = opcodeCounts(before.opcodes("hq6", "k"), "hq6.k()")

        // Each command keeps its handler and loses its own branch: the first branch after the
        // slug's `const-string` is the one the edit replaces.
        val slugSteps = requireNotNull(before.steps("hq6", "k")) { "hq6.k is not in the fixture" }
        listOf("premium", "business").forEach { slug ->
            assertEquals(
                "the /$slug command's branch has to start as the guard the edit replaces",
                IF_EQZ,
                branchAfter(slugSteps, slug).opcode
            )
        }

        val (patched, results) =
            DexProcessor.patchDexSurgically(dexBytes = classes3, patches = patches)
        assertTrue("classes3.dex produced no output", patched.isNotEmpty())
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} failed: ${it.detail}", StepStatus.OK, it.status)
        }

        val after = DexView(patched)
        val afterRow = opcodeCounts(after.opcodes(rowBuilder, "yd"), "ProfileActivity.yd()")
        assertEquals(
            "the business row's insert is skipped and its branch is all that moved",
            mapOf(GOTO to 1, IF_NEZ to -1),
            countsDelta(beforeRow, afterRow)
        )
        val afterSlugs = opcodeCounts(after.opcodes("hq6", "k"), "hq6.k()")
        assertEquals(
            "both command branches become jumps and the cascade is otherwise untouched",
            mapOf(GOTO to 2, IF_EQZ to -2),
            countsDelta(beforeSlugs, afterSlugs)
        )
        val afterSlugSteps = requireNotNull(after.steps("hq6", "k"))
        listOf("premium", "business").forEach { slug ->
            assertEquals(
                "the /$slug command's branch is now a jump past its handler",
                GOTO,
                branchAfter(afterSlugSteps, slug).opcode.substringBefore('/')
            )
        }

        // The Business row on the app's own Settings screen is the fourth edit. It sits in the same
        // class as the Settings premium row, so this also shows the two sets edit that class
        // independently: a run that selects this one alone moves a single branch there.
        val settings = "teb"
        val settingsBefore = opcodeCounts(before.opcodes(settings, "c3"), "teb.c3()")
        val settingsAfter = opcodeCounts(after.opcodes(settings, "c3"), "teb.c3()")
        val settingsDelta = (settingsAfter.keys + settingsBefore.keys)
            .associateWith { opcode -> (settingsAfter[opcode] ?: 0) - (settingsBefore[opcode] ?: 0) }
            .filterValues { it != 0 }
        assertEquals(
            "hiding the Telegram Business row in Settings replaces one branch with a jump, and " +
                "leaves the Settings premium row's branch alone",
            mapOf(GOTO to 1, "if-nez" to -1),
            settingsDelta
        )

        // The run selected the business set alone, so none of this may move.
        val untouched = uploaders + emitters.map { "cn8" to it } + ("yb3" to "g") +
            ("org/telegram/ui/ActionBar/p" to "E2")
        assertEquals(
            "nothing this run did not select may change",
            opcodesOf(before, untouched),
            opcodesOf(after, untouched)
        )
    }

    /**
     * The premium-sheet guard: four instructions in front of the sheet helper's body, and the body
     * behind them unchanged.
     */
    @Test
    fun thePremiumSheetGuardRefusesOnlyThePremiumFragment() = runBlocking {
        val dexEntries = readDexEntries()
        val sheets = OctoGramPatches.PREMIUM_SHEETS
        val selection = PatchSelection.ofKeys(
            OctoGramPatchItems.itemKeyOf(sheets.id, "sheetGuard")
        )
        val patches = OctoGramPatchItems.patches(sheets.id, selection)
        assertEquals("the sheet guard is one edit", 1, patches.size)

        val classes3 = dexEntries.getValue("classes3.dex")
        val sheetHelper = "org/telegram/ui/ActionBar/p"
        val before = DexView(classes3)
        val beforeOpcodes = requireNotNull(before.opcodes(sheetHelper, "E2")) {
            "$sheetHelper.E2 is not in the fixture"
        }
        assertEquals(
            "the fixture's sheet helper starts by reading the fragment's parent activity",
            "invoke-virtual",
            beforeOpcodes.first()
        )

        val (patched, results) =
            DexProcessor.patchDexSurgically(dexBytes = classes3, patches = patches)
        assertTrue("classes3.dex produced no output", patched.isNotEmpty())
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} failed: ${it.detail}", StepStatus.OK, it.status)
        }

        val after = DexView(patched)
        val afterOpcodes = requireNotNull(after.opcodes(sheetHelper, "E2"))
        assertEquals(
            "the guard adds exactly four instructions and the body behind them is the one it was",
            listOf("instance-of", IF_EQZ, "const", "return-object") + beforeOpcodes,
            afterOpcodes
        )
        val afterSteps = requireNotNull(after.steps(sheetHelper, "E2"))
        assertEquals(
            "the fragment the guard tests for is the paywall's",
            "Lorg/telegram/ui/PremiumPreviewFragment;",
            afterSteps.first().reference
        )
        assertEquals(
            "the guard nulls and returns the fragment it tested, which is the method's parameter",
            afterSteps[2].registers.first(),
            afterSteps.first().registers.last()
        )
        assertEquals(
            "and the value it returns is that same register",
            afterSteps[2].registers.first(),
            afterSteps[3].registers.first()
        )

        // The run selected the sheet guard alone, so none of this may move.
        val untouched = uploaders + emitters.map { "cn8" to it } + ("yb3" to "g") +
            ("org/telegram/ui/ProfileActivity" to "yd") + ("hq6" to "k")
        assertEquals(
            "nothing this run did not select may change",
            opcodesOf(before, untouched),
            opcodesOf(after, untouched)
        )
    }

    /**
     * The call that registers the external-browser default, and where it sits in the constructor.
     *
     * The invoke alone occurs in the class once per setting, so the key's own `const-string` locates
     * this one: it is the first invoke after that string.
     */
    private fun defaultInvoke(steps: List<DexView.Step>): DefaultInvoke {
        val key = steps.indexOfFirst {
            it.opcode == CONST_STRING && it.reference == EXTERNAL_BROWSER_KEY
        }
        assertTrue("the constructor no longer registers $EXTERNAL_BROWSER_KEY", key >= 0)
        val invoke = steps.withIndex().drop(key + 1)
            .firstOrNull { it.value.opcode.substringBefore('/') == "invoke-virtual" }
            ?: error("no invoke follows the $EXTERNAL_BROWSER_KEY constant")
        return DefaultInvoke(invoke.index, invoke.value)
    }

    /** The first branch after [slug]'s own `const-string`: the handler's own guard or jump. */
    private fun branchAfter(steps: List<DexView.Step>, slug: String): DexView.Step {
        val at = steps.indexOfFirst { it.opcode == CONST_STRING && it.reference == slug }
        assertTrue("no const-string for $slug", at >= 0)
        return steps.drop(at + 1).first { it.opcode.substringBefore('/') in COMMAND_BRANCHES }
    }

    /** The call that registers the external-browser default, and where it sits in the method. */
    private data class DefaultInvoke(val index: Int, val step: DexView.Step) {
        /** The register the call passes as its second argument: the default it registers. */
        val register: Int get() = step.registers[1]
    }

    /**
     * The field the last `sget-object` into [register] before [index] reads, or null when the
     * method holds no such read.
     *
     * Both Boolean constants are read into their registers once near the top of the constructor
     * and nothing writes either register before the default is registered, so the nearest
     * preceding field read is the value the call sees.
     */
    private fun fieldReadInto(steps: List<DexView.Step>, index: Int, register: Int): String? =
        steps.subList(0, index)
            .lastOrNull { it.opcode == SGET_OBJECT && it.registers.firstOrNull() == register }
            ?.reference

    /** The per-opcode difference between two counts, zeroes dropped. */
    private fun countsDelta(before: Map<String, Int>, after: Map<String, Int>): Map<String, Int> =
        (before.keys + after.keys)
            .associateWith { opcode -> (after[opcode] ?: 0) - (before[opcode] ?: 0) }
            .filterValues { it != 0 }

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
        const val IF_EQZ = "if-eqz"
        const val IF_NEZ = "if-nez"
        const val CONST_STRING = "const-string"
        const val SGET_OBJECT = "sget-object"

        /** The `sget-object` targets the external-browser default moves between. */
        const val BOOLEAN_TRUE = "Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;"
        const val BOOLEAN_FALSE = "Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;"

        /** The preference key the external-browser default is registered under. */
        const val EXTERNAL_BROWSER_KEY = "openLinksExternalBrowser"

        /**
         * The three branch mnemonics this build's premium-row edits move, as dexlib2 spells
         * them.
         */
        val BRANCHES = setOf(IF_NEZ, "if-gez", GOTO)

        /** The branches the business-command edits move: each handler's own guard. */
        val COMMAND_BRANCHES = setOf(IF_EQZ, GOTO)
    }
}

/**
 * One DEX as dexlib2 reads it, with the questions these tests ask of it: what instructions a method
 * holds, what operands they name, and whether a string is anywhere in the DEX.
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

    /**
     * One instruction as [steps] reports it: the opcode, the registers the encoding names, and the
     * string constant or member reference it carries when it has one.
     */
    data class Step(val opcode: String, val registers: List<Int>, val reference: String?)

    /** The method's opcodes in order, with the encoding widths folded together, or null. */
    fun opcodes(className: String, methodName: String): List<String>? =
        methodOf(className, methodName)?.implementation?.instructions
            ?.map { it.opcode.name.substringBefore('/') }

    /**
     * The method's instructions in order, with the registers and reference each encoding carries.
     *
     * [opcodes] answers which instructions a method holds; this view answers what operands they
     * name, for the edits [opcodes] cannot see. The external-browser default moves a call onto a
     * different register without changing a single opcode, so the register has to be read from the
     * encoding. A reference is spelled as the disassembler writes it: a string constant by its
     * text, a field as `owner->name:type`.
     */
    fun steps(className: String, methodName: String): List<Step>? =
        methodOf(className, methodName)?.implementation?.instructions?.map { instruction ->
            Step(
                opcode = instruction.opcode.name,
                registers = registersOf(instruction),
                reference = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                    null -> null
                    is StringReference -> reference.string
                    is FieldReference -> "${reference.definingClass}->${reference.name}:${reference.type}"
                    else -> reference.toString()
                }
            )
        }

    /** The method with that name in that class, or null when either is absent. */
    private fun methodOf(className: String, methodName: String): Method? =
        dex.classes
            .firstOrNull { it.type == "L$className;" }
            ?.methods
            ?.firstOrNull { it.name == methodName }

    /** The registers one instruction names: its destination first, then its sources. */
    private fun registersOf(instruction: Instruction): List<Int> = when (instruction) {
        is FiveRegisterInstruction -> listOf(
            instruction.registerC,
            instruction.registerD,
            instruction.registerE,
            instruction.registerF,
            instruction.registerG
        ).take(instruction.registerCount)

        is TwoRegisterInstruction -> listOf(instruction.registerA, instruction.registerB)
        is OneRegisterInstruction -> listOf(instruction.registerA)
        else -> emptyList()
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
