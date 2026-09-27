package dev.sleepy.app.util

import java.io.File
import java.security.MessageDigest

object HashUtils {
    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * The same digest for a file, read a chunk at a time.
     *
     * A finished APK is over a hundred megabytes and is already on disk, so the hash the user
     * is shown comes from the file rather than from a copy of it.
     */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val chunk = ByteArray(64 * 1024)
            var read = input.read(chunk)
            while (read >= 0) {
                if (read > 0) digest.update(chunk, 0, read)
                read = input.read(chunk)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
