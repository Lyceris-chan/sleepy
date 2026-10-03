package dev.sleepy.app.engine

import dev.sleepy.app.model.SplitSource
import dev.sleepy.app.util.Downloader
import dev.sleepy.app.util.HashUtils
import java.io.File

/**
 * The integrity comparison behind a source or split download.
 *
 * [verified] is null when the source publishes no hash, because no comparison exists to report.
 * It is true only when the bytes that arrived hash to the published value, and false when they do
 * not. [sizeMatches] is null when the source publishes no size, and otherwise records whether the
 * downloaded size equals it. [of] is the only way to build one, so a report cannot carry a
 * verdict without a comparison.
 */
internal class SourceIntegrity private constructor(
    /** The SHA-256 the source publishes, or null when it publishes none. */
    val expectedSha256: String?,
    /** The SHA-256 computed from the downloaded bytes, or null when no hash was published. */
    val actualSha256: String?,
    /** The size of the downloaded file in bytes. */
    val sizeBytes: Long,
    /** The size the source publishes, or null when it publishes none. */
    val expectedSizeBytes: Long?,
    /** Whether the download matched the published hash, or null when none was published. */
    val verified: Boolean?,
    /** Whether the downloaded size equals the published size, or null when none was published. */
    val sizeMatches: Boolean?
) {
    companion object {

        /** Compares [bytes] with [expectedSha256] and [expectedSizeBytes] and records the result. */
        fun of(
            expectedSha256: String?,
            bytes: ByteArray,
            expectedSizeBytes: Long? = null
        ): SourceIntegrity {
            val actualSha256 = expectedSha256?.let { HashUtils.sha256Hex(bytes) }
            val actualSize = bytes.size.toLong()
            return SourceIntegrity(
                expectedSha256 = expectedSha256,
                actualSha256 = actualSha256,
                sizeBytes = actualSize,
                expectedSizeBytes = expectedSizeBytes,
                verified = actualSha256?.equals(expectedSha256, ignoreCase = true),
                sizeMatches = expectedSizeBytes?.let { it == actualSize }
            )
        }
    }
}

/**
 * Downloads [url] and reports how the bytes compare with [expectedSha256].
 *
 * The comparison runs here, where the bytes are, so a caller records the result of a comparison
 * rather than the presence of a hash field. Bytes that do not match a published hash are not
 * written to [destination]: the caller stops the run and reports both hashes instead of patching
 * a file the source does not describe.
 */
internal suspend fun fetchSourceApk(
    url: String,
    expectedSha256: String?,
    destination: File,
    onProgress: suspend (Long, Long) -> Unit
): SourceIntegrity {
    val bytes = Downloader.download(url, onProgress)
    val integrity = SourceIntegrity.of(expectedSha256, bytes)
    if (integrity.verified != false) {
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
    }
    return integrity
}

/**
 * Downloads the configuration split [split] describes and reports how the bytes compare with the
 * hash and size the source publishes for it.
 *
 * A split that does not match a published hash or size is not written to [destination]: the
 * caller stops the run and reports the comparison rather than merging a file the source does not
 * describe. The caller supplies the destination because a split is tens of megabytes and the
 * merge reads it from disk.
 */
internal suspend fun fetchSplitApk(split: SplitSource, destination: File): SourceIntegrity {
    val bytes = Downloader.download(split.url) { _, _ -> }
    val integrity = SourceIntegrity.of(split.sha256Expected, bytes, split.sizeBytes)
    if (integrity.verified != false && integrity.sizeMatches != false) {
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
    }
    return integrity
}
