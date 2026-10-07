package dev.sleepy.app.engine

import dev.sleepy.app.patches.DiscordHermesBundlePatch.FunctionPatch
import java.security.MessageDigest

/**
 * Writes whole recorded-build function bodies into a Hermes bundle.
 *
 * A function body is a fixed-size region of the bundle: the function header specifies where it
 * starts and how long it is, and nothing else in the file addresses its interior. Replacement
 * therefore depends on size alone. When the recorded body is no longer than the one the
 * bundle already has, the patcher writes it over the old one and fills the remainder—which
 * sits after the body's final `Ret` and is unreachable—with `AsyncBreakCheck` (`0x7E`, a
 * valid one-byte zero-operand opcode in HBC 98), so that no part of the old function's
 * instructions remains. When the recorded body is longer, the patcher appends it to the end
 * of the file behind a large `FunctionHeader` and re-points the function's 96-bit table entry
 * at it, because the function's original region cannot contain the extra bytes.
 *
 * ## Bodies are declared at the recorded build's size, not at the bundle's
 *
 * A replacement is usually shorter than the body it replaces, and the recorded build declares
 * the shorter extent: function 13894's body is 186 bytes in the base bundle and 6 in the
 * recorded build. The declaration is therefore narrowed to the replacement's length, in the 96-bit
 * entry's `bytecodeSizeInBytes` bits or in the large header's `+12` slot, as the recorded change
 * set does. Padding without narrowing the declaration also runs identically, because the padding
 * is unreachable, but it leaves 189 of the patched functions declaring a body longer than the one
 * the recorded build declares for them, and the two bundles cannot then be compared function for
 * function—the comparison this patcher is designed to produce. Applied to the Discord 349.5
 * bundle, the patcher produces 155,427 function bodies that are byte-identical to the
 * recorded build's; [dev.sleepy.app.patches.DiscordHermesBundlePatch] records why the other two
 * differ on purpose.
 *
 * ## One body, two functions
 *
 * Functions 62045 and 62046 (`isVirtualCurrencyEnabled`, `useVirtualCurrencyMobileEnabled`)
 * share a single 47-byte body in this bundle but have different replacements, 47 and 19 bytes.
 * One write cannot serve both, so the longer replacement keeps the shared region and the other
 * function is relocated: its own 19 bytes are written at the end of the file, where its large
 * header declares that size. Nothing is overwritten and both bodies are identical to the
 * recorded build's; writing both into the shared region overwrites one of them with
 * `AsyncBreakCheck`.
 *
 * ## What is not invented
 *
 * A relocated function needs a large `FunctionHeader` (37 bytes: nine 32-bit fields, then a
 * flags byte). Every function relocated here already has one, because a body past 2^25 or
 * longer than 16,383 bytes needs a large header. The patcher copies that header from the
 * function itself, so the fields it does not name—the flags byte and the word at `+32`—
 * keep the bundle's own values rather than values invented by the patcher; only `+0 offset` and
 * `+12 bytecodeSizeInBytes` are written. A function small enough to be encoded entirely in its
 * 96-bit entry has no header to copy: the patcher then builds one over a byte template taken
 * from any other overflowed function, with the entry's packed fields unpacked into its 32-bit
 * slots ([largeHeader]). That path follows the format but is unexercised by the Discord patch
 * set, which relocates only functions that already have a header.
 *
 * ## Ordering
 *
 * The output is the input's bytes up to the old footer, then the relocated bodies and headers
 * four-byte aligned, then a fresh SHA-1 footer, with `fileLength` increased to cover the
 * appended bytes. Anything the input carried past `fileLength` is dropped: it is not part of
 * the bundle. The input array is not modified, and when no patch applies the patcher returns
 * it unchanged.
 */
object HermesBundlePatcher {

    /** `offsetof(BytecodeFileHeader, fileLength)`. */
    private const val FILE_LENGTH_OFFSET = 32

    /** `offsetof(BytecodeFileHeader, functionCount)`. */
    private const val FUNCTION_COUNT_OFFSET = 40

    /** The file closes with a SHA-1 of everything that precedes it. */
    private const val SHA1_FOOTER_SIZE = 20

    /** Lowest HBC version whose function entries use the 96-bit Modern12 packing. */
    private const val FIRST_MODERN_VERSION = 97

    /** First function entry, immediately after the 128-byte file header. */
    private const val ENTRY_TABLE_OFFSET = 128

    /** Bytes per entry. */
    private const val ENTRY_SIZE = 12

