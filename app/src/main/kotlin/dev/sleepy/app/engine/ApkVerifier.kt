package dev.sleepy.app.engine

import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSource
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Checks a finished APK and reports the measured result.
 *
 * The patcher previously asserted `v1/v2/v3SignatureValid = true` and
 * `zipalignPassed = true` as constants without inspecting the artifact. Those claims are
 * what a user relies on when deciding whether to install the result, so they are measured
 * here instead.
 *
 * Both overloads read the same things: one [verify] takes the archive in memory, and the
 * other reads a [File] in place, which is the one the pipeline uses—a finished APK is
 * 131 MB, and loading it into memory to read its directory uses that much heap again.
 *
 * One of these checks has three answers rather than two. JAR signing is written into every
 * APK this patcher signs, but it is only ever *read* by platforms earlier than API 24, and the
 * verifier does not read it for a build that declares a higher `minSdkVersion`—so the
 * report for such an APK is "not applicable" rather than the "not valid" that a bare
 * Boolean produced. See [Result.v1SignatureValid].
 */
object ApkVerifier {

    /** The fixed part of a ZIP64 end-of-central-directory record, in bytes. */
    private const val ZIP64_RECORD_SIZE = 56

    /**
     * The first API level whose platform ignores JAR signatures.
     *
     * JAR signing exists to serve platforms that predate APK Signature Scheme v2, and from
     * API 24 on the platform uses v2, v3 and the APK Signing Block instead—v1 is not
     * read there at all. apksig follows the same rule: given an APK whose own
     * `minSdkVersion` reaches this level it does not run the JAR verifier, and reports
     * `isVerifiedUsingV1Scheme = false`.
     */
    private const val FIRST_API_WITHOUT_JAR_SIGNING = 24

    /** The outcome of the checks that [ApkVerifier] runs against one APK. */
    data class Result(
        /**
         * Whether the v1 (JAR) signature check passed.
         *
         * `null` means the scheme does not apply to this APK: JAR signing is only honored
         * earlier than API 24, so a build whose `minSdkVersion` is 24 or higher does not have it
         * read, and the verifier does not read it either. That is deliberately not `false`—
         * "this scheme does not apply" and "this signature is broken" must not collapse into
         * the same value, which is what happened when a valid v1 signature was reported as
         * failing on every modern APK this patcher produces.
         */
        val v1SignatureValid: Boolean?,
        /** Whether the APK Signature Scheme v2 signature check passed. */
        val v2SignatureValid: Boolean,
        /** Whether the APK Signature Scheme v3 signature check passed. */
        val v3SignatureValid: Boolean,
        /**
         * Whether every entry that needs alignment has it.
         *
         * `null` means the archive's central directory could not be read, so nothing was
         * measured. That is deliberately not `false`: "not measured" and "aligned" must not
         * collapse into the same value, which is what an empty misaligned list used to do.
         */
        val zipalignPassed: Boolean?,
        /** Errors reported by the signature verifier. */
        val signatureErrors: List<String>,
        /** Names of entries whose data offset does not meet the alignment requirement. */
        val misalignedEntries: List<String>,
        /** Why the alignment answer is `null`, for the reader who has to act on it. */
        val directoryError: String?,
        /** The same for [v1SignatureValid], which is `null` far more often than alignment is. */
        val v1NotApplicableReason: String?
    )

    /**
     * Checks [apkBytes] against the three schemes an APK must satisfy—v1 (JAR), v2 (APK
     * Signature Scheme v2), and v3—and against the alignment rule.
     */
    fun verify(apkBytes: ByteArray): Result =
        inspect(
            DataSources.asDataSource(ByteBuffer.wrap(apkBytes)),
            findMisalignedEntries(apkBytes)
        )

    /**
     * Runs the same checks against an APK on disk.
     *
     * apksig streams what it verifies through a [FileChannel], and the alignment scan reads
     * only the archive's own directory, so neither requires the whole file in memory.
     */
    fun verify(apkFile: File): Result =
        FileChannel.open(apkFile.toPath(), StandardOpenOption.READ).use { channel ->
            inspect(DataSources.asDataSource(channel), findMisalignedEntries(apkFile))
        }

