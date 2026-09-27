package dev.sleepy.app.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Rebuilds an APK in memory, replacing and appending entries while keeping the archive
 * byte-compatible with what the platform expects.
 *
 * Two properties matter and are easy to lose when repacking:
 *
 * 1. **Alignment.** Uncompressed entries — `resources.arsc` above all — must start on a
 *    4-byte boundary. Re-deflating anything shifts every following offset, so the padding
 *    inherited from the source APK is wrong the moment one entry changes size. Alignment
 *    is therefore recomputed from the real output position for every entry.
 * 2. **Storage method.** An entry that the source APK stored uncompressed stays
 *    uncompressed, so a bundled asset does not silently change its on-device access
 *    pattern. Native libraries are the exception: they arrive uncompressed in an ABI
 *    split, and callers that deflate them must also flip `android:extractNativeLibs`;
 *    see [BinaryXmlEditor.makeStandaloneManifest].
 */
object ZipRepacker {

    /** An entry to append that was not present in the source APK (e.g. a merged split library). */
    data class AdditionalEntry(
        val data: ByteArray,
        val method: Int = ZipEntry.DEFLATED
    )

    /** Reports what the rebuild actually did, so the pipeline can log it truthfully. */
    data class RepackResult(
        val bytes: ByteArray,
        val replacedEntries: List<String>,
        val addedEntries: List<String>,
        val droppedEntries: List<String>
    )

    /** Android's `zipalign` padding field identifier, written little-endian. */
    private const val ALIGNMENT_EXTRA_ID = 0xd935

    private const val DETERMINISTIC_TIME = 347155200000L

    private val SIGNATURE_ENTRIES = setOf(
        "META-INF/CERT.SF",
        "META-INF/CERT.RSA",
        "META-INF/MANIFEST.MF",
        "META-INF/OCTOGRAM.SF",
        "META-INF/OCTOGRAM.RSA"
    )

    /** Superseded artefacts that must not survive into a repacked APK. */
    private val DROPPED_ENTRIES = setOf("assets/index.android.bundle.patch")

