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
 * A subset of the JavaScript patch table, applied to the real bundle and checked against the
 * recorded build.
 *
 * Per-item selection exists to apply a subset, so the functions the selection names must match
 * the recorded build and the functions it does not name must keep their base bytes. The bundles
 * come from the same two Discord 349.5 APKs the full comparison test uses.
 */
class HermesSubsetComparisonTest {

    /** The shipped build the patch set was extracted from, and the patched recorded build. */
    private val baseApk = ComparisonApks.discordBaseApk
    private val recordedApk = ComparisonApks.discordRecordedApk

    private val fileLengthOffset = 32
    private val sha1FooterSize = 20

    /**
     * Two features a subset selection can name.
     *
     * The unit of selection is a feature, not a function, so a subset is now "these things" rather
     * than "these ids": the functions come from the features, and one function cannot be selected
     * out of the feature it belongs to.
     *
     * Both are features whose functions all fit where they are. A feature holding one of the two
     * functions that share a body with another would relocate a function rather than write it in
     * place, which is a different case and is covered by the whole-table comparison test.
     */
    private val chosenFeatures = listOf("gift_buttons", "analytics_events")

    /** The functions those features cover, in the table's own order. */
    private val chosen = DiscordHermesBundlePatch.PATCHES
        .map { it.functionId }
        .filter { DiscordHermesFunctionCatalog.featureOf(it)?.slug in chosenFeatures }

    /** Patched by the whole-table path, and in a feature the selection does not name. */
    private val leftAlone = 79601

    private fun bundleName(chosen: Boolean) = if (chosen) "chosen" else "base"

    @Test
    fun aSubsetChangesTheChosenFunctionsAndNothingElse() {
        assumeTrue(
            "the Discord 349.5 APKs are not on this machine (${baseApk.path}, " +
                "${recordedApk.path})",
            baseApk.isFile && recordedApk.isFile
        )

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
        assertEquals(
            "a key saved before features existed selects the feature that now covers it",
            DiscordHermesFunctionCatalog.featureOf(62908)?.functionIds,
            DiscordHermesFunctionCatalog
                .selectPatches(
                    PatchSelection.fromSavedIds(listOf("discord_hermes:fn62908"), PatchItemCatalog)
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
        val recorded = bundleOf(recordedApk)

        assertEquals(
            "every patch must land: a skip leaves a function holding the old body",
            emptyList<String>(),
            result.skipped.map { "${it.functionId} (${it.name}): ${it.detail}" }
        )
        assertEquals(chosen.size, result.appliedCount)
        assertEquals(chosen, result.writtenInPlace.map { it.functionId }.sorted())
        assertEquals(
            "none of the three grows its function, so nothing needs relocating",
            emptyList<Int>(),
            result.relocated.map { it.functionId }
        )

        // Every function in the bundle, compared against the body it should have: the recorded
        // build's for the three that were selected, the input's for all the others.
        val mismatches = ArrayList<String>()
        var matchedChosen = 0
        var matchedUntouched = 0
        for (functionId in 0 until DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT) {
            val wasChosen = functionId in chosen
            val expected = if (wasChosen) recorded else base
            val ours = HermesFunctionTable.locate(patched, functionId)
            val theirs = HermesFunctionTable.locate(expected, functionId)
            if (ours == null || theirs == null) {
                mismatches += "fn $functionId: patched=${ours != null}, " +
                    "${bundleName(wasChosen)}=${theirs != null}"
                continue
            }
            if (ours.bytecodeSize != theirs.bytecodeSize) {
                mismatches += "fn $functionId: ${ours.bytecodeSize} bytes, " +
                    "${bundleName(wasChosen)} has ${theirs.bytecodeSize}"
                continue
            }
            if (
                !regionsEqual(
                    patched,
                    ours.bodyOffset,
                    expected,
                    theirs.bodyOffset,
                    ours.bytecodeSize
                )
            ) {
                mismatches +=
                    "fn $functionId: differs from the ${bundleName(wasChosen)} bundle at " +
                        firstDifference(
                            patched,
                            ours.bodyOffset,
                            expected,
                            theirs.bodyOffset,
                            ours.bytecodeSize
                        )
                continue
            }
            if (wasChosen) matchedChosen++ else matchedUntouched++
        }

        println(
            "HermesSubsetComparisonTest: ${chosen.size} chosen functions match the recorded bundle, " +
                "$matchedUntouched of " +
                "${DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT - chosen.size} " +
                "unchosen ones are byte-identical to the input"
        )
        assertEquals(mismatches.take(10), emptyList<String>())
        assertEquals(chosen.size, matchedChosen)
        assertEquals(DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT - chosen.size, matchedUntouched)

        // Stated separately because the sweep above reports it as one of 155,426 lines: a subset
        // that widened back to the whole table without a report.
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

        // In-place writes only, so the file is the size it was: three functions changed and
        // nothing else moved.
        assertEquals(base.size, patched.size)

        // The bundle still has to be loadable, which means a footer matching the file as it now is.
        val fileLength = readU32Le(patched, fileLengthOffset)
        assertEquals("the footer must close the file exactly", patched.size, fileLength)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(patched, 0, fileLength - sha1FooterSize)
        assertTrue(
            "the SHA-1 footer does not cover the patched file",
            digest.digest().contentEquals(
                patched.copyOfRange(fileLength - sha1FooterSize, fileLength)
            )
        )

        // The input bundle must come back unmodified: the pipeline keeps using it.
        val baseDigest = MessageDigest.getInstance("SHA-1")
        baseDigest.update(base, 0, base.size - sha1FooterSize)
        assertTrue(
            "the patcher modified the input bundle it was given",
            baseDigest.digest().contentEquals(
                base.copyOfRange(base.size - sha1FooterSize, base.size)
            )
        )
    }


}
