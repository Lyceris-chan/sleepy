package dev.sleepy.app

import dev.sleepy.app.engine.HermesBundlePatcher
import dev.sleepy.app.engine.HermesFunctionTable
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Applies a *subset* of the JavaScript patch table to the real Discord 348.5 bundle and checks
 * that it changed the chosen functions and nothing else.
 *
 * A subset is the whole point of per-item selection: the pipeline filters the list it hands to
 * [HermesBundlePatcher.apply], so the risk this guards against is a filter that is off by an
 * index, or a table whose order does not line up with the content table's, quietly patching the
 * wrong function.
 *
 * The two bundles this compares are not in the repository (55 MB each), so they are read out of
 * the Discord 348.5 APKs at test time — the base split and the desktop build's patched reference
 * — the same fixtures [HermesBundleParityTest] uses, taken from the same archives. Reading them
 * out of the APKs rather than from a hand-extracted copy under `/tmp` is what keeps them: the
 * scratch copy did not survive a reboot, and a fixture that is gone for good leaves this test
 * skipping forever, which covers nothing while still looking green.
 *
 * A machine that has neither APK — CI on a clean runner — reports the test as skipped rather than
 * failing on something it never had.
 */
class HermesSubsetPatchTest {

    private companion object {
        /** The shipped build the patch set was extracted from, and the patched reference build. */
        const val BASE_APK =
            "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted/base.apk"
        const val REFERENCE_APK =
            "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/out/discord-alpha-348.5-patched-unsigned.apk"

        /** The entry both APKs carry the bundle in. */
        const val BUNDLE_ENTRY = "assets/index.android.bundle"
    }

    private val baseApk = File(BASE_APK)
    private val referenceApk = File(REFERENCE_APK)

    private val fileLengthOffset = 32
    private val sha1FooterSize = 20

    /** Three functions the whole-table path patches in place: a gift button, a predicate, a log. */
    private val chosen = listOf(57688, 62294, 69783)

    /** Patched by the whole-table path, and next to a chosen function in the table. */
    private val leftAlone = 62298

    private fun bundleName(chosen: Boolean) = if (chosen) "reference" else "base"

    @Test
    fun testASubsetChangesTheChosenFunctionsAndNothingElse() {
        assumeTrue(
            "the Discord 348.5 APKs are not on this machine (${baseApk.path}, ${referenceApk.path})",
            baseApk.isFile && referenceApk.isFile
        )

        val selection = PatchSelection.ofKeys(
            *chosen.reversed().map { DiscordHermesFunctionCatalog.itemKeyOf(it) }.toTypedArray()
        )
        val patches = DiscordHermesFunctionCatalog.selectPatches(selection)

        assertEquals(
            "the subset is exactly the chosen functions, in the table's order rather than the selection's",
            chosen,
            patches.map { it.functionId }
        )
        assertEquals(
            "a key that names no patched function selects nothing",
            emptyList<Int>(),
            DiscordHermesFunctionCatalog.selectPatches(PatchSelection.ofKeys("discord_hermes:fn999999")).map { it.functionId }
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
                mismatches += "fn $functionId: patched=${ours != null}, ${bundleName(wasChosen)}=${theirs != null}"
                continue
            }
            if (ours.bytecodeSize != theirs.bytecodeSize) {
                mismatches += "fn $functionId: ${ours.bytecodeSize} bytes, ${bundleName(wasChosen)} has ${theirs.bytecodeSize}"
                continue
            }
            if (!regionsEqual(patched, ours.bodyOffset, expected, theirs.bodyOffset, ours.bytecodeSize)) {
                mismatches += "fn $functionId: differs from the ${bundleName(wasChosen)} bundle at " +
                    firstDifference(patched, ours.bodyOffset, expected, theirs.bodyOffset, ours.bytecodeSize)
                continue
            }
            if (wasChosen) matchedChosen++ else matchedUntouched++
        }

        println(
            "HermesSubsetPatchTest: ${chosen.size} chosen functions match the reference, " +
                "$matchedUntouched of ${DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT - chosen.size} " +
                "unchosen ones are byte-identical to the input"
        )
        assertEquals(mismatches.take(10), emptyList<String>())
        assertEquals(chosen.size, matchedChosen)
        assertEquals(DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT - chosen.size, matchedUntouched)

        // Stated separately because it is the failure the sweep above would report as one of
        // 128,466 lines: a subset that quietly widened back to the whole table.
        val alone = requireNotNull(HermesFunctionTable.locate(patched, leftAlone))
        val aloneInBase = requireNotNull(HermesFunctionTable.locate(base, leftAlone))
        assertEquals(aloneInBase.bytecodeSize, alone.bytecodeSize)
        assertTrue(
            "function $leftAlone is patched by the whole table and must be untouched by this subset",
            regionsEqual(patched, alone.bodyOffset, base, aloneInBase.bodyOffset, aloneInBase.bytecodeSize)
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
            digest.digest().contentEquals(patched.copyOfRange(fileLength - sha1FooterSize, fileLength))
        )

        // The bundle handed in must come back unmodified: the pipeline keeps using it.
        val baseDigest = MessageDigest.getInstance("SHA-1")
        baseDigest.update(base, 0, base.size - sha1FooterSize)
        assertTrue(
            "the patcher modified the input bundle it was given",
            baseDigest.digest().contentEquals(base.copyOfRange(base.size - sha1FooterSize, base.size))
        )
    }

    /**
     * [apk]'s `assets/index.android.bundle` entry, read out of the archive rather than from a
     * copy of it placed beside the APK. The archive is opened as a `ZipFile` and only the one
     * entry is read, so the 96 MB the APK weighs is never held in memory.
     *
     * A missing entry throws rather than skips: the APK being absent is the machine saying it
     * never had the fixture, but an APK that is here and holds no bundle is one that is not the
     * build this test is about, and that has to fail loudly.
     */
    private fun bundleOf(apk: File): ByteArray = ZipFile(apk).use { zip ->
        val entry = zip.getEntry(BUNDLE_ENTRY)
            ?: throw AssertionError("${apk.path} carries no $BUNDLE_ENTRY entry")
        zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun regionsEqual(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, length: Int): Boolean {
        for (index in 0 until length) {
            if (a[aOffset + index] != b[bOffset + index]) return false
        }
        return true
    }

    private fun firstDifference(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, length: Int): String {
        for (index in 0 until length) {
            if (a[aOffset + index] != b[bOffset + index]) {
                return "$index (${(a[aOffset + index].toInt() and 0xFF).toString(16)} vs " +
                    "${(b[bOffset + index].toInt() and 0xFF).toString(16)})"
            }
        }
        return "no difference"
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
