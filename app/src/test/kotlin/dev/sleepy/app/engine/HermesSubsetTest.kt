package dev.sleepy.app.engine

import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.bundleOf
import dev.sleepy.app.testing.firstDifference
import dev.sleepy.app.testing.readU32Le
import dev.sleepy.app.testing.regionsEqual
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A subset of the JavaScript patch table, applied to the real bundle.
 *
 * Per-item selection exists to apply a subset, so the two halves both matter: the functions the
 * selection names must be the ones the table's replacements were written for, and every other
 * function in the bundle must be exactly as it came in. Both are checked here against the table
 * itself rather than against a recorded build, which is the stronger statement - a recorded build
 * would only say the two agree.
 */
class HermesSubsetTest {

    /** The shipped build the patch set was extracted from. */
    private val baseApk = ComparisonApks.discordBaseApk

    private val fileLengthOffset = 32
    private val sha1FooterSize = 20

    /**
     * Two features a subset selection can name.
     *
     * The unit of selection is a feature, not a function, so a subset is "these things" rather
     * than "these ids": the functions come from the features, and one function cannot be selected
     * out of the feature it belongs to.
     *
     * Both are features whose functions all fit where they are. A feature holding one of the seven
     * functions the whole table relocates would move a function rather than write it in place,
     * which is a different case and is covered by [HermesBundleApplicationTest].
     */
    private val chosenFeatures = listOf("gift_buttons", "analytics_events")

    /** The functions those features cover, in the table's own order. */
    private val chosen = DiscordHermesBundlePatch.PATCHES
        .map { it.functionId }
        .filter { DiscordHermesFunctionCatalog.featureOf(it)?.slug in chosenFeatures }

    /** The first patched function in a feature the selection does not name. */
    private val leftAlone = DiscordHermesBundlePatch.PATCHES
        .map { it.functionId }
        .first { DiscordHermesFunctionCatalog.featureOf(it)?.slug !in chosenFeatures }