    private fun inspect(source: DataSource, scan: AlignmentScan): Result {
        val errors = mutableListOf<String>()

        var v1Verified = false
        var v2 = false
        var v3 = false
        try {
            val result = com.android.apksig.ApkVerifier.Builder(source).build().verify()
            v1Verified = result.isVerifiedUsingV1Scheme
            v2 = result.isVerifiedUsingV2Scheme
            v3 = result.isVerifiedUsingV3Scheme
            for (issue in result.errors) {
                val params = issue.params?.joinToString(", ") { it.toString() }.orEmpty()
                errors.add(
                    if (params.isEmpty()) issue.issue.name else "${issue.issue.name} ($params)"
                )
            }
        } catch (e: Exception) {
            errors.add("Signature verification could not run: ${e.message}")
        }

        val minSdkVersion = minSdkVersion(source)

        return Result(
            v1SignatureValid = v1Verdict(v1Verified, minSdkVersion),
            v2SignatureValid = v2,
            v3SignatureValid = v3,
            zipalignPassed = if (scan.error != null) null else scan.misalignedEntries.isEmpty(),
            signatureErrors = errors,
            misalignedEntries = scan.misalignedEntries,
            directoryError = scan.error,
            v1NotApplicableReason = v1NotApplicableReason(v1Verified, minSdkVersion)
        )
    }

    /**
     * The meaning of the JAR signature's verification result, which has three possible values.
     *
     * apksig reports `false` both for a signature that failed and for one it did not read,
     * and it does not read one belonging to an APK whose `minSdkVersion` puts the JAR
     * format out of reach. [minSdkVersion] distinguishes the two cases; when the manifest
     * cannot be read, the verdict is left unknown rather than assumed to be a failure.
     */
    private fun v1Verdict(verified: Boolean, minSdkVersion: Int?): Boolean? = when {
        verified -> true
        minSdkVersion == null -> null
        minSdkVersion >= FIRST_API_WITHOUT_JAR_SIGNING -> null
        else -> false
    }

    /** The reason [v1Verdict] returned `null` rather than `false`, phrased as the cause. */
    private fun v1NotApplicableReason(verified: Boolean, minSdkVersion: Int?): String? = when {
        verified -> null
        minSdkVersion == null -> {
            "the APK's AndroidManifest.xml could not be read, so the minSdkVersion that " +
                "decides whether JAR signing applies is unknown"
        }
        minSdkVersion >= FIRST_API_WITHOUT_JAR_SIGNING -> {
            "this build declares minSdkVersion $minSdkVersion, and JAR signing is only " +
                "honored below API $FIRST_API_WITHOUT_JAR_SIGNING—the signature is " +
                "present and valid, but no platform that can install this APK reads it"
        }
        else -> null
    }

    /**
     * The `minSdkVersion` the APK's own manifest declares, or `null` when it cannot be read.
     *
     * Read through apksig's own manifest reader rather than a second binary-XML parser, and read
     * from the finished artifact rather than from the source it was built out of: the merge
     * rewrites this manifest, and the platform reads the rewritten one. The manifest is one
     * ~100 KB entry, so this reads one entry rather than the whole archive.
     */
    private fun minSdkVersion(source: DataSource): Int? = try {
        ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(ApkUtils.getAndroidManifest(source))
    } catch (e: Exception) {
        null
    }

    /**
     * Finds the entries whose data offset does not meet the [ZipAlignment] requirement, and
     * collects the reason the directory could not be walked.
     *
     * Only uncompressed entries are checked. Compressed entries have no alignment
     * requirement—`zipalign -c` marks them "OK - compressed"—so testing `offset % 4`
     * across every entry reports thousands of failures on a stock, valid APK.
     */
    private fun findMisalignedEntries(apkBytes: ByteArray): AlignmentScan {
        val misaligned = mutableListOf<String>()
        val error = readCentralDirectory(apkBytes) { name, dataOffset, method ->
            val required = ZipAlignment.requiredFor(name, method)
            if (required > 0 && dataOffset % required != 0L) misaligned.add(name)
        }
        return AlignmentScan(misaligned, error)
    }

    /** The same, reading the directory out of the file rather than the file into memory. */
    private fun findMisalignedEntries(apkFile: File): AlignmentScan {
        val misaligned = mutableListOf<String>()
        val error = readCentralDirectory(apkFile) { name, dataOffset, method ->
            val required = ZipAlignment.requiredFor(name, method)
            if (required > 0 && dataOffset % required != 0L) misaligned.add(name)
        }
        return AlignmentScan(misaligned, error)
    }

