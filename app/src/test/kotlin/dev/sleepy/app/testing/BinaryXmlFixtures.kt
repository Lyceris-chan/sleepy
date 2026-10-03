package dev.sleepy.app.testing

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Chunk-level readers the binary XML tests share.
 *
 * The chunk walk is the platform's own: every test that edits a document checks that the result
 * still tiles exactly, and every test that compares a document against another walks the same
 * chunks. Keeping one walk means a change to what "well formed" means happens in one place.
 */

/**
 * Walks the chunk tree and requires it to tile [xml] exactly.
 *
 * This is the same walk the platform performs: a chunk that overruns the file, or a size smaller
 * than a header, is a document the platform rejects. [label] names the document in a failure.
 */
fun assertTilesExactly(xml: ByteArray, label: String) {
    val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(
        "$label: root chunk is not binary XML",
        0x0003,
        buf.getShort(0).toInt() and 0xFFFF
    )
    assertEquals("$label: root size does not match the file", xml.size, buf.getInt(4))

    var offset = buf.getShort(2).toInt() and 0xFFFF
    var starts = 0
    var ends = 0
    while (offset + 8 <= xml.size) {
        val type = buf.getShort(offset).toInt() and 0xFFFF
        val chunkSize = buf.getInt(offset + 4)
        assertTrue("$label: chunk at $offset has size $chunkSize", chunkSize >= 8)
        assertTrue("$label: chunk at $offset overruns the file", offset + chunkSize <= xml.size)
        if (type == 0x0102) starts++
        if (type == 0x0103) ends++
        offset += chunkSize
    }
    assertEquals("$label: the walk must end exactly at the end of the file", xml.size, offset)
    assertEquals("$label: every element must still be closed", starts, ends)
    assertTrue("$label: no elements were walked", starts > 0)
}

/** Every chunk of [xml], as (type, bytes), in the order the walk visits them. */
fun chunksOf(xml: ByteArray): List<Pair<Int, ByteArray>> {
    val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
    val chunks = mutableListOf<Pair<Int, ByteArray>>()
    var offset = buf.getShort(2).toInt() and 0xFFFF
    while (offset + 8 <= xml.size) {
        val type = buf.getShort(offset).toInt() and 0xFFFF
        val size = buf.getInt(offset + 4)
        if (size < 8 || offset + size > xml.size) break
        chunks.add(type to xml.copyOfRange(offset, offset + size))
        offset += size
    }
    return chunks
}

/** Byte-array pairs compare by identity, so the chunk comparison is spelled out. */
fun sameChunk(a: Pair<Int, ByteArray>, b: Pair<Int, ByteArray>): Boolean =
    a.first == b.first && a.second.contentEquals(b.second)

/** A `RES_XML_START_ELEMENT_TYPE` chunk naming [nameIndex] with the given attribute pairs. */
fun startTag(nameIndex: Int, attributes: List<Pair<Int, Int>>): ByteArray {
    val attributeStart = 20
    val size = 16 + attributeStart + attributes.size * 20
    val chunk = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    chunk.putShort(0x0102)
    chunk.putShort(16)
    chunk.putInt(size)
    chunk.putInt(1)                  // lineNumber
    chunk.putInt(0xFFFFFFFF.toInt()) // comment
    chunk.putInt(-1)                 // ns
    chunk.putInt(nameIndex)
    chunk.putShort(attributeStart.toShort())
    chunk.putShort(20)               // attributeSize
    chunk.putShort(attributes.size.toShort())
    chunk.putShort(0)                // idIndex
    chunk.putShort(0)                // classIndex
    chunk.putShort(0)                // styleIndex
    for ((attributeNameIndex, valueIndex) in attributes) {
        chunk.putInt(0)              // ns: the android namespace is resolved by resource map
        chunk.putInt(attributeNameIndex)
        chunk.putInt(0xFFFFFFFF.toInt()) // rawValue
        chunk.putShort(8)            // Res_value.size
        chunk.put(0)                 // res0
        chunk.put(0x03)              // TYPE_STRING
        chunk.putInt(valueIndex)
    }
    return chunk.array()
}

/** A `RES_XML_END_ELEMENT_TYPE` chunk naming [nameIndex]. */
fun endTag(nameIndex: Int): ByteArray =
    ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0x0103)
        putShort(16)
        putInt(24)
        putInt(1)                  // lineNumber
        putInt(0xFFFFFFFF.toInt()) // comment
        putInt(-1)                 // ns
        putInt(nameIndex)
    }.array()
