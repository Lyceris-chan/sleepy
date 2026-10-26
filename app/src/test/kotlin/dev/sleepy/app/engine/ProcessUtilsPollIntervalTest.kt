package dev.sleepy.app.engine

import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.bundleOf
import dev.sleepy.app.testing.readU32Le
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The once-a-second process-usage poll, slowed in place.
 *
 * `ProcessUtils` starts a `setInterval` when its module is imported and never clears it. Every
 * tick made three React Native calls - `getCurrentCpuUsagePercent`, `getCumulativeCpuUsage` and
 * `getCurrentMemoryUsageKb` - and two of those read fields the app's own resource sampler kept
 * current. The patch set stops that sampler in the native `DeviceResourceUsageManager.start()`,
 * so the fields no longer change, and the feature that stubs the poll callback (`86445`) already
 * removed the work inside the tick. The timer itself kept waking the JavaScript thread every
 * second, for a callback that returned immediately.
 *
 * The edit changes one instruction's 32-bit immediate, from 1000 to 3,600,000 milliseconds. No
 * instruction changes size, so nothing moves and no jump label needs remapping: the replacement
 * is the stock 699-byte body with three bytes different. This test pins that shape - the exact
 * bytes that differ, the opcode they belong to, and the value on both sides of the patch.
 */
class ProcessUtilsPollIntervalTest {

    private companion object {
        /** The module factory that creates the ProcessUtils instance and starts the timer. */
        const val FUNCTION_ID = 1379
        const val BODY_SIZE = 699

        /** LoadConstInt r0's 4-byte immediate, relative to the function's first byte. */
        const val IMMEDIATE_OFFSET = 649
        const val LOAD_CONST_INT_OPCODE = 0x8C

        const val STOCK_INTERVAL_MS = 1000
        const val PATCHED_INTERVAL_MS = 3_600_000

        /** The callback the timer invokes; the same feature stubs its body. */
        const val CALLBACK_ID = 88493
        const val FEATURE_SLUG = "performance_metrics"
    }

    @Test
    fun theProcessPollTimerIsSlowedWithoutMovingAnyInstruction() {
        val apk = ComparisonApks.discordBaseApk
        assumeTrue("${apk.path} is not on this machine", apk.isFile)
        val base = bundleOf(apk)
        assumeTrue(
            "the base bundle is not the build the patch set names",
            base.size.toLong() == DiscordHermesBundlePatch.TARGET_BUNDLE_SIZE.toLong()
        )

        val patch = DiscordHermesBundlePatch.PATCHES.single { it.functionId == FUNCTION_ID }
        assertEquals(
            "the replacement must be the size of the body it replaces, or the edit is a " +
                "relocation and this test's fixed offsets no longer describe it",
            BODY_SIZE,
            patch.originalSize
        )
        assertEquals("the replacement body must be the same size", BODY_SIZE, patch.replacement.size)

        val located = requireNotNull(HermesFunctionTable.locate(base, FUNCTION_ID)) {
            "function $FUNCTION_ID is not locatable in the base bundle"
        }
        assertEquals("the body the patch was built from", BODY_SIZE, located.bytecodeSize)

        // The stock instruction is LoadConstInt r0, 1000: opcode 0x8c, register byte 0, then a
        // little-endian immediate. Reading the opcode here is what makes the offset a fact about
        // this instruction rather than a position that happened to hold 1000.
        assertEquals(
            "the byte before the immediate must be the r0 register operand",
            0,
            base[located.bodyOffset + IMMEDIATE_OFFSET - 1].toInt() and 0xFF
        )
        assertEquals(
            "the instruction at the anchored offset must be LoadConstInt",
            LOAD_CONST_INT_OPCODE,
            base[located.bodyOffset + IMMEDIATE_OFFSET - 2].toInt() and 0xFF
        )
        assertEquals(
            "the stock timer must be the once-a-second one",
            STOCK_INTERVAL_MS,
            readU32Le(base, located.bodyOffset + IMMEDIATE_OFFSET)
        )

        val result = HermesBundlePatcher.apply(base, listOf(patch))
        assertEquals("one entry applied", 1, result.appliedCount)
        assertEquals(
            emptyList<String>(),
            result.skipped.map { "${it.functionId}: ${it.detail}" }
        )
        assertEquals(
            "a same-size body must be written where the old one was, not relocated",
            listOf(FUNCTION_ID),
            result.writtenInPlace.map { it.functionId }
        )
        assertTrue("nothing may be relocated", result.relocated.isEmpty())

        val after = result.bundleBytes
        val afterLocated = requireNotNull(HermesFunctionTable.locate(after, FUNCTION_ID))
        assertEquals(
            "the function must still start where it did",
            located.bodyOffset,
            afterLocated.bodyOffset
        )
        assertEquals(
            "the timer must now wait an hour",
            PATCHED_INTERVAL_MS,
            readU32Le(after, afterLocated.bodyOffset + IMMEDIATE_OFFSET)
        )

        // The whole edit, stated as bytes: only the three non-zero bytes of the immediate differ.
        // If a future port changes anything else about this body, the claim above that no
        // instruction moved stops being true, and this is where that shows up.
        val stockBody = base.copyOfRange(located.bodyOffset, located.bodyOffset + BODY_SIZE)
        val patchedBody = after.copyOfRange(
            afterLocated.bodyOffset,
            afterLocated.bodyOffset + BODY_SIZE
        )
        val differing = (0 until BODY_SIZE).filter { stockBody[it] != patchedBody[it] }
        assertEquals(
            "only the low three bytes of the interval immediate may differ",
            listOf(IMMEDIATE_OFFSET, IMMEDIATE_OFFSET + 1, IMMEDIATE_OFFSET + 2),
            differing
        )

        // The timer is only harmless because its callback is stubbed, and both edits belong to
        // the same switch: enabling performance-metrics applies them together.
        val feature = DiscordHermesFunctionCatalog.FEATURES.single { it.slug == FEATURE_SLUG }
        assertTrue(
            "slowing the timer must be part of the feature that stubs its callback",
            FUNCTION_ID in feature.functionIds && CALLBACK_ID in feature.functionIds
        )
        assertTrue(
            "the callback must still have a stub to run",
            DiscordHermesBundlePatch.PATCHES.any { it.functionId == CALLBACK_ID }
        )
    }
}
