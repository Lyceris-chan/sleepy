package dev.sleepy.app.engine

import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.bundleOf
import dev.sleepy.app.testing.readU32Le
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Discord Hermes patch table, applied to the build it names.
 *
 * The table is data extracted from one bundle: every entry declares the size of the body it
 * replaces and carries bytes written for that body. Nothing about the entry is checked at the
 * point it is used, so a table built for one release and used on another writes plausible bytes
 * into unrelated functions. [HermesBundlePatcher] refuses an entry whose declared size does not
 * match, which turns that mistake into a skip rather than a corruption - and this test is the
 * other half: on the build the table was extracted from, *nothing* may be skipped.
 *
 * What it cannot check is whether a replacement does what its table entry says. There is no
 * recorded build of the patched 351.2.5 bundle to compare against, and the earlier suite's
 * function-for-function comparison is gone with it. The check that survives is structural and
 * still catches the failure that matters most - a body that is not valid bytecode, which is how
 * a truncated replacement would ship - so a real disassembler reads every patched function back.
 *
 * The base split is a fixture outside the repository; a machine without it reports the tests as
 * skipped.
 */
class HermesBundleApplicationTest {

    /** The shipped build the patch table was extracted from. */
    private val baseApk = ComparisonApks.discordBaseApk
    private val hermesDecomp = ComparisonApks.hermesDecomp

    private val fileLengthOffset = 32
    private val functionCountOffset = 40
    private val sha1FooterSize = 20

    /** The 96-bit function table: where it starts, and how wide one entry is. */
    private val entryTableOffset = 128
    private val entryBytes = 12

    /** The 37-byte large header's flags byte, and the two bits of it that name trailing data. */
    private val largeHeaderFlagsField = 36
    private val flagHasExceptionHandler = 0x08
    private val flagHasDebugInfo = 0x10

    /**
     * The functions whose replacements do not fit where the bodies they replace are.
     *
     * Six are longer than the body they replace, which no in-place write can hold: 8346 inserts
     * the cache clamp into the middle of the function, the four settings rows append a
     * `usePredicate` property, and 34451 clamps the pixel budget to a wider constant. Four share
     * a body with another entry - 32481 with 32480, 65890 with 65889, 75050 with 75038, and
     * 42009 with 42008, whose two compiler variants were compiled to identical code and
     * deduplicated - and a shared region can hold only one replacement, so the lower function
     * id keeps it and the other is relocated beside it.
     */
    private val relocated =
        listOf(8346, 15959, 15965, 15972, 16829, 32481, 34451, 42009, 65890, 75050)

    @Test
    fun everyEntryAppliesToTheBuildTheTableNames() {
        assumeTrue("the Discord base APK is not on this machine (${baseApk.path})", baseApk.isFile)

        val base = bundleOf(baseApk)
        assertEquals(
            "the base bundle is not the build the patch set was extracted from",
            DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toLong(),
            base.size.toLong()
        )
        assertEquals(
            "the bundle's function count is not the one the patch set records",
            DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT,
            readU32Le(base, functionCountOffset)
        )

        val result = HermesBundlePatcher.apply(base, DiscordHermesBundlePatch.PATCHES)
        val patched = result.bundleBytes

        // The bundle passed in must come back unmodified: callers keep using their own copy.
        val baseDigest = MessageDigest.getInstance("SHA-1")
        baseDigest.update(base, 0, base.size - sha1FooterSize)
        assertTrue(
            "the patcher modified the input bundle it was given",
            baseDigest.digest()
                .contentEquals(base.copyOfRange(base.size - sha1FooterSize, base.size))
        )

        // A skip means a function was left holding the old body, which on this build is a bug in
        // the table and not a property of the input.
        assertEquals(
            emptyList<String>(),
            result.skipped.map { "${it.functionId} (${it.name}): ${it.detail}" }
        )
        assertEquals(DiscordHermesBundlePatch.PATCHES.size, result.appliedCount)

        assertEquals(
            "in-place: " + result.writtenInPlace.joinToString { it.functionId.toString() },
            DiscordHermesBundlePatch.PATCHES.size - relocated.size,
            result.writtenInPlace.size
        )
        assertEquals(
            "relocated: " + result.relocated.joinToString { it.functionId.toString() },
            relocated,
            result.relocated.map { it.functionId }
        )

        // The spec's padding: what a shorter replacement leaves behind is AsyncBreakCheck, and
        // the function's declaration is narrowed to the replacement so the body it names is the
        // replacement alone.
        for (outcome in result.writtenInPlace) {
            val patch = DiscordHermesBundlePatch.PATCHES
                .first { it.functionId == outcome.functionId }
            val located = requireNotNull(HermesFunctionTable.locate(base, patch.functionId)) {
                "function ${patch.functionId} is not locatable in the base bundle"
            }
            assertEquals(
                "function ${patch.functionId} declares the wrong size after patching",
                patch.replacement.size,
                requireNotNull(HermesFunctionTable.locate(patched, patch.functionId)).bytecodeSize
            )
            for (offset in (located.bodyOffset + patch.replacement.size) until
                (located.bodyOffset + patch.originalSize)) {
                assertEquals(
                    "function ${patch.functionId} byte $offset is not AsyncBreakCheck padding",
                    0x7E,
                    patched[offset].toInt() and 0xFF
                )
            }
        }

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

        println(
            "HermesBundleApplicationTest: ${result.appliedCount} entries applied to " +
                "${base.size} bytes - ${result.writtenInPlace.size} written in place, " +
                "${result.relocated.size} relocated"
        )

        publishBundleForVerification(patched)
    }

