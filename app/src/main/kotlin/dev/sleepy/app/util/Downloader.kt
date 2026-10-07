package dev.sleepy.app.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a source APK into memory.
 *
 * A network URL is rejected unless it uses HTTPS, and the bytes read must start with the ZIP
 * magic number. A size limit applies to both the declared content length and the bytes read from
 * the stream.
 */
object Downloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val MAX_SIZE_BYTES = 512L * 1024 * 1024 // 512 MB guard

    /**
     * Downloads the APK at [url] into a byte array and reports progress through [onProgress].
     *
     * A `file://` URL is read from the local filesystem. Any other URL must use HTTPS. The bytes
     * read must start with the ZIP magic number.
     */
    suspend fun download(
        url: String,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        if (url.startsWith("file://", ignoreCase = true)) {
            val file = java.io.File(java.net.URI(url).path)
            if (!file.exists()) throw IOException("Local file not found: ${file.absolutePath}")
            currentCoroutineContext().ensureActive()
            val bytes = file.readBytes()
            if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
                throw IOException(
                    "Local file is not a valid APK/ZIP archive (magic header check failed)."
                )
            }
            onProgress(bytes.size.toLong(), bytes.size.toLong())
            return@withContext bytes
        }

        if (!url.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException(
                "Insecure URL rejected by security policy: Only HTTPS is permitted."
            )
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "sleepy-patcher/1.0 (Android)")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Download failed with HTTP ${response.code}: ${response.message}")
        }

        val body = response.body
        val contentLength = body.contentLength()

        if (contentLength > MAX_SIZE_BYTES) {
            throw IOException(
                "File size ($contentLength bytes) exceeds security limit of $MAX_SIZE_BYTES bytes."
            )
        }

        val estimatedSize = if (contentLength > 0) contentLength.toInt() else 32 * 1024 * 1024
        val byteBuffer = ByteArrayOutputStream(estimatedSize)

        body.byteStream().use { inStream ->
            val chunk = ByteArray(64 * 1024)
            var totalRead = 0L
            var read: Int

            while (inStream.read(chunk).also { read = it } != -1) {
                // A socket read and a buffer write are both blocking calls with no suspension
                // point of their own, so this loop would never observe a cancel on its own: the
                // transfer would run to the end, and on a 96 MB download over a slow connection
                // that is minutes of pressing Stop with nothing happening.
                currentCoroutineContext().ensureActive()
                totalRead += read
                if (totalRead > MAX_SIZE_BYTES) {
                    throw IOException(
                        "Download stream exceeded maximum security size of $MAX_SIZE_BYTES bytes."
                    )
                }
                byteBuffer.write(chunk, 0, read)
                onProgress(totalRead, contentLength)
            }
        }

        val bytes = byteBuffer.toByteArray()
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            throw IOException(
                "Downloaded content is not a valid APK/ZIP archive (magic header check failed)."
            )
        }

        bytes
    }
}
