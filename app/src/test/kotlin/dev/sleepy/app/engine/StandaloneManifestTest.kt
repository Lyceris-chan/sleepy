package dev.sleepy.app.engine

import dev.sleepy.app.testing.ReferenceApks
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A split's manifest becomes a standalone one by dropping its split attributes.
 *
 * A base split's manifest declares the split types it belongs to and asks the platform to extract
 * native libraries; a standalone APK declares neither. The edit has to leave the rest of the
 * document alone, and a manifest without those attributes byte-identical.
 */
class StandaloneManifestTest {

    @Test
    fun editRemovesSplitAttributesAndRewritesExtractNativeLibs() {
        val manifest = manifestWith(
            intAttribute(BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES, 1),
            intAttribute(BinaryXmlEditor.ATTR_SPLIT_TYPES, 1),
            booleanAttribute(BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS, false),
            intAttribute(0x0101021b, 349205) // unrelated versionCode, must survive
        )

        assertEquals(
            "precondition: extractNativeLibs starts false",
            false,
            BinaryXmlEditor.readBooleanAttribute(manifest, BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS)
        )

        val result = BinaryXmlEditor.makeStandaloneManifest(manifest)

        assertEquals(
            listOf(BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES, BinaryXmlEditor.ATTR_SPLIT_TYPES),
            result.attributesRemoved
        )
        assertEquals(listOf(BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS), result.attributesRewritten)
        assertTrue("no requested attribute may go missing", result.missing.isEmpty())

        // The two split attributes must be gone, and extractNativeLibs must now read true.
        assertEquals(
            true,
            BinaryXmlEditor.readBooleanAttribute(
                result.bytes,
                BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS
            )
        )
        assertEquals(
            349205,
            BinaryXmlEditor.readIntAttribute(result.bytes, 0x0101021b)
        )

        // Chunk sizes must stay self-consistent or the platform cannot parse the manifest.
        assertChunksAreConsistent(result.bytes)
    }

    /**
     * The synthetic documents above only prove the editor is self-consistent. This runs it
     * against the real Discord base split, whose manifest declares
     * `requiredSplitTypes="base__abi,base__density"`—the attribute that made a base-only
     * APK unlaunchable—and whose attribute names resolve through a resource map rather
     * than being resource IDs inline.
     */
    @Test
    fun standaloneManifestOnRealDiscordSplit() {
        val apkFile = File(ReferenceApks.discordExtracted, "base.apk")
        assumeTrue("${apkFile.path} is not on this machine", apkFile.exists())

        val zip = ZipFile(apkFile)
        val entry = zip.getEntry("AndroidManifest.xml")
        assertNotNull("manifest must be present", entry)
        val manifest = zip.getInputStream(entry).readBytes()
        zip.close()

        assertEquals(
            "precondition: this build declares extractNativeLibs=false",
            false,
            BinaryXmlEditor.readBooleanAttribute(manifest, BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS)
        )
        assertNotNull(
            "precondition: requiredSplitTypes is present",
            BinaryXmlEditor.readAttributeValue(manifest, BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES)
        )
        // Read the version code out of the fixture rather than repeating it here, so the next
        // bump of the Discord source does not leave a stale expectation behind.
        val versionCode = BinaryXmlEditor.readIntAttribute(manifest, 0x0101021b)
        assertNotNull("precondition: the manifest declares a versionCode", versionCode)

        val result = BinaryXmlEditor.makeStandaloneManifest(manifest)

        assertEquals(
            listOf(BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES, BinaryXmlEditor.ATTR_SPLIT_TYPES),
            result.attributesRemoved
        )
        assertEquals(listOf(BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS), result.attributesRewritten)
        assertTrue(
            "every requested attribute must be found, missing=${result.missing}",
            result.missing.isEmpty()
        )

        assertNull(
            "requiredSplitTypes must be gone",
            BinaryXmlEditor.readAttributeValue(
                result.bytes,
                BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES
            )
        )
        assertNull(
            "splitTypes must be gone",
            BinaryXmlEditor.readAttributeValue(
                result.bytes,
                BinaryXmlEditor.ATTR_SPLIT_TYPES
            )
        )
        assertEquals(
            "extractNativeLibs must now be true",
            true,
            BinaryXmlEditor.readBooleanAttribute(
                result.bytes,
                BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS
            )
        )
        assertEquals(
            "unrelated attributes must survive",
            versionCode,
            BinaryXmlEditor.readIntAttribute(result.bytes, 0x0101021b)
        )
        assertEquals(
            "exactly two 20-byte attributes should have been removed",
            manifest.size - 2 * 20,
            result.bytes.size
        )
        assertChunksAreConsistent(result.bytes)
        println(
            "Real Discord manifest: split declarations removed, extractNativeLibs=true, " +
                "chunks consistent"
        )
    }

