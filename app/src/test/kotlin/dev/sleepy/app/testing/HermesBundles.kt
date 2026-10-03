package dev.sleepy.app.testing

/**
 * Readers the Hermes bundle parity tests share.
 *
 * A parity test compares two bundles region by region and reports the first byte that differs;
 * both the full-table test and the per-item subset test do it the same way, so the comparison
 * lives once.
 */

/** Whether `length` bytes at [aOffset] of [a] equal the same span at [bOffset] of [b]. */
fun regionsEqual(
    a: ByteArray,
    aOffset: Int,
    b: ByteArray,
    bOffset: Int,
    length: Int
): Boolean {
    for (index in 0 until length) {
        if (a[aOffset + index] != b[bOffset + index]) return false
    }
    return true
}

/** Where the two spans first differ, as an offset and the two byte values, or "no difference". */
fun firstDifference(
    a: ByteArray,
    aOffset: Int,
    b: ByteArray,
    bOffset: Int,
    length: Int
): String {
    for (index in 0 until length) {
        if (a[aOffset + index] != b[bOffset + index]) {
            return "$index (${(a[aOffset + index].toInt() and 0xFF).toString(16)} vs " +
                "${(b[bOffset + index].toInt() and 0xFF).toString(16)})"
        }
    }
    return "no difference"
}

/** The little-endian 32-bit word at [offset] of [bytes]. */
fun readU32Le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
