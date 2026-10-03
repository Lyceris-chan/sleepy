package dev.sleepy.app

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.patches.DiscordNativePatches
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The two halves of the EGL fix, applied to the real 348.5 build and read back off the DEX.
 *
 * `EglRenderer.init()` used to call the graphics setup through
 * `ThreadUtils.invokeAtFrontUninterruptibly`, which blocks the UI thread until the render thread
 * has created the EGL context — one parking of the UI thread per video tile. Replacing that with a
 * plain `post` is what removes the stall, and it is only safe together with a guard: `release()`
 * nulls `renderThreadHandler` and jumps its cleanup to the *front* of the render thread's queue, so
 * a create still queued behind that cleanup would build a context after teardown with nothing left
 * to free it. The reference build records the cost of shipping the `post` half alone: 242 leaked
 * contexts while tiles were attached and detached in a call, until `eglCreateContext` failed with
 * `EGL_BAD_ALLOC` (0x3003) and the exception was fatal on the render thread.
 *
 * So this test is written against the *set*, not against the guard text on its own: it takes
 * whatever `discord_native_media` holds for this class, applies it with the engine the app uses, and
 * asserts the result. Removing the guard item — the failure this exists to catch — leaves the
 * lambda unguarded and fails here, whichever half of the set survives.
 *
 * The fixture is a build output outside the repository, so a machine without it reports the test
 * as skipped rather than passing it without having read anything.
 */
class EglRendererReleasedGuardTest {

    private companion object {
        /** The desktop build's extracted Discord 348.5 fixtures; see `PatchingPipelineHarnessTest`. */
        const val DISCORD_EXTRACTED =
            "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted"

        /** The class both halves of the EGL fix edit. */
        const val EGL_RENDERER = "com/discord/media/engine/video/egl_renderer/EglRenderer.smali"

        /** The lambda `init()` posts: it creates the context on the render thread. */
        const val CREATE_LAMBDA = "init\$lambda\$10\$lambda\$9"

        /** The field `release()` nulls, and so the one an "already released" guard reads. */
        const val HANDLER_FIELD = "renderThreadHandler"

        /** DEX entry names of a single APK. */
        val DEX_ENTRY_NAME = Regex("classes\\d*\\.dex")

        /**
         * The stock method's shape: `(EglRenderer; J)V` is one object parameter plus a wide one,
         * so three parameter registers and no locals.
         */
        const val STOCK_REGISTERS = 3

        /** The guarded method: one local register for the guard's `v0`. */
        const val GUARDED_REGISTERS = 4
    }

    @Test
    fun theDeferredEglCreateSkipsWhenTheRendererWasAlreadyReleased() = runBlocking {
        val baseApk = File(DISCORD_EXTRACTED, "base.apk")
        assumeTrue("the Discord fixtures are not on this machine (${baseApk.path})", baseApk.isFile)

        val dexEntries = extractDexEntries(baseApk)
        val descriptor = "L" + EGL_RENDERER.removeSuffix(".smali") + ";"
        val dexName = requireNotNull(DexProcessor.buildClassToDexIndex(dexEntries)[descriptor]) {
            "$descriptor is not in this build"
        }
        val dex = dexEntries.getValue(dexName)

        // The guard is a set item, not something this test spells out; if it is dropped from the
        // set the assertions below fail on the output rather than on a missing string here.
        val patches = DiscordNativePatches.MEDIA.smaliPatches.filter { it.smaliPath == EGL_RENDERER }
        assertTrue(
            "discord_native_media must hold both halves of the EGL fix, found: ${patches.map { it.title }}",
            patches.any { it.replacement?.contains(HANDLER_FIELD) == true }
        )

        // Before: the stock method, so the after-state is a change and not the input read twice.
        val before = EglDexView(dex).method(descriptor, CREATE_LAMBDA)
        assertNotNull("$CREATE_LAMBDA must be in the stock build", before)
        assertEquals("the stock lambda has no locals to guard with", STOCK_REGISTERS, before!!.registerCount)
        assertEquals(
            "the stock lambda must not already read the handler",
            "const-string",
            before.instructions.first().opcode.name.substringBefore('/')
        )

        // Apply the set's own edits with the engine the pipeline uses.
        val (patched, results) = DexProcessor.patchDexSurgically(dexBytes = dex, patches = patches)
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach { assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status) }

        // After: the guard is the first thing the lambda does, and the original body still follows
        // the label it jumps to.
        val after = requireNotNull(EglDexView(patched).method(descriptor, CREATE_LAMBDA)) {
            "$CREATE_LAMBDA is not in the patched build"
        }
        assertEquals(
            "the guard's local register makes the method one register wider",
            GUARDED_REGISTERS,
            after.registerCount
        )
        val opcodes = after.instructions.map { it.opcode.name.substringBefore('/') }
        assertEquals(
            "the lambda must open with the released-guard, then the body it always had",
            listOf("iget-object", "if-nez", "return-void", "const-string"),
            opcodes.take(4)
        )
        val guardRead = (after.instructions.first() as ReferenceInstruction).reference
        assertTrue("the guard must read $HANDLER_FIELD", guardRead is FieldReference)
        assertEquals(
            "the guard must read the field `release()` nulls on the renderer itself",
            "$descriptor->$HANDLER_FIELD:Landroid/os/Handler;",
            with(guardRead as FieldReference) { "$definingClass->$name:$type" }
        )
        assertEquals(
            "the body below the guard must still end by returning, as it did",
            "return-void",
            opcodes.last()
        )
    }

    /** The APK's DEX entries, by entry name. */
    private fun extractDexEntries(apk: File): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(BufferedInputStream(apk.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (DEX_ENTRY_NAME.matches(entry.name)) entries[entry.name] = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }
}

/**
 * One method of one DEX as dexlib2 reads it: the register count and the instructions, with the
 * encoding widths folded together. The file is written to disk because that is how dexlib2 opens a
 * DEX; a method that does not resolve returns null so the assertion reports it.
 */
private class EglDexView(bytes: ByteArray) {

    private val file: File = File.createTempFile("eglGuard", ".dex").apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    private val dex: DexFile = DexFileFactory.loadDexFile(file, Opcodes.forApi(28))

    fun method(descriptor: String, methodName: String): EglMethodView? {
        val method = dex.classes
            .firstOrNull { it.type == descriptor }
            ?.methods
            ?.firstOrNull { it.name == methodName }
            ?: return null
        val implementation = method.implementation ?: return null
        return EglMethodView(
            registerCount = implementation.registerCount,
            instructions = implementation.instructions.toList()
        )
    }
}

/** A method's register count and instructions, in order. */
private class EglMethodView(
    val registerCount: Int,
    val instructions: List<Instruction>
)
