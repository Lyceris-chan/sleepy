package dev.sleepy.app.testing

import dev.sleepy.app.engine.ApkVerifier
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP builders the repack tests share.
 *
 * A repack is checked on the bytes it produces, so the tests build the smallest archives that
 * carry the entries they make claims about: [zipOf] for compressed entries, [zipOfStored] for the
 * stored ones whose alignment is the point, and [entryMethods] to read the compression method of
 * every entry back out of the central directory.
 */

/** A ZIP holding [entries], each written with the default compression method. */
fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
        for ((name, data) in entries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(data)
            zos.closeEntry()
        }
    }
    return out.toByteArray()
}

/** A ZIP whose entries are all STORED, as a real APK's `resources.arsc` is. */
fun zipOfStored(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
        for ((name, data) in entries) {
            val crc = CRC32().apply { update(data) }
            val entry = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = data.size.toLong()
                compressedSize = data.size.toLong()
                this.crc = crc.value
            }
            zos.putNextEntry(entry)
            zos.write(data)
            zos.closeEntry()
        }
    }
    return out.toByteArray()
}

/** Every entry of the archive with the compression method its directory entry records. */
fun entryMethods(apkBytes: ByteArray): Map<String, Int> {
    val methods = mutableMapOf<String, Int>()
    ApkVerifier.readCentralDirectory(apkBytes) { name, _, method -> methods[name] = method }
    return methods
}
