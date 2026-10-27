package dev.sleepy.app.patches

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import dev.sleepy.app.engine.DexProcessor
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
 * The external-browser routing edits, applied to the real 353.2.0 build and read back.
 *
 * 353.2.0 already falls back to Chrome when the stored browser choice is absent, so the set is
 * two edits: make the exported constants stop reading the stored in-app choice, and route
 * `openInAppURL` through the external-browser call. This checks both against the build's own
 * disassembly and applies them with the pipeline's engine.
 */
class BrowserManagerExternalBrowserTest {

    private companion object {
        const val BROWSER_MODULE = "com/discord/browser_manager/BrowserManagerModule.smali"
        const val BROWSER_MODULE_DESCRIPTOR =
            "Lcom/discord/browser_manager/BrowserManagerModule;"

        /** How the getter reads the stored choice, and how `selectBrowser` writes it. */
        const val STORED_KEY_READ = "    const-string v3, \"SELECTED_BROWSER\""
        const val STORED_KEY_WRITE = "    const-string v1, \"SELECTED_BROWSER\""

        /** The key the getter reads after the set's edit. */
        const val IGNORED_KEY_READ = "    const-string v3, \"SELECTED_BROWSER_IGNORED\""

        /** The in-app Custom Tab call and the external-browser call. */
        const val CUSTOM_TABS_CALL = "Lt6/a;->c("
        const val EXTERNAL_CALL = "Lt6/a;->d("
    }

    @Test
    fun everyLinkLeavesTheApp() = runBlocking {
        val baseApk = ComparisonApks.discordBaseApk
        assumeTrue("the Discord fixtures are not on this machine (${baseApk.path})", baseApk.isFile)

        val dexEntries = dexEntries(baseApk)
        val dexName = requireNotNull(
            DexProcessor.buildClassToDexIndex(dexEntries)[BROWSER_MODULE_DESCRIPTOR]
        ) { "$BROWSER_MODULE_DESCRIPTOR is not in this build" }
        val dex = dexEntries.getValue(dexName)

        val patches = DiscordNativePatches.EXTERNAL_BROWSER.smaliPatches
            .filter { it.smaliPath == BROWSER_MODULE }
        assertEquals(
            "discord_native_external_browser must hold the key read and the re-pointed " +
                "in-app call",
            2,
            patches.size
        )
        assertEquals(
            "every edit of the set is an anchor edit",
            patches.size,
            patches.count { it.anchor != null && it.replacement != null }
        )

        val stock = BrowserSmali(dex, BROWSER_MODULE_DESCRIPTOR).text(BROWSER_MODULE)
        assumeTrue(
            "the fixture no longer has the stock browser shape",
            stock.contains(STORED_KEY_READ) && stock.contains(CUSTOM_TABS_CALL)
        )
        patches.forEach { patch ->
            assertEquals(
                "the anchor must occur exactly once in the stock build:\n${patch.anchor}",
                1,
                occurrences(stock, requireNotNull(patch.anchor))
            )
        }
        assertTrue("stock must read the stored browser choice", stock.contains(STORED_KEY_READ))
        assertTrue("stock openInAppURL must render a Custom Tab", stock.contains(CUSTOM_TABS_CALL))

        val (patched, results) = DexProcessor.patchDexSurgically(dexBytes = dex, patches = patches)
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
        }

        val after = BrowserSmali(patched, BROWSER_MODULE_DESCRIPTOR).text(BROWSER_MODULE)
        assertFalse("the getter must stop reading the stored choice", after.contains(STORED_KEY_READ))
        assertTrue("the getter must read the ignored key", after.contains(IGNORED_KEY_READ))
        assertTrue("the app must still be able to save a choice", after.contains(STORED_KEY_WRITE))
        assertFalse(
            "openInAppURL must no longer render a Custom Tab",
            after.contains(CUSTOM_TABS_CALL)
        )
        assertEquals(
            "openInAppURL and the other external call sites must use the external browser",
            4,
            occurrences(after, EXTERNAL_CALL)
        )
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}

/**
 * One class of a DEX as the engine's own baksmali writes it.
 *
 * The anchors are written in this spelling - baksmali 3.0.10 labels branches by address rather
 * than sequentially - so the fixture has to be read back through the same tool the engine
 * disassembles with. A class that does not resolve is an empty string.
 */
private class BrowserSmali(dexBytes: ByteArray, vararg descriptors: String) {

    private companion object {
        const val API_LEVEL = 28
    }

    private val directory: File = File.createTempFile("browserSmali", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    init {
        val dexFile = File.createTempFile("browserSmaliIn", ".dex").apply {
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
