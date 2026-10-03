package dev.sleepy.app.engine

import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.testing.ReferenceApks
import dev.sleepy.app.testing.bundleOf
import dev.sleepy.app.testing.firstDifference
import dev.sleepy.app.testing.readU32Le
import dev.sleepy.app.testing.regionsEqual
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Discord Hermes bundle patch set, checked byte for byte against the reference build.
 *
 * Every function of the patched bundle is located and compared against the same function in the
 * reference bundle, one byte at a time, and a disassembler is asked to read every relocated
 * function. The bundles are extracted from the two Discord 348.5 APKs at test time; a machine
 * without those fixtures reports the tests as skipped.
 */
class HermesBundleParityTest {

    /**
     * The shipped build the patch table was extracted from, and the desktop build's patched
     * output, the bundle this test compares against function for function.
     */
    private val baseApk = ReferenceApks.discordBaseApk
    private val referenceApk = ReferenceApks.discordReferenceApk
    private val hermesDecomp = ReferenceApks.hermesDecomp

    private val fileLengthOffset = 32
    private val sha1FooterSize = 20

    @Test
    fun patchedBundleMatchesReferenceFunctionForFunction() {
        assumeTrue(
            "the Discord 348.5 APKs are not on this machine (${baseApk.path}, " +
                "${referenceApk.path})",
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

        // The bundle passed in must come back unmodified: callers keep using their own copy.
        val baseDigest = MessageDigest.getInstance("SHA-1")
        baseDigest.update(base, 0, base.size - sha1FooterSize)
        assertTrue(
            "the patcher modified the input bundle it was given",
            baseDigest.digest()
                .contentEquals(base.copyOfRange(base.size - sha1FooterSize, base.size))
        )

        // Every patch must land: a skip means a function was left holding the old body.
        assertEquals(
            emptyList<String>(),
            result.skipped.map { "${it.functionId} (${it.name}): ${it.detail}" }
        )
        assertEquals(DiscordHermesBundlePatch.PATCHES.size, result.appliedCount)

        // 138 replacements fit in place, but 57120's fits only by writing AsyncBreakCheck over
        // 57119, which shares its body, so the four grown bodies and that one are relocated.
        assertEquals(
            "in-place: " + result.writtenInPlace.joinToString { it.functionId.toString() },
            140,
            result.writtenInPlace.size
        )
        assertEquals(
            "relocated: " + result.relocated.joinToString { it.functionId.toString() },
            5,
            result.relocated.size
        )
        assertEquals(
            listOf(14518, 14522, 14529, 15420, 57120),
            result.relocated.map { it.functionId }
        )

        // The spec's padding: what a shorter replacement leaves behind is AsyncBreakCheck, and
        // the function's declaration is narrowed to the replacement so the body it names is the
        // replacement alone.
        for (outcome in result.writtenInPlace) {
            val patch = DiscordHermesBundlePatch.PATCHES
                .first { it.functionId == outcome.functionId }
            val located = HermesFunctionTable.locate(base, patch.functionId)
            requireNotNull(located) {
                "function ${patch.functionId} is not locatable in the base bundle"
            }
            for (offset in (located.bodyOffset + patch.replacement.size) until
                (located.bodyOffset + patch.originalSize)) {
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
                mismatches += "fn $functionId: ours=${ours ?: "unlocatable"}, " +
                    "reference=${theirs ?: "unlocatable"}"
                continue
            }
            if (ours.bytecodeSize != theirs.bytecodeSize) {
                mismatches += "fn $functionId: ${ours.bytecodeSize} bytes, reference has " +
                    "${theirs.bytecodeSize}"
                continue
            }
            if (
                !regionsEqual(
                    patched, ours.bodyOffset, reference, theirs.bodyOffset, ours.bytecodeSize
                )
            ) {
                mismatches += "fn $functionId: ${ours.bytecodeSize} bytes differ at " +
                    firstDifference(
                        patched,
                        ours.bodyOffset,
                        reference,
                        theirs.bodyOffset,
                        ours.bytecodeSize
                    )
                continue
            }
            identical++
        }

        println(
            "HermesBundleParityTest: $identical/" +
                "${DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT} function bodies " +
                "byte-identical to the reference " +
                "(${DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT - result.appliedCount} " +
                "untouched, ${result.writtenInPlace.size} written in place, " +
                "${result.relocated.size} relocated)"
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
            digest.digest()
                .contentEquals(patched.copyOfRange(fileLength - sha1FooterSize, fileLength))
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
                val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() } ?:
                    "<no output>"
                println(
                    "hermes-decomp decompile --function ${outcome.functionId}: exit=$exit, " +
                        "$firstLine"
                )
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


}
