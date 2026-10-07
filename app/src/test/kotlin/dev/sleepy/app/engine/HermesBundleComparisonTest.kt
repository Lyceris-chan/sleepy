package dev.sleepy.app.engine

import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.testing.ComparisonApks
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
 * The Discord Hermes bundle patch set, checked byte for byte against the recorded build.
 *
 * Every function of the patched bundle is located and compared against the same function in the
 * recorded bundle, one byte at a time, and a disassembler is asked to read every relocated
 * function. The bundles are extracted from the two Discord 349.5 APKs at test time; a machine
 * without those fixtures reports the tests as skipped.
 */
class HermesBundleComparisonTest {

    /**
     * The shipped build the patch table was extracted from, and the recorded build's patched
     * output, the bundle this test compares against function for function.
     */
    private val baseApk = ComparisonApks.discordBaseApk
    private val recordedApk = ComparisonApks.discordRecordedApk
    private val hermesDecomp = ComparisonApks.hermesDecomp

    private val fileLengthOffset = 32
    private val sha1FooterSize = 20

    /**
     * One function this build patches differently from the recorded build on purpose.
     *
     * @param functionId The bundle's identifier for the function.
     * @param reason Why the two bodies differ, in the terms of the caller that reads the return.
     * @param recordedBodyHex The body the recorded bundle holds, which this build rejects, or
     *   null when the recorded build holds the base bundle's body, which is the case for the
     *   compiled copies it never patches.
     */
    private data class DeliberateDifference(
        val functionId: Int,
        val reason: String,
        val recordedBodyHex: String?
    )

    /**
     * The functions whose bodies are not the recorded build's, and why.
     *
     * In 349.5, 49956 and 58239 name functions unrelated to the upsell buttons they held in 348.5,
     * and the recorded change set still stubs them to `undefined`. The one caller of each function
     * dereferences the return, so the recorded build's own value throws a TypeError wherever that
     * caller runs: 58216 hands 58239's return to `hasTypingIndicatorContent`, which reads
     * `.length`, and 49954 reads `.result` off 49956's return. The replacements in
     * [DiscordHermesBundlePatch.PATCHES] carry a value of the shape each caller reads.
     *
     * 75719 and 62409 are the React Compiler copies of the two profile content components. The
     * recorded change set patches only the copy the app runs by default, so the compiled copy
     * still builds the Board and Wishlist tabs whenever the React Compiler experiment is on. This
     * build edits both copies, so its body differs from the recorded build by design; the
     * recorded build still holds the base bundle's body, and the check below compares it against
     * that.
     *
     * 45447 and 45448 are the two branches of the Shop This Look experiment gate. The recorded
     * change set removes the sheet and the coachmark but not the opener, whose body it cannot
     * replace without taking an unrelated display-name sheet with it, so the overflow menu's Shop
     * This Look row is still on screen and still opens an empty sheet. This build answers false at
     * the gate, which the row is built from, so its body differs from the recorded build by
     * design; as with the two above, the recorded build still holds the base bundle's body.
     *
     * The exception is per function id and checks both sides, so it cannot hide any other
     * difference: the comparison below requires our body to be exactly the table's replacement
     * and the recorded body to be exactly the body named here, and it still compares every other
     * function byte for byte.
     */
    private val deliberateDifferences = listOf(
        DeliberateDifference(
            49956,
            "the RPC interceptor reads .result off the handler's return, so this build returns " +
                "{result: {confirmed: false}} where the recorded build returns undefined",
            "93007e7600"
        ),
        DeliberateDifference(
            58239,
            "hasTypingIndicatorContent reads .length off the hook's return, so this build " +
                "returns an empty array where the recorded build returns undefined",
            "93007e7600"
        ),
        DeliberateDifference(
            62409,
            "the recorded build leaves the React Compiler copy of the other-profile content " +
                "component unpatched, so its removal does not hold when the React Compiler " +
                "experiment is on and the compiled copy still builds the Board and Wishlist " +
                "tabs; this build removes them from both copies",
            null
        ),
        DeliberateDifference(
            75719,
            "the recorded build leaves the React Compiler copy of the You screen content component " +
                "unpatched, so its removal does not hold when the React Compiler experiment is " +
                "on and the compiled copy still builds the Board and Wishlist tabs; this build " +
                "removes them from both copies",
            null
        ),
        DeliberateDifference(
            45447,
            "the recorded build leaves the Shop This Look experiment gate answering the server's " +
                "variation, so the overflow menu's row for the feature is still built and still " +
                "opens an empty sheet; this build answers false, which is where the row's " +
                "visibility comes from",
            null
        ),
        DeliberateDifference(
            45448,
            "the recorded build leaves the Shop This Look experiment gate answering the server's " +
                "variation, so the overflow menu's row for the feature is still built and still " +
                "opens an empty sheet; this build answers false, which is where the row's " +
                "visibility comes from",
            null
        )
    )

