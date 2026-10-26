package dev.sleepy.app.engine

/**
 * Locates a Hermes bytecode function's body and length inside an HBC bundle.
 *
 * The function header table starts after the 128-byte file header. For HBC 97 and newer
 * ("Modern12") each entry is three 32-bit words:
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
 * `offset` and `bytecodeSizeInBytes` locate the functions small enough to store them; the rest
 * are marked overflowed and point at a wide header holding `offset` at `+0` and
 * `bytecodeSizeInBytes` at `+12`.
 *
 * [locate] was checked against `hermes-decomp dump --kind functions` for all 160,128 functions
 * of the Discord 353.2.0 bundle. Bodies are not contiguous, and an offset that is missing from
 * the header table is still located through the large-header table, so neither property is a
 * valid check.
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
    /**
     * `SmallFuncHeader::Offset`, which is 25 bits in both of the header's states: the body's
     * address when the entry is not overflowed, and the low bits of the large header's address
     * when it is. Hermes reads `(getFunctionName() << 24) | getOffset()`, so both are masked
     * with this rather than with the 24 bits its writer happens to fill.
     */
    private const val OFFSET_MASK = 0x1FFFFFF
    private const val OVERFLOW_OFFSET_MASK = OFFSET_MASK
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
