package dev.sleepy.app.engine

import com.android.apksig.ApkVerifier
import com.android.apksig.util.DataSources
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Checks a finished APK and reports what genuinely holds.
 *
 * The patcher previously asserted `v1/v2/v3SignatureValid = true` and
 * `zipalignPassed = true` as constants without inspecting the artefact. Those claims are
 * what a user relies on when deciding whether to install the result, so they are measured
 * here instead.
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
    fun verify(apkBytes: ByteArray): Result {
        val errors = mutableListOf<String>()

        var v1 = false
        var v2 = false
        var v3 = false
        try {
            val source = DataSources.asDataSource(ByteBuffer.wrap(apkBytes))
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

        val misaligned = findMisalignedEntries(apkBytes)
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
     * Returns the names of entries whose data does not start on a 4-byte boundary.
     *
     * Android requires uncompressed entries to be aligned; an unaligned `resources.arsc`
     * is the classic repack failure, so this is checked on every entry rather than assumed.
     */
    private fun findMisalignedEntries(apkBytes: ByteArray): List<String> {
        val misaligned = mutableListOf<String>()
        readCentralDirectory(apkBytes) { name, dataOffset, _ ->
            if (dataOffset % 4 != 0L) misaligned.add(name)
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

    /** Data offset of the entry at [localOffset], i.e. past its local header and extra field. */
    private fun localHeaderDataOffset(apkBytes: ByteArray, buf: ByteBuffer, localOffset: Long): Long {
        val index = localOffset.toInt()
        if (index < 0 || index + 30 > apkBytes.size) return -1
        if (buf.getInt(index) != 0x04034b50) return -1
        val nameLength = buf.getShort(index + 26).toInt() and 0xFFFF
        val extraLength = buf.getShort(index + 28).toInt() and 0xFFFF
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