    /**
     * @param replacements entry name -> new contents, written with the source entry's own
     *   storage method (DEFLATE for entries the source APK lacks).
     * @param additionalEntries entry name -> contents to append, for entries the source APK lacks.
     * @param alignment byte boundary every entry's data must start on.
     */
    fun repack(
        inputApkBytes: ByteArray,
        replacements: Map<String, ByteArray>,
        additionalEntries: Map<String, AdditionalEntry> = emptyMap(),
        alignment: Int = 4
    ): RepackResult {
        val addedSize = additionalEntries.values.sumOf { it.data.size.toLong() }
        val initialCapacity = (inputApkBytes.size.toLong() + addedSize + 8L * 1024 * 1024)
            .coerceIn(0, Int.MAX_VALUE.toLong())
            .toInt()
        val out = ByteArrayOutputStream(initialCapacity)
        val dropped = mutableListOf<String>()
        val sourceMethods = mutableMapOf<String, Int>()

        val counter = CountingOutputStream(out)
        ZipOutputStream(counter).use { zos ->
            ZipInputStream(ByteArrayInputStream(inputApkBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (shouldDrop(name, replacements, additionalEntries)) {
                        dropped.add(name)
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    val entryData = zis.readBytes()
                    sourceMethods[name] = entry.method
                    val newEntry = ZipEntry(name).apply {
                        method = entry.method
                        time = entry.time
                        comment = entry.comment
                    }
                    if (entry.method == ZipEntry.STORED) {
                        setStoredMetadata(newEntry, entryData)
                    }
                    writeEntry(zos, counter, newEntry, entryData, alignment, sanitizeExtra(entry.extra))
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            for ((name, data) in replacements) {
                val method = sourceMethods[name] ?: ZipEntry.DEFLATED
                val newEntry = ZipEntry(name).apply {
                    this.method = method
                    time = DETERMINISTIC_TIME
                }
                if (method == ZipEntry.STORED) {
                    setStoredMetadata(newEntry, data)
                }
                writeEntry(zos, counter, newEntry, data, alignment, ByteArray(0))
            }

            for ((name, additional) in additionalEntries) {
                val newEntry = ZipEntry(name).apply {
                    method = additional.method
                    time = DETERMINISTIC_TIME
                }
                if (additional.method == ZipEntry.STORED) {
                    setStoredMetadata(newEntry, additional.data)
                }
                writeEntry(zos, counter, newEntry, additional.data, alignment, ByteArray(0))
            }
        }

        return RepackResult(
            bytes = out.toByteArray(),
            replacedEntries = replacements.keys.toList(),
            addedEntries = additionalEntries.keys.toList(),
            droppedEntries = dropped
        )
    }

    private fun shouldDrop(
        name: String,
        replacements: Map<String, ByteArray>,
        additionalEntries: Map<String, AdditionalEntry>
    ): Boolean {
        if (name in SIGNATURE_ENTRIES || name in DROPPED_ENTRIES) return true
        if (name in replacements || name in additionalEntries) return true
        if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) return true
        return false
    }

    private fun setStoredMetadata(entry: ZipEntry, data: ByteArray) {
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        val crc = CRC32()
        crc.update(data)
        entry.crc = crc.value
    }

    /**
     * Writes one entry, first extending its extra field so the entry data lands on an
     * [alignment] boundary given the archive's current length.
     *
     * A DEFLATED entry's sizes are unknown up front, so the stream appends a 16-byte data
     * descriptor *after* the data. That shifts the following entry, not this one, and the
     * running count has already absorbed it by the time the next entry is measured.
     */
    private fun writeEntry(
        zos: ZipOutputStream,
        counter: CountingOutputStream,
        entry: ZipEntry,
        data: ByteArray,
        alignment: Int,
        extra: ByteArray
    ) {
        val nameLength = entry.name.toByteArray(Charsets.UTF_8).size
        val base = counter.count + 30 + nameLength + extra.size
        entry.extra = extra + alignmentField(paddingFor(base, alignment))

        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    /**
     * Bytes to append so that `base + pad` is a multiple of [alignment].
     *
     * An extra field costs a 4-byte header, so a non-zero result is never below 4; padding
     * is grown by whole alignment steps until it is representable.
     */
    internal fun paddingFor(base: Long, alignment: Int): Int {
        if (alignment <= 1) return 0
        val remainder = (base % alignment).toInt()
        if (remainder == 0) return 0
        var pad = alignment - remainder
        while (pad < 4) pad += alignment
        return pad
    }

    /** Builds a `zipalign`-style padding field of exactly [pad] bytes (0 produces nothing). */
    private fun alignmentField(pad: Int): ByteArray {
        if (pad <= 0) return ByteArray(0)
        val field = ByteArray(pad)
        field[0] = (ALIGNMENT_EXTRA_ID and 0xFF).toByte()
        field[1] = ((ALIGNMENT_EXTRA_ID ushr 8) and 0xFF).toByte()
        val payload = pad - 4
        field[2] = (payload and 0xFF).toByte()
        field[3] = ((payload ushr 8) and 0xFF).toByte()
        return field
    }

    /** Drops pre-existing alignment padding so padding is computed against a clean base. */
    private fun sanitizeExtra(extra: ByteArray?): ByteArray {
        if (extra == null || extra.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream(extra.size)
        var i = 0
        while (i + 4 <= extra.size) {
            val id = (extra[i].toInt() and 0xFF) or ((extra[i + 1].toInt() and 0xFF) shl 8)
            val size = (extra[i + 2].toInt() and 0xFF) or ((extra[i + 3].toInt() and 0xFF) shl 8)
            if (i + 4 + size > extra.size) break
            if (id != ALIGNMENT_EXTRA_ID) out.write(extra, i, 4 + size)
            i += 4 + size
        }
        return out.toByteArray()
    }

    /**
     * Counts bytes written so far. [ZipOutputStream] exposes no position, so alignment
     * padding has to be computed from an explicit running total.
     */
    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
