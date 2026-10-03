package dev.sleepy.app.engine

import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.testing.ReferenceApks
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
 * reference.
 *
 * Per-item selection exists to apply a subset, so the functions the selection names must match
 * the reference build and the functions it does not name must keep their base bytes. The bundles
 * come from the same two Discord 349.5 APKs the full parity test uses.
 */
class HermesSubsetParityTest {

    /** The shipped build the patch set was extracted from, and the patched reference build. */
    private val baseApk = ReferenceApks.discordBaseApk
    private val referenceApk = ReferenceApks.discordReferenceApk

    private val fileLengthOffset = 32
    private val sha1FooterSize = 20

    /** Three functions the whole-table path patches in place: a gift button, a predicate, a log. */
    private val chosen = listOf(62908, 68593, 79600)

    /** Patched by the whole-table path, and next to a chosen function in the table. */
    private val leftAlone = 79601

    private fun bundleName(chosen: Boolean) = if (chosen) "reference" else "base"

    @Test
    fun aSubsetChangesTheChosenFunctionsAndNothingElse() {
        assumeTrue(
            "the Discord 349.5 APKs are not on this machine (${baseApk.path}, " +
                "${referenceApk.path})",
            baseApk.isFile && referenceApk.isFile
        )

        val selection = PatchSelection.ofKeys(
            *chosen.reversed().map { DiscordHermesFunctionCatalog.itemKeyOf(it) }.toTypedArray()
        )
        val patches = DiscordHermesFunctionCatalog.selectPatches(selection)

        assertEquals(
            "the subset is exactly the chosen functions, in the table's order rather than the " +
                "selection's",
            chosen,
            patches.map { it.functionId }
        )
        assertEquals(
            "a key that names no patched function selects nothing",
            emptyList<Int>(),
            DiscordHermesFunctionCatalog
                .selectPatches(PatchSelection.ofKeys("discord_hermes:fn999999"))
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
        val reference = bundleOf(referenceApk)

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

        // Every function in the bundle, compared against the body it should have: the reference
        // build's for the three that were selected, the input's for all the others.
        val mismatches = ArrayList<String>()
        var matchedChosen = 0
        var matchedUntouched = 0
        for (functionId in 0 until DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT) {
            val wasChosen = functionId in chosen
            val expected = if (wasChosen) reference else base
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
            "HermesSubsetParityTest: ${chosen.size} chosen functions match the reference, " +
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
