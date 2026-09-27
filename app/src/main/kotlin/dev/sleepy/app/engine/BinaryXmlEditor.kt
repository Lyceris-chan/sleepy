package dev.sleepy.app.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Chunk-level editor for Android binary XML (`AndroidManifest.xml` and the files under `res/`).
 *
 * Unlike [BinaryXmlModifier], which rewrites the string pool to rename a package, this walks
 * the chunk tree and edits individual `START_TAG` attributes. A split-APK merge needs that:
 * the platform keys Android attributes off their resource ID, not their text, so an
 * attribute can only be neutralised by removing it or rewriting its typed value.
 *
 * ## Resolving an attribute's identity
 *
 * An attribute's `name` field is **not** a resource ID — it is an index into the string
 * pool. The resource ID is obtained by looking that index up in the document's
 * `RES_XML_RESOURCE_MAP_TYPE` chunk. Attributes declared outside the `android` namespace
 * (such as `package`) have no entry in the map and therefore no resource ID. Matching on the
 * raw `name` field silently finds nothing; that mistake is why the map is resolved here
 * first.
 */
object BinaryXmlEditor {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

    /** `AndroidManifest.xml` attribute resource IDs (`android` namespace). */
    const val ATTR_REQUIRED_SPLIT_TYPES = 0x0101064e
    const val ATTR_SPLIT_TYPES = 0x0101064f
    const val ATTR_EXTRACT_NATIVE_LIBS = 0x010104ea

    private const val TYPE_NULL = 0x00
    private const val TYPE_INT_BOOLEAN = 0x12

    private const val NODE_HEADER_SIZE = 16
    private const val ATTRIBUTE_EXT_SIZE = 20
    private const val ATTRIBUTE_SIZE = 20
    private const val ROOT_HEADER_SIZE = 8
    private const val CHUNK_HEADER_SIZE = 8

    /** No resource ID: the attribute is not a framework attribute. */
    private const val NO_RESOURCE_ID = 0

    /**
     * Result of an edit pass, describing what actually changed so the caller can report it
     * truthfully instead of assuming the edit landed.
     */
    data class EditResult(
        val bytes: ByteArray,
        val attributesRemoved: List<Int>,
        val attributesRewritten: List<Int>,
        val missing: List<Int>
    )

    /**
     * Removes every attribute in [stripAttributeIds] and rewrites every attribute in
     * [booleanOverrides] to the given boolean value, rebuilding the document with corrected
     * chunk sizes.
     *
     * Attributes that were requested but not found are reported in [EditResult.missing]
     * rather than silently ignored.
     */
    fun edit(
        xml: ByteArray,
        stripAttributeIds: Set<Int> = emptySet(),
        booleanOverrides: Map<Int, Boolean> = emptyMap()
    ): EditResult {
        val requested = stripAttributeIds + booleanOverrides.keys
        if (xml.size < ROOT_HEADER_SIZE) return EditResult(xml, emptyList(), emptyList(), requested.toList())

        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        if ((buf.getShort(0).toInt() and 0xFFFF) != RES_XML_TYPE) {
            return EditResult(xml, emptyList(), emptyList(), requested.toList())
        }

        val resourceIds = readResourceMap(buf, xml)
        val out = ByteArrayOutputStream(xml.size)
        out.write(ByteArray(ROOT_HEADER_SIZE)) // root header rewritten once the size is known

        val removed = mutableListOf<Int>()
        val rewritten = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()

        var offset = buf.getShort(2).toInt() and 0xFFFF
        while (offset + CHUNK_HEADER_SIZE <= xml.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < CHUNK_HEADER_SIZE || offset + chunkSize > xml.size) break

            if (type == RES_XML_START_ELEMENT_TYPE) {
                out.write(
                    editStartElement(
                        xml, buf, offset, chunkSize, resourceIds,
                        stripAttributeIds, booleanOverrides, removed, rewritten, seen
                    )
                )
            } else {
                out.write(xml, offset, chunkSize)
            }
            offset += chunkSize
        }

        val body = out.toByteArray()
        val header = ByteBuffer.allocate(ROOT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(RES_XML_TYPE.toShort())
        header.putShort(ROOT_HEADER_SIZE.toShort())
        header.putInt(body.size)
        System.arraycopy(header.array(), 0, body, 0, ROOT_HEADER_SIZE)

        return EditResult(body, removed, rewritten, requested.filter { it !in seen })
    }

