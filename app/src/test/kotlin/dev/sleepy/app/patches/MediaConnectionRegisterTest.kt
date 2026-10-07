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
 * The media engine's connection registry, applied to the real build and read back from the DEX.
 *
 * `MediaEngineNativeConnections.register` refuses a connection id it already holds. Its one caller
 * is `MediaEngine.createVoiceConnection`, and the caller's caller is
 * `MediaEngineModule.createOwnStreamConnectionWithOptions` - the path a camera is turned on
 * through - which runs inside a coroutine on the media engine's own scope. The throw is therefore
 * never reported to JavaScript: the callback that would have carried the result is never invoked,
 * and before the scope was put on a supervisor job the throw cancelled every later media call
 * with it. Turning a camera on is where this is reached from, which is why the failure is
 * reported as a call that drops when the camera goes on rather than as an error.
 *
 * Two details of the stock method are asserted because the replacement depends on them. The three
 * argument checks come first, so the replacement has to keep them; and the throw is reached only
 * after `getEngine().createVoiceConnection(..)` has already built a native connection, so the
 * stock path leaks one on every refusal and the replacement has to dispose the body it replaces
 * rather than dropping it.
 *
 * The replacement is a whole-method body, so a mistake in it fails at assembly rather than at
 * runtime: the assertions below check the shape the assembler cannot check for itself - that the
 * same connection object is never disposed, and that the disposal happens after the map write
 * rather than instead of it.
 */
class MediaConnectionRegisterTest {

    private companion object {
        /** The class that owns the registry. */
        const val PATH = "com/discord/media/engine/MediaEngineNativeConnections.smali"
        const val DESCRIPTOR = "Lcom/discord/media/engine/MediaEngineNativeConnections;"

        /** The method the patch replaces, as baksmali 3.0.10 spells it. */
        const val SIGNATURE =
            ".method public final register(ILcom/discord/native/engine/NativeConnection;)V"

        /** The refusal the stock build ships, and the message it carries. */
        const val THROW = "throw p1"
        const val MESSAGE = "Check failed."

        /** What the replacement has to do instead. */
        const val PUT = "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        const val DISPOSE = "Lcom/discord/native/engine/NativeConnection;->dispose()V"

        /**
         * The guards that keep the connection the map now holds from being freed.
         *
         * Matched up to the branch's label, not including it: a label is a name in the text the
         * assembler reads and nothing in the DEX it writes, so the disassembler that reads the
         * patched class back invents one of its own. The instruction and its operands are what
         * survives the round trip.
         */
        const val SAME_OBJECT_GUARD = "if-eq v0, p2, :"
        const val NULL_GUARD = "if-eqz v0, :"
    }

    @Test
    fun theRegistryTakesTheNewConnectionInsteadOfThrowing() = runBlocking {
        val dexEntries = dexEntries(ComparisonApks.discordBaseApk)
        assumeTrue(
            "${ComparisonApks.discordBaseApk.path} is not on this machine",
            ComparisonApks.discordBaseApk.exists()
        )
        val dexName = dexEntries.keys.firstOrNull { name ->
            MediaConnectionsSmali(dexEntries.getValue(name), DESCRIPTOR).text(PATH).isNotEmpty()
        }
        assertTrue("$DESCRIPTOR is not in this build", dexName != null)
        val dex = dexEntries.getValue(dexName!!)

        val patches: List<SmaliPatch> = DiscordNativePatches.MEDIA.smaliPatches
            .filter { it.smaliPath == PATH && it.methodSignature != null }
        assertEquals("this class carries one method replacement", 1, patches.size)
        val patch = patches.single()

        val stock = MediaConnectionsSmali(dex, DESCRIPTOR).text(PATH)
        assertTrue("the fixture must contain the class under test", stock.isNotEmpty())
        assertEquals(
            "the signature must identify exactly one method, or the engine would replace the " +
                "wrong body:\n${patch.methodSignature}",
            1,
            occurrences(stock, patch.methodSignature!!)
        )

        // The fault the patch exists for. A build whose register no longer refuses has nothing
        // left to fix, and the replacement below would be describing a method that is not there.
        //
        // Read out of the one method rather than the whole file: `removeAndDisposeAll` refuses
        // with the same "Check failed." string and `removeAndDispose` frees a connection too, so
        // a file-wide search would pass whichever method was patched.
        val stockMethod = methodBody(stock, SIGNATURE)
        assertTrue("the stock register must refuse a duplicate id", stockMethod.contains(THROW))
        assertTrue("and say so the way Discord does", stockMethod.contains(MESSAGE))

        val (patched, results) = DexProcessor.patchDexSurgically(dexBytes = dex, patches = patches)
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
        }

        val after = MediaConnectionsSmali(patched, DESCRIPTOR).text(PATH)
        assertTrue("the patched class has to disassemble", after.isNotEmpty())
        val method = methodBody(after, SIGNATURE)
        assertFalse(
            "the refusal must be gone, along with the branch that reached it",
            method.contains(THROW) || method.contains(MESSAGE)
        )
        assertTrue("the argument check must survive", method.contains("checkNotNullParameter"))
        assertTrue("the id must be mapped to the connection", method.contains(PUT))
        assertTrue("the connection it replaced must be freed", method.contains(DISPOSE))
        assertTrue("and only when it is a different object", method.contains(SAME_OBJECT_GUARD))
        assertTrue("and only when there was one", method.contains(NULL_GUARD))

        // Order is the whole edit: a dispose before the write frees the connection the map then
        // holds, which is worse than the throw it replaces.
        val putAt = method.indexOf(PUT)
        val disposeAt = method.indexOf(DISPOSE)
        assertTrue("the map write must come first", putAt in 1 until disposeAt)
        assertTrue(
            "both guards must stand between the write and the disposal",
            method.indexOf(NULL_GUARD) in putAt until disposeAt &&
                method.indexOf(SAME_OBJECT_GUARD) in putAt until disposeAt
        )
    }

    /**
     * The text of the one method [signature] names, up to its closing directive.
     *
     * The assertions above are about one method, and the class holds three that free a connection
     * and two that raise the same message, so searching the file would report a pass for a patch
     * that landed on the wrong one.
     */
    private fun methodBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start == -1) return ""
        val end = text.indexOf(".end method", start)
        return if (end == -1) "" else text.substring(start, end)
    }

    /** How many times [needle] occurs in [haystack] - the engine's replace edits the first one. */
    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}

/**
 * Classes of a DEX as the engine's own baksmali writes them.
 *
 * The signature is written in this spelling - baksmali 3.0.10 labels branches by address rather
 * than sequentially - so the fixture has to be read back through the same tool the engine
 * disassembles with. A class that does not resolve is an empty string, which fails the assertions
 * above rather than throwing here.
 */
private class MediaConnectionsSmali(dexBytes: ByteArray, vararg descriptors: String) {

    private companion object {
        /** The API level the pipeline disassembles its DEX files at. */
        const val API_LEVEL = 28
    }

    private val directory: File = File.createTempFile("mediaConnectionsSmali", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    init {
        val dexFile = File.createTempFile("mediaConnectionsSmaliIn", ".dex").apply {
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
