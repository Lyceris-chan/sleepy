package dev.sleepy.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Saves a finished APK to the device's public Downloads folder. */
object FileUtils {

    /**
     * Saves [sourceFile] to the public Downloads folder under [displayName], and returns the URI
     * of the saved file, or null when the save did not complete.
     *
     * Suspends on the IO dispatcher. The file is a whole patched APK, over a hundred megabytes,
     * and the copy is a plain stream-to-stream one, so running it on the caller's thread would
     * hold the interface still for as long as the copy takes.
     *
     * Below Android 10 there is no scoped storage, so the copy goes to a public path the platform
     * refuses unless the app holds `WRITE_EXTERNAL_STORAGE`. sleepy does not ask for it, so that
     * path fails rather than taking the process with it, and the caller can offer Share instead.
     */
    suspend fun saveApkToDownloads(
        context: Context,
        sourceFile: File,
        displayName: String
    ): Uri? = withContext(Dispatchers.IO) {
        val fileName = if (displayName.endsWith(".apk")) displayName else "$displayName.apk"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/sleepy")
            }

            val uri = try {
                context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                // Reporting a save that did not happen is the whole point of the return value, so
                // a failure here is an answer rather than something to propagate.
                null
            } ?: return@withContext null

            // A stream that cannot be opened leaves a row behind and no file. The row goes with
            // it, so the Downloads entry the user sees and the file on disk stay in step.
            val outStream = try {
                context.contentResolver.openOutputStream(uri)
            } catch (e: Exception) {
                null
            }
            if (outStream == null) {
                context.contentResolver.delete(uri, null, null)
                return@withContext null
            }

            outStream.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            }
            uri
        } else {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val sleepyDir = File(downloadsDir, "sleepy").apply { mkdirs() }
                val destFile = File(sleepyDir, fileName)
                FileInputStream(sourceFile).use { inStream ->
                    FileOutputStream(destFile).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                Uri.fromFile(destFile)
            } catch (e: Exception) {
                null
            }
        }
    }
}
