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
 * The camera frame-rate log edit, applied to the real build and read back from the DEX.
 *
 * `CameraVideoCapturer$CameraStatistics$1.run()` reposts itself for the whole camera session and
 * builds a frame-rate string on every tick. The edit removes that build and the log call it
 * feeds, and keeps the `"CameraStatistics"` tag: the freeze-detection branch further down the
 * same method reuses `v1` as its tag without reloading it, so an edit that removed the constant
 * too would leave `v1` undefined on every path to that branch and ART would reject the class at
 * load, which is the moment the camera opens. The assertions below pin both halves - the log
 * gone, the tag and the freeze branch still there, the tag written before the branch - so the
 * unsafe shape fails here rather than on a device.
 */
class CameraStatisticsLogTest {

    private companion object {
        /** The self-reposting statistics runnable the whole edit lives in. */
        const val CAPTURER = "org/webrtc/CameraVideoCapturer\$CameraStatistics\$1.smali"

        /** The descriptor baksmali disassembles for that path. */
        const val CAPTURER_DESCRIPTOR = "Lorg/webrtc/CameraVideoCapturer\$CameraStatistics\$1;"

        /** The log call the edit removes, in the engine's own spelling. */
        const val FPS_LOG_CALL =
            "    invoke-static {v1, v0}, Lorg/webrtc/Logging;->d(Ljava/lang/String;Ljava/lang/String;)V"

        /** The tag the freeze-detection branch reuses, and the call that reuses it. */
        const val LOG_TAG = "    const-string v1, \"CameraStatistics\""
        const val FREEZE_LOG =
            "    invoke-static {v1, v0}, Lorg/webrtc/Logging;->e(Ljava/lang/String;Ljava/lang/String;)V"

        /** Text only the removed string build produces, which must not survive. */
        const val FPS_PREFIX = "Camera fps: "
    }

    @Test
    fun theFrameRateLogGoesAndTheFreezeDetectionStays() = runBlocking {
        val baseApk = ReferenceApks.discordBaseApk
        assumeTrue("the Discord fixtures are not on this machine (${baseApk.path})", baseApk.isFile)

        val dexEntries = dexEntries(baseApk)
        val dexName = requireNotNull(
            DexProcessor.buildClassToDexIndex(dexEntries)[CAPTURER_DESCRIPTOR]
        ) { "$CAPTURER_DESCRIPTOR is not in this build" }
        val dex = dexEntries.getValue(dexName)

        // The edit is a set item, not something this test spells out.
        val patches = DiscordNativePatches.CAMERA_LOG.smaliPatches
            .filter { it.smaliPath == CAPTURER }
        assertEquals(
            "discord_native_camera_log must hold the one edit to the statistics runnable",
            1,
            patches.size
        )
        assertEquals(
            "the edit is an anchor edit",
            patches.size,
            patches.count { it.anchor != null && it.replacement != null }
        )

        // Before: the stock tick, so the after-state is a change and not the input read twice.
        // The anchor must occur exactly once, or the engine's replace would edit more than the
        // one block the patch describes.
        val stock = CameraSmali(dex, CAPTURER_DESCRIPTOR).text(CAPTURER)
        patches.forEach { patch ->
            val anchor = requireNotNull(patch.anchor)
            assertEquals(
                "the anchor must occur exactly once in the stock build:\n$anchor",
                1,
                occurrences(stock, anchor)
            )
        }
        assertEquals("one fps log call in stock", 1, occurrences(stock, FPS_LOG_CALL))
        assertEquals("one tag constant in stock", 1, occurrences(stock, LOG_TAG))
        assertEquals("one freeze log in stock", 1, occurrences(stock, FREEZE_LOG))
        assertTrue("stock must build the frame-rate string", stock.contains(FPS_PREFIX))

        // Apply the set's own edit with the engine the pipeline uses.
        val (patched, results) = DexProcessor.patchDexSurgically(dexBytes = dex, patches = patches)
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
        }

        // After: the build and the log call are gone, the tag and the freeze branch are not.
        val after = CameraSmali(patched, CAPTURER_DESCRIPTOR).text(CAPTURER)
        assertFalse("the fps log call must be gone", after.contains("Lorg/webrtc/Logging;->d"))
        assertFalse("nothing may build the frame-rate string", after.contains(FPS_PREFIX))
        assertFalse("no string builder may remain in the class", after.contains("StringBuilder"))
        assertEquals("the tag must survive, reloaded once", 1, occurrences(after, LOG_TAG))
        assertTrue("the freeze log must survive", after.contains(FREEZE_LOG))
        assertTrue("the freeze detection must survive", after.contains("onCameraFreezed"))
        assertTrue("the freeze message must survive", after.contains("Camera freezed."))
        assertTrue(
            "the tag must be written before the branch that reuses v1, or the class fails " +
                "verification when the camera opens",
            after.indexOf(LOG_TAG) < after.indexOf(FREEZE_LOG)
        )
    }

    /** How many times [needle] occurs in [haystack] - the engine's replace edits every one. */
    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}

/**
 * One class of a DEX as the engine's own baksmali writes it.
 *
 * The anchors are written in this spelling - baksmali 3.0.10 labels branches by address rather
 * than sequentially - so the fixture has to be read back through the same tool the engine
 * disassembles with. A class that does not resolve is an empty string, which fails the
 * assertions above rather than throwing here.
 */
private class CameraSmali(dexBytes: ByteArray, vararg descriptors: String) {

    private companion object {
        /** The API level the pipeline disassembles its DEX files at. */
        const val API_LEVEL = 28
    }

    private val directory: File = File.createTempFile("cameraSmali", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    init {
        val dexFile = File.createTempFile("cameraSmaliIn", ".dex").apply {
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
