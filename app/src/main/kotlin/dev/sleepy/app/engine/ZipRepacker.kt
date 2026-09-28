package dev.sleepy.app.engine

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Rebuilds an APK, replacing and appending entries while keeping the archive byte-compatible
 * with what the platform expects.
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
 *
 * Nothing here is proportional to the size of the archive: [repackTo] reads one entry at a
 * time from a file and writes to a stream, and only [repack] ever holds the result. A
 * Discord base split is ~96 MB and the libraries merged into it another ~74 MB, so an
 * implementation that materialises either — let alone both — cannot run in the heap a phone
 * grants an app.
 */
object ZipRepacker {

    /**
     * An entry to append that was not present in the source APK (e.g. a merged split library).
     *
     * Contents come either from memory or from a file, and that choice is the point of this
     * type: the ABI split of an App Bundle is tens of megabytes of shared objects, and holding
     * them as `ByteArray`s while the base APK and the archive being written are also live is
     * what exhausted the heap on a real device. A file-backed entry costs one read buffer
     * instead of its own size.
     */
    class AdditionalEntry private constructor(
        private val bytes: ByteArray?,
        private val file: File?,
        /** Storage method the entry is written with. */
        val method: Int
    ) {
        /** An entry whose contents are already in memory. */
        constructor(data: ByteArray, method: Int = ZipEntry.DEFLATED) : this(data, null, method)

        /** An entry whose contents are on disk, streamed as the archive is written. */
        constructor(file: File, method: Int = ZipEntry.DEFLATED) : this(null, file, method)

        /** Bytes this entry contributes, whether it is held in memory or on disk. */
        val size: Long get() = bytes?.size?.toLong() ?: file?.length() ?: 0L

        /** Opens the contents afresh; the caller closes it. */
        internal fun openStream(): InputStream =
            bytes?.let { ByteArrayInputStream(it) } ?: checkNotNull(file) { "entry has neither bytes nor a file" }.inputStream()
    }

