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
 * The media-engine call-path edits, applied to the real build and read back from the DEX.
 *
 * These are the shapes that end a call badly: a duplicate connection id threw out of the
 * coroutine that registered it, reset removed entries from the map it was iterating, the media
 * scope was built on a plain job, and the foreground-service chain dropped the microphone type
 * as soon as the window left the foreground. The assertions below pin the properties the fixes
 * rely on - the throwing branches gone, removal from a snapshot, a supervisor job in the
 * context, and the two window-foreground gates removed without touching the fallback type -
 * so a replacement that assembles but does not change the behaviour fails here.
 */
class MediaCallPatchesTest {

    private companion object {
        const val MODULE = "com/discord/media/engine/MediaEngineModule.smali"
        const val MODULE_DESCRIPTOR = "Lcom/discord/media/engine/MediaEngineModule;"

        const val CREATE_CONNECTION = "cd/q0.smali"
        const val CREATE_CONNECTION_DESCRIPTOR = "Lcd/q0;"

        const val TEARDOWN = "cd/x0.smali"
        const val TEARDOWN_DESCRIPTOR = "Lcd/x0;"

        const val STREAM = "cd/s0.smali"
        const val STREAM_DESCRIPTOR = "Lcd/s0;"

        const val THUMBNAIL = "a7/b.smali"
        const val THUMBNAIL_DESCRIPTOR = "La7/b;"
        const val THUMBNAIL_CASE = ":pswitch_969"

        const val FOREGROUND = "androidx/core/view/f1.smali"
        const val FOREGROUND_DESCRIPTOR = "Landroidx/core/view/f1;"
        const val FOREGROUND_MIC_GATE = "    if-eqz v16, :cond_a8"
        const val FOREGROUND_CAMERA_GATE = "    if-eqz v16, :cond_c8"

        val MEDIA_PATHS = setOf(MODULE, CREATE_CONNECTION, TEARDOWN, THUMBNAIL)
    }

