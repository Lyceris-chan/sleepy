package dev.sleepy.app.patches

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.testing.ReferenceApks
import dev.sleepy.app.testing.dexEntries
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The incoming-call image fetch, stubbed on the real build and read back from the DEX.
 *
 * `fetchImage` builds the Fresco pipeline and then suspends on a network round-trip, and it is
 * called from inside `IncomingCallActivity`'s three `runBlocking` calls on the main thread. The
 * stub returns null, which the callers already handle: they cast the result to `Bitmap` and hand
 * it to `ImageView.setImageBitmap`, which clears the view. Dropping the stub from the set fails
 * the assertions below on the patched output rather than on a missing string here.
 */
class IncomingCallImageFetchTest {

    private companion object {
        /** The activity the incoming-call screen is. */
        const val INCOMING_CALL =
            "com/discord/notifications/renderer/IncomingCallActivity.smali"

        /** The descriptor baksmali disassembles for that path. */
        const val INCOMING_CALL_DESCRIPTOR =
            "Lcom/discord/notifications/renderer/IncomingCallActivity;"

        /** The suspend function the screen's blocking waits end at. */
        const val FETCH_IMAGE =
            ".method private final fetchImage(Ljava/lang/String;" +
                "Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"

        /** What the stock fetch does before it can return: build Fresco, then fetch over the net. */
        const val FRESCO_INIT = "FrescoModuleDiscord\$Companion;->initializeFresco("
        const val NETWORK_FETCH = "FrescoFetchDecodedImageKt;->fetchDecodedImage("

        /** The stock body's register count: four locals plus the two parameter registers. */
        const val STOCK_REGISTERS = 6

        /** The stub's register count: `.locals 0` plus the same two parameter registers. */
        const val STUBBED_REGISTERS = 3
    }

    @Test
    fun theIncomingCallScreenDoesNotWaitOnTheAvatar() = runBlocking {
        val baseApk = ReferenceApks.discordBaseApk
        assumeTrue("the Discord fixtures are not on this machine (${baseApk.path})", baseApk.isFile)

        val dexEntries = dexEntries(baseApk)
        val dexName = requireNotNull(
            DexProcessor.buildClassToDexIndex(dexEntries)[INCOMING_CALL_DESCRIPTOR]
        ) { "$INCOMING_CALL_DESCRIPTOR is not in this build" }
        val dex = dexEntries.getValue(dexName)

        // The stub is a set item, not something this test spells out.
        val patches = DiscordNativePatches.INCOMING_CALL.smaliPatches
            .filter { it.smaliPath == INCOMING_CALL }
        assertEquals("discord_native_incoming_call must hold the fetchImage stub", 1, patches.size)
        assertEquals(
            "the stub must target the suspend function the screen waits on",
            FETCH_IMAGE,
            patches.single().methodSignature
        )

        // Before: the stock fetch, so the after-state is a change and not the input read twice.
        val stock = CallSmali(dex, INCOMING_CALL_DESCRIPTOR).text(INCOMING_CALL)
        val stockFetch = methodOf(stock, FETCH_IMAGE)
        assertTrue("stock fetchImage must build the image pipeline", stockFetch.contains(FRESCO_INIT))
        assertTrue("stock fetchImage must fetch over the network", stockFetch.contains(NETWORK_FETCH))
        assertEquals(
            "the stock fetch has four locals for the two-step wait",
            STOCK_REGISTERS,
            registersOf(stockFetch)
        )

        // Apply the set's own edit with the engine the pipeline uses.
        val (patched, results) = DexProcessor.patchDexSurgically(dexBytes = dex, patches = patches)
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
        }

        // After: the function is the stub, so nothing waits on Fresco or the network. The
        // callers hand its null to `check-cast Bitmap` and `ImageView.setImageBitmap`, which
        // clears the view, so only the picture is dropped.
        val after = CallSmali(patched, INCOMING_CALL_DESCRIPTOR).text(INCOMING_CALL)
        val stubbedFetch = methodOf(after, FETCH_IMAGE)
        assertFalse(
            "the stub must not build the image pipeline",
            stubbedFetch.contains(FRESCO_INIT)
        )
        assertFalse("the stub must not fetch over the network", stubbedFetch.contains(NETWORK_FETCH))
        assertEquals(
            "the stub has no locals, which is what makes it return at once",
            STUBBED_REGISTERS,
            registersOf(stubbedFetch)
        )
        assertTrue(
            "the stub must return null, which the callers clear the view with",
            stubbedFetch.contains("    const/4 p1, 0x0\n\n    return-object p1")
        )
    }

    /** The text of [signature]'s method, which must be declared exactly once. */
    private fun methodOf(text: String, signature: String): String {
        assertEquals(
            "$signature must be declared exactly once in the build",
            1,
            text.split(signature).size - 1
        )
        return text.substringAfter(signature).substringBefore(".end method")
    }

    /** The register count a method body declares, as the engine's baksmali renders it. */
    private fun registersOf(method: String): Int = method.lineSequence()
        .first { it.trim().startsWith(".registers") }
        .substringAfter(".registers")
        .trim()
        .toInt()
}

/**
 * One class of a DEX as the engine's own baksmali writes it.
 *
 * The patch replaces a whole method body by signature, so the fixture has to be read back through
 * the same tool the engine disassembles with. A class that does not resolve is an empty string,
 * which fails the assertions above rather than throwing here.
 */
private class CallSmali(dexBytes: ByteArray, vararg descriptors: String) {

    private companion object {
        /** The API level the pipeline disassembles its DEX files at. */
        const val API_LEVEL = 28
    }

    private val directory: File = File.createTempFile("callSmali", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    init {
        val dexFile = File.createTempFile("callSmaliIn", ".dex").apply {
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
