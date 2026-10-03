package dev.sleepy.app.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alignment verdicts for archives whose central directory the classic record cannot describe.
 *
 * A ZIP64 archive is read through its locator record, and an archive with no readable directory
 * at all is reported as unknown. Neither may be reported as aligned: the caller treats an empty
 * misalignment list as a pass, so "could not read the archive" and "the archive is aligned" must
 * not share a value.
 */
class ApkVerifierAlignmentTest {

    /**
     * A misaligned ZIP64 archive on disk, where both readers can open it.
     *
     * The claim is the one the pipeline depends on: the file reader—the reader the pipeline
     * uses—must report the same misalignment as the whole-file reader, and must not report an
     * archive as aligned when it is not.
     */
    @Test
    fun aMisalignedZip64ArchiveIsNeverReportedAsAligned() {
        val archive = File.createTempFile("sleepy-zip64-", ".apk")
        try {
            val layout = writeZip64Archive(archive)

            // The premise, asserted rather than assumed: the ZIP64 end-of-central-directory
            // record lies further into the file than the tail the reader starts from, so the
            // old bounds test against that tail could not have found it.
            assertTrue(
                "the fixture must put its ZIP64 record outside the reader's tail window",
                layout.zip64RecordOffset > archive.length() - TAIL_WINDOW_BYTES
            )

            // An independent reader—the platform's own—must accept the fixture, so a
            // malformed archive cannot be what makes the assertions below pass or fail.
            ZipFile(archive).use { zip ->
                assertEquals(
                    "the platform's ZIP reader must see every entry",
                    layout.entries.map { it.name },
                    zip.entries().asSequence().map { it.name }.toList()
                )
                for (entry in layout.entries) {
                    val stored = zip.getInputStream(zip.getEntry(entry.name)).readBytes()
                    assertTrue(
                        "the platform's reader must return ${entry.name}'s data intact",
                        stored.contentEquals(entry.data)
                    )
                }
            }

            val expected = layout.entries
                .filter { entry ->
                    val required = ZipAlignment.requiredFor(entry.name, ZipEntry.STORED)
                    required > 0 && entry.dataOffset % required != 0L
                }
                .map { it.name }
            assertTrue(
                "the fixture must contain a misaligned entry, or this proves nothing",
                expected.isNotEmpty()
            )

            // The whole-file reader reads the ZIP64 record correctly, so its result is the
            // expected one; the file reader has to agree with it.
            val inMemory = ApkVerifier.verify(archive.readBytes())
            assertEquals(
                "the whole-file reader must see the misalignment",
                expected,
                inMemory.misalignedEntries
            )
            assertEquals(false, inMemory.zipalignPassed)

            val onDisk = ApkVerifier.verify(archive)
            assertEquals(
                "the file reader and the whole-file reader must reach the same verdict",
                expected,
                onDisk.misalignedEntries
            )
            assertEquals(
                "a misaligned ZIP64 archive must not read as aligned",
                false,
                onDisk.zipalignPassed
            )
            assertNull("a directory that was read has no error to report", onDisk.directoryError)
        } finally {
            archive.delete()
        }
    }

    /**
     * An archive whose directory cannot be read is a third outcome, not a pass.
     *
     * A zero-byte file is the smallest instance of it: there is no end-of-central-directory
     * record to find, so no entry was ever measured. Reporting that as "aligned" is the claim
     * this asserts against.
     */
    @Test
    fun anArchiveWithNoReadableDirectoryIsUnknownRatherThanAligned() {
        val empty = File.createTempFile("sleepy-empty-", ".apk")
        try {
            empty.writeBytes(ByteArray(0))

            val onDisk = ApkVerifier.verify(empty)
            assertNull("nothing was measured, so the answer is unknown", onDisk.zipalignPassed)
            assertTrue(
                "the misaligned list must not stand in for an answer",
                onDisk.misalignedEntries.isEmpty()
            )
            assertNotNull("and the reason has to reach the caller", onDisk.directoryError)

            val inMemory = ApkVerifier.verify(ByteArray(0))
            assertNull("the whole-file reader agrees", inMemory.zipalignPassed)
            assertNotNull(inMemory.directoryError)
        } finally {
            empty.delete()
        }
    }

    // --- the fixture ---------------------------------------------------------------------

    /** One entry of the fixture archive, and the offset of its data. */
    private class FixtureEntry(val name: String, val data: ByteArray, val dataOffset: Long)

    /** The fixture archive's layout, for asserting against a fact rather than a guess. */
    private class Fixture(
        val entries: List<FixtureEntry>,
        val zip64RecordOffset: Long
    )

