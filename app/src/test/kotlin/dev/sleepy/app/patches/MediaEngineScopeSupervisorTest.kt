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
 * The media engine's own coroutine scope, read back from the real build.
 *
 * `MediaEngine` is a second scope, separate from `MediaEngineModule.appScope`. Its constructor
 * merges only the dispatcher and a `CoroutineName("MediaEngine")` (`sr/v`), and the
 * `CoroutineScope(context)` factory it is handed to installs a plain `Job` when the context holds
 * none. The scope has one launch site: `setBroadcastThumbnailParams` dispatches the JPEG encode of
 * a screen-share thumbnail to it. The encode calls `Bitmap.compress`, and its continuation then
 * invokes the React callback; either can throw, and an uncaught coroutine exception on that scope
 * reaches the thread's uncaught-exception handler, which is the process kill the camera patch
 * addressed for the module scope.
 *
 * This test pins the same two properties the module test pins: the anchor is unique in the stock
 * build, so a rename fails here instead of silently leaving the fault in place, and every class
 * the injection names exists in the build, so a stale name cannot produce a reference that
 * verifies at assemble time and fails on the device.
 */
class MediaEngineScopeSupervisorTest {

    private companion object {
        const val ENGINE = "com/discord/media/engine/MediaEngine.smali"
        const val DESCRIPTOR = "Lcom/discord/media/engine/MediaEngine;"

        /** Selects this edit among the MEDIA set's other MediaEngine patches. */
        const val TITLE = "Keeping the broadcast thumbnail scope alive after one failure"

        /** The context merge the edit is written after; the stock class has exactly one. */
        const val CONTEXT_PLUS = "Lkotlin/coroutines/e;->c("

        /** The scope factory call the merged context is handed to. */
        const val SCOPE_FACTORY = "Lsr/x;->b("

        const val SUPERVISOR_JOB = "Lsr/m1"
        const val JOB_BASE = "Lsr/y0"
        const val EXCEPTION_HANDLER = "Ltr/b"

        val REFERENCED = listOf("Lsr/m1;", "Lsr/y0;", "Ltr/b;")
    }

    @Test
    fun theEngineScopeIsBuiltOnASupervisorJobAndTheNamesItUsesExist() = runBlocking {
        assumeTrue(
            "${ComparisonApks.discordBaseApk.path} is not on this machine",
            ComparisonApks.discordBaseApk.exists()
        )
        val dexEntries = dexEntries(ComparisonApks.discordBaseApk)
        val dexName = dexEntries.keys.firstOrNull { name ->
            MediaSmali(dexEntries.getValue(name), DESCRIPTOR).text(ENGINE).isNotEmpty()
        }
        assertTrue("$DESCRIPTOR is not in this build", dexName != null)
        val dex = dexEntries.getValue(dexName!!)

        val patch = DiscordNativePatches.MEDIA.smaliPatches.single { it.title == TITLE }
        val stock = MediaSmali(dex, DESCRIPTOR).text(ENGINE)
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
        assertFalse(
            "the stock scope must not already carry the exception handler",
            stock.contains(EXCEPTION_HANDLER)
        )

        // The names the injection uses are library classes and can live in any DEX entry.
        // Only absence is a fault: a cross-DEX reference is ordinary class loading.
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

        val (patched, results) = DexProcessor.patchDexSurgically(
            dexBytes = dex,
            patches = listOf(patch)
        )
        assertEquals("one step per edit", 1, results.size)
        results.forEach {
            assertEquals("${it.label} did not land: ${it.detail}", StepStatus.OK, it.status)
        }

        val after = MediaSmali(patched, DESCRIPTOR).text(ENGINE)
        assertTrue("the patched class has to disassemble", after.isNotEmpty())
        assertEquals(
            "the stock merge plus one merge for each injected element",
            3,
            occurrences(after, CONTEXT_PLUS)
        )

        // Order is the whole edit: both elements have to be in the context the scope factory
        // reads, and building either after the scope does nothing.
        val firstMergeAt = after.indexOf(CONTEXT_PLUS)
        val supervisorAt = after.indexOf("new-instance p2, $SUPERVISOR_JOB;")
        val supervisorCtorAt = after.indexOf("invoke-direct {p2}, $JOB_BASE;-><init>()V")
        val secondMergeAt = after.indexOf(CONTEXT_PLUS, firstMergeAt + CONTEXT_PLUS.length)
        val handlerAt = after.indexOf("new-instance p2, $EXCEPTION_HANDLER;")
        val handlerCtorAt = after.indexOf("invoke-direct {p2}, $EXCEPTION_HANDLER;-><init>()V")
        val thirdMergeAt = after.indexOf(CONTEXT_PLUS, secondMergeAt + CONTEXT_PLUS.length)
        val factoryAt = after.indexOf(SCOPE_FACTORY)
        assertTrue(
            "the supervisor must be built after the stock merge and before its own",
            firstMergeAt >= 0 && supervisorAt > firstMergeAt
        )
        assertTrue(
            "the supervisor base constructor must be called",
            supervisorCtorAt > supervisorAt
        )
        assertTrue(
            "the supervisor must join the context before the handler is built",
            secondMergeAt > supervisorCtorAt
        )
        assertTrue(
            "the handler must be built after the supervisor's merge",
            handlerAt > secondMergeAt
        )
        assertTrue(
            "the handler's no-argument constructor must be called",
            handlerCtorAt > handlerAt
        )
        assertTrue(
            "the handler must join the context",
            thirdMergeAt > handlerCtorAt
        )
        assertTrue(
            "and the merged context must exist before the scope is built from it",
            factoryAt > thirdMergeAt
        )
    }

    /** How many times [needle] occurs in [haystack] - the engine's replace edits every one. */
    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1
}
