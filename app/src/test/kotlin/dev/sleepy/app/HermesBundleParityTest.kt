package dev.sleepy.app

import dev.sleepy.app.engine.HermesBundlePatcher
import dev.sleepy.app.engine.HermesFunctionTable
import dev.sleepy.app.patches.DiscordHermesBundlePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Proves that patching the Discord 348.5 bundle with [DiscordHermesBundlePatch.PATCHES] lands on
 * the same bytecode its reference build ships, function for function.
 *
 * The check is exhaustive and byte-level: all 128,469 functions of the output are located with
 * [HermesFunctionTable] and compared against the same function's body in the reference bundle,
 * one byte at a time. Nothing is sampled and nothing is compared through a disassembler, so a
 * function that was patched into something that merely decompiles alike still fails here.
 *
 * The bundles are not in the repository (55 MB each), so they are read out of the two Discord
 * 348.5 APKs at test time: the base split the patch table was extracted from, and the desktop
 * build's patched reference. Deriving them from those APKs is what makes them durable — the
 * earlier arrangement read a hand-extracted copy under `/tmp`, which no reboot survives, and a
 * fixture that is gone for good turns this comparison into a permanent skip, the same coverage
 * loss as the early `return` that used to make an absent bundle look like a pass. The reference
 * comes from the patched APK the desktop build produced rather than from the loose
 * `decompiled/` copy beside it, because the APK is the artefact that was built; a hand-placed
 * copy can drift from it without anything noticing.
 *
 * A machine that has neither APK — CI on a clean runner — reports these tests as skipped rather
 * than failing on something it never had. Skipped is a visible outcome.
 */
class HermesBundleParityTest {

    private companion object {
        /**
         * The two Discord 348.5 APKs, both outside the repository. The first is the shipped
         * build the patch table was extracted from and whose bundle size the patch set pins;
         * the second is the desktop build's patched output, the bundle this test compares
         * against function for function.
         */
        const val BASE_APK =
            "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted/base.apk"
        const val REFERENCE_APK =
            "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/out/discord-alpha-348.5-patched-unsigned.apk"

        /** The entry both APKs carry the bundle in. */
        const val BUNDLE_ENTRY = "assets/index.android.bundle"
    }

    private val baseApk = File(BASE_APK)
    private val referenceApk = File(REFERENCE_APK)
    private val hermesDecomp = File(
        "/home/sleepy/Documents/antigravity/quirky-noether/discord/tools/hermes-decomp"
    )

    private val fileLengthOffset = 32
    private val sha1FooterSize = 20

