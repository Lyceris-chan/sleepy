package dev.sleepy.app.engine

import java.util.zip.ZipEntry

/**
 * How far an APK entry's data must be aligned, and which entries are exempt.
 *
 * Android only requires alignment for **uncompressed** entries: a deflated entry's data
 * offset is whatever the compressed stream produced and nothing maps it directly. This is
 * easy to get backwards. `zipalign -c -v 4` on a stock APK labels every compressed entry
 * "(OK - compressed)" and checks only the stored ones, so a checker that tests `offset % 4`
 * across the board reports thousands of failures on a perfectly valid APK — which is exactly
 * what an earlier version of [ApkVerifier] did.
 *
 * The rule lives here so the repacker and the verifier cannot drift apart: the repacker pads
 * by [requiredFor] and the verifier asserts it.
 */
object ZipAlignment {

    /** Uncompressed entries must start on a 4-byte boundary. */
    const val DEFAULT = 4

    /**
     * Uncompressed native libraries are mapped straight out of the APK with `mmap`, so they
     * need page alignment, not 4-byte alignment. 16 KiB is the current requirement on
     * 16 KiB-page devices and is a multiple of 4 KiB, so it satisfies both.
     */
    const val NATIVE_LIBRARY = 16384

    private const val LIB_PREFIX = "lib/"
    private const val LIB_SUFFIX = ".so"

    /**
     * Bytes the entry's data offset must be a multiple of, or `0` when the entry is exempt.
     */
    fun requiredFor(name: String, method: Int): Int = when {
        method != ZipEntry.STORED -> 0
        name.startsWith(LIB_PREFIX) && name.endsWith(LIB_SUFFIX) -> NATIVE_LIBRARY
        else -> DEFAULT
    }
}
