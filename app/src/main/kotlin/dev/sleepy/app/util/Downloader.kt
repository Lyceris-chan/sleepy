package dev.sleepy.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object Downloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val MAX_SIZE_BYTES = 512L * 1024 * 1024 // 512 MB guard

    /**
     * Downloads an APK into RAM directly, verifying HTTPS and ZIP magic bytes.
     */
    suspend fun download(
        url: String,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        if (url.startsWith("file://", ignoreCase = true)) {
            val file = java.io.File(java.net.URI(url).path)
            if (!file.exists()) throw IOException("Local file not found: ${file.absolutePath}")
            val bytes = file.readBytes()
            if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
                throw IOException("Local file is not a valid APK/ZIP archive (magic header check failed).")
            }
            onProgress(bytes.size.toLong(), bytes.size.toLong())
            return@withContext bytes
        }

        if (!url.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("Insecure URL rejected by security policy: Only HTTPS is permitted.")
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "sleepy-patcher/1.0 (Android)")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Download failed with HTTP ${response.code}: ${response.message}")
        }

        val body = response.body ?: throw IOException("Empty response body from $url")
        val contentLength = body.contentLength()

        if (contentLength > MAX_SIZE_BYTES) {
            throw IOException("File size ($contentLength bytes) exceeds security limit of $MAX_SIZE_BYTES bytes.")
        }

        val estimatedSize = if (contentLength > 0) contentLength.toInt() else 32 * 1024 * 1024
        val byteBuffer = ByteArrayOutputStream(estimatedSize)

        body.byteStream().use { inStream ->
            val chunk = ByteArray(64 * 1024)
            var totalRead = 0L
            var read: Int

            while (inStream.read(chunk).also { read = it } != -1) {
                totalRead += read
                if (totalRead > MAX_SIZE_BYTES) {
                    throw IOException("Download stream exceeded maximum security size of $MAX_SIZE_BYTES bytes.")
                }
                byteBuffer.write(chunk, 0, read)
                onProgress(totalRead, contentLength)
            }
        }

        val bytes = byteBuffer.toByteArray()
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            throw IOException("Downloaded content is not a valid APK/ZIP archive (magic header check failed).")
        }

        bytes
    }
}