    /** Which entries the rebuild replaced, added and dropped, without returning the archive. */
    data class RepackReport(
        val replacedEntries: List<String>,
        val addedEntries: List<String>,
        val droppedEntries: List<String>
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

    /** Buffer for streamed entries: large enough to keep the deflater fed, small enough to ignore. */
    private const val CHUNK_SIZE = 64 * 1024

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
     * Files that describe the archive this one was rebuilt from rather than anything it holds.
     *
     * `stamp-cert-sha256` is the Play source stamp: it records which signed build an APK was
     * derived from. A rebuilt APK is signed with a key of our own, so carrying the stamp forward
     * states a provenance this file does not have. The desktop reference has no such entry for the
     * same reason — apktool's decoder groups it with `AndroidManifest.xml` and the META-INF
     * signature files in `ApkInfo.ORIGINAL_FILES_PATTERN` and never writes it back.
     */
    private val STAMP_ENTRIES = setOf("stamp-cert-sha256")

    /**
     * Rebuilds [inputApk] into [output], reading the source a single entry at a time and never
     * holding the result: the archive being produced is written straight through to whatever
     * [output] is, so it exists once rather than as a buffer plus a copy of that buffer.
     *
     * [output] is flushed but not closed — the caller owns it and decides when the file is done.
     *
     * @param replacements entry name -> new contents, written with the source entry's own
     *   storage method (DEFLATE for entries the source APK lacks).
     * @param additionalEntries entry name -> contents to append, for entries the source APK lacks.
     * @param droppedEntries extra names to leave out, on top of the built-in set. The calling
     *   pipeline supplies these from whichever patch set is active, so dropping a crash
     *   reporter's own artefacts happens only when that reporter is being disabled.
     *
     *   A dropped name is dropped however the rebuild would have written it — from the source
     *   archive, from [replacements], or as one of [additionalEntries]. That is the whole point of
     *   taking the set here rather than filtering the source: a merged split contributes entries
     *   the source APK never held, and a caller cannot filter those for itself without walking the
     *   splits' contents a second time.
     */
    fun repackTo(
        inputApk: File,
        output: OutputStream,
        replacements: Map<String, ByteArray>,
        additionalEntries: Map<String, AdditionalEntry> = emptyMap(),
        droppedEntries: Set<String> = emptySet()
    ): RepackReport = BufferedInputStream(inputApk.inputStream(), CHUNK_SIZE).use { input ->
        rebuild(input, output, replacements, additionalEntries, droppedEntries)
    }

    /**
     * The same rebuild, for callers that hold the source in memory and want the result there
     * too. The whole output is materialised, so this is for small archives; the pipeline uses
     * [repackTo] precisely because an APK is not small.
     */
    fun repack(
        inputApkBytes: ByteArray,
        replacements: Map<String, ByteArray>,
        additionalEntries: Map<String, AdditionalEntry> = emptyMap(),
        droppedEntries: Set<String> = emptySet()
    ): RepackResult {
        val out = ByteArrayOutputStream()
        val report = rebuild(ByteArrayInputStream(inputApkBytes), out, replacements, additionalEntries, droppedEntries)
        return RepackResult(out.toByteArray(), report.replacedEntries, report.addedEntries, report.droppedEntries)
    }

    /** Shared implementation: [inputApk] is walked strictly forwards, once. */
    private fun rebuild(
        inputApk: InputStream,
        output: OutputStream,
        replacements: Map<String, ByteArray>,
        additionalEntries: Map<String, AdditionalEntry>,
        droppedEntries: Set<String>
    ): RepackReport {
        // Insertion-ordered and a set, because more than one of the walks below can reach the same
        // name — a name can be in the source archive and in the replacements at once — and the
        // report names each entry once.
        val dropped = mutableSetOf<String>()
        val sourceMethods = mutableMapOf<String, Int>()
        // One buffer for the whole rebuild: every entry is copied through it in turn, so no
        // entry's contents are ever held in full.
        val chunk = ByteArray(CHUNK_SIZE)
        val counter = CountingOutputStream(output)

        ZipOutputStream(NonClosingOutputStream(counter)).use { zos ->
            ZipInputStream(inputApk).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // Read here, above the drop check, because a replaced entry is dropped from
                    // the copied stream and rewritten from the replacement — with the source's own
                    // method, which is the one thing about it the replacement does not carry. Read
                    // below the check it was never seen for exactly the entries that needed it,
                    // and every replacement fell back to DEFLATE: the resource table the merge
                    // builds and the JavaScript bundle the patch writes both came out compressed
                    // where the source APK stores them for the platform to map rather than unpack.
                    sourceMethods[name] = entry.method
                    if (shouldDrop(name, replacements, additionalEntries, droppedEntries)) {
                        dropped.add(name)
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    val newEntry = ZipEntry(name).apply {
                        method = entry.method
                        time = entry.time
                        comment = entry.comment
                    }
                    val extra = sanitizeExtra(entry.extra)
                    if (entry.method == ZipEntry.STORED && entry.crc >= 0 && entry.size >= 0) {
                        // A stored entry needs its size and CRC in the header, before its data,
                        // and the source header already carries both. Taking them from there is
                        // what keeps the largest entry a Discord build stores — a 53 MB
                        // JavaScript bundle — on the same 64 KB buffer as everything else,
                        // instead of being read in full to be measured and then written.
                        //
                        // Nothing is trusted on faith: ZipOutputStream checks the size and the
                        // CRC against the bytes it is handed and refuses the entry if they
                        // disagree.
                        newEntry.size = entry.size
                        newEntry.compressedSize = entry.size
                        newEntry.crc = entry.crc
                        writeEntry(zos, counter, newEntry, zis, extra, chunk)
                    } else if (entry.method == ZipEntry.STORED) {
                        // A stored entry whose header has no sizes (a data descriptor follows
                        // instead) can only be measured by reading it.
                        val data = zis.readBytes()
                        setStoredMetadata(newEntry, data)
                        writeEntry(zos, counter, newEntry, data, extra)
                    } else {
                        writeEntry(zos, counter, newEntry, zis, extra, chunk)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            for ((name, data) in replacements) {
                // A name that is also being dropped is left out here too. The walk above already
                // passed over the source's copy of it, so writing the replacement would put the
                // entry back and make the drop report a claim about something that is still there.
                if (isDropped(name, droppedEntries)) {
                    dropped.add(name)
                    continue
                }
                val method = sourceMethods[name] ?: ZipEntry.DEFLATED
                val newEntry = ZipEntry(name).apply {
                    this.method = method
                    time = DETERMINISTIC_TIME
                }
                if (method == ZipEntry.STORED) {
                    setStoredMetadata(newEntry, data)
                }
                writeEntry(zos, counter, newEntry, data, ByteArray(0))
            }

            for ((name, additional) in additionalEntries) {
                // The merged-in case, and the one the drop above used to miss: an ABI split's
                // libraries are entries the base APK never held, so a drop list checked against
                // the source archive alone left a crash reporter's own shared objects in the
                // output of a build that documents them as removed.
                if (isDropped(name, droppedEntries)) {
                    dropped.add(name)
                    continue
                }
                val newEntry = ZipEntry(name).apply {
                    method = additional.method
                    time = DETERMINISTIC_TIME
                }
                if (additional.method == ZipEntry.STORED) {
                    // A stored entry's CRC has to be known before its header is written, so the
                    // contents are read twice: once to measure, once to write.
                    additional.openStream().use { setStoredMetadata(newEntry, it, additional.size) }
                }
                additional.openStream().use { content ->
                    writeEntry(zos, counter, newEntry, content, ByteArray(0), chunk)
                }
            }
        }

        output.flush()
        return RepackReport(
            replacedEntries = replacements.keys.toList(),
            addedEntries = additionalEntries.keys.toList(),
            droppedEntries = dropped.toList()
        )
    }

    private fun shouldDrop(
        name: String,
        replacements: Map<String, ByteArray>,
        additionalEntries: Map<String, AdditionalEntry>,
        droppedEntries: Set<String>
    ): Boolean {
        if (isDropped(name, droppedEntries)) return true
        return name in replacements || name in additionalEntries
    }

    /**
     * Whether [name] must be left out however it arrives, which is the question every entry the
     * rebuild writes has to answer — the source's own entries, the replacements, and the entries a
     * merged split contributed.
     *
     * The two built-in sets are entries no rebuilt archive may carry: the signature files of the
     * APK this one replaces, and the source stamp that names which build it was signed off. A
     * caller's [droppedEntries] says the same about the artefacts of whatever it is disabling.
     */
    private fun isDropped(name: String, droppedEntries: Set<String>): Boolean {
        if (name in SIGNATURE_ENTRIES || name in DROPPED_ENTRIES || name in STAMP_ENTRIES || name in droppedEntries) return true
        return name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))
    }

    private fun setStoredMetadata(entry: ZipEntry, data: ByteArray) {
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        val crc = CRC32()
        crc.update(data)
        entry.crc = crc.value
    }

    /** The same for an entry on disk, which is measured by reading it. */
    private fun setStoredMetadata(entry: ZipEntry, content: InputStream, size: Long) {
        val crc = CRC32()
        val chunk = ByteArray(CHUNK_SIZE)
        var read = content.read(chunk)
        while (read >= 0) {
            if (read > 0) crc.update(chunk, 0, read)
            read = content.read(chunk)
        }
        entry.size = size
        entry.compressedSize = size
        entry.crc = crc.value
    }

    /**
     * Writes one entry, first extending its extra field so the entry data lands on the
     * boundary [ZipAlignment] requires for it. Compressed entries are exempt and are written
     * with no padding at all, which is what keeps the output comparable to `zipalign`'s.
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
        extra: ByteArray
    ) {
        prepareEntry(counter, entry, extra)
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    /** The same, for an entry whose contents are read from [content] as they are written. */
    private fun writeEntry(
        zos: ZipOutputStream,
        counter: CountingOutputStream,
        entry: ZipEntry,
        content: InputStream,
        extra: ByteArray,
        chunk: ByteArray
    ) {
        prepareEntry(counter, entry, extra)
        zos.putNextEntry(entry)
        var read = content.read(chunk)
        while (read >= 0) {
            if (read > 0) zos.write(chunk, 0, read)
            read = content.read(chunk)
        }
        zos.closeEntry()
    }

    /** Sets the extra field that lands this entry's data on the boundary it needs. */
    private fun prepareEntry(counter: CountingOutputStream, entry: ZipEntry, extra: ByteArray) {
        val required = ZipAlignment.requiredFor(entry.name, entry.method)
        val nameLength = entry.name.toByteArray(Charsets.UTF_8).size
        val base = counter.count + 30 + nameLength + extra.size
        entry.extra = if (required > 0) {
            extra + alignmentField(paddingFor(base, required))
        } else {
            extra
        }
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

    /**
     * Lets the [ZipOutputStream] finish — it still has a central directory to write — without
     * closing a stream the caller owns.
     *
     * The array write is overridden because [FilterOutputStream]'s own implementation copies
     * byte by byte, which would put every compressed block through a virtual call.
     */
    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
        }

        override fun close() {
            flush()
        }
    }
}
