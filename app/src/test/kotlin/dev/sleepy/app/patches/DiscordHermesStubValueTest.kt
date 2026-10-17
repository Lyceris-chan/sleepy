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
 * The typing hook's stub returns a value of the shape its caller reads.
 *
 * 62137 is the uncompiled variant of `useTypingUserIdsForDisplay`, in
 * `modules/chat/native/TypingIndicator.tsx`, and the React Compiler experiment defaults off, so
 * it is the variant the app runs. What the recorded change set did with the id it held in an
 * earlier release was to stub it to `undefined`, and the caller dereferences the return: 62113
 * passes it to `hasTypingIndicatorContent`, which reads `.length`, and that read throws a
 * TypeError on `undefined`. The stub here builds an empty array instead - the hook's own shape
 * with no typing users - so the read finds 0.
 *
 * The earlier release had a second function with the same problem, the SHOW_CONFIRM_MODAL handler
 * in `modules/vibegrations/lib/vibegrationsPreviewNativeSurfaces.tsx`, and the same reasoning
 * shaped its stub. It is not here any more: no release since 349.5 ships a `modules/vibegrations/`
 * path at all, so the module and the function are gone and there is nothing to return a value from.
 *
 * The first test pins the replacement, which is the change itself. The second applies the whole
 * table to the real base split and reads the result back, so the value that reaches the
 * dereference is checked in the bytes that ship rather than in the table alone.
 */
class DiscordHermesStubValueTest {

    private val baseApk = ComparisonApks.discordBaseApk
    private val hermesDecomp = ComparisonApks.hermesDecomp

    /** 62137: `NewArray r0, 0; AsyncBreakCheck; Ret r0`, an empty array. */
    private val typingHookStub = "080000007e7600"

    /** What the recorded change set wrote for the id: `LoadConstUndefined r0; ...; Ret r0`. */
    private val undefinedStub = "93007e7600"

    @Test
    fun theTableBuildsAnArrayWhereTheEarlierChangeSetLoadedUndefined() {
        val patch = DiscordHermesBundlePatch.PATCHES
            .first { it.functionId == TYPING_HOOK }

        assertEquals(typingHookStub, patch.replacementHex)
        assertTrue(
            "the typing hook's stub must build an array, not load undefined",
            patch.replacement[0].toInt() and 0xFF == 0x08
        )
        assertTrue(
            "the typing hook must not carry the earlier change set's undefined stub",
            patch.replacementHex != undefinedStub
        )
    }

    @Test
    fun thePatchedBundleFeedsAnArrayIntoTheLengthRead() {
        assumeTrue("the Discord base APK is not on this machine (${baseApk.path})", baseApk.isFile)
        assumeTrue("${hermesDecomp.path} is not executable", hermesDecomp.canExecute())

        val base = bundleOf(baseApk)
        assumeTrue(
            "the base bundle is not the build the patch set was extracted from",
            base.size.toLong() == DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toLong()
        )

        val patched = HermesBundlePatcher.apply(base, DiscordHermesBundlePatch.PATCHES).bundleBytes
        val temp = File.createTempFile("hermes-stub-values-", ".bundle")
        try {
            temp.writeBytes(patched)

            assertEquals(
                listOf("NewArray r0, 0", "AsyncBreakCheck", "Ret r0"),
                disassembly(temp, TYPING_HOOK)
            )
            assertTrue(
                "the hook must return an empty array",
                decompilation(temp, TYPING_HOOK).contains("return [];")
            )

            // The caller. It reads the hook off the settings object into r24, calls it with the
            // return landing in r25, then hands r25 to hasTypingIndicatorContent - which is the
            // read that would throw on undefined.
            val caller = disassembly(temp, TYPING_HOOK_CALLER)
            assertTrue(
                "the caller must load the hook into r24 and call it into r25: $caller",
                caller.contains("GetByIdLong r24, r25, 30, \"useTypingUserIdsForDisplay\"") &&
                    caller.contains("Call3 r25, r24, r25, r14, r0")
            )
            assertTrue(
                "the caller must hand the hook's return to hasTypingIndicatorContent: $caller",
                caller.contains("GetByIdLong r14, r24, 32, \"hasTypingIndicatorContent\"") &&
                    caller.contains("Call4 r37, r14, r24, r19, r25, r35")
            )
        } finally {
            temp.delete()
        }
    }

    private fun disassembly(bundle: File, functionId: Int): List<String> =
        run(bundle, "disasm", functionId).lineSequence()
            .map { it.substringAfter("  ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("L") }
            .toList()

    private fun decompilation(bundle: File, functionId: Int): String =
        run(bundle, "decompile", functionId)

    private fun run(bundle: File, verb: String, functionId: Int): String {
        val process = ProcessBuilder(
            hermesDecomp.absolutePath, verb, "--function", functionId.toString(), bundle.absolutePath
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("hermes-decomp timed out on function $functionId")
        }
        return output
    }

    private companion object {
        /**
         * The uncompiled variant of `useTypingUserIdsForDisplay`, in
         * `modules/chat/native/TypingIndicator.tsx`.
         */
        const val TYPING_HOOK = 62137

        /**
         * Its caller, the chat input, which reads `.length` off the return.
         *
         * Resolved by string fingerprint rather than by the structural walk: the caller is a
         * module-level function that no `CreateClosure` points at, so there is nothing to walk up
         * through, but it names 120 strings no other function in the bundle names, which is a
         * stronger match than a closure ordinal.
         */
        const val TYPING_HOOK_CALLER = 62113
    }
}