    @Test
    fun editIsANoOpWhenTheAttributesAreAbsent() {
        val manifest = manifestWith(intAttribute(0x0101021b, 7))
        val result = BinaryXmlEditor.makeStandaloneManifest(manifest)

        assertTrue(result.attributesRemoved.isEmpty())
        assertTrue(result.attributesRewritten.isEmpty())
        assertEquals(
            "both split attributes should be reported missing",
            2,
            result.missing.count {
                it == BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES ||
                    it == BinaryXmlEditor.ATTR_SPLIT_TYPES
            }
        )
        assertArrayEquals(manifest, result.bytes)
    }

    /**
     * Walks the chunk tree and requires it to tile the document exactly.
     *
     * The synthetic manifest holds a start element and no end element, so only the walk's size
     * and tiling claims are checked: the editor is given the document the app's own writer
     * produces, not a document the platform would load.
     */
    private fun assertChunksAreConsistent(xml: ByteArray) {
        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0003, buf.getShort(0).toInt() and 0xFFFF)
        assertEquals("root chunk size must equal the document length", xml.size, buf.getInt(4))
        var offset = buf.getShort(2).toInt() and 0xFFFF
        var seen = 0
        while (offset + 8 <= xml.size) {
            val chunkSize = buf.getInt(offset + 4)
            assertTrue("chunk at $offset has size $chunkSize", chunkSize >= 8)
            assertTrue("chunk at $offset overruns the document", offset + chunkSize <= xml.size)
            offset += chunkSize
            seen++
        }
        assertEquals("chunks must tile the document exactly", xml.size, offset)
        assertTrue(seen > 0)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue("byte arrays must be identical", expected.contentEquals(actual))
    }

    private fun intAttribute(resourceId: Int, value: Int): ByteArray =
        attribute(resourceId, 0x10, value) // TYPE_INT_DEC

    private fun booleanAttribute(resourceId: Int, value: Boolean): ByteArray =
        attribute(resourceId, 0x12, if (value) 1 else 0) // TYPE_INT_BOOLEAN

    /**
     * Builds one attribute. Its `name` field is a **string-pool index**, resolved through
     * the resource map exactly as the platform does—writing the resource ID there is the
     * mistake the real Discord manifest exposed.
     */
    private fun attribute(resourceId: Int, type: Int, data: Int): ByteArray {
        val nameIndex = RESOURCE_IDS.indexOf(resourceId)
        require(nameIndex >= 0) {
            "resource id ${Integer.toHexString(resourceId)} is not in the test resource map"
        }
        return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0)          // ns
            putInt(nameIndex)  // name: string-pool index, NOT the resource id
            putInt(0)          // rawValue
            putShort(8.toShort()) // Res_value.size
            put(0)                // res0
            put(type.toByte())    // Res_value.dataType
            putInt(data)          // Res_value.data
        }.array()
    }

    /**
     * Serializes a single `<manifest>` start tag carrying [attributes] inside a
     * RES_XML_TYPE document, with an empty string pool—enough for the editor to walk.
     */
    private fun manifestWith(vararg attributes: ByteArray): ByteArray {
        val attrBytes = attributes.fold(ByteArray(0)) { acc, a -> acc + a }
        val attrStart = 20
        val startElementSize = 16 + attrStart + attrBytes.size

        val startElement =
            ByteBuffer.allocate(startElementSize).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0x0102.toShort())               // RES_XML_START_ELEMENT_TYPE
                putShort(16.toShort())                   // headerSize
                putInt(startElementSize)                 // size
                putInt(1)                                // lineNumber
                putInt(0xFFFFFFFF.toInt())               // comment
                putInt(0xFFFFFFFF.toInt())               // ns
                putInt(0xFFFFFFFF.toInt())               // name
                putShort(attrStart.toShort())            // attributeStart
                putShort(20.toShort())                   // attributeSize
                putShort(attributes.size.toShort())      // attributeCount
                putShort(0.toShort())                    // idIndex
                putShort(0.toShort())                    // classIndex
                putShort(0.toShort())                    // styleIndex
                put(attrBytes)
            }.array()

        val resourceMap =
            ByteBuffer.allocate(8 + RESOURCE_IDS.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0x0180.toShort())            // RES_XML_RESOURCE_MAP_TYPE
                putShort(8.toShort())                 // headerSize
                putInt(8 + RESOURCE_IDS.size * 4)     // size
                RESOURCE_IDS.forEach { putInt(it) }
            }.array()

        val total = 8 + resourceMap.size + startElement.size
        val root = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0003.toShort()) // RES_XML_TYPE
            putShort(8.toShort())      // headerSize
            putInt(total)
        }.array()

        return root + resourceMap + startElement
    }

    private companion object {
        /** Index i holds the resource ID that string-pool index i resolves to. */
        val RESOURCE_IDS = intArrayOf(
            0x0101021b, // versionCode, used as an unrelated attribute that must survive
            BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES,
            BinaryXmlEditor.ATTR_SPLIT_TYPES,
            BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS
        )
    }
}
