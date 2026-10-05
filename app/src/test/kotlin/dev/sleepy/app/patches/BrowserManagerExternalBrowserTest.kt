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
 * The external-browser routing edits, applied to the real build and read back from the DEX.
 *
 * The browser setting reads a stored choice and falls back to the in-app tab, and the module's
 * in-app entry point renders a Chrome Custom Tab inside Discord's own task. The set is
 * deliberately redundant: the edits behind the exported constants and the re-pointed entry point
 * each send the link out on their own. Dropping any of them fails the assertions below on the
 * patched output rather than on a missing string here.
 */
class BrowserManagerExternalBrowserTest {

    private companion object {
        /** The module the whole link surface goes through. */
        const val BROWSER_MODULE = "com/discord/browser_manager/BrowserManagerModule.smali"

        /** The descriptor baksmali disassembles for that path. */
        const val BROWSER_MODULE_DESCRIPTOR = "Lcom/discord/browser_manager/BrowserManagerModule;"

        /** How the getter reads the stored browser choice, and how `selectBrowser` writes it. */
        const val STORED_KEY_READ = "    const-string v2, \"SELECTED_BROWSER\""
        const val STORED_KEY_WRITE = "    const-string v1, \"SELECTED_BROWSER\""

        /** The key the getter reads after the set's edit, which nothing writes. */
        const val IGNORED_KEY_READ = "    const-string v2, \"SELECTED_BROWSER_IGNORED\""

        /** The fallback that loads the in-app browser, and the one that loads Chrome. */
        const val IN_APP_FALLBACK = "    const/4 v1, 0x1"
        const val CHROME_FALLBACK = "    const/4 v1, 0x2"

        /** The two same-descriptor calls the in-app entry point can make. */
        const val CUSTOM_TABS_CALL = "BrowserManager;->tryOpenUrlWithCustomTabs("
        const val EXTERNAL_CALL = "BrowserManager;->tryOpenUrlExternally("

        /** The fallback's branch, in the engine's own address-based label spelling. */
        const val IN_APP_BRANCH = ":cond_28\n    if-eqz v0, :cond_2c"
    }

    @Test
    fun everyLinkLeavesTheApp() = runBlocking {
        val baseApk = ReferenceApks.discordBaseApk
        assumeTrue("the Discord fixtures are not on this machine (${baseApk.path})", baseApk.isFile)

        val dexEntries = dexEntries(baseApk)
        val dexName = requireNotNull(
            DexProcessor.buildClassToDexIndex(dexEntries)[BROWSER_MODULE_DESCRIPTOR]
        ) { "$BROWSER_MODULE_DESCRIPTOR is not in this build" }
        val dex = dexEntries.getValue(dexName)

        // The edits are set items, not something this test spells out.
        val patches = DiscordNativePatches.EXTERNAL_BROWSER.smaliPatches
            .filter { it.smaliPath == BROWSER_MODULE }
        assertEquals(
            "discord_native_external_browser must hold the two halves of the setting and the " +
                "re-pointed in-app call",
            3,
            patches.size
        )
        assertEquals(
            "every edit of the set is an anchor edit",
            patches.size,
            patches.count { it.anchor != null && it.replacement != null }
        )

        // Before: the stock routing, so the after-state is a change and not the input read
        // twice. Each anchor must occur exactly once, or the engine's replace would edit the
        // wrong place or several of them.
        val stock = BrowserSmali(dex, BROWSER_MODULE_DESCRIPTOR).text(BROWSER_MODULE)
        patches.forEach { patch ->
            val anchor = requireNotNull(patch.anchor)
            assertEquals(
                "the anchor must occur exactly once in the stock build:\n$anchor",
                1,
                occurrences(stock, anchor)
            )
        }
        // The edit that points the in-app entry point at the external call writes the call
        // openInChromeURL already makes, character for character - the same registers, the same
        // descriptor - so that one replacement text is in the stock build as the sibling call
        // site and the other two are not. A replacement already present elsewhere would mean
        // the anchor is not the edit this set describes.
        patches.forEach { patch ->
            val replacement = requireNotNull(patch.replacement)
            assertEquals(
                "unexpected copies of this replacement in the stock build:\n$replacement",
                if ("tryOpenUrlExternally(" in replacement) 1 else 0,
                occurrences(stock, replacement)
            )
        }
        assertEquals(
            "the only external-browser call in the stock module is openInChromeURL's",
            1,
            occurrences(stock, EXTERNAL_CALL)
        )
        assertTrue("stock must read the stored browser choice", stock.contains(STORED_KEY_READ))
        assertTrue(
            "stock must fall back to the in-app tab",
            stock.contains(fallback(IN_APP_FALLBACK))
        )
        assertTrue("stock openInAppURL must render a Custom Tab", stock.contains(CUSTOM_TABS_CALL))

        // Apply the set's own edits with the engine the pipeline uses.
        val (patched, results) = DexProcessor.patchDexSurgically(dexBytes = dex, patches = patches)
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
        }

        // After: the stored choice is out of the read path, both fallbacks load Chrome, and the
        // in-app entry point hands the URL to the external call. `selectBrowser` still writes the
        // stored key - the app's own settings screen keeps working, it is just never read.
        val after = BrowserSmali(patched, BROWSER_MODULE_DESCRIPTOR).text(BROWSER_MODULE)
        assertFalse("the getter must stop reading the stored choice", after.contains(STORED_KEY_READ))
        assertTrue("the getter must read the ignored key", after.contains(IGNORED_KEY_READ))
        assertTrue("the app must still be able to save a choice", after.contains(STORED_KEY_WRITE))
        assertFalse(
            "no fallback may load the in-app browser",
            after.contains(fallback(IN_APP_FALLBACK))
        )
        assertTrue(
            "the branch that checked for Custom Tabs must load Chrome",
            after.contains(fallback(CHROME_FALLBACK))
        )
        assertFalse(
            "nothing in the module may render a Custom Tab any more",
            after.contains(CUSTOM_TABS_CALL)
        )
        assertEquals(
            "openInAppURL and openInChromeURL must both call the external browser",
            2,
            occurrences(after, EXTERNAL_CALL)
        )
    }

    /** How many times [needle] occurs in [haystack] - the engine's replace edits every one. */
    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    /** The fallback's branch and the constant it loads, as one block. */
    private fun fallback(constant: String): String =
        "$IN_APP_BRANCH\n\n    .line 42\n    .line 43\n$constant"
}

/**
 * One class of a DEX as the engine's own baksmali writes it.
 *
 * The anchors are written in this spelling - baksmali 3.0.10 labels branches by address rather
 * than sequentially - so the fixture has to be read back through the same tool the engine
 * disassembles with. A class that does not resolve is an empty string, which fails the
 * assertions above rather than throwing here.
 */
private class BrowserSmali(dexBytes: ByteArray, vararg descriptors: String) {

    private companion object {
        /** The API level the pipeline disassembles its DEX files at. */
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

    /** The smali of the class at [path], or an empty string when it was not disassembled. */
    fun text(path: String): String =
        File(directory, path).takeIf { it.isFile }?.readText().orEmpty()
}