    /**
     * Writes the patched bundle where a verifier outside this JVM can read it.
     *
     * The check that matters most cannot run here: whether Hermes itself accepts the bundle.
     * Every assertion in this file, and the disassembler cross-check below, reads the bytes with
     * a reimplementation. A workflow that has a real Hermes to hand needs the artifact, and this
     * is the only place it exists, so it is written out when `SLEEPY_PATCHED_BUNDLE_OUT` names a
     * directory. Unset - which is every normal run - nothing is written and nothing changes.
     *
     * The function ids go beside it, taken from the table rather than from the workflow, so a
     * verifier disassembles exactly the functions this build patches and no others.
     */
    private fun publishBundleForVerification(patched: ByteArray) {
        val dir = System.getenv("SLEEPY_PATCHED_BUNDLE_OUT") ?: return
        val out = File(dir)
        out.mkdirs()
        File(out, "patched.bundle").writeBytes(patched)
        File(out, "patched-functions.txt").writeText(
            DiscordHermesBundlePatch.PATCHES.joinToString("\n") { it.functionId.toString() } + "\n"
        )
        Properties().also { properties ->
            properties["bundleSize"] = patched.size.toString()
            properties["entries"] = DiscordHermesBundlePatch.PATCHES.size.toString()
            properties["targetBundleSize"] =
                DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toString()
        }.store(File(out, "patched.properties").outputStream(), "written by HermesBundleApplicationTest")
        println("HermesBundleApplicationTest: wrote the patched bundle to ${out.absolutePath}")
    }

    /**
     * A relocated function's header must carry neither an exception table nor debug offsets.
     *
     * Those two are not part of the body. Hermes reads them from immediately behind the 37-byte
     * large header, and only when the flags say they are there
     * (`BCProviderFromBuffer::getExceptionTableAndDebugOffsets`). A header written on its own,
     * with nothing behind it and a flag copied from the header it replaced, sends Hermes into
     * whatever the append layout put next - and that is not a wrong value, it is `abort()`. The
     * disassembler trips an assertion on it, and the runtime takes the same path, so a bundle
     * built that way kills the app rather than misbehaving inside it.
     *
     * Asserted off the written bundle rather than off the flags the patcher meant to clear,
     * because the flag byte is copied from a header the patch set does not own: a function with
     * no large header of its own gets one synthesised over a template borrowed from an unrelated
     * function, and that template arrives with its own flags.
     */
    @Test
    fun aRelocatedHeaderCarriesNeitherAnExceptionTableNorDebugOffsets() {
        assumeTrue("the Discord base APK is not on this machine (${baseApk.path})", baseApk.isFile)

        val base = bundleOf(baseApk)
        assumeTrue(
            "the base bundle is not the build the patch set was extracted from",
            base.size.toLong() == DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toLong()
        )
        val result = HermesBundlePatcher.apply(base, DiscordHermesBundlePatch.PATCHES)
        val patched = result.bundleBytes

        assertTrue(
            "no entry was relocated, so this test checked nothing",
            result.relocated.isNotEmpty()
        )
        for (outcome in result.relocated) {
            val slot = entryTableOffset + entryBytes * outcome.functionId
            val word0 = readU32Le(patched, slot)
            val word1 = readU32Le(patched, slot + 4)
            val headerOffset = (((word1 ushr 14) and 0xFF) shl 24) or (word0 and 0x00FFFFFF)
            val flags = patched[headerOffset + largeHeaderFlagsField].toInt() and 0xFF
            assertEquals(
                "function ${outcome.functionId} was relocated to $headerOffset with flags " +
                    "0x${flags.toString(16)}: a header whose function has no exception table " +
                    "and no debug offsets must not claim either",
                0,
                flags and (flagHasExceptionHandler or flagHasDebugInfo)
            )
        }
    }

