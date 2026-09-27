package dev.sleepy.app.engine

import com.android.apksig.util.DataSource
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Checks a finished APK and reports what genuinely holds.
 *
 * The patcher previously asserted `v1/v2/v3SignatureValid = true` and
 * `zipalignPassed = true` as constants without inspecting the artefact. Those claims are
 * what a user relies on when deciding whether to install the result, so they are measured
 * here instead.
 *
 * Both spellings of the check read the same things: [verify] takes the archive in memory and
 * [verify] over a [File] reads it where it lies, which is what the pipeline uses — a finished
 * APK is 131 MB, and loading it again to look at its directory would undo the point of
 * writing it straight to disk.
 */
object ApkVerifier {

    data class Result(
        val v1SignatureValid: Boolean,
        val v2SignatureValid: Boolean,
        val v3SignatureValid: Boolean,
        val zipalignPassed: Boolean,
        val signatureErrors: List<String>,
        val misalignedEntries: List<String>
    )

    /** Schemes an APK must satisfy: v1 (JAR), v2 (APK Signature Scheme v2), v3. */
    fun verify(apkBytes: ByteArray): Result =
        inspect(DataSources.asDataSource(ByteBuffer.wrap(apkBytes)), findMisalignedEntries(apkBytes))

    /**
     * The same checks against an APK on disk.
     *
     * apksig streams what it verifies through a [FileChannel], and the alignment scan reads
     * only the archive's own directory, so neither costs the size of the file.
     */
    fun verify(apkFile: File): Result =
        FileChannel.open(apkFile.toPath(), StandardOpenOption.READ).use { channel ->
            inspect(DataSources.asDataSource(channel), findMisalignedEntries(apkFile))
        }

    private fun inspect(source: DataSource, misaligned: List<String>): Result {
        val errors = mutableListOf<String>()

        var v1 = false
        var v2 = false
        var v3 = false
        try {
            val result = com.android.apksig.ApkVerifier.Builder(source).build().verify()
            v1 = result.isVerifiedUsingV1Scheme
            v2 = result.isVerifiedUsingV2Scheme
            v3 = result.isVerifiedUsingV3Scheme
            for (issue in result.errors) {
                val params = issue.params?.joinToString(", ") { it.toString() }.orEmpty()
                errors.add(if (params.isEmpty()) issue.issue.name else "${issue.issue.name} ($params)")
            }
        } catch (e: Exception) {
            errors.add("Signature verification could not run: ${e.message}")
        }

        return Result(
            v1SignatureValid = v1,
            v2SignatureValid = v2,
            v3SignatureValid = v3,
            zipalignPassed = misaligned.isEmpty(),
            signatureErrors = errors,
            misalignedEntries = misaligned
        )
    }

    /**
     * Names of entries whose data does not start where [ZipAlignment] requires.
     *
     * Only uncompressed entries are checked. Compressed entries have no alignment
     * requirement — `zipalign -c` marks them "OK - compressed" — so testing `offset % 4`
     * across every entry reports thousands of failures on a stock, perfectly valid APK.
     */
    private fun findMisalignedEntries(apkBytes: ByteArray): List<String> {
        val misaligned = mutableListOf<String>()
        readCentralDirectory(apkBytes) { name, dataOffset, method ->
            val required = ZipAlignment.requiredFor(name, method)
            if (required > 0 && dataOffset % required != 0L) misaligned.add(name)
        }
        return misaligned
    }

    /** The same, reading the directory out of the file rather than the file into memory. */
    private fun findMisalignedEntries(apkFile: File): List<String> {
        val misaligned = mutableListOf<String>()
        readCentralDirectory(apkFile) { name, dataOffset, method ->
            val required = ZipAlignment.requiredFor(name, method)
            if (required > 0 && dataOffset % required != 0L) misaligned.add(name)
        }
        return misaligned
    }

    /**
     * Walks the central directory of [apkBytes], invoking [onEntry] with each entry's name,
     * the local-header-derived data offset, and its compression method.
     */
    internal fun readCentralDirectory(
        apkBytes: ByteArray,
        onEntry: (name: String, dataOffset: Long, method: Int) -> Unit
    ) {
        val buf = ByteBuffer.wrap(apkBytes).order(ByteOrder.LITTLE_ENDIAN)
        val eocdOffset = findEndOfCentralDirectory(buf) ?: return
        var count = buf.getShort(eocdOffset + 10).toInt() and 0xFFFF
        var offset = buf.getInt(eocdOffset + 16).toLong() and 0xFFFFFFFFL

        // ZIP64: fall back to the ZIP64 locator when the classic fields are saturated.
        if (offset == 0xFFFFFFFFL || count == 0xFFFF) {
            val zip64 = findZip64EndOfCentralDirectory(buf, eocdOffset) ?: return
            count = buf.getLong(zip64 + 32).toInt()
            offset = buf.getLong(zip64 + 48)
        }

        repeat(count) {
            val index = offset.toInt()
            if (index + 46 > apkBytes.size) return
            if (buf.getInt(index) != 0x02014b50) return

            val method = buf.getShort(index + 10).toInt() and 0xFFFF
            val nameLength = buf.getShort(index + 28).toInt() and 0xFFFF
            val extraLength = buf.getShort(index + 30).toInt() and 0xFFFF
            val commentLength = buf.getShort(index + 32).toInt() and 0xFFFF
            val localOffset = buf.getInt(index + 42).toLong() and 0xFFFFFFFFL
            val name = String(apkBytes, index + 46, nameLength, Charsets.UTF_8)

            val dataOffset = localHeaderDataOffset(apkBytes, buf, localOffset)
            if (dataOffset >= 0) onEntry(name, dataOffset, method)

            offset = index + 46L + nameLength + extraLength + commentLength
        }
    }