    private const val FLAG_OVERFLOWED = 0x20
    private const val OVERFLOW_OFFSET_MASK = 0x00FFFFFF
    private const val BYTECODE_SIZE_MASK = 0x3FFF

    /** `functionName` in the 96-bit entry doubles as the high byte of a large header pointer. */
    private const val FUNCTION_NAME_SHIFT = 14
    private const val FUNCTION_NAME_MASK = 0xFF

    private const val PARAM_COUNT_SHIFT = 25
    private const val PARAM_COUNT_MASK = 0x1F
    private const val LOOP_DEPTH_SHIFT = 30
    private const val LOOP_DEPTH_MASK = 0x3
    private const val NUM_REG_COUNT_SHIFT = 22
    private const val NUM_REG_COUNT_MASK = 0x1F
    private const val NON_PTR_REG_COUNT_SHIFT = 27
    private const val NON_PTR_REG_COUNT_MASK = 0x1F
    private const val FRAME_SIZE_MASK = 0xFF

    /** Legacy `FunctionHeader`: nine 32-bit fields, then a flags byte. */
    private const val LARGE_HEADER_SIZE = 37
    private const val LARGE_HEADER_OFFSET_FIELD = 0
    private const val LARGE_HEADER_PARAM_COUNT_FIELD = 4
    private const val LARGE_HEADER_LOOP_DEPTH_FIELD = 8
    private const val LARGE_HEADER_SIZE_FIELD = 12
    private const val LARGE_HEADER_FUNCTION_NAME_FIELD = 16
    private const val LARGE_HEADER_NUM_REG_COUNT_FIELD = 20
    private const val LARGE_HEADER_NON_PTR_REG_COUNT_FIELD = 24
    private const val LARGE_HEADER_FRAME_SIZE_FIELD = 28

    /** Appended bodies and headers start on a four-byte boundary. */
    private const val APPEND_ALIGNMENT = 4

    /** Filler for the bytes a shorter replacement leaves behind. */
    private const val ASYNC_BREAK_CHECK: Byte = 0x7E

    /** What became of one patch. */
    enum class Action { WRITTEN_IN_PLACE, RELOCATED, SKIPPED }

    /** One patch's fate, by function id. */
    data class PatchOutcome(
        val functionId: Int,
        val name: String,
        val action: Action,
        /** A description of the outcome, with byte counts and offsets where they apply. */
        val detail: String
    )

    /** The patched bundle and what became of every patch, in the order the patches were given. */
    class Result(
        val bundleBytes: ByteArray,
        val outcomes: List<PatchOutcome>
    ) {
        val writtenInPlace: List<PatchOutcome>
            get() = outcomes.filter { it.action == Action.WRITTEN_IN_PLACE }
        val relocated: List<PatchOutcome> get() = outcomes.filter { it.action == Action.RELOCATED }
        val skipped: List<PatchOutcome> get() = outcomes.filter { it.action == Action.SKIPPED }

        /** Patches that were applied, in place or by relocation. */
        val appliedCount: Int get() = outcomes.size - skipped.size
    }

