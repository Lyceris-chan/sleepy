package dev.sleepy.app.engine

import dev.sleepy.app.testing.ReferenceApks
import dev.sleepy.app.testing.entryOf
import dev.sleepy.app.testing.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a clone package name has to meet before a run starts.
 *
 * The name is written into the manifest and into the resource table's fixed package field, so a
 * name that breaks a rule is rejected at the field rather than by a step of a run that has
 * already downloaded the build. The length rule is checked against
 * [dev.sleepy.app.engine.ResourceTableMerger]: the longest name the rules allow is the longest
 * name the table's field takes.
 */
class PackageNameRulesTest {

    private val baseApk = ReferenceApks.discordBaseApk

    @Test
    fun acceptsTheNamesACloneIsGiven() {
        assertNull(PackageNameRules.validate("com.discord.sleepy"))
        assertNull(PackageNameRules.validate("app.sleepy"))
        assertNull(PackageNameRules.validate("com.example.app2"))
        assertNull(PackageNameRules.validate("com.example.my_app"))
        assertNull(PackageNameRules.validate("COM.Example.App"))
        // A keyword is only reserved in the case the language reserves it.
        assertNull(PackageNameRules.validate("com.New.app"))
    }

    @Test
    fun refusesAnEmptyName() {
        assertEquals(PackageNameProblem.Blank, PackageNameRules.validate(""))
        assertEquals(PackageNameProblem.Blank, PackageNameRules.validate("   "))
    }

    @Test
    fun refusesANameWithTooFewSegments() {
        assertEquals(PackageNameProblem.NotEnoughSegments, PackageNameRules.validate("sleepy"))
        assertEquals(PackageNameProblem.NotEnoughSegments, PackageNameRules.validate("sleepyclone"))
    }

    @Test
    fun refusesAnEmptySegment() {
        assertEquals(PackageNameProblem.EmptySegment, PackageNameRules.validate(".com.discord"))
        assertEquals(PackageNameProblem.EmptySegment, PackageNameRules.validate("com..discord"))
        assertEquals(PackageNameProblem.EmptySegment, PackageNameRules.validate("com.discord."))
    }

    @Test
    fun refusesASegmentThatDoesNotStartWithALetter() {
        assertEquals(
            PackageNameProblem.SegmentStartsWrong,
            PackageNameRules.validate("1com.discord")
        )
        assertEquals(
            PackageNameProblem.SegmentStartsWrong,
            PackageNameRules.validate("com.2discord")
        )
        assertEquals(
            PackageNameProblem.SegmentStartsWrong,
            PackageNameRules.validate("com._discord")
        )
    }

    @Test
    fun refusesACharacterASegmentCannotHold() {
        assertEquals(PackageNameProblem.IllegalCharacter, PackageNameRules.validate("com.dis-cord"))
        assertEquals(PackageNameProblem.IllegalCharacter, PackageNameRules.validate("com.dis cord"))
        assertEquals(PackageNameProblem.IllegalCharacter, PackageNameRules.validate("com.dis/cord"))
        assertEquals(PackageNameProblem.IllegalCharacter, PackageNameRules.validate("com.discörd"))
        assertEquals(PackageNameProblem.IllegalCharacter, PackageNameRules.validate("com.discord!"))
    }

    @Test
    fun refusesAJavaKeywordAsASegment() {
        assertEquals(
            PackageNameProblem.ReservedWord("new"),
            PackageNameRules.validate("com.new.app")
        )
        assertEquals(PackageNameProblem.ReservedWord("int"), PackageNameRules.validate("com.int"))
        assertEquals(
            PackageNameProblem.ReservedWord("true"),
            PackageNameRules.validate("com.true.app")
        )
        assertEquals(
            PackageNameProblem.ReservedWord("null"),
            PackageNameRules.validate("com.app.null")
        )
        assertEquals(PackageNameProblem.ReservedWord("goto"), PackageNameRules.validate("goto.com"))
    }

    /**
     * A clone that keeps the original's name is not a clone: the pipeline skips the rename for an
     * equal name, so the run finishes with every other change applied and no renamed package.
     */
    @Test
    fun refusesTheNameTheBuildAlreadyUses() {
        assertEquals(
            PackageNameProblem.SameAsOriginal,
            PackageNameRules.validate("com.discord", originalPackageName = "com.discord")
        )
        assertNull(
            "the same name is fine against a different build",
            PackageNameRules.validate("com.discord", originalPackageName = "com.other")
        )
        assertNull(
            "a caller with no build in hand checks only the shape",
            PackageNameRules.validate("com.discord")
        )
    }

    /**
     * The length rule and the resource table's field agree at the boundary.
     *
     * The field holds 128 UTF-16 code units including the terminator, so 127 characters is the
     * longest name that fits. Both sides are checked at 127 and at 128: a rule that rejected a
     * name the table can hold blocks valid clones, and one that accepted a name the table rejects
     * lets a run reach the rename only to fail there.
     */
    @Test
    fun theLengthRuleMatchesTheResourceTableField() {
        val longest = "com." + "a".repeat(123)
        assertEquals(127, longest.length)
        assertNull(
            "the rules must accept the longest name the field holds",
            PackageNameRules.validate(longest)
        )

        val oneMore = longest + "a"
        assertEquals(PackageNameProblem.TooLong, PackageNameRules.validate(oneMore))

        val table = entryOf(baseApk, "resources.arsc")
        val renamed = ResourceTableMerger.renamePackage(table, longest)
        assertNotNull("the table must accept the longest name the rules allow", renamed)
        assertEquals(longest, ResourceTableMerger.packageName(requireNotNull(renamed)))
        assertNull(
            "the table must refuse the name the rules refuse",
            ResourceTableMerger.renamePackage(table, oneMore)
        )
    }

    /**
     * The field and the start button are wired to the rules.
     *
     * The clone field is in a Compose screen, which a unit test on the JVM cannot compose, so the
     * wiring is checked in its source: the screen validates the name it shows, marks the field as
     * an error, and does not start while a problem remains, and the view model checks the same
     * rule before it passes the name to the pipeline.
     */
    @Test
    fun theCloneFieldAndTheStartButtonUseTheRules() {
        val screen = source("app/src/main/kotlin/dev/sleepy/app/ui/screens/PatchSelectScreen.kt")
        assertTrue(
            "the clone field is not validated",
            screen.contains("PackageNameRules.validate(customPackageName")
        )
        assertTrue(
            "the field does not show the problem",
            screen.contains("isError = problem != null")
        )
        assertTrue(
            "the start button does not refuse a name that breaks a rule",
            screen.contains("packageNameProblem == null")
        )

        val viewModel = source("app/src/main/kotlin/dev/sleepy/app/viewmodel/PatchViewModel.kt")
        assertTrue(
            "a run can start with a clone name the pipeline cannot write",
            viewModel.contains("PackageNameRules.validate(finalCustomPackage, source.packageName)")
        )
    }




}
