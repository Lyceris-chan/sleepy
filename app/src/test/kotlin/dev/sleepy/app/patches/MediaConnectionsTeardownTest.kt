package dev.sleepy.app.patches

import dev.sleepy.app.engine.DexProcessor
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.dexEntries
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The media connection registry's teardown, read back from the real build.
 *
 * The stock `removeAndDisposeAll()` iterates `connections.keySet()` and calls
 * `removeAndDispose(id)` inside the loop, which removes that key from the same map. The next
 * `Iterator.next()` therefore sees a changed `modCount` and throws
 * `ConcurrentModificationException` as soon as the map held at least two entries - a voice
 * connection and a stream connection, for example. `MediaEngine.reset()` calls this method
 * before `nativeEngine.dispose()`, so the throw abandons the rest of teardown: the remaining
 * native connections and the native engine are never released.
 *
 * The patch copies the keys first and then removes them, so the iteration and the mutation no
 * longer share a map. This test checks the shape it replaces and the shape it writes back.
 */
class MediaConnectionsTeardownTest {

    private companion object {
        const val CONNECTIONS = "com/discord/media/engine/MediaEngineNativeConnections.smali"
        const val DESCRIPTOR = "Lcom/discord/media/engine/MediaEngineNativeConnections;"
        const val TITLE = "Disposing every media connection on reset"
        const val METHOD = ".method public final removeAndDisposeAll()V"

        /** The stock remove-during-iteration shape this patch exists to replace. */
        const val ITERATOR_NEXT = "Ljava/util/Iterator;->next()Ljava/lang/Object;"
        const val REMOVE = "->removeAndDispose(I)V"

        /** The copy the replacement takes before removing anything. */
        const val SNAPSHOT = "Ljava/util/Collection;->toArray()[Ljava/lang/Object;"
    }

    @Test
    fun teardownCopiesTheKeysBeforeRemovingThem() = runBlocking {
        assumeTrue(
            "${ComparisonApks.discordBaseApk.path} is not on this machine",
            ComparisonApks.discordBaseApk.exists()
        )
        val dexEntries = dexEntries(ComparisonApks.discordBaseApk)
        val dexName = dexEntries.keys.firstOrNull { name ->
            MediaSmali(dexEntries.getValue(name), DESCRIPTOR).text(CONNECTIONS).isNotEmpty()
        }
        assertTrue("$DESCRIPTOR is not in this build", dexName != null)
        val dex = dexEntries.getValue(dexName!!)

        val patch = DiscordNativePatches.MEDIA.smaliPatches.single { it.title == TITLE }
        assertEquals("the patch must replace the whole method", METHOD, patch.methodSignature)

        val stock = MediaSmali(dex, DESCRIPTOR).text(CONNECTIONS)
        assertTrue("the fixture must contain the class under test", stock.isNotEmpty())
        val stockMethod = methodBody(stock, METHOD)

        // The defect has to be present in the stock build, or this patch has nothing to fix.
        assertTrue("the stock method must iterate the key set", stockMethod.contains("keySet()"))
        assertTrue(
            "the stock method must advance an iterator",
            stockMethod.contains(ITERATOR_NEXT)
        )
        assertTrue(
            "the stock method must dispose through removeAndDispose",
            stockMethod.contains(REMOVE)
        )
        assertTrue(
            "the removal must happen inside the iteration, which is the fault",
            stockMethod.indexOf(ITERATOR_NEXT) < stockMethod.indexOf(REMOVE)
        )
        assertFalse(
            "the stock method must not already snapshot the keys",
            stockMethod.contains(SNAPSHOT)
        )

        val (patched, results) = DexProcessor.patchDexSurgically(
            dexBytes = dex,
            patches = listOf(patch)
        )
        assertEquals("one step per edit", 1, results.size)
        results.forEach {
            assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
        }

        val after = MediaSmali(patched, DESCRIPTOR).text(CONNECTIONS)
        assertTrue("the patched class has to disassemble", after.isNotEmpty())
        val afterMethod = methodBody(after, METHOD)

        // The replacement copies the keys, walks the copy by index, and still disposes each
        // connection and keeps the post-condition check the stock method ended with.
        assertTrue(
            "the keys must be copied out of the map before anything is removed",
            afterMethod.contains(SNAPSHOT)
        )
        assertTrue("the copy must be walked by length", afterMethod.contains("array-length"))
        assertTrue("the copy must be indexed", afterMethod.contains("aget-object"))
        assertTrue("the copy's index must advance", afterMethod.contains("add-int/lit8"))
        assertTrue(
            "each key must still be disposed",
            afterMethod.contains(
                "invoke-virtual {p0, v3}, " +
                    "Lcom/discord/media/engine/MediaEngineNativeConnections;->removeAndDispose(I)V"
            )
        )
        assertFalse(
            "the patched method must not iterate the map it mutates",
            afterMethod.contains(ITERATOR_NEXT)
        )
        assertTrue(
            "the empty-map post-condition must survive",
            afterMethod.contains("isEmpty()Z") && afterMethod.contains("Check failed.")
        )
    }

    /** The text from [signature] to the method's closing directive. */
    private fun methodBody(smali: String, signature: String): String {
        val start = smali.indexOf(signature)
        assertTrue("$signature is not in the stock class", start >= 0)
        val end = smali.indexOf(".end method", start)
        assertTrue("$signature has no closing directive", end > start)
        return smali.substring(start, end)
    }
}
