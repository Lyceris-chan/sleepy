package dev.sleepy.app.engine

import dev.sleepy.app.util.Downloader
import dev.sleepy.app.util.HashUtils
import java.io.File

/**
 * The SHA-256 comparison behind a source download.
 *
 * [verified] is null when the source publishes no hash, because no comparison exists to report.
 * It is true only when the bytes that arrived hash to the published value, and false when they do
 * not. [of] is the only way to build one, so a report cannot carry a verdict without a
 * comparison.
 */
internal class SourceIntegrity private constructor(
    /** The SHA-256 the source publishes, or null when it publishes none. */
    val expectedSha256: String?,
    /** The SHA-256 computed from the downloaded bytes, or null when no hash was published. */
    val actualSha256: String?,
    /** The size of the downloaded file in bytes. */
    val sizeBytes: Long,
    /** Whether the download matched the published hash, or null when none was published. */
    val verified: Boolean?
) {
    companion object {

        /** Compares [bytes] with [expectedSha256] and records the result. */
        fun of(expectedSha256: String?, bytes: ByteArray): SourceIntegrity =
            if (expectedSha256 == null) {
                SourceIntegrity(
                    expectedSha256 = null,
                    actualSha256 = null,
                    sizeBytes = bytes.size.toLong(),
                    verified = null
                )
            } else {
                val actualSha256 = HashUtils.sha256Hex(bytes)
                SourceIntegrity(
                    expectedSha256 = expectedSha256,
                    actualSha256 = actualSha256,
                    sizeBytes = bytes.size.toLong(),
                    verified = actualSha256.equals(expectedSha256, ignoreCase = true)
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
