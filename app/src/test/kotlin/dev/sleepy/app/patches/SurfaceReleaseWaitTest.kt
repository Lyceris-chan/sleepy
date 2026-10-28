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
 * The video surface-release waits, bounded so a backgrounded or screen-off call cannot freeze the
 * interface.
 *
 * Both waits are entered on the UI thread: `TextureView.onSurfaceTextureDestroyed` runs there, and
 * so does the React Native view manager's drop of a video tile. Stock waits for the render thread
 * with no usable ceiling - one with `Long.MAX_VALUE`, the other with the unbounded overload - so a
 * renderer that is wedged turns screen-off or backgrounding into an ANR. The edits cap each at two
 * seconds and leave the normal fast path unchanged.
 */
class SurfaceReleaseWaitTest {

    private companion object {
        const val TEXTURE = "id/e.smali"
        const val TEXTURE_DESCRIPTOR = "Lid/e;"
        const val VIEW = "id/h.smali"
        const val VIEW_DESCRIPTOR = "Lid/h;"
        const val SURFACE = "org/webrtc/SurfaceEglRenderer.smali"
        const val SURFACE_DESCRIPTOR = "Lorg/webrtc/SurfaceEglRenderer;"
        const val UNBOUNDED =
            "Lorg/webrtc/ThreadUtils;->awaitUninterruptibly(Ljava/util/concurrent/CountDownLatch;)V"
        const val TIMEOUT = "0x7d0"

        val PATHS = listOf(TEXTURE, VIEW, SURFACE)
    }

    @Test
    fun theUiThreadSurfaceWaitsHaveATwoSecondCeiling() = runBlocking {
        val baseApk = ComparisonApks.discordBaseApk
        assumeTrue("the Discord fixtures are not on this machine (${baseApk.path})", baseApk.isFile)

        val dexEntries = dexEntries(baseApk)
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)
        val patches = DiscordNativePatches.MEDIA.smaliPatches.filter {
            it.smaliPath in PATHS && it.title?.startsWith("Bounding") == true
        }
        assertEquals("the media set must hold the three surface-wait edits", 3, patches.size)

        val stock = mapOf(
            TEXTURE to SurfaceSmali(
                dexEntries.getValue(classToDex.getValue(TEXTURE_DESCRIPTOR)),
                TEXTURE_DESCRIPTOR
            ).text(TEXTURE),
            VIEW to SurfaceSmali(
                dexEntries.getValue(classToDex.getValue(VIEW_DESCRIPTOR)),
                VIEW_DESCRIPTOR
            ).text(VIEW),
            SURFACE to SurfaceSmali(
                dexEntries.getValue(classToDex.getValue(SURFACE_DESCRIPTOR)),
                SURFACE_DESCRIPTOR
            ).text(SURFACE)
        )
        patches.forEach { patch ->
            val anchor = requireNotNull(patch.anchor)
            assertEquals(
                "the anchor must occur exactly once in the stock build:\n$anchor",
                1,
                occurrences(stock.getValue(patch.smaliPath), anchor)
            )
        }
        assertTrue(
            "stock TextureView waits with Long.MAX_VALUE",
            stock.getValue(TEXTURE).contains("0x7fffffffffffffffL")
        )
        assertTrue(
            "stock video-tile release uses the unbounded overload",
            stock.getValue(VIEW).contains(UNBOUNDED)
        )
        assertTrue(
            "stock SurfaceEglRenderer uses the unbounded overload",
            stock.getValue(SURFACE).contains(UNBOUNDED)
        )

        val patchedByDex = mutableMapOf<String, ByteArray>()
        patches.groupBy { classToDex.getValue(descriptorOf(it.smaliPath)) }.forEach { (dex, group) ->
            val (patched, results) = DexProcessor.patchDexSurgically(
                dexBytes = dexEntries.getValue(dex),
                patches = group
            )
            patchedByDex[dex] = patched
            results.forEach {
                assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
            }
        }

        fun after(path: String, descriptor: String): String {
            val dex = patchedByDex.getValue(classToDex.getValue(descriptor))
            return SurfaceSmali(dex, descriptor).text(path)
        }

        val texture = after(TEXTURE, TEXTURE_DESCRIPTOR)
        assertFalse(
            "the TextureView wait still has no usable ceiling",
            texture.contains("0x7fffffffffffffffL")
        )
        assertTrue("the TextureView wait is not two seconds", texture.contains(TIMEOUT))

        val view = after(VIEW, VIEW_DESCRIPTOR)
        assertFalse(
            "the video-tile release still uses the unbounded overload",
            view.contains(UNBOUNDED)
        )
        assertTrue(
            "the video-tile release was not given the timed overload",
            view.contains(
                "Lorg/webrtc/ThreadUtils;->awaitUninterruptibly(Ljava/util/concurrent/CountDownLatch;J)Z"
            )
        )
        assertTrue("the video-tile wait is not two seconds", view.contains(TIMEOUT))

        val surface = after(SURFACE, SURFACE_DESCRIPTOR)
        assertFalse(
            "SurfaceEglRenderer still uses the unbounded overload",
            surface.contains(UNBOUNDED)
        )
        assertTrue(
            "SurfaceEglRenderer was not given the timed overload",
            surface.contains(
                "Lorg/webrtc/ThreadUtils;->awaitUninterruptibly(Ljava/util/concurrent/CountDownLatch;J)Z"
            )
        )
        assertTrue("the surface wait is not two seconds", surface.contains(TIMEOUT))
    }

    private fun descriptorOf(smaliPath: String): String =
        "L" + smaliPath.removeSuffix(".smali") + ";"

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}

/** One class of a DEX as the engine's own baksmali writes it. */
private class SurfaceSmali(dexBytes: ByteArray, vararg descriptors: String) {

    private companion object {
        const val API_LEVEL = 28
    }

    private val directory: File = File.createTempFile("surfaceSmali", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    init {
        val dexFile = File.createTempFile("surfaceSmaliIn", ".dex").apply {
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