    @Test
    fun testPatchedBundleMatchesReferenceFunctionForFunction() {
        assumeTrue(
            "the Discord 348.5 APKs are not on this machine (${baseApk.path}, ${referenceApk.path})",
            baseApk.isFile && referenceApk.isFile
        )

        val base = bundleOf(baseApk)
        assertEquals(
            "the base bundle is not the build the patch set was extracted from",
            DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toLong(),
            base.size.toLong()
        )

        val result = HermesBundlePatcher.apply(base, DiscordHermesBundlePatch.PATCHES)
        val patched = result.bundleBytes
        val reference = bundleOf(referenceApk)

        // The bundle handed in must come back unmodified: callers keep using theirs.
        val baseDigest = MessageDigest.getInstance("SHA-1")
        baseDigest.update(base, 0, base.size - sha1FooterSize)
        assertTrue(
            "the patcher modified the input bundle it was given",
            baseDigest.digest().contentEquals(base.copyOfRange(base.size - sha1FooterSize, base.size))
        )

        // Every patch must land: a skip means a function was left holding the old body.
        assertEquals(emptyList<String>(), result.skipped.map { "${it.functionId} (${it.name}): ${it.detail}" })
        assertEquals(DiscordHermesBundlePatch.PATCHES.size, result.appliedCount)

        // 138 replacements fit in place, but 57120's fits only by writing AsyncBreakCheck over
        // 57119, which shares its body, so the four grown bodies and that one are relocated.
        assertEquals(
            "in-place: " + result.writtenInPlace.joinToString { it.functionId.toString() },
            137,
            result.writtenInPlace.size
        )
        assertEquals(
            "relocated: " + result.relocated.joinToString { it.functionId.toString() },
            5,
            result.relocated.size
        )
        assertEquals(listOf(14518, 14522, 14529, 15420, 57120), result.relocated.map { it.functionId })

        // The spec's padding: what a shorter replacement leaves behind is AsyncBreakCheck, and
        // the function's declaration is narrowed to the replacement so the body it names is the
        // replacement alone.
        for (outcome in result.writtenInPlace) {
            val patch = DiscordHermesBundlePatch.PATCHES.first { it.functionId == outcome.functionId }
            val located = HermesFunctionTable.locate(base, patch.functionId)
            requireNotNull(located) { "function ${patch.functionId} is not locatable in the base bundle" }
            for (offset in (located.bodyOffset + patch.replacement.size) until (located.bodyOffset + patch.originalSize)) {
                assertEquals(
                    "function ${patch.functionId} byte $offset is not AsyncBreakCheck padding",
                    0x7E,
                    patched[offset].toInt() and 0xFF
                )
            }
        }

        val mismatches = ArrayList<String>()
        var identical = 0
        for (functionId in 0 until DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT) {
            val ours = HermesFunctionTable.locate(patched, functionId)
            val theirs = HermesFunctionTable.locate(reference, functionId)
            if (ours == null || theirs == null) {
                mismatches += "fn $functionId: ours=${ours ?: "unlocatable"}, reference=${theirs ?: "unlocatable"}"
                continue
            }
            if (ours.bytecodeSize != theirs.bytecodeSize) {
                mismatches += "fn $functionId: ${ours.bytecodeSize} bytes, reference has ${theirs.bytecodeSize}"
                continue
            }
            if (!regionsEqual(patched, ours.bodyOffset, reference, theirs.bodyOffset, ours.bytecodeSize)) {
                mismatches += "fn $functionId: ${ours.bytecodeSize} bytes differ " +
                    "at ${firstDifference(patched, ours.bodyOffset, reference, theirs.bodyOffset, ours.bytecodeSize)}"
                continue
            }
            identical++
        }

        println(
            "HermesBundleParityTest: $identical/${DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT} function " +
                "bodies byte-identical to the reference (${DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT - result.appliedCount} " +
                "untouched, ${result.writtenInPlace.size} written in place, ${result.relocated.size} relocated)"
        )
        assertEquals(mismatches.take(10), emptyList<String>())
        assertEquals(DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT, identical)

        // The bundle has to stay loadable, which means a footer matching the file as it now is.
        val fileLength = readU32Le(patched, fileLengthOffset)
        assertEquals("the footer must close the file exactly", patched.size, fileLength)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(patched, 0, fileLength - sha1FooterSize)
        assertTrue(
            "the SHA-1 footer does not cover the patched file",
            digest.digest().contentEquals(patched.copyOfRange(fileLength - sha1FooterSize, fileLength))
        )
    }

    /**
     * Re-reads the relocated bodies with the reference disassembler, which parses the bundle
     * from the file header rather than from [HermesFunctionTable]'s reading of it. A relocated
     * body that only our own reader can find would pass the comparison above and fail here.
     *
     * A test of its own because `hermes-decomp` is a tool this machine may not have: as a branch
     * inside the comparison above, its absence was a line of printed output and the cross-check
     * was reported as covered. Here it is reported as skipped, and the comparison keeps its own,
     * separately reported verdict either way.
     */
    @Test
    fun aRealDisassemblerAcceptsEveryRelocatedFunction() {
        assumeTrue("the Discord base APK is not on this machine (${baseApk.path})", baseApk.isFile)
        assumeTrue("${hermesDecomp.path} is not executable", hermesDecomp.canExecute())

        val base = bundleOf(baseApk)
        assertEquals(
            "the base bundle is not the build the patch set was extracted from",
            DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toLong(),
            base.size.toLong()
        )
        val result = HermesBundlePatcher.apply(base, DiscordHermesBundlePatch.PATCHES)
        val patched = result.bundleBytes

        val temp = File.createTempFile("hermes-parity-", ".bundle")
        try {
            temp.writeBytes(patched)
            for (outcome in result.relocated) {
                val (exit, output) = decompile(temp, outcome.functionId)
                val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() } ?: "<no output>"
                println("hermes-decomp decompile --function ${outcome.functionId}: exit=$exit, $firstLine")
                assertEquals(
                    "hermes-decomp rejected the bundle at function ${outcome.functionId}: $output",
                    0,
                    exit
                )
                assertTrue(
                    "hermes-decomp produced nothing for function ${outcome.functionId}",
                    output.contains("function ")
                )
            }
        } finally {
            temp.delete()
        }
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

    private fun decompile(bundle: File, functionId: Int): Pair<Int, String> {
        val process = ProcessBuilder(
            hermesDecomp.absolutePath,
            "decompile",
            "--function",
            functionId.toString(),
            bundle.absolutePath
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to "timed out after 300s"
        }
        return process.exitValue() to output
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