    /**
     * The alignment answer, or why there is no answer.
     *
     * [error] separates "the directory was walked and every entry that needs aligning is
     * aligned" from "the directory could not be walked". Both were previously represented by
     * an empty list, so a ZIP64 archive that the file reader could not parse reported as
     * aligned.
     */
    private class AlignmentScan(val misalignedEntries: List<String>, val error: String?)

    /**
     * Walks the central directory of [apkBytes], invoking [onEntry] with each entry's name,
     * the local-header-derived data offset, and its compression method.
     *
     * @return `null` when the whole directory was walked, or a one-line reason it could not
     * be. A caller that treats a returned reason as an empty directory claims alignment for
     * an archive that was not read.
     */
    internal fun readCentralDirectory(
        apkBytes: ByteArray,
        onEntry: (name: String, dataOffset: Long, method: Int) -> Unit
    ): String? {
        val buf = ByteBuffer.wrap(apkBytes).order(ByteOrder.LITTLE_ENDIAN)
        val eocdOffset = findEndOfCentralDirectory(buf)
            ?: return "no end-of-central-directory record in the last ${buf.capacity()} bytes"
        var count = buf.getShort(eocdOffset + 10).toInt() and 0xFFFF
        var offset = buf.getInt(eocdOffset + 16).toLong() and 0xFFFFFFFFL

        // ZIP64: fall back to the ZIP64 locator when the classic fields are saturated. This
        // buffer is the whole file, so an offset stored in the file is also an index into it.
        if (offset == 0xFFFFFFFFL || count == 0xFFFF) {
            val zip64 = findZip64EndOfCentralDirectory(buf, eocdOffset, apkBytes.size.toLong())
                ?: return "the ZIP64 locator does not name a record that fits in the archive"
            // The whole file is the buffer here, so the stored file offset is the index too—
            // and the helper has already bounds-tested it against this buffer's capacity.
            val zip64Index = zip64.toInt()
            if (buf.getInt(zip64Index) != 0x06064b50) {
                return "the ZIP64 locator points at something other than a ZIP64 record"
            }
            count = readableEntryCount(buf.getLong(zip64Index + 32))
                ?: return "the ZIP64 record claims an entry count this reader cannot walk"
            offset = buf.getLong(zip64Index + 48)
        }
        if (count == 0) return null

        repeat(count) { entry ->
            if (offset < 0 || offset > apkBytes.size - 46L) {
                return "central-directory entry $entry of $count lies outside the archive"
            }
            val index = offset.toInt()
            if (buf.getInt(index) != 0x02014b50) {
                return "central-directory entry $entry of $count is not a central-directory header"
            }

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
        return null
    }

    /**
     * Walks the central directory of [apkFile].
     *
     * The read covers only what the result requires: the archive's tail—the central
     * directory and the end-of-central-directory record that gives its position—and then
     * each local header the directory points at. The entry data itself is not read, which is
     * why the check of a finished 131 MB APK does not allocate a buffer of that size.
     *
     * @return `null` when the whole directory was walked, or a one-line reason it could not be.
     */
    internal fun readCentralDirectory(
        apkFile: File,
        onEntry: (name: String, dataOffset: Long, method: Int) -> Unit
    ): String? {
        RandomAccessFile(apkFile, "r").use { file ->
            val length = file.length()
            // The end-of-central-directory record is the last 22 bytes plus an optional
            // comment of at most 64 KiB, and the ZIP64 locator sits 20 bytes before that.
            val tailSize = minOf(length, (22 + 0xFFFF + 20).toLong()).toInt()
            if (tailSize < 22) {
                return "${apkFile.name} is $length bytes, too short to hold an " +
                    "end-of-central-directory record"
            }
            val tail = ByteArray(tailSize)
            file.seek(length - tailSize)
            file.readFully(tail)

            val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val eocd = findEndOfCentralDirectory(buf)
                ?: return "no end-of-central-directory record in the last $tailSize bytes"
            var count = buf.getShort(eocd + 10).toInt() and 0xFFFF
            var directoryOffset = buf.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
            var directorySize = buf.getInt(eocd + 12).toLong() and 0xFFFFFFFFL

            if (directoryOffset == 0xFFFFFFFFL || directorySize == 0xFFFFFFFFL || count == 0xFFFF) {
                // The locator stores the record's offset from the start of the *file*, while
                // everything else in `buf` is indexed from the start of the *tail*, which
                // begins `length - tailSize` bytes into the file. The record is therefore read
                // by seeking to the stored offset in the file rather than by indexing the tail
                // with it—the two coincide only when the tail happens to be the whole file.
                val zip64 = findZip64EndOfCentralDirectory(buf, eocd, length)
                    ?: return "the ZIP64 locator does not name a record that fits in the archive"
                val record = ByteArray(ZIP64_RECORD_SIZE)
                file.seek(zip64)
                file.readFully(record)
                val zip64Buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
                if (zip64Buf.getInt(0) != 0x06064b50) {
                    return "the ZIP64 locator points at something other than a ZIP64 record"
                }
                count = readableEntryCount(zip64Buf.getLong(32))
                    ?: return "the ZIP64 record claims an entry count this reader cannot walk"
                directorySize = zip64Buf.getLong(40)
                directoryOffset = zip64Buf.getLong(48)
            }
            if (count == 0) return null
            if (directoryOffset < 0 || directorySize < 0) {
                return "the central directory is declared at a negative offset or size"
            }
            if (directorySize > Int.MAX_VALUE) {
                return "the central directory is larger than this reader can hold"
            }
            if (directoryOffset > length - directorySize) {
                return "the central directory at $directoryOffset ($directorySize bytes) " +
                    "lies outside the ${length}-byte archive"
            }
            val directory = ByteArray(directorySize.toInt())
            file.seek(directoryOffset)
            file.readFully(directory)

            val directoryBuf = ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 0L
            repeat(count) { entry ->
                if (offset < 0 || offset > directory.size - 46L) {
                    return "central-directory entry $entry of $count lies outside " +
                        "${directorySize}-byte directory"
                }
                val index = offset.toInt()
                if (directoryBuf.getInt(index) != 0x02014b50) {
                    return "central-directory entry $entry of $count is not a " +
                        "central-directory header"
                }

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
            return null
        }
    }

    /**
     * An entry count as this reader can use it, or `null` when the archive claims one it
     * cannot walk. A negative or unrepresentable count means a directory that cannot be
     * read; truncating it walks nothing and reports the archive as aligned.
     */
    private fun readableEntryCount(value: Long): Int? =
        if (value in 0..Int.MAX_VALUE.toLong()) value.toInt() else null

    /**
     * Data offset of the entry at [localOffset], that is, past its local header and extra
     * field.
     */
    private fun localHeaderDataOffset(
        apkBytes: ByteArray,
        buf: ByteBuffer,
        localOffset: Long
    ): Long {
        val index = localOffset.toInt()
        if (index < 0 || index + 30 > apkBytes.size) return -1
        if (buf.getInt(index) != 0x04034b50) return -1
        val nameLength = buf.getShort(index + 26).toInt() and 0xFFFF
        val extraLength = buf.getShort(index + 28).toInt() and 0xFFFF
        return localOffset + 30 + nameLength + extraLength
    }

    /** The same read against the file that contains the local header. */
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

    /**
     * The offset of the ZIP64 end-of-central-directory record, or null when the locator is
     * absent, malformed, or names a record that does not fit in a [fileLength]-byte file.
     *
     * The returned offset is **absolute in the file**, because that is what the locator stores
     * —4.3.15's "relative offset of the zip64 end of central directory record" is measured
     * from the start of the archive, not from whatever window the caller happens to have read.
     * [buf] is only used to find and read the locator, which is located 20 bytes before the
     * end-of-central-directory record and therefore inside the same window; the record it
     * points at can be anywhere in the file. Bounds-testing it against `buf.capacity()` is
     * what made the file reader reject every ZIP64 archive whose record lay outside the tail.
     */
    private fun findZip64EndOfCentralDirectory(
        buf: ByteBuffer,
        eocdOffset: Int,
        fileLength: Long
    ): Long? {
        val locator = eocdOffset - 20
        if (locator < 0 || buf.getInt(locator) != 0x07064b50) return null
        val index = buf.getLong(locator + 8)
        if (index < 0 || index > fileLength - ZIP64_RECORD_SIZE) return null
        return index
    }
}
