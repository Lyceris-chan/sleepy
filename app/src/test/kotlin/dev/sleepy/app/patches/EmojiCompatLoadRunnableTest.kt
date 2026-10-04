package dev.sleepy.app.patches

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.testing.ReferenceApks
import dev.sleepy.app.testing.dexEntries
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The emoji font load runnable, applied to the real build and read back from the DEX.
 *
 * AndroidX Startup builds the EmojiCompat singleton and posts a runnable that asks the Google
 * Fonts provider for the emoji font, which nothing in Discord draws with. The resource-monitor
 * set stubs that runnable and leaves the initializer alone, because `EmojiCompat.get()` throws
 * when no singleton was built. Dropping the entry from the set fails the assertions below on the
 * patched output rather than on a missing string here.
 */
class EmojiCompatLoadRunnableTest {

    private companion object {
        /** The class holding the runnable AndroidX Startup posts. */
        const val EMOJI_INITIALIZER = "k2/l.smali"

        /** The runnable itself, and the only method the class has. */
        const val RUN = "run"

        /**
         * The stock method's register count: two locals for the trace section and the singleton
         * check, plus the parameter register for `this`.
         */
        const val STOCK_REGISTERS = 3

        /** The stub's register count: `.locals 0` plus the parameter register for `this`. */
        const val STUBBED_REGISTERS = 1
    }

    @Test
    fun theEmojiFontLoadRunnableBecomesAReturn() = runBlocking {
        val baseApk = ReferenceApks.discordBaseApk
        assumeTrue("the Discord fixtures are not on this machine (${baseApk.path})", baseApk.isFile)

        val dexEntries = dexEntries(baseApk)
        val descriptor = "L" + EMOJI_INITIALIZER.removeSuffix(".smali") + ";"
        val dexName = requireNotNull(DexProcessor.buildClassToDexIndex(dexEntries)[descriptor]) {
            "$descriptor is not in this build"
        }
        val dex = dexEntries.getValue(dexName)

        // The stub is a set item, not something this test spells out.
        val patches = DiscordNativePatches.RESOURCE_MONITORS.smaliPatches
            .filter { it.smaliPath == EMOJI_INITIALIZER }
        assertEquals(
            "discord_native_resource_monitors must hold the emoji load stub",
            1,
            patches.size
        )

        // Before: the stock runnable, so the after-state is a change and not the input read
        // twice. It opens on the trace section the initializer wraps the load in.
        val before = requireNotNull(EmojiDexView(dex).method(descriptor, RUN)) {
            "$descriptor->$RUN()V must be in the stock build"
        }
        assertEquals(
            "the stock runnable reads the singleton the initializer built",
            STOCK_REGISTERS,
            before.registerCount
        )
        assertTrue(
            "the stock runnable must be more than a return, got " +
                before.instructions.map { it.opcode.name },
            before.instructions.size > 1
        )
        assertEquals(
            "the stock runnable must open on the trace section",
            "EmojiCompat.EmojiCompatInitializer.run",
            before.stringConstants.firstOrNull()
        )

        // Apply the set's own edit with the engine the pipeline uses.
        val (patched, results) = DexProcessor.patchDexSurgically(dexBytes = dex, patches = patches)
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
        }

        // After: the runnable is a single return, and the class around it still loads.
        val view = EmojiDexView(patched)
        val after = requireNotNull(view.method(descriptor, RUN)) {
            "$descriptor->$RUN()V is not in the patched build"
        }
        assertEquals("the stub has no locals", STUBBED_REGISTERS, after.registerCount)
        assertEquals(
            "the stub must be a return and nothing else",
            listOf("return-void"),
            after.instructions.map { it.opcode.name.substringBefore('/') }
        )
        assertEquals(
            "the patched DEX must keep every class it had",
            view.classCount,
            EmojiDexView(dex).classCount
        )
    }
}

/**
 * One method of one DEX as dexlib2 reads it. The file is written to disk because that is how
 * dexlib2 opens a DEX; a method that does not resolve returns null so the assertion reports it.
 */
private class EmojiDexView(bytes: ByteArray) {

    private val file: File = File.createTempFile("emojiStub", ".dex").apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    private val dex: DexFile = DexFileFactory.loadDexFile(file, Opcodes.forApi(28))

    /** The number of classes the DEX declares. */
    val classCount: Int = dex.classes.count()

    fun method(descriptor: String, methodName: String): EmojiMethodView? {
        val method = dex.classes
            .firstOrNull { it.type == descriptor }
            ?.methods
            ?.firstOrNull { it.name == methodName }
            ?: return null
        val implementation = method.implementation ?: return null
        return EmojiMethodView(
            registerCount = implementation.registerCount,
            instructions = implementation.instructions.toList()
        )
    }
}

/** A method's register count, instructions and string constants, in order. */
private class EmojiMethodView(
    val registerCount: Int,
    val instructions: List<Instruction>
) {

    /** Every string constant the method loads, in instruction order. */
    val stringConstants: List<String> = instructions.mapNotNull { instruction ->
        (instruction as? ReferenceInstruction)?.reference
            ?.let { it as? StringReference }
            ?.string
    }
}