    /**
     * Convenience wrapper for the split-merge case: drop the split declarations and force
     * `android:extractNativeLibs="true"` so merged, DEFLATE-compressed native libraries are
     * extracted at install time instead of being mapped out of the APK.
     */
    fun makeStandaloneManifest(manifestBytes: ByteArray): EditResult = edit(
        xml = manifestBytes,
        stripAttributeIds = setOf(ATTR_REQUIRED_SPLIT_TYPES, ATTR_SPLIT_TYPES),
        booleanOverrides = mapOf(ATTR_EXTRACT_NATIVE_LIBS to true)
    )

    /** Reads an attribute's typed value by resource ID, or null when absent or null-typed. */
    fun readAttributeValue(xml: ByteArray, attributeId: Int): Int? {
        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        if (xml.size < ROOT_HEADER_SIZE) return null
        if ((buf.getShort(0).toInt() and 0xFFFF) != RES_XML_TYPE) return null
        val resourceIds = readResourceMap(buf, xml)

        var offset = buf.getShort(2).toInt() and 0xFFFF
        while (offset + CHUNK_HEADER_SIZE <= xml.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < CHUNK_HEADER_SIZE || offset + chunkSize > xml.size) return null
            if (type == RES_XML_START_ELEMENT_TYPE) {
                val found = findAttributeValue(buf, xml, offset, chunkSize, resourceIds, attributeId)
                if (found != null) return found.value
            }
            offset += chunkSize
        }
        return null
    }

    /** Reads a boolean attribute as a boolean, or null when it is absent. */
    fun readBooleanAttribute(xml: ByteArray, attributeId: Int): Boolean? =
        readAttributeValue(xml, attributeId)?.let { it != 0 }

    /** Reads an integer attribute, or null when it is absent. */
    fun readIntAttribute(xml: ByteArray, attributeId: Int): Int? =
        readAttributeValue(xml, attributeId)

    private data class AttributeValue(val value: Int?)

    private fun findAttributeValue(
        buf: ByteBuffer,
        xml: ByteArray,
        chunkStart: Int,
        chunkSize: Int,
        resourceIds: IntArray,
        attributeId: Int
    ): AttributeValue? {
        if (chunkSize < ATTRIBUTE_EXT_SIZE) return null
        val attributeStart = buf.getShort(chunkStart + 24).toInt() and 0xFFFF
        val attributeSize = buf.getShort(chunkStart + 26).toInt() and 0xFFFF
        val attributeCount = buf.getShort(chunkStart + 28).toInt() and 0xFFFF
        if (attributeSize != ATTRIBUTE_SIZE) return null

        val attributesOffset = chunkStart + NODE_HEADER_SIZE + attributeStart
        for (i in 0 until attributeCount) {
            val attrStart = attributesOffset + i * attributeSize
            if (attrStart + ATTRIBUTE_SIZE > xml.size) return null
            if (attributeIdAt(buf, resourceIds, attrStart) != attributeId) continue
            // Res_value: size (u16) at +12, res0 (u8) at +14, dataType (u8) at +15, data (u32) at +16.
            val dataType = buf.get(attrStart + 15).toInt() and 0xFF
            if (dataType == TYPE_NULL) return AttributeValue(null)
            return AttributeValue(buf.getInt(attrStart + 16))
        }
        return null
    }

    private fun editStartElement(
        xml: ByteArray,
        buf: ByteBuffer,
        chunkStart: Int,
        chunkSize: Int,
        resourceIds: IntArray,
        stripAttributeIds: Set<Int>,
        booleanOverrides: Map<Int, Boolean>,
        removed: MutableList<Int>,
        rewritten: MutableList<Int>,
        seen: MutableSet<Int>
    ): ByteArray {
        val original = xml.copyOfRange(chunkStart, chunkStart + chunkSize)
        if (chunkSize < ATTRIBUTE_EXT_SIZE) return original

        val attributeStart = buf.getShort(chunkStart + 24).toInt() and 0xFFFF
        val attributeSize = buf.getShort(chunkStart + 26).toInt() and 0xFFFF
        val attributeCount = buf.getShort(chunkStart + 28).toInt() and 0xFFFF

        // `attributeStart` is an offset from the start of ResXMLTree_attrExt, which itself
        // begins 16 bytes into the chunk — not from the start of the chunk.
        val attributesOffset = NODE_HEADER_SIZE + attributeStart
        val attributesEnd = attributesOffset + attributeCount * attributeSize

        if (attributeSize != ATTRIBUTE_SIZE ||
            attributeStart < ATTRIBUTE_EXT_SIZE ||
            chunkStart + attributesEnd > xml.size
        ) {
            return original
        }

        val head = xml.copyOfRange(chunkStart, chunkStart + attributesOffset)
        val keep = ByteArrayOutputStream(attributeCount * attributeSize)
        var kept = 0
        // Removing an attribute and rewriting one in place are tracked separately: a pure
        // in-place rewrite leaves the count unchanged, and keying the "nothing happened"
        // check off the count alone would then discard the rewrite.
        var changed = false

        for (i in 0 until attributeCount) {
            val attrStart = chunkStart + attributesOffset + i * attributeSize
            val attributeId = attributeIdAt(buf, resourceIds, attrStart)
            val attr = xml.copyOfRange(attrStart, attrStart + attributeSize)

            if (attributeId != NO_RESOURCE_ID && attributeId in stripAttributeIds) {
                removed.add(attributeId)
                seen.add(attributeId)
                changed = true
                continue
            }

            val override = if (attributeId == NO_RESOURCE_ID) null else booleanOverrides[attributeId]
            if (override != null) {
                seen.add(attributeId)
                val previous = buf.getInt(attrStart + 16) != 0
                attr[12] = 8 // Res_value.size, low byte
                attr[13] = 0 // Res_value.size, high byte
                attr[14] = 0 // Res_value.res0
                attr[15] = TYPE_INT_BOOLEAN.toByte()
                val data = if (override) 1 else 0
                attr[16] = data.toByte()
                attr[17] = 0
                attr[18] = 0
                attr[19] = 0
                if (previous != override) {
                    rewritten.add(attributeId)
                    changed = true
                }
            }

            keep.write(attr)
            kept++
        }

        if (!changed) return original

        val newSize = attributesOffset + kept * attributeSize
        val headBuf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        headBuf.putInt(4, newSize)
        headBuf.putShort(28, kept.toShort())

        val rebuilt = ByteArrayOutputStream(newSize + (chunkSize - attributesEnd))
        rebuilt.write(head)
        rebuilt.write(keep.toByteArray())
        rebuilt.write(xml, chunkStart + attributesEnd, chunkSize - attributesEnd)
        return rebuilt.toByteArray()
    }

    /**
     * Resolves an attribute's resource ID from its string-pool index.
     *
     * `ResXMLTree_attribute.name` is an index into the string pool. The resource ID is
     * `resourceMap[name]` when that index is inside the map, and there is no resource ID
     * otherwise (a non-framework attribute such as `package`).
     */
    private fun attributeIdAt(buf: ByteBuffer, resourceIds: IntArray, attrStart: Int): Int {
        val nameIndex = buf.getInt(attrStart + 4)
        if (nameIndex < 0 || nameIndex >= resourceIds.size) return NO_RESOURCE_ID
        return resourceIds[nameIndex]
    }

    /** Reads the `RES_XML_RESOURCE_MAP_TYPE` chunk, which maps string indices to resource IDs. */
    private fun readResourceMap(buf: ByteBuffer, xml: ByteArray): IntArray {
        var offset = buf.getShort(2).toInt() and 0xFFFF
        while (offset + CHUNK_HEADER_SIZE <= xml.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val headerSize = buf.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < CHUNK_HEADER_SIZE || offset + chunkSize > xml.size) break
            if (type == RES_XML_RESOURCE_MAP_TYPE) {
                val count = (chunkSize - headerSize) / 4
                if (count <= 0) return IntArray(0)
                val ids = IntArray(count)
                for (i in 0 until count) {
                    ids[i] = buf.getInt(offset + headerSize + i * 4)
                }
                return ids
            }
            offset += chunkSize
        }
        return IntArray(0)
    }
}
