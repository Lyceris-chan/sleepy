package dev.sleepy.app.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipRepacker {

    private val SIGNATURE_ENTRIES = setOf(
        "META-INF/CERT.SF",
        "META-INF/CERT.RSA",
        "META-INF/MANIFEST.MF",
        "META-INF/OCTOGRAM.SF",
        "META-INF/OCTOGRAM.RSA"
    )

    /**
     * Rebuild the APK in-memory, replacing specified files (e.g. patched DEX and bundle),
     * and preserving all STORED vs DEFLATED flags and CRC for resources.arsc byte-identically.
     */
    fun repack(
        inputApkBytes: ByteArray,
        replacements: Map<String, ByteArray>
    ): ByteArray {
        val out = ByteArrayOutputStream(inputApkBytes.size + (8 * 1024 * 1024))

        ZipOutputStream(out).use { zos ->
            ZipInputStream(ByteArrayInputStream(inputApkBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name

                    // Drop old signature files, replaced files, and Discord bundle patch
                    if (name in SIGNATURE_ENTRIES || name in replacements || name == "assets/index.android.bundle.patch" || (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")))) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    val entryData = zis.readBytes()
                    val newEntry = ZipEntry(name).apply {
                        method = entry.method
                        time = entry.time
                        comment = entry.comment
                        extra = entry.extra
                        if (entry.method == ZipEntry.STORED) {
                            size = entryData.size.toLong()
                            compressedSize = entryData.size.toLong()
                            val crcCalculator = CRC32()
                            crcCalculator.update(entryData)
                            crc = crcCalculator.value
                        }
                    }

                    zos.putNextEntry(newEntry)
                    zos.write(entryData)
                    zos.closeEntry()
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Write all replaced files (patched dex files and modified assets)
            for ((name, data) in replacements) {
                val newEntry = ZipEntry(name).apply {
                    method = ZipEntry.DEFLATED
                    time = 347155200000L // Deterministic timestamp
                }
                zos.putNextEntry(newEntry)
                zos.write(data)
                zos.closeEntry()
            }
        }

        return out.toByteArray()
    }
}
