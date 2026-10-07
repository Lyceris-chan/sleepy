package dev.sleepy.app.engine

/**
 * Locates a Hermes bytecode function's body and length inside an HBC bundle.
 *
 * Patching a JavaScript function on-device means writing new bytecode over the old, which
 * requires the offset where that function's bytecode starts and its length. Hermes stores
 * both in a function header table that begins immediately after the 128-byte file header.
 *
 * ## The layout
 *
 * For HBC 97 and newer ("Modern12") each entry is a 96-bit word, not the 128-bit
 * `SmallFuncHeader` the format used before 97. Fields are packed across the three 32-bit
 * words like this:
 *
 * ```
 * offset                  bits  0..24   (25)
 * paramCount              bits 25..29   ( 5)
 * loopDepth               bits 30..31   ( 2)
 * bytecodeSizeInBytes     bits 32..45   (14)
 * functionName            bits 46..53   ( 8)
 * numberRegCount          bits 54..58   ( 5)
 * nonPtrRegCount          bits 59..63   ( 5)
 * frameSize               bits 64..71   ( 8)
 * readCacheSize           bits 72..79   ( 8)
 * writeCacheSize          bits 80..85   ( 6)
 * numCacheNewObject       bit  86
 * privateNameCacheSize    bit  87
 * flags                   bits 88..95   ( 8)   overflowed = 0x20
 * ```
 *
 * `offset` and `bytecodeSizeInBytes` give the location for the 1,839 functions small enough
 * to store them directly. The other 153,590 are marked overflowed and store a pointer to a
 * second, wider header instead:
 * `largeOffset = (functionName shl 24) or (offset and 0x00FFFFFF)`, whose record holds
 * `offset` at `+0` and `bytecodeSizeInBytes` at `+12` as full 32-bit fields.
 *
 * ## Verification
 *
 * The offsets returned by [locate] were compared with `hermes-decomp dump --kind functions`
 * for all 155,429 functions of the Discord 349.5 bundle, including the 14 targets that
 * the patch set uses, and the two outputs matched byte for byte. That dump is what this table is checked against:
 * if this file changes, compare the result against a real bundle rather than against the
 * arithmetic alone.
 *
 * Two properties of the format that this implementation accounts for:
 *
 * - The body offsets are **not** stored in the function header table as values that can be
 *   found by searching for them. Only 1,839 of them appear there directly; the rest are in the
 *   large-header table near the end of the file. Finding an offset only in the large-header
 *   table does not mean that the function header table lacks an entry for it.
 * - Hermes bodies are **not** stored contiguously. Only 115,954 of 155,428 consecutive pairs
 *   satisfy `offset[i] + size[i] == offset[i + 1]`, and 6,598 consecutive pairs share a body
 *   exactly. A check that assumes contiguous bodies rejects this layout, so it does not
 *   validate one.
 */
object HermesFunctionTable {

    /** The offset and byte length of a function's bytecode inside the bundle. */
    data class FunctionLocation(
        val bodyOffset: Int,
        val bytecodeSize: Int
    )

    /** `sizeof(BytecodeFileHeader)`—the table follows it immediately. */
    private const val TABLE_OFFSET = 128

    /** HBC 97+ packs an entry into 96 bits. HBC 96 and older used a 128-bit entry. */
    private const val MODERN_ENTRY_SIZE = 12

    /** Lowest HBC version whose entries use the 96-bit packing implemented here. */
    private const val FIRST_MODERN_VERSION = 97

    private const val FLAG_OVERFLOWED = 0x20
    private const val OFFSET_MASK = 0x1FFFFFF
    private const val OVERFLOW_OFFSET_MASK = 0x00FFFFFF
    private const val BYTECODE_SIZE_MASK = 0x3FFF

    /** Offset of the large `FunctionHeader`'s bytecode size, as a 32-bit field. */
    private const val LARGE_HEADER_SIZE_FIELD = 12

    /**
     * Returns the location of [functionId] in [bundleBytes], or `null` when the bundle's
     * version uses a layout that this implementation does not support.
     */
    fun locate(bundleBytes: ByteArray, functionId: Int): FunctionLocation? {
        if (functionId < 0) return null
        if (!usesModernEntrySize(bundleBytes)) return null

        val slot = TABLE_OFFSET + MODERN_ENTRY_SIZE * functionId
        if (slot < 0 || slot + MODERN_ENTRY_SIZE > bundleBytes.size) return null

        val word0 = readU32Le(bundleBytes, slot)
        val word1 = readU32Le(bundleBytes, slot + 4)
        val word2 = readU32Le(bundleBytes, slot + 8)

        val flags = (word2 ushr 24) and 0xFF
        if (flags and FLAG_OVERFLOWED == 0) {
            return FunctionLocation(
                bodyOffset = word0 and OFFSET_MASK,
                bytecodeSize = word1 and BYTECODE_SIZE_MASK
            )
        }

        val functionName = (word1 ushr 14) and 0xFF
        val largeOffset = (functionName shl 24) or (word0 and OVERFLOW_OFFSET_MASK)
        if (largeOffset <= 0 ||
            largeOffset + LARGE_HEADER_SIZE_FIELD + 4 > bundleBytes.size
        ) {
            return null
        }

        val bodyOffset = readU32Le(bundleBytes, largeOffset)
        val bytecodeSize = readU32Le(bundleBytes, largeOffset + LARGE_HEADER_SIZE_FIELD)
        if (bodyOffset < 0 || bytecodeSize < 0) return null
        if (bodyOffset.toLong() + bytecodeSize.toLong() > bundleBytes.size) return null

        return FunctionLocation(bodyOffset, bytecodeSize)
    }

    /** Reads the bytecode version from the file header's `version` field. */
    fun bytecodeVersion(bundleBytes: ByteArray): Int? {
        if (bundleBytes.size < TABLE_OFFSET) return null
        return readU32Le(bundleBytes, 8)
    }

    private fun usesModernEntrySize(bundleBytes: ByteArray): Boolean {
        val version = bytecodeVersion(bundleBytes) ?: return false
        return version >= FIRST_MODERN_VERSION
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