    /**
     * Applies every patch that fits, and reports why the rest did not.
     *
     * A patch is skipped rather than forced when the function is not in the bundle's table, or
     * when the body found there is not the size the patch was extracted from—that second case
     * means the bundle is not the build this patch set targets, and writing anyway overwrites
     * whatever function occupies that offset.
     */
    fun apply(bundleBytes: ByteArray, patches: List<FunctionPatch>): Result {
        if (!HermesPatcher.isHermesBytecode(bundleBytes)) {
            return skipAll(bundleBytes, patches, "asset is not a Hermes bytecode bundle")
        }

        val version = HermesFunctionTable.bytecodeVersion(bundleBytes)
        if (version == null || version < FIRST_MODERN_VERSION) {
            return skipAll(
                bundleBytes,
                patches,
                "Hermes bytecode version $version has a function header layout this patcher does " +
                    "not implement; it writes the HBC $FIRST_MODERN_VERSION 96-bit entries"
            )
        }

        val fileLength = readU32Le(bundleBytes, FILE_LENGTH_OFFSET)
        if (fileLength < ENTRY_TABLE_OFFSET + SHA1_FOOTER_SIZE || fileLength > bundleBytes.size) {
            return skipAll(
                bundleBytes,
                patches,
                "the file header declares $fileLength bytes, which is not a bundle of " +
                    "${bundleBytes.size} bytes"
            )
        }

        // Every location comes from the input as it stands, before anything is written: a
        // relocation rewrites the table entry it moves, and the patches are independent.
        val outcomes = arrayOfNulls<PatchOutcome>(patches.size)
        val plans = ArrayList<Plan>(patches.size)
        for ((patchIndex, patch) in patches.withIndex()) {
            val location = HermesFunctionTable.locate(bundleBytes, patch.functionId)
            if (location == null) {
                outcomes[patchIndex] = skip(
                    patch,
                    "function ${patch.functionId} is not in this bundle's function table"
                )
                continue
            }
            if (location.bytecodeSize != patch.originalSize) {
                outcomes[patchIndex] = skip(
                    patch,
                    "function ${patch.functionId} is ${location.bytecodeSize} bytes in this " +
                        "bundle but the patch was extracted from a ${patch.originalSize}-byte " +
                        "body, so this bundle is not the build the patch set targets"
                )
                continue
            }
            plans += Plan(
                patchIndex,
                patch,
                patch.replacement,
                location.bodyOffset,
                location.bytecodeSize
            )
        }

        // Nothing to write: the patcher returns the bundle as it came in, including the footer,
        // rather than re-emitting it byte for byte.
        if (plans.isEmpty()) return resultOf(bundleBytes, outcomes)

        // A replacement larger than the body cannot be written over it, and a replacement
        // written over a body another patch also claims cannot serve both.
        val relocated = BooleanArray(plans.size)
        for (index in plans.indices) {
            if (plans[index].replacement.size > plans[index].bytecodeSize) relocated[index] = true
        }
        for (group in overlappingGroups(plans)) {
            val winner = group
                .sortedWith(
                    compareByDescending<Int> { plans[it].replacement.size }
                        .thenBy { plans[it].patch.functionId }
                )
                .first()
            for (index in group) {
                if (index != winner) relocated[index] = true
            }
        }

        val appends =
            layoutAppends(fileLength, plans.indices.filter { relocated[it] }.map { plans[it] })
        val output = ByteArray(appends.newFileLength)
        System.arraycopy(bundleBytes, 0, output, 0, fileLength - SHA1_FOOTER_SIZE)

        for (index in plans.indices) {
            val plan = plans[index]
            if (relocated[index]) continue
            plan.writeInPlace(output)
            outcomes[plan.patchIndex] = PatchOutcome(
                plan.patch.functionId,
                plan.patch.name,
                Action.WRITTEN_IN_PLACE,
                "${plan.replacement.size} bytes written at ${plan.bodyOffset}, " +
                    "${plan.bytecodeSize - plan.replacement.size} padded with AsyncBreakCheck, " +
                    "declared as ${plan.replacement.size} bytes instead of ${plan.bytecodeSize}"
            )
        }

        for (append in appends.entries) {
            val plan = append.plan
            System.arraycopy(plan.replacement, 0, output, append.bodyOffset, plan.replacement.size)
            val header = largeHeader(output, plan, append.bodyOffset)
            System.arraycopy(header, 0, output, append.headerOffset, LARGE_HEADER_SIZE)
            repoint(output, plan.patch.functionId, append.headerOffset)
            outcomes[plan.patchIndex] = PatchOutcome(
                plan.patch.functionId,
                plan.patch.name,
                Action.RELOCATED,
                "${plan.replacement.size} bytes appended at ${append.bodyOffset} behind a " +
                    "large header at ${append.headerOffset}, replacing " +
                    if (plan.replacement.size > plan.bytecodeSize) {
                        "a ${plan.bytecodeSize}-byte body that cannot grow in place"
                    } else {
                        "a ${plan.bytecodeSize}-byte body shared with another patched function"
                    }
            )
        }

        writeU32Le(output, FILE_LENGTH_OFFSET, appends.newFileLength)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(output, 0, appends.newFileLength - SHA1_FOOTER_SIZE)
        System.arraycopy(
            digest.digest(),
            0,
            output,
            appends.newFileLength - SHA1_FOOTER_SIZE,
            SHA1_FOOTER_SIZE
        )

        return resultOf(output, outcomes)
    }