    /**
     * The same walk for an APK on disk.
     *
     * Only what the answer needs is read: the archive's tail — the central directory and the
     * end-of-central-directory record that gives its position — and then each local header the
     * directory points at. The entry data itself is never touched, which is the whole reason a
     * finished 131 MB APK can be checked without a heap to match.
     */
    internal fun readCentralDirectory(
        apkFile: File,
        onEntry: (name: String, dataOffset: Long, method: Int) -> Unit
    ) {
        RandomAccessFile(apkFile, "r").use { file ->
            val length = file.length()
            // The end-of-central-directory record is the last 22 bytes plus an optional
            // comment of at most 64 KiB, and the ZIP64 locator sits 20 bytes before that.
            val tailSize = minOf(length, (22 + 0xFFFF + 20).toLong()).toInt()
            if (tailSize < 22) return
            val tail = ByteArray(tailSize)
            file.seek(length - tailSize)
            file.readFully(tail)

            val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val eocd = findEndOfCentralDirectory(buf) ?: return
            var count = buf.getShort(eocd + 10).toInt() and 0xFFFF
            var directoryOffset = buf.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
            var directorySize = buf.getInt(eocd + 12).toLong() and 0xFFFFFFFFL

            if (directoryOffset == 0xFFFFFFFFL || directorySize == 0xFFFFFFFFL || count == 0xFFFF) {
                val zip64 = findZip64EndOfCentralDirectory(buf, eocd) ?: return
                val record = ByteArray(56)
                // The helper indexes the tail it was given; the record lives that far into the
                // file, which starts `tailSize` bytes before the tail does.
                file.seek(length - tailSize + zip64)
                file.readFully(record)
                val zip64Buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
                count = zip64Buf.getLong(32).toInt()
                directorySize = zip64Buf.getLong(40)
                directoryOffset = zip64Buf.getLong(48)
            }

            if (directoryOffset < 0 || directorySize < 0 || directoryOffset + directorySize > length) return
            val directory = ByteArray(directorySize.toInt())
            file.seek(directoryOffset)
            file.readFully(directory)

            val directoryBuf = ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 0L
            repeat(count) {
                val index = offset.toInt()
                if (index + 46 > directory.size) return
                if (directoryBuf.getInt(index) != 0x02014b50) return

                val method = directoryBuf.getShort(index + 10).toInt() and 0xFFFF
                val nameLength = directoryBuf.getShort(index + 28).toInt() and 0xFFFF
                val extraLength = directoryBuf.getShort(index + 30).toInt() and 0xFFFF
                val commentLength = directoryBuf.getShort(index + 32).toInt() and 0xFFFF
                val localOffset = directoryBuf.getInt(index + 42).toLong() and 0xFFFFFFFFL
                val name = String(directory, index + 46, nameLength, Charsets.UTF_8)

                val dataOffset = localHeaderDataOffset(file, localOffset)
                if (dataOffset >= 0) onEntry(name, dataOffset, method)

                offset = index + 46L + nameLength + extraLength + commentLength
            }
        }
    }

    /** Data offset of the entry at [localOffset], i.e. past its local header and extra field. */
    private fun localHeaderDataOffset(apkBytes: ByteArray, buf: ByteBuffer, localOffset: Long): Long {
        val index = localOffset.toInt()
        if (index < 0 || index + 30 > apkBytes.size) return -1
        if (buf.getInt(index) != 0x04034b50) return -1
        val nameLength = buf.getShort(index + 26).toInt() and 0xFFFF
        val extraLength = buf.getShort(index + 28).toInt() and 0xFFFF
        return localOffset + 30 + nameLength + extraLength
    }

    /** The same read, against the file the local header actually lives in. */
    private fun localHeaderDataOffset(file: RandomAccessFile, localOffset: Long): Long {
        if (localOffset < 0 || localOffset + 30 > file.length()) return -1
        val header = ByteArray(30)
        file.seek(localOffset)
        file.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != 0x04034b50) return -1
        val nameLength = buf.getShort(26).toInt() and 0xFFFF
        val extraLength = buf.getShort(28).toInt() and 0xFFFF
        return localOffset + 30 + nameLength + extraLength
    }

    private fun findEndOfCentralDirectory(buf: ByteBuffer): Int? {
        val min = (buf.capacity() - 22 - 0xFFFF).coerceAtLeast(0)
        for (i in buf.capacity() - 22 downTo min) {
            if (buf.getInt(i) == 0x06054b50) return i
        }
        return null
    }

    private fun findZip64EndOfCentralDirectory(buf: ByteBuffer, eocdOffset: Int): Int? {
        val locator = eocdOffset - 20
        if (locator < 0 || buf.getInt(locator) != 0x07064b50) return null
        val index = buf.getLong(locator + 8).toInt()
        if (index < 0 || index + 56 > buf.capacity()) return null
        return if (buf.getInt(index) == 0x06064b50) index else null
    }
}
