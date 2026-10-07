package dev.sleepy.app.patches

import dev.sleepy.app.engine.HermesBundlePatcher
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.bundleOf
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The two stubs this build gives a shaped return instead of `undefined`.
 *
 * The ids 49956 and 58239 held an upsell button in 348.5 and unrelated functions in 349.5, and
 * the recorded change set's table stubs both to `undefined` without noticing. The one caller
 * of each function dereferences the return, so that value throws: 58216 passes 58239's return
 * to `hasTypingIndicatorContent`, which reads `.length`, and 49954 reads `.result` off 49956's
 * return. The patch table returns an empty array and an object respectively.
 *
 * The first test pins the two replacement bodies, which is the change itself. The second applies
 * the whole table to the real base split and reads the result with the recorded build's own
 * disassembler, so the value that reaches each dereference is checked in the bytes that ship
 * rather than in the table alone. Both fail if either stub goes back to `undefined`.
 */
class DiscordHermesStubValueTest {

    private val baseApk = ComparisonApks.discordBaseApk
    private val hermesDecomp = ComparisonApks.hermesDecomp

    /** 58239: `NewArray r0, 0; AsyncBreakCheck; Ret r0`, an empty array. */
    private val typingHookStub = "080000007e7600"

    /**
     * 49956: `NewObjectWithBuffer r0, 15190, 52600; LoadConstFalse r1;
     * PutOwnBySlotIdx r0, r1, 0; NewObjectWithBufferLong r1, 15191, 313591;
     * PutOwnBySlotIdx r1, r0, 0; AsyncBreakCheck; Ret r1`, which is
     * `{result: {confirmed: false}, answered: null, subject: null}`. The two templates the body
     * names are the ones the body it replaces used, so the property names come from the bundle.
     */
    private val confirmHandlerStub =
        "0100563b78cd9601520001000201573b0000f7c80400520100007e7601"

    /** The recorded build's value for both ids: `LoadConstUndefined r0; AsyncBreakCheck; Ret r0`.
     */
    private val recordedStub = "93007e7600"

    @Test
    fun theTableReturnsAShapedValueWhereTheReferenceReturnsUndefined() {
        val byId = DiscordHermesBundlePatch.PATCHES.associateBy { it.functionId }

        assertEquals(typingHookStub, byId.getValue(58239).replacementHex)
        assertEquals(confirmHandlerStub, byId.getValue(49956).replacementHex)
        assertEquals(
            "the typing hook's stub must build an array, not load undefined",
            0x08,
            byId.getValue(58239).replacement[0].toInt() and 0xFF
        )
        assertEquals(
            "the confirm handler's stub must build an object, not load undefined",
            0x01,
            byId.getValue(49956).replacement[0].toInt() and 0xFF
        )
        for (functionId in listOf(49956, 58239)) {
            val replacement = byId.getValue(functionId).replacementHex
            assertTrue(
                "fn $functionId must not carry the recorded build's undefined stub",
                replacement != recordedStub
            )
        }
    }

    @Test
    fun thePatchedBundleFeedsASafeValueIntoEachDereference() {
        assumeTrue("the Discord base APK is not on this machine (${baseApk.path})", baseApk.isFile)
        assumeTrue("${hermesDecomp.path} is not executable", hermesDecomp.canExecute())
        assumeTrue(
            "the base bundle is not the build the patch set was extracted from",
            bundleOf(baseApk).size.toLong() == DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toLong()
        )

        val patched = HermesBundlePatcher
            .apply(bundleOf(baseApk), DiscordHermesBundlePatch.PATCHES)
            .bundleBytes
        val temp = File.createTempFile("hermes-stub-values-", ".bundle")
        try {
            temp.writeBytes(patched)

            // The producer. 58216 loads the hook, calls it, and passes its return in r24 to
            // hasTypingIndicatorContent; 58236 reads .length off its second parameter on the path
            // where the channel sets no slow-rate limit.
            assertEquals(
                listOf("NewArray r0, 0", "AsyncBreakCheck", "Ret r0"),
                disassembly(temp, 58239)
            )
            assertTrue(
                "the hook must return an empty array",
                decompilation(temp, 58239).contains("return [];")
            )
            val hookCaller = disassembly(temp, 58216)
            assertTrue(
                "58216 must call the hook and keep its return in r24: $hookCaller",
                hookCaller.contains("Call3 r24, r22, r24, r14, r0")
            )
            assertTrue(
                "58216 must pass r24 to hasTypingIndicatorContent: $hookCaller",
                hookCaller.contains("Call4 r37, r14, r22, r19, r24, r35")
            )
            val typingCheck = disassembly(temp, 58236)
            assertEquals(
                "the .length read must follow the parameter that holds the hook's return: " +
                    "$typingCheck",
                "GetByIdShort r3, r3, 1, \"length\"",
                typingCheck[typingCheck.indexOf("LoadParam r3, 2") + 1]
            )

            // The consumer of the confirm handler. 49954 calls the handler found for the command
            // and reads .result off its return in r5.
            assertEquals(
                listOf(
                    "NewObjectWithBuffer r0, 15190, 52600",
                    "LoadConstFalse r1",
                    "PutOwnBySlotIdx r0, r1, 0",
                    "NewObjectWithBufferLong r1, 15191, 313591",
                    "PutOwnBySlotIdx r1, r0, 0",
                    "AsyncBreakCheck",
                    "Ret r1"
                ),
                disassembly(temp, 49956)
            )
            assertTrue(
                "the handler must return an object carrying a confirm result",
                decompilation(temp, 49956)
                    .contains("return { result: { confirmed: false }, answered: null, subject: null };")
            )
            val answerFor = disassembly(temp, 49954)
            assertEquals(
                "the .result read must follow the call that produced the handler's return: " +
                    "$answerFor",
                "GetByIdShort r4, r5, 3, \"result\"",
                answerFor[answerFor.indexOf("Call3 r5, r5, r1, r8, r4") + 1]
            )
        } finally {
            temp.delete()
        }
    }

    /** The instructions of one function, in order, without labels. */
    private fun disassembly(bundle: File, functionId: Int): List<String> =
        tool("disasm", "--function", functionId.toString(), bundle.absolutePath)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.endsWith(":") }
            .toList()

    private fun decompilation(bundle: File, functionId: Int): String =
        tool("decompile", "--function", functionId.toString(), bundle.absolutePath)

    private fun tool(vararg arguments: String): String {
        val command = listOf(hermesDecomp.absolutePath) + arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("hermes-decomp timed out: ${command.joinToString(" ")}")
        }
        assertEquals(
            "hermes-decomp rejected ${arguments.joinToString(" ")}: $output",
            0,
            process.exitValue()
        )
        return output
    }
}
