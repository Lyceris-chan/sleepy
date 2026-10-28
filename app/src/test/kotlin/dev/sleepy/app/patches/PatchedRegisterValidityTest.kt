package dev.sleepy.app.patches

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.engine.SmaliPatcher
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.dexEntries
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Every native patch, applied to its stock method, must leave smali the ART verifier can accept.
 *
 * The engine's own reassembly check catches syntax and branch labels; it does not know what a
 * method's parameter register count is. A replacement that writes `p1` into a method with only
 * `this` reassembles cleanly and then fails at class load with `VerifyError: register index out of
 * range` - which is a crash the first time the class is used. This walks every patched class from
 * the real 353.2.0 build, applies the set's own edits, and rejects a `pN` operand the method's
 * descriptor cannot supply.
 */
class PatchedRegisterValidityTest {

    @Test
    fun noPatchUsesAParameterRegisterItsMethodDoesNotHave() = runBlocking {
        val baseApk = ComparisonApks.discordBaseApk
        assumeTrue("the Discord fixtures are not on this machine (${baseApk.path})", baseApk.isFile)

        val dexEntries = dexEntries(baseApk)
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        val patchesByPath = (DiscordPatches.ALL + DiscordNativePatches.ALL)
            .flatMap { it.smaliPatches }
            .groupBy { it.smaliPath }

        val notApplied = mutableListOf<String>()
        val problems = mutableListOf<String>()

        patchesByPath.forEach { (path, patches) ->
            val descriptor = "L" + path.removeSuffix(".smali") + ";"
            val dexName = classToDex[descriptor] ?: return@forEach
            val stock = RegisterSmali(dexEntries.getValue(dexName), descriptor).text(path)
            if (stock.isEmpty()) {
                problems += "$path: class was not found in ${dexName}"
                return@forEach
            }

            val files = mutableMapOf(path to stock)
            patches.forEach { patch ->
                val result = SmaliPatcher.apply(files, patch)
                if (result.status != StepStatus.OK) {
                    notApplied += "$path :: ${result.label} -> ${result.detail}"
                }
            }
            problems += parameterRegisterProblems(path, files.getValue(path))
        }

        assertTrue(
            "these native patches did not apply to the stock class:\n" +
                notApplied.joinToString("\n"),
            notApplied.isEmpty()
        )
        assertTrue(
            "these patched methods reference a parameter register the method does not have:\n" +
                problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    /**
     * Every `pN` operand in [text] must be below the number of parameter registers its method
     * declares (one for `this` unless static, two for each long/double parameter).
     */
    private fun parameterRegisterProblems(path: String, text: String): List<String> {
        val problems = mutableListOf<String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val header = lines[i].trim()
            if (!header.startsWith(".method ")) {
                i++
                continue
            }

            val signature = header.removePrefix(".method ").trim()
            val open = signature.indexOf('(')
            val close = signature.indexOf(')', startIndex = open.coerceAtLeast(0))
            val descriptor = if (open >= 0 && close > open) {
                signature.substring(open, close + 1)
            } else {
                "()"
            }
            val modifiers = if (open >= 0) signature.substring(0, open) else signature
            val parameterWords =
                (if (Regex("\\bstatic\\b").containsMatchIn(modifiers)) 0 else 1) +
                    descriptorWords(descriptor)
            val methodName = signature.substringBefore('(').ifBlank { signature }

            var registers: Int? = null
            var locals: Int? = null
            var j = i + 1
            while (j < lines.size && !lines[j].trim().startsWith(".end method")) {
                val line = lines[j].trim()
                if (line.startsWith(".registers ")) {
                    registers = line.removePrefix(".registers ").trim().toIntOrNull()
                }
                if (line.startsWith(".locals ")) {
                    locals = line.removePrefix(".locals ").trim().toIntOrNull()
                }
                if (isInstruction(line)) {
                    val code = line.replace(Regex("\"[^\"]*\""), "")
                    // A register operand is a standalone token. The same text inside a type
                    // descriptor (`Lio/sentry/p2;`), a member name (`->p1:I`) or a method name
                    // is not a register, so the boundaries exclude those neighbours.
                    Regex("(?<![A-Za-z0-9_$/>-])p(\\d+)(?![A-Za-z0-9_:/;(])")
                        .findAll(code).forEach { match ->
                        val index = match.groupValues[1].toInt()
                        if (index >= parameterWords) {
                            problems += "$path: $methodName uses p$index but the method has " +
                                "$parameterWords parameter register(s)"
                        }
                    }
                }
                j++
            }

            // A frame smaller than its parameters cannot hold the p registers the descriptor
            // implies even if no instruction happens to name one.
            val frame = locals?.plus(parameterWords) ?: registers
            if (frame != null && frame < parameterWords) {
                problems += "$path: $methodName declares $frame register(s) for " +
                    "$parameterWords parameter register(s)"
            }
            i = j + 1
        }
        return problems
    }

    private fun isInstruction(line: String): Boolean =
        line.isNotEmpty() &&
            !line.startsWith(".") &&
            !line.startsWith(":") &&
            !line.startsWith("#")

    /** The register words a method descriptor's parameters occupy. */
    private fun descriptorWords(descriptor: String): Int {
        var i = 1
        var words = 0
        while (i < descriptor.length && descriptor[i] != ')') {
            when (descriptor[i]) {
                'L' -> {
                    val end = descriptor.indexOf(';', i)
                    i = if (end < 0) descriptor.length else end + 1
                    words++
                }
                '[' -> {
                    while (i < descriptor.length && descriptor[i] == '[') i++
                    if (i < descriptor.length && descriptor[i] == 'L') {
                        val end = descriptor.indexOf(';', i)
                        i = if (end < 0) descriptor.length else end + 1
                    } else {
                        i++
                    }
                    words++
                }
                'J', 'D' -> {
                    words += 2
                    i++
                }
                else -> {
                    words++
                    i++
                }
            }
        }
        return words
    }
}

/** One class of a DEX as the engine's own baksmali writes it. */
private class RegisterSmali(dexBytes: ByteArray, vararg descriptors: String) {

    private companion object {
        const val API_LEVEL = 28
    }

    private val directory: File = File.createTempFile("registerSmali", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    init {
        val dexFile = File.createTempFile("registerSmaliIn", ".dex").apply {
            writeBytes(dexBytes)
            deleteOnExit()
        }
        val options = BaksmaliOptions().apply { apiLevel = API_LEVEL }
        val disassembled = Baksmali.disassembleDexFile(
            DexFileFactory.loadDexFile(dexFile, Opcodes.forApi(API_LEVEL)),
            directory,
            1,
            options,
            descriptors.toList()
        )
        check(disassembled) { "baksmali refused the fixture" }
    }

    fun text(path: String): String =
        File(directory, path).takeIf { it.isFile }?.readText().orEmpty()
}