    /** A patch to write, resolved against the input bundle. */
    private class Plan(
        val patchIndex: Int,
        val patch: FunctionPatch,
        val replacement: ByteArray,
        val bodyOffset: Int,
        val bytecodeSize: Int
    ) {
        /**
         * Writes the replacement over the old body, pads the remainder, and narrows the
         * declaration to the replacement. The same flag that the bundle's own reader checks
         * selects the field to narrow: an overflowed function keeps its size in the large
         * header's `+12` slot, and the rest keep it in the 96-bit entry's bits 0..13.
         */
        fun writeInPlace(output: ByteArray) {
            System.arraycopy(replacement, 0, output, bodyOffset, replacement.size)
            for (offset in (bodyOffset + replacement.size) until (bodyOffset + bytecodeSize)) {
                output[offset] = ASYNC_BREAK_CHECK
            }

            val words = entryWords(output, patch.functionId)
            if (words.overflowed) {
                writeU32Le(
                    output,
                    words.largeHeaderOffset + LARGE_HEADER_SIZE_FIELD,
                    replacement.size
                )
            } else {
                writeEntry(
                    output,
                    patch.functionId,
                    words.w0,
                    (words.w1 and BYTECODE_SIZE_MASK.inv()) or replacement.size,
                    words.w2
                )
            }
        }
    }

    private class Append(val plan: Plan, val bodyOffset: Int, val headerOffset: Int)

    private class Appends(val entries: List<Append>, val newFileLength: Int)

    /**
     * Lays relocated bodies and headers out after the input's last real byte—which is where
     * the 20-byte footer began—aligning each, and returns the file length they imply.
     */
    private fun layoutAppends(fileLength: Int, relocated: List<Plan>): Appends {
        var cursor = fileLength - SHA1_FOOTER_SIZE
        val entries = ArrayList<Append>(relocated.size)
        for (plan in relocated.sortedBy { it.patch.functionId }) {
            cursor = alignUp(cursor)
            val bodyOffset = cursor
            cursor = alignUp(cursor + plan.replacement.size)
            val headerOffset = cursor
            cursor += LARGE_HEADER_SIZE
            entries += Append(plan, bodyOffset, headerOffset)
        }
        return Appends(entries, cursor + SHA1_FOOTER_SIZE)
    }

    /**
     * The 37-byte large `FunctionHeader` describing a relocated body.
     *
     * A function's own header is copied when it has one, so the fields this patcher does not
     * write keep the bundle's values. A function encoded entirely in its 96-bit entry has none
     * to copy and gets one over a template from an overflowed function instead, with the entry's
     * packed fields unpacked into their 32-bit slots; the cache sizes and flags that the entry
     * stores but the wide header has no separate slots for then come from that template rather
     * than from zeroes.
     */
    private fun largeHeader(state: ByteArray, plan: Plan, bodyOffset: Int): ByteArray {
        val words = entryWords(state, plan.patch.functionId)
        val header = ByteArray(LARGE_HEADER_SIZE)
        if (words.overflowed) {
            System.arraycopy(state, words.largeHeaderOffset, header, 0, LARGE_HEADER_SIZE)
        } else {
            val template = firstOverflowedHeader(state)
            if (template >= 0) {
                System.arraycopy(state, template, header, 0, LARGE_HEADER_SIZE)
            }
            writeU32Le(
                header,
                LARGE_HEADER_PARAM_COUNT_FIELD,
                (words.w0 ushr PARAM_COUNT_SHIFT) and PARAM_COUNT_MASK
            )
            writeU32Le(
                header,
                LARGE_HEADER_LOOP_DEPTH_FIELD,
                (words.w0 ushr LOOP_DEPTH_SHIFT) and LOOP_DEPTH_MASK
            )
            writeU32Le(
                header,
                LARGE_HEADER_FUNCTION_NAME_FIELD,
                (words.w1 ushr FUNCTION_NAME_SHIFT) and FUNCTION_NAME_MASK
            )
            writeU32Le(
                header,
                LARGE_HEADER_NUM_REG_COUNT_FIELD,
                (words.w1 ushr NUM_REG_COUNT_SHIFT) and NUM_REG_COUNT_MASK
            )
            writeU32Le(
                header,
                LARGE_HEADER_NON_PTR_REG_COUNT_FIELD,
                (words.w1 ushr NON_PTR_REG_COUNT_SHIFT) and NON_PTR_REG_COUNT_MASK
            )
            writeU32Le(header, LARGE_HEADER_FRAME_SIZE_FIELD, words.w2 and FRAME_SIZE_MASK)
        }
        writeU32Le(header, LARGE_HEADER_OFFSET_FIELD, bodyOffset)
        writeU32Le(header, LARGE_HEADER_SIZE_FIELD, plan.replacement.size)
        return header
    }