    /**
     * Writes a ZIP64 archive of stored entries to [file] and reports the offsets of its records.
     *
     * The archive is written here rather than by `ZipOutputStream` because the JDK's writer
     * only emits the ZIP64 records for archives that need them (`Zip64Mode.Always`, which the
     * Android SDK's `java.util.zip` does not have). This is the shape a ZIP64 archive actually
     * has: the three classic end-of-central-directory fields are saturated, so a reader has to
     * follow the locator to get the real count and the real directory offset.
     *
     * The entries are stored and their names differ in length, so their data offsets differ in
     * their low bits: one lands on a 4-byte boundary and two do not, one of those being a
     * stored native library, which needs page alignment. The padding entry keeps the file
     * larger than the reader's tail window, which is the condition that used to make the file
     * reader lose the ZIP64 record entirely.
     */
    private fun writeZip64Archive(file: File): Fixture {
        val entries = listOf(
            FixtureEntry("assets/pad.bin", ByteArray(PAD_BYTES) { 0x41.toByte() }, 0L),
            FixtureEntry("lib/arm64-v8a/libfoo.so", ByteArray(64) { 0x7F.toByte() }, 0L),
            FixtureEntry("resources.arsc", ByteArray(16) { 0x03.toByte() }, 0L)
        )

        val out = ByteArrayOutputStream()
        val dataOffsets = mutableListOf<Long>()
        val localOffsets = mutableListOf<Long>()
        for (entry in entries) {
            localOffsets.add(out.size().toLong())
            out.write(localHeader(entry.name, entry.data))
            dataOffsets.add(out.size().toLong())
            out.write(entry.data)
        }

        val directory = ByteArrayOutputStream()
        for ((index, entry) in entries.withIndex()) {
            directory.write(centralHeader(entry, localOffsets[index]))
        }
        val directoryBytes = directory.toByteArray()
        val directoryOffset = out.size().toLong()
        out.write(directoryBytes)

        val zip64RecordOffset = out.size().toLong()
        out.write(zip64EndOfCentralDirectory(entries.size, directoryBytes.size, directoryOffset))
        out.write(zip64Locator(zip64RecordOffset))
        out.write(endOfCentralDirectory())

        file.writeBytes(out.toByteArray())

        return Fixture(
            entries = entries.mapIndexed { index, entry ->
                FixtureEntry(entry.name, entry.data, dataOffsets[index])
            },
            zip64RecordOffset = zip64RecordOffset
        )
    }

    /** A stored entry's local file header followed by its name, with no extra field. */
    private fun localHeader(name: String, data: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(30 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x04034b50)
            putShort(20)                    // version needed to extract
            putShort(0)                     // general purpose flags
            putShort(ZipEntry.STORED.toShort())
            putShort(0)                     // modification time
            putShort(0)                     // modification date
            putInt(crcOf(data).toInt())
            putInt(data.size)
            putInt(data.size)
            putShort(nameBytes.size.toShort())
            putShort(0)                     // extra field length
            put(nameBytes)
        }.array()
    }

    /** The central directory's record of one entry, pointing back at its local header. */
    private fun centralHeader(entry: FixtureEntry, localOffset: Long): ByteArray {
        val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(46 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x02014b50)
            putShort(20)                    // version made by
            putShort(20)                    // version needed to extract
            putShort(0)                     // general purpose flags
            putShort(ZipEntry.STORED.toShort())
            putShort(0)                     // modification time
            putShort(0)                     // modification date
            putInt(crcOf(entry.data).toInt())
            putInt(entry.data.size)
            putInt(entry.data.size)
            putShort(nameBytes.size.toShort())
            putShort(0)                     // extra field length
            putShort(0)                     // file comment length
            putShort(0)                     // disk number start
            putShort(0)                     // internal attributes
            putInt(0)                       // external attributes
            putInt(localOffset.toInt())
            put(nameBytes)
        }.array()
    }

    /** The 56-byte ZIP64 end-of-central-directory record the locator points at. */
    private fun zip64EndOfCentralDirectory(
        entryCount: Int,
        directorySize: Int,
        directoryOffset: Long
    ): ByteArray =
        ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x06064b50)
            putLong(44)                     // size of this record, less its first 12 bytes
            putShort(45)                    // version made by
            putShort(45)                    // version needed to extract
            putInt(0)                       // this disk
            putInt(0)                       // disk the directory starts on
            putLong(entryCount.toLong())    // entries on this disk
            putLong(entryCount.toLong())    // entries in total
            putLong(directorySize.toLong())
            putLong(directoryOffset)
        }.array()

    /** The 20-byte locator that names the record above, by absolute file offset. */
    private fun zip64Locator(recordOffset: Long): ByteArray =
        ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x07064b50)
            putInt(0)                       // disk the ZIP64 record is on
            putLong(recordOffset)
            putInt(1)                       // total number of disks
        }.array()

    /**
     * The classic record, with all three fields a ZIP64 archive saturates left saturated:
     * a reader that does not follow the locator reads 65,535 entries at offset 4 GB.
     */
    private fun endOfCentralDirectory(): ByteArray =
        ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x06054b50)
            putShort(0)                     // this disk
            putShort(0)                     // disk the directory starts on
            putShort(0xFFFF.toShort())      // entries on this disk: saturated
            putShort(0xFFFF.toShort())      // entries in total: saturated
            putInt(-1)                      // directory size: saturated
            putInt(-1)                      // directory offset: saturated
            putShort(0)                     // comment length
        }.array()

    private fun crcOf(data: ByteArray): Long = CRC32().apply { update(data) }.value

    private companion object {
        /** The window [ApkVerifier] reads from the end of a file: 22 + 0xFFFF + 20 bytes. */
        const val TAIL_WINDOW_BYTES = 22 + 0xFFFF + 20

        /** Enough that the archive is several times the reader's tail window. */
        const val PAD_BYTES = 200_000
    }
}