    /**
     * Every entry that names a function must be pointing at that function.
     *
     * `HermesBundlePatcher` compares the size an entry declares against the size the bundle
     * holds, which catches a table built for another release. It does not catch a table built
     * for *this* release whose ids were resolved wrongly, because the size was read from the
     * function the id now names - it agrees with itself whatever that function is.
     *
     * The bundle names its functions, and those names are Discord's own: `recordStart`,
     * `recordEnd`, `serializeEnd`. This is the check that would have caught the 351.2.0 table
     * shipping `recordStart` at the id of `recordEnd`; it was found by hand instead, with the
     * body in front of it.
     */
    @Test
    fun everyNamedEntryNamesTheFunctionItPatches() {
        assumeTrue("the Discord base APK is not on this machine (${baseApk.path})", baseApk.isFile)
        assumeTrue("${hermesDecomp.path} is not executable", hermesDecomp.canExecute())

        val base = bundleOf(baseApk)
        assumeTrue(
            "the base bundle is not the build the patch set was extracted from",
            base.size.toLong() == DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toLong()
        )

        val temp = File.createTempFile("hermes-names-", ".bundle")
        val names: Map<Int, String>
        try {
            temp.writeBytes(base)
            val output = functionTableDump(temp)
            names = parseFunctionNames(output)
        } finally {
            temp.delete()
        }
        assertTrue(
            "the disassembler produced no function names, so this check cannot run",
            names.size > DiscordHermesBundlePatch.TARGET_FUNCTION_COUNT / 2
        )

        val wrong = DiscordHermesBundlePatch.PATCHES
            .filter { it.name.isNotEmpty() }
            .mapNotNull { patch ->
                val actual = names[patch.functionId] ?: "<not in the table>"
                if (actual == patch.name) null
                else "fn ${patch.functionId}: the table names it ${patch.name}, the bundle " +
                    "names it $actual"
            }
        assertEquals(wrong.take(10), emptyList<String>())
    }

    /** `id -> name` from `hermes-decomp dump --kind functions`, skipping the unnamed. */
    private fun parseFunctionNames(dump: String): Map<Int, String> =
        dump.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4 || parts[0].toIntOrNull() == null) return@mapNotNull null
            val name = parts.subList(1, parts.size - 2).joinToString(" ")
            if (name.isEmpty()) null else parts[0].toInt() to name
        }.toMap()

    /**
     * Reads every patched function back with the real disassembler.
     *
     * The failure this is here for is a body that is not valid bytecode at all: a replacement
     * whose last instruction is missing its operands still occupies the right number of bytes and
     * still passes every check above, because all of them are about *where* the bytes are rather
     * than whether they parse. The only way to tell is to decode them, and the only decoder here
     * reads a bundle.
     *
     * Every patched function is read, not only the relocated ones: a truncated body can be
     * written in place just as easily.
     *
     * A test of its own because `hermes-decomp` is a tool this machine may not have: as a branch
     * inside the test above, its absence would be a line of printed output and the cross-check
     * would be reported as covered.
     */
    @Test
    fun aRealDisassemblerReadsEveryPatchedFunction() {
        assumeTrue("the Discord base APK is not on this machine (${baseApk.path})", baseApk.isFile)
        assumeTrue("${hermesDecomp.path} is not executable", hermesDecomp.canExecute())

        val base = bundleOf(baseApk)
        assertEquals(
            "the base bundle is not the build the patch set was extracted from",
            DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toLong(),
            base.size.toLong()
        )
        val patched = HermesBundlePatcher.apply(base, DiscordHermesBundlePatch.PATCHES).bundleBytes

        val temp = File.createTempFile("hermes-application-", ".bundle")
        val rejected = ArrayList<String>()
        try {
            temp.writeBytes(patched)
            for (patch in DiscordHermesBundlePatch.PATCHES) {
                val (exit, output) = disassemble(temp, patch.functionId)
                if (exit != 0 || !output.contains("function ")) {
                    rejected += "fn ${patch.functionId}: exit=$exit, " +
                        output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                }
            }
        } finally {
            temp.delete()
        }
        println(
            "HermesBundleApplicationTest: ${DiscordHermesBundlePatch.PATCHES.size} patched " +
                "functions read back by hermes-decomp, ${rejected.size} rejected"
        )
        assertEquals(rejected.take(10), emptyList<String>())
    }

    /** Runs `hermes-decomp dump --kind functions` and returns its output. */
    private fun functionTableDump(bundle: File): String {
        val process = ProcessBuilder(
            hermesDecomp.absolutePath, "dump", "--kind", "functions", bundle.absolutePath
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(600, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("hermes-decomp timed out reading the function table")
        }
        return output
    }

    private fun disassemble(bundle: File, functionId: Int): Pair<Int, String> {
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