    @Test
    fun theCallPathEditsChangeTheShapesThatBrokeCalls() = runBlocking {
        val baseApk = ComparisonApks.discordBaseApk
        assumeTrue("the Discord fixtures are not on this machine (${baseApk.path})", baseApk.isFile)

        val dexEntries = dexEntries(baseApk)
        val classToDex = DexProcessor.buildClassToDexIndex(dexEntries)

        val mediaPatches = DiscordNativePatches.MEDIA.smaliPatches
            .filter { it.smaliPath in MEDIA_PATHS }
        val foregroundPatches = DiscordNativePatches.FOREGROUND_SERVICE.smaliPatches
            .filter { it.smaliPath == FOREGROUND }
        assertEquals(
            "the media set must hold the supervisor, registration, teardown and thumbnail edits",
            4,
            mediaPatches.size
        )
        assertEquals(
            "the foreground set must hold both type gates and all three catch widenings",
            5,
            foregroundPatches.size
        )
        val patches = mediaPatches + foregroundPatches
        assertTrue(
            "every call-path edit is an anchor or switch-case edit",
            patches.all {
                (it.anchor != null && it.replacement != null) ||
                    (it.switchCaseLabel != null && it.switchCaseBody != null)
            }
        )

        val dexNames = patches.mapTo(mutableSetOf()) { patch ->
            val descriptor = "L" + patch.smaliPath.removeSuffix(".smali") + ";"
            requireNotNull(classToDex[descriptor]) { "$descriptor is not in this build" }
        }
        assertEquals("all call-path edits must share the base DEX", 1, dexNames.size)
        val dex = dexEntries.getValue(dexNames.single())

        // Before: every anchor is unique in the stock build, so the engine's replace can only
        // edit the block each patch describes.
        val stock = mapOf(
            MODULE to MediaSmali(dex, MODULE_DESCRIPTOR).text(MODULE),
            CREATE_CONNECTION to
                MediaSmali(dex, CREATE_CONNECTION_DESCRIPTOR).text(CREATE_CONNECTION),
            TEARDOWN to MediaSmali(dex, TEARDOWN_DESCRIPTOR).text(TEARDOWN),
            STREAM to MediaSmali(dex, STREAM_DESCRIPTOR).text(STREAM),
            THUMBNAIL to MediaSmali(dex, THUMBNAIL_DESCRIPTOR).text(THUMBNAIL),
            FOREGROUND to MediaSmali(dex, FOREGROUND_DESCRIPTOR).text(FOREGROUND)
        )
        patches.filter { it.anchor != null }.forEach { patch ->
            val anchor = requireNotNull(patch.anchor)
            val text = stock.getValue(patch.smaliPath)
            assertEquals(
                "the anchor must occur exactly once in the stock build:\n$anchor",
                1,
                occurrences(text, anchor)
            )
        }
        assertTrue(
            "stock registration throws on a duplicate id",
            stock.getValue(CREATE_CONNECTION).contains("Ljava/util/Map;->containsKey")
        )
        assertTrue(
            "stock teardown iterates the live key set",
            stock.getValue(TEARDOWN).contains("Ljava/util/Iterator;->next")
        )
        assertFalse(
            "stock media scope has no supervisor",
            stock.getValue(MODULE).contains("Lk40/t1;")
        )
        assertTrue(
            "stock foreground chain gates the microphone on the window",
            stock.getValue(FOREGROUND).contains(FOREGROUND_MIC_GATE)
        )
        assertTrue(
            "stock foreground chain gates the camera on the window",
            stock.getValue(FOREGROUND).contains(FOREGROUND_CAMERA_GATE)
        )
        assertTrue(
            "the stock thumbnail case is the one the edit wraps",
            stock.getValue(THUMBNAIL).contains(THUMBNAIL_CASE)
        )
        assertTrue(
            "the stock thumbnail case compresses the bitmap",
            stock.getValue(THUMBNAIL)
                .contains("Lkv/d;->r(Landroid/graphics/Bitmap;Ld10/c;)Ljava/lang/Object;")
        )
        assertEquals(
            "the stock class has four catch-alls",
            4,
            occurrences(stock.getValue(THUMBNAIL), ".catchall")
        )

        val (patched, results) = DexProcessor.patchDexSurgically(dexBytes = dex, patches = patches)
        assertEquals("one step per edit", patches.size, results.size)
        results.forEach {
            assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
        }

        val after = mapOf(
            MODULE to MediaSmali(patched, MODULE_DESCRIPTOR).text(MODULE),
            CREATE_CONNECTION to
                MediaSmali(patched, CREATE_CONNECTION_DESCRIPTOR).text(CREATE_CONNECTION),
            TEARDOWN to MediaSmali(patched, TEARDOWN_DESCRIPTOR).text(TEARDOWN),
            STREAM to MediaSmali(patched, STREAM_DESCRIPTOR).text(STREAM),
            THUMBNAIL to MediaSmali(patched, THUMBNAIL_DESCRIPTOR).text(THUMBNAIL),
            FOREGROUND to MediaSmali(patched, FOREGROUND_DESCRIPTOR).text(FOREGROUND)
        )

        // Registration: no check throws, the replaced connection is disposed.
        val registration = after.getValue(CREATE_CONNECTION)
        assertFalse(
            "registration must not throw on a duplicate id",
            registration.contains("Ljava/util/Map;->containsKey")
        )
        assertFalse(
            "registration must not throw when the instance is already mapped",
            registration.contains("Ljava/util/Map;->containsValue")
        )
        assertFalse(
            "registration must not construct the check failure",
            registration.contains("\"Check failed.\"")
        )
        assertTrue(
            "the previous mapping for the connection instance is removed",
            registration.contains("Ljava/util/Map${'$'}Entry;->getValue")
        )
        assertTrue(
            "the replaced connection is disposed",
            registration.contains("Lcom/discord/native/engine/NativeConnection;->dispose()V")
        )
        assertTrue("the new connection is registered", registration.contains("Ljava/util/Map;->put"))

        // The camera path is the stream-connection coroutine, which registers through the same
        // createConnection coroutine the edit above changes.
        assertTrue(
            "createOwnStreamConnectionWithOptions must still launch the stream coroutine",
            after.getValue(MODULE).contains("Lcd/s0;")
        )
        assertTrue(
            "the stream coroutine must register through createConnection",
            after.getValue(STREAM).contains("access\$createConnection")
        )

        // Teardown: keys are snapshotted, so removal cannot modify the iterator.
        val teardown = after.getValue(TEARDOWN)
        assertTrue(
            "teardown takes a key snapshot",
            teardown.contains("Ljava/util/Set;->toArray()[Ljava/lang/Object;")
        )
        assertFalse(
            "teardown no longer iterates the live key set",
            teardown.contains("Ljava/util/Iterator;")
        )
        assertTrue(
            "teardown still removes each key",
            teardown.contains("Ljava/util/Map;->remove(Ljava/lang/Object;)Ljava/lang/Object;")
        )
        assertTrue(
            "teardown still disposes each connection",
            teardown.contains("Lcom/discord/native/engine/NativeConnection;->dispose()V")
        )

        // Thumbnail: the compression and callback now sit inside one more catch-all, and the
        // suspension path still returns COROUTINE_SUSPENDED rather than the unit value.
        val thumbnail = after.getValue(THUMBNAIL)
        assertEquals(
            "the thumbnail case gains a catch-all",
            5,
            occurrences(thumbnail, ".catchall")
        )
        assertEquals(
            "the class gains exactly one exception handler",
            8,
            occurrences(thumbnail, "move-exception")
        )
        assertTrue(
            "the compression is still performed",
            thumbnail.contains("Lkv/d;->r(Landroid/graphics/Bitmap;Ld10/c;)Ljava/lang/Object;")
        )
        assertTrue(
            "the thumbnail still invokes the JavaScript callback",
            thumbnail.contains("Laf/g;->invoke(Ljava/lang/Object;)Ljava/lang/Object;")
        )
        val compression = thumbnail.indexOf("Lkv/d;->r(Landroid/graphics/Bitmap;Ld10/c;)")
        val suspensionCheck = thumbnail.indexOf("if-ne v2, v0", compression)
        assertTrue(
            "the suspension result must still be branched on immediately after the compression",
            compression >= 0 && suspensionCheck > compression &&
                suspensionCheck - compression < 300
        )

        // Supervisor: the scope context carries k40/t1 before CoroutineScope() sees it.
        val module = after.getValue(MODULE)
        assertTrue("the scope gets a SupervisorJob", module.contains("Lk40/t1;"))
        assertTrue(
            "the supervisor is built from e1's constructor",
            module.contains("invoke-direct {v2}, Lk40/e1;-><init>()V")
        )
        assertTrue(
            "the supervisor is merged into the dispatcher context",
            module.contains("Lb10/h;->B(Lb10/h;)Lb10/h;")
        )
        assertTrue(
            "the supervisor is merged before the scope is created",
            module.indexOf("Lk40/t1;") < module.indexOf("Lk40/z;->b(Lb10/h;)Lp40/d;")
        )

        // Foreground: both window gates are gone; the fallback computation and the other
        // foreground read are untouched.
        val foreground = after.getValue(FOREGROUND)
        assertFalse(
            "the microphone type must not depend on the window foreground state",
            foreground.contains(FOREGROUND_MIC_GATE)
        )
        assertFalse(
            "the camera type must not depend on the window foreground state",
            foreground.contains(FOREGROUND_CAMERA_GATE)
        )
        assertTrue(
            "the foreground state is still computed for the fallback type",
            foreground.contains("sget-boolean v16, Lwc/g;->a:Z")
        )
        // Reassembly renumbers the address-based try labels, so count the catch types rather
        // than pin a label spelling that changes when the gates above shrink.
        assertEquals(
            "all three service-type attempts catch a refusal",
            3,
            occurrences(foreground, ".catch Ljava/lang/Exception;")
        )
        assertEquals(
            "no service-type attempt still catches only the security exception",
            0,
            occurrences(foreground, ".catch Ljava/lang/SecurityException;")
        )
    }

    /** How many times [needle] occurs in [haystack] - the engine's replace edits every one. */
    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}

/**
 * One class of a DEX as the engine's own baksmali writes it.
 *
 * The anchors are written in this spelling, so the fixture has to be read back through the same
 * tool the engine disassembles with. A class that does not resolve is an empty string, which
 * fails the assertions above rather than throwing here.
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
