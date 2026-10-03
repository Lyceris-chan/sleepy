package dev.sleepy.app.engine

import java.util.zip.ZipEntry

/**
 * How far an APK entry's data must be aligned, and which entries are exempt.
 *
 * Android requires alignment only for **uncompressed** entries: the data offset of a deflated
 * entry is whatever the compressed stream produced, and nothing maps it directly.
 * `zipalign -c -v 4` on a stock APK labels every compressed entry "(OK - compressed)" and
 * checks only the stored entries, so a checker that applies an `offset % 4` test to every
 * entry reports thousands of failures on a valid APK. An earlier version of [ApkVerifier]
 * did that.
 *
 * The rule is defined here so that the repacker and the verifier stay consistent: the
 * repacker pads by [requiredFor], and the verifier applies the same check.
 */
object ZipAlignment {

    /** Uncompressed entries must start on a 4-byte boundary. */
    const val DEFAULT = 4

    /**
     * Uncompressed native libraries are mapped from the APK with `mmap`, so they need page
     * alignment rather than 4-byte alignment. 16 KiB is the current requirement on
     * 16 KiB-page devices and is a multiple of 4 KiB, so it satisfies both requirements.
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