    @Test
    fun aSubsetChangesTheChosenFunctionsAndNothingElse() {
        assumeTrue("the Discord base APK is not on this machine (${baseApk.path})", baseApk.isFile)

        val selection = PatchSelection.ofKeys(
            *chosenFeatures.reversed().map { DiscordHermesFunctionCatalog.itemKeyOf(it) }
                .toTypedArray()
        )
        val patches = DiscordHermesFunctionCatalog.selectPatches(selection)

        assertEquals(
            "the subset is exactly the chosen functions, in the table's order rather than the " +
                "selection's",
            chosen,
            patches.map { it.functionId }
        )
        assertEquals(
            "a key that names no feature and no function selects nothing",
            emptyList<Int>(),
            DiscordHermesFunctionCatalog
                .selectPatches(PatchSelection.ofKeys("discord_hermes:fn999999"))
                .map { it.functionId }
        )
        // A selection saved by 3.2.0 names one function per key, so reading it has to turn each
        // into the feature that now covers that function rather than keeping a key that would
        // match nothing.
        val legacyId = DiscordHermesBundlePatch.PATCHES.first().functionId
        assertEquals(
            "a key saved before features existed selects the feature that now covers it",
            DiscordHermesFunctionCatalog.featureOf(legacyId)?.functionIds,
            DiscordHermesFunctionCatalog
                .selectPatches(
                    PatchSelection.fromSavedIds(
                        listOf("discord_hermes:fn$legacyId"),
                        PatchItemCatalog
                    )
                )
                .map { it.functionId }
        )
        assertEquals(
            "and neither does an empty selection",
            emptyList<Int>(),
            DiscordHermesFunctionCatalog.selectPatches(PatchSelection()).map { it.functionId }
        )

        val base = bundleOf(baseApk)
        assertEquals(
            "the base bundle is not the build the patch set was extracted from",
            DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toLong(),
            base.size.toLong()
        )

        val result = HermesBundlePatcher.apply(base, patches)
        val patched = result.bundleBytes

        assertEquals(
            "every patch must land: a skip leaves a function holding the old body",
            emptyList<String>(),
            result.skipped.map { "${it.functionId} (${it.name}): ${it.detail}" }
        )
        assertEquals(chosen.size, result.appliedCount)
        assertEquals(chosen, result.writtenInPlace.map { it.functionId }.sorted())
        assertEquals(
            "none of these features grows its functions, so nothing needs relocating",
            emptyList<Int>(),
            result.relocated.map { it.functionId }
        )

        // Every function in the bundle, checked against the body it should have: the table's
        // replacement for the chosen ones, the input's own bytes for all the others.
        val replacements = DiscordHermesBundlePatch.PATCHES.associateBy { it.functionId }
        val mismatches = ArrayList<String>()
        var matchedChosen = 0
        var matchedUntouched = 0
        for (functionId in 0 until DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT) {
            val wasChosen = functionId in chosen
            val ours = HermesFunctionTable.locate(patched, functionId)
            if (ours == null) {
                mismatches += "fn $functionId: not locatable after patching"
                continue
            }
            if (wasChosen) {
                val replacement = requireNotNull(replacements[functionId]).replacement
                if (ours.bytecodeSize != replacement.size ||
                    !regionsEqual(patched, ours.bodyOffset, replacement, 0, replacement.size)
                ) {
                    mismatches += "fn $functionId: not the table's ${replacement.size}-byte " +
                        "replacement (declared ${ours.bytecodeSize} bytes)"
                    continue
                }
                matchedChosen++
                continue
            }
            val before = requireNotNull(HermesFunctionTable.locate(base, functionId)) {
                "function $functionId is not locatable in the base bundle"
            }
            if (ours.bytecodeSize != before.bytecodeSize) {
                mismatches += "fn $functionId: ${ours.bytecodeSize} bytes, the input has " +
                    "${before.bytecodeSize}"
                continue
            }
            if (
                !regionsEqual(patched, ours.bodyOffset, base, before.bodyOffset, ours.bytecodeSize)
            ) {
                mismatches += "fn $functionId: an unselected function differs from the input at " +
                    firstDifference(
                        patched,
                        ours.bodyOffset,
                        base,
                        before.bodyOffset,
                        ours.bytecodeSize
                    )
                continue
            }
            matchedUntouched++
        }

        println(
            "HermesSubsetTest: ${chosen.size} chosen functions carry the table's replacements, " +
                "$matchedUntouched of " +
                "${DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT - chosen.size} " +
                "unselected ones are byte-identical to the input"
        )
        assertEquals(mismatches.take(10), emptyList<String>())
        assertEquals(chosen.size, matchedChosen)
        assertEquals(DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT - chosen.size, matchedUntouched)

        // Stated separately because the sweep above reports it as one of its lines: a subset that
        // widened back to the whole table without a report.
        val alone = requireNotNull(HermesFunctionTable.locate(patched, leftAlone))
        val aloneInBase = requireNotNull(HermesFunctionTable.locate(base, leftAlone))
        assertEquals(aloneInBase.bytecodeSize, alone.bytecodeSize)
        assertTrue(
            "function $leftAlone is patched by the whole table and must be untouched by this " +
                "subset",
            regionsEqual(
                patched,
                alone.bodyOffset,
                base,
                aloneInBase.bodyOffset,
                aloneInBase.bytecodeSize
            )
        )

        // In-place writes only, so the file is the size it was: the chosen functions changed and
        // nothing else moved.
        assertEquals(base.size, patched.size)

        // The bundle still has to be loadable, which means a footer matching the file as it now is.
        val fileLength = readU32Le(patched, fileLengthOffset)
        assertEquals("the footer must close the file exactly", patched.size, fileLength)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(patched, 0, fileLength - sha1FooterSize)
        assertTrue(
            "the SHA-1 footer does not cover the patched file",
            digest.digest()
                .contentEquals(patched.copyOfRange(fileLength - sha1FooterSize, fileLength))
        )

        // The input bundle must come back unmodified: the pipeline keeps using it.
        val baseDigest = MessageDigest.getInstance("SHA-1")
        baseDigest.update(base, 0, base.size - sha1FooterSize)
        assertTrue(
            "the patcher modified the input bundle it was given",
            baseDigest.digest()
                .contentEquals(base.copyOfRange(base.size - sha1FooterSize, base.size))
        )
    }
}
