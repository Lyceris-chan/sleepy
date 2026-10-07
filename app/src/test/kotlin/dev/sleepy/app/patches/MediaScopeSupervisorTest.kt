package dev.sleepy.app.patches

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.dexEntries
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The media engine's coroutine scope, applied to the real build and read back from the DEX.
 *
 * This patch fixes a fault in the app rather than removing something from it, so the failure it
 * prevents is worth stating: `MediaEngineModule.appScope` is built with only a dispatcher in its
 * context, and `CoroutineScope(context)` installs a plain `Job`. A plain job cancels itself and
 * every child the first time one child throws, and the whole media ReactMethod surface launches
 * on that scope - so a camera that fails to start, or a duplicate connection id, turns every
 * later media call into a no-op and the call ends. The edit adds a `SupervisorJob` to the
 * context, so a child's failure stops at that child.
 *
 * The recorded change set shipped this patch broken for a whole release without noticing: it
 * spelled four R8-obfuscated coroutine class names, the 349.5 update renamed the package, its
 * anchor stopped matching, and the only trace was a skip line in a long log. Two assertions
 * here exist because of that. The anchor must occur exactly once *in the stock build*, so a
 * rename fails this test instead of quietly leaving the fault in place; and the four classes
 * the injection references must all exist in the build, so a stale name cannot produce a
 * dangling reference that verifies at assemble time and fails on the device.
 */
class MediaScopeSupervisorTest {

    private companion object {
        /** The class whose constructor builds the scope. */
        const val MODULE = "com/discord/media/engine/MediaEngineModule.smali"
        const val MODULE_DESCRIPTOR = "Lcom/discord/media/engine/MediaEngineModule;"

        /** The line the injection is written after, which the patch leaves in place. */
        const val DISPATCHER_CTOR =
            "    invoke-direct {v2, v1}, Lmr/r0;-><init>(Ljava/util/concurrent/Executor;)V"

        /** The scope factory call that follows the injection, now built on the supervisor. */
        const val SCOPE_FACTORY = "Lmr/x;->b("

        /** The supervisor the injection builds, and the job base whose constructor it calls. */
        const val SUPERVISOR_JOB = "Lmr/n1"
        const val JOB_BASE = "Lmr/y0"

        /** Every class the injection names, checked against the build before it is written. */
        val REFERENCED = listOf("Lmr/r0;", "Lmr/x;", SUPERVISOR_JOB + ";", JOB_BASE + ";")

        /** The context merge that attaches the supervisor to the dispatcher. */
        const val CONTEXT_PLUS = "Lkotlin/coroutines/e;->c("
    }

    @Test
    fun theMediaScopeIsBuiltOnASupervisorJobAndTheNamesItUsesExist() = runBlocking {
        val dexEntries = dexEntries(ComparisonApks.discordBaseApk)
        assumeTrue(
            "${ComparisonApks.discordBaseApk.path} is not on this machine",
            ComparisonApks.discordBaseApk.exists()
        )
        val dexName = dexEntries.keys.firstOrNull { name ->
            MediaSmali(dexEntries.getValue(name), MODULE_DESCRIPTOR).text(MODULE).isNotEmpty()
        }
        assertTrue("$MODULE_DESCRIPTOR is not in this build", dexName != null)
        val dex = dexEntries.getValue(dexName!!)

        val patches: List<SmaliPatch> = DiscordNativePatches.MEDIA.smaliPatches
            .filter { it.smaliPath == MODULE && it.anchor != null }
        assertEquals("this class carries one anchor edit", 1, patches.size)
        val patch = patches.single()

        val stock = MediaSmali(dex, MODULE_DESCRIPTOR).text(MODULE)
        assertTrue("the fixture must contain the class under test", stock.isNotEmpty())
        assertEquals(
            "the anchor must occur exactly once in the stock build, or the engine's replace " +
                "would edit more than the line this patch describes:\n${patch.anchor}",
            1,
            occurrences(stock, patch.anchor!!)
        )
        assertFalse(
            "the stock scope must not already be supervised, or there is nothing to do",
            stock.contains(SUPERVISOR_JOB)
        )

        // What the injection will name has to be in the build. A name that resolves at assemble
        // time and not at class load is how the recorded change set's copy of this patch died.
        //
        // Searched across every DEX entry rather than the one holding the module: these are
        // library classes and live wherever the splitter put them, and a reference across two
        // DEX files is ordinary - the class loader resolves it. Only absence is a fault.
        REFERENCED.forEach { descriptor ->
            val path = descriptor.removePrefix("L").removeSuffix(";") + ".smali"
            val found = dexEntries.values.any { bytes ->
                MediaSmali(bytes, descriptor).text(path).isNotEmpty()
            }
            assertTrue(
                "the injection references $descriptor, which no DEX entry in this build holds",
                found
            )
        }

        val (patched, results) = DexProcessor.patchDexSurgically(dexBytes = dex, patches = patches)
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
        }

        val after = MediaSmali(patched, MODULE_DESCRIPTOR).text(MODULE)
        assertTrue("the patched class has to disassemble", after.isNotEmpty())
        assertTrue("the dispatcher construction must survive", after.contains(DISPATCHER_CTOR))
        assertTrue("the supervisor must be built", after.contains("new-instance v1, $SUPERVISOR_JOB;"))
        assertTrue(
            "it must be the job base's constructor that is called",
            after.contains("invoke-direct {v1}, $JOB_BASE;-><init>()V")
        )
        assertTrue("it must be merged into the scope's context", after.contains(CONTEXT_PLUS))

        // Order is the whole edit: a supervisor built after the scope is built does nothing.
        val dispatcherAt = after.indexOf(DISPATCHER_CTOR)
        val supervisorAt = after.indexOf("new-instance v1, $SUPERVISOR_JOB;")
        val mergeAt = after.indexOf(CONTEXT_PLUS)
        val factoryAt = after.indexOf(SCOPE_FACTORY, dispatcherAt)
        assertTrue("the scope factory call must still follow the construction", factoryAt > dispatcherAt)
        assertTrue(
            "the supervisor must be built after the dispatcher and before the context it joins",
            dispatcherAt < supervisorAt && supervisorAt < mergeAt
        )
        assertTrue(
            "and the merged context must exist before the scope is built from it",
            mergeAt < factoryAt
        )
    }

    /** How many times [needle] occurs in [haystack] - the engine's replace edits every one. */
    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}

/**
 * Classes of a DEX as the engine's own baksmali writes them.
 *
 * The anchor is written in this spelling - baksmali 3.0.10 labels branches by address rather than
 * sequentially - so the fixture has to be read back through the same tool the engine disassembles
 * with. A class that does not resolve is an empty string, which fails the assertions above rather
 * than throwing here.
 */
private class MediaSmali(dexBytes: ByteArray, vararg descriptors: String) {

    private companion object {
        /** The API level the pipeline disassembles its DEX files at. */
        const val API_LEVEL = 28
    }

    private val directory: File = File.createTempFile("mediaSmali", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    init {
        val dexFile = File.createTempFile("mediaSmaliIn", ".dex").apply {
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

    /** The smali of the class at [path], or an empty string when it was not disassembled. */
    fun text(path: String): String =
        File(directory, path).takeIf { it.isFile }?.readText().orEmpty()
}
