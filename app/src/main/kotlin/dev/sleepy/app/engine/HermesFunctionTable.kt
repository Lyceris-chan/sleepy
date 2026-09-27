package dev.sleepy.app.engine

/**
 * Locates a Hermes bytecode function's body and length inside an HBC bundle.
 *
 * Patching a JavaScript function on-device means writing new bytecode over the old, which
 * requires knowing exactly where that function's bytecode starts and how long it is. Hermes
 * keeps both in a function header table that follows the file header directly.
 *
 * ## Why this refuses to guess
 *
 * An earlier implementation assumed the table began at byte 128 with a **12-byte** stride,
 * read the flags byte at `slot + 11`, and reconstructed the overflow pointer from the
 * `functionName` field. Measured against the real Discord 348.5 bundle, all fourteen target
 * functions took the overflow branch, which then read a body offset and length out of
 * unrelated bytes and used them to overwrite roughly 3 KB of live JavaScript bytecode at
 * effectively arbitrary positions — after recomputing the SHA-1 footer, so Hermes happily
 * loaded the corrupted bundle and the app died on launch.
 *
 * ## What the format actually says
 *
 * `facebook/hermes` `include/hermes/BCGen/HBC/BytecodeFileFormat.h` defines the layout. The
 * non-obvious parts, all of which the old code got wrong:
 *
 * - `BytecodeFileHeader` ends with `BytecodeOptions options` plus `uint8_t padding[19]`, and
 *   is `static_assert`ed to be a multiple of 32. Function headers follow it **immediately**,
 *   which puts the table at byte 128 — the one part of the old assumption that was right.
 * - `SmallFuncHeader` is `offset:25 | paramCount:7`, `bytecodeSizeInBytes:15 |
 *   functionName:17`, `infoOffset:25 | frameSize:7`, then `environmentSize`,
 *   `highestReadCacheIndex`, `highestWriteCacheIndex`, and a one-byte `FunctionHeaderFlag`.
 *   That is **16 bytes**, not 12; the format `static_assert`s the size divides 32.
 * - `FunctionHeaderFlag` is `prohibitInvoke:2, strictMode:1, hasExceptionHandler:1,
 *   hasDebugInfo:1, overflowed:1`, so the overflow bit is `0x20` at byte `slot + 15`.
 * - An overflowed entry stores its `FunctionHeader` offset as
 *   `(infoOffset << 16) | offset` — built from `infoOffset`, not `functionName` — and that
 *   `FunctionHeader` is a run of full `uint32_t` fields followed by the same flags byte.
 *
 * ## Status
 *
 * The field layout above is taken from the Hermes source and is authoritative, but the byte
 * position of the table has **not** yet been confirmed against a v98 bundle: reading it at
 * byte 128 with a 16-byte stride does not reproduce the function offsets `hermes-decomp`
 * reports for Discord 348.5. Until that is resolved — and checked with [validateContiguity],
 * which uses the invariant that Hermes lays bodies out back to back, so a correct table must
 * satisfy `offset[i] + size[i] == offset[i + 1]` — [locate] returns `null` and the patcher
 * writes nothing.
 *
 * A skipped patch is visible and harmless; a misplaced write corrupts the bundle. Do not
 * reinstate a guessed layout: the previous guess produced a build that installed, launched
 * and crashed.
 */
object HermesFunctionTable {

    /** Where a function's bytecode lives, and how many bytes of it there are. */
    data class FunctionLocation(
        val bodyOffset: Int,
        val bytecodeSize: Int
    )

    /**
     * Returns the location of [functionId] in [bundleBytes], or `null` when the bundle's
     * function table layout has not been verified.
     */
    fun locate(bundleBytes: ByteArray, functionId: Int): FunctionLocation? {
        // No verified layout exists yet, so there is nothing safe to return. See the class
        // documentation for what a layout must satisfy before it can be plugged in here.
        return null
    }

    /**
     * Checks a candidate layout against the bundle's own structure.
     *
     * Hermes stores function bodies contiguously and in function-id order, so a layout is
     * only credible if consecutive entries describe adjacent regions. [sample] entries are
     * checked; any single violation disproves the layout.
     *
     * @param locations function id to the location a candidate layout produced for it.
     */
    fun validateContiguity(locations: Map<Int, FunctionLocation>, sample: Int = 512): Boolean {
        val ids = locations.keys.sorted().take(sample)
        if (ids.size < 2) return false
        for (i in 0 until ids.size - 1) {
            val current = locations[ids[i]] ?: return false
            val next = locations[ids[i + 1]] ?: return false
            if (current.bodyOffset + current.bytecodeSize != next.bodyOffset) return false
        }
        return true
    }
}