    /**
     * Points a function's 96-bit entry at a large header: the overflow flag, and the 32 bits the
     * pointer is split across—`offset`'s low 24 bits and `functionName`'s 8.
     */
    private fun repoint(state: ByteArray, functionId: Int, headerOffset: Int) {
        val words = entryWords(state, functionId)
        val w0 =
            (words.w0 and OVERFLOW_OFFSET_MASK.inv()) or (headerOffset and OVERFLOW_OFFSET_MASK)
        val w1 = (words.w1 and BYTECODE_SIZE_MASK.inv() and
            (FUNCTION_NAME_MASK shl FUNCTION_NAME_SHIFT).inv()) or
            (((headerOffset ushr 24) and FUNCTION_NAME_MASK) shl FUNCTION_NAME_SHIFT)
        writeEntry(state, functionId, w0, w1, words.w2 or (FLAG_OVERFLOWED shl 24))
    }

    /** Offset of the large header of the first overflowed function in the table, or -1. */
    private fun firstOverflowedHeader(state: ByteArray): Int {
        val functionCount = readU32Le(state, FUNCTION_COUNT_OFFSET)
        for (functionId in 0 until functionCount) {
            if (ENTRY_TABLE_OFFSET + ENTRY_SIZE * (functionId + 1) > state.size) return -1
            val words = entryWords(state, functionId)
            if (words.overflowed && words.largeHeaderOffset + LARGE_HEADER_SIZE <= state.size) {
                return words.largeHeaderOffset
            }
        }
        return -1
    }

    /**
     * Index ranges of plans whose bodies overlap, so that at most one of each range can be
     * written where it is. Overlap is common in this bundle: 6,598 consecutive pairs of its
     * functions share a body exactly, and the Discord patch set carries two such pairs.
     */
    private fun overlappingGroups(plans: List<Plan>): List<List<Int>> {
        val order = plans.indices.sortedBy { plans[it].bodyOffset }
        val groups = ArrayList<List<Int>>()
        var start = 0
        while (start < order.size) {
            var end = start + 1
            var reach = plans[order[start]].bodyOffset + plans[order[start]].bytecodeSize
            while (end < order.size && plans[order[end]].bodyOffset < reach) {
                reach = maxOf(reach, plans[order[end]].bodyOffset + plans[order[end]].bytecodeSize)
                end++
            }
            if (end - start > 1) groups += order.subList(start, end).toList()
            start = end
        }
        return groups
    }

    /** The three 32-bit words of a Modern12 entry, and what they point at. */
    private class EntryWords(val w0: Int, val w1: Int, val w2: Int) {
        val overflowed: Boolean get() = ((w2 ushr 24) and 0xFF) and FLAG_OVERFLOWED != 0

        /**
         * `(functionName shl 24) or (offset and 0x00FFFFFF)`, as [HermesFunctionTable] reads it.
         */
        val largeHeaderOffset: Int
            get() =
                (((w1 ushr FUNCTION_NAME_SHIFT) and FUNCTION_NAME_MASK) shl 24) or
                    (w0 and OVERFLOW_OFFSET_MASK)
    }

    private fun entryWords(state: ByteArray, functionId: Int): EntryWords {
        val slot = ENTRY_TABLE_OFFSET + ENTRY_SIZE * functionId
        return EntryWords(
            readU32Le(state, slot),
            readU32Le(state, slot + 4),
            readU32Le(state, slot + 8)
        )
    }

    private fun writeEntry(state: ByteArray, functionId: Int, w0: Int, w1: Int, w2: Int) {
        val slot = ENTRY_TABLE_OFFSET + ENTRY_SIZE * functionId
        writeU32Le(state, slot, w0)
        writeU32Le(state, slot + 4, w1)
        writeU32Le(state, slot + 8, w2)
    }

    private fun skipAll(
        bundleBytes: ByteArray,
        patches: List<FunctionPatch>,
        reason: String
    ): Result =
        Result(bundleBytes, patches.map { skip(it, reason) })

    private fun resultOf(bundleBytes: ByteArray, outcomes: Array<PatchOutcome?>): Result =
        Result(bundleBytes, outcomes.map { it ?: error("a patch was neither applied nor skipped") })

    private fun skip(patch: FunctionPatch, reason: String): PatchOutcome =
        PatchOutcome(patch.functionId, patch.name, Action.SKIPPED, reason)

    private fun alignUp(offset: Int): Int =
        (offset + APPEND_ALIGNMENT - 1) / APPEND_ALIGNMENT * APPEND_ALIGNMENT

    private fun readU32Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeU32Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