    private fun hexOf(text: String): ByteArray =
        ByteArray(text.length / 2) { index ->
            text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    @Test
    fun patchedBundleMatchesTheRecordedBundleFunctionForFunction() {
        assumeTrue(
            "the Discord 349.5 APKs are not on this machine (${baseApk.path}, " +
                "${recordedApk.path})",
            baseApk.isFile && recordedApk.isFile
        )

        val base = bundleOf(baseApk)
        assertEquals(
            "the base bundle is not the build the patch set was extracted from",
            DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toLong(),
            base.size.toLong()
        )

        val result = HermesBundlePatcher.apply(base, DiscordHermesBundlePatch.PATCHES)
        val patched = result.bundleBytes
        val recorded = bundleOf(recordedApk)

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

        // 202 replacements fit in place. Five bodies grow—7762 is the odd one, since the recorded
        // change set bounds a cache by writing instructions into the middle of the function
        // rather than replacing it—and two pairs share a body, 62045 with 62046 and 71516 with
        // 71528. A shared region can hold only the larger replacement, so the smaller of each
        // pair is relocated along with the five grown bodies. The two compiled-copy edits, 62409
        // and 75719, keep their bodies' size, and the two gate edits, 45447 and 45448, are
        // shorter than the bodies they replace, so all four land in place and the relocation set
        // is unchanged.
        assertEquals(
            "in-place: " + result.writtenInPlace.joinToString { it.functionId.toString() },
            202,
            result.writtenInPlace.size
        )
        assertEquals(
            "relocated: " + result.relocated.joinToString { it.functionId.toString() },
            7,
            result.relocated.size
        )
        assertEquals(
            listOf(7762, 14786, 14790, 14797, 15698, 62046, 71516),
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
        val differences = deliberateDifferences.associateBy { it.functionId }
        val seenDifferences = HashSet<Int>()
        var identical = 0
        for (functionId in 0 until DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT) {
            val ours = HermesFunctionTable.locate(patched, functionId)
            val theirs = HermesFunctionTable.locate(recorded, functionId)
            if (ours == null || theirs == null) {
                mismatches += "fn $functionId: ours=${ours ?: "unlocatable"}, " +
                    "recorded=${theirs ?: "unlocatable"}"
                continue
            }
            val difference = differences[functionId]
            if (difference != null) {
                seenDifferences += functionId
                mismatches += checkDifference(base, patched, ours, recorded, theirs, difference)
                continue
            }
            if (ours.bytecodeSize != theirs.bytecodeSize) {
                mismatches += "fn $functionId: ${ours.bytecodeSize} bytes, the recorded bundle has " +
                    "${theirs.bytecodeSize}"
                continue
            }
            if (
                !regionsEqual(
                    patched, ours.bodyOffset, recorded, theirs.bodyOffset, ours.bytecodeSize
                )
            ) {
                mismatches += "fn $functionId: ${ours.bytecodeSize} bytes differ at " +
                    firstDifference(
                        patched,
                        ours.bodyOffset,
                        recorded,
                        theirs.bodyOffset,
                        ours.bytecodeSize
                    )
                continue
            }
            identical++
        }

        val undeclared = differences.keys.filterNot { seenDifferences.contains(it) }
        assertEquals(
            "every named difference must be a patched function in both bundles",
            emptyList<Int>(),
            undeclared
        )

        val compared = DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT - differences.size
        println(
            "HermesBundleComparisonTest: $identical/$compared function bodies " +
                "byte-identical to the recorded bundle " +
                "(${DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT - result.appliedCount} " +
                "untouched, ${result.writtenInPlace.size} written in place, " +
                "${result.relocated.size} relocated), plus ${differences.size} deliberate " +
                "differences: " +
                deliberateDifferences.joinToString { "${it.functionId} (${it.reason})" }
        )
        assertEquals(mismatches.take(10), emptyList<String>())
        assertEquals(compared, identical)

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
     * Re-reads the relocated bodies with the recorded build's disassembler, which parses the
     * bundle from the file header rather than from [HermesFunctionTable]'s reading of it. A
     * relocated body only our own reader can find would pass the comparison above and fail here.
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

        val temp = File.createTempFile("hermes-comparison-", ".bundle")
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

    /**
     * Checks one declared difference in both directions, and returns a mismatch description when
     * either side fails: our bundle must carry exactly the patch table's replacement for the id,
     * and the recorded bundle must carry exactly the body the difference names, either the hex
     * it holds or, when it holds null, the base bundle's body. A difference anywhere else in the
     * two bodies is a drift this test still reports.
     */
    private fun checkDifference(
        base: ByteArray,
        patched: ByteArray,
        ours: HermesFunctionTable.FunctionLocation,
        recorded: ByteArray,
        theirs: HermesFunctionTable.FunctionLocation,
        difference: DeliberateDifference
    ): List<String> {
        val failures = ArrayList<String>()
        val replacement = DiscordHermesBundlePatch.PATCHES
            .first { it.functionId == difference.functionId }
            .replacement
        if (ours.bytecodeSize != replacement.size ||
            !regionsEqual(patched, ours.bodyOffset, replacement, 0, replacement.size)
        ) {
            failures += "fn ${difference.functionId}: this build's body is not the table's " +
                "${replacement.size}-byte replacement (declared ${ours.bytecodeSize} bytes)"
        }
        val recordedBody = difference.recordedBodyHex?.let(::hexOf) ?: run {
            val located =
                requireNotNull(HermesFunctionTable.locate(base, difference.functionId)) {
                    "function ${difference.functionId} is not locatable in the base bundle"
                }
            base.copyOfRange(located.bodyOffset, located.bodyOffset + located.bytecodeSize)
        }
        if (theirs.bytecodeSize != recordedBody.size ||
            !regionsEqual(recorded, theirs.bodyOffset, recordedBody, 0, recordedBody.size)
        ) {
            failures += "fn ${difference.functionId}: the recorded bundle does not hold " +
                "${difference.recordedBodyHex ?: "the base bundle's body"} " +
                "(declared ${theirs.bytecodeSize} bytes), so the recorded difference no longer " +
                "matches it"
        }
        return failures
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
