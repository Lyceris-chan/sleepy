package dev.sleepy.app.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object BinaryXmlModifier {

    /**
     * Modifies the package name and provider authorities in an Android binary XML manifest (AndroidManifest.xml).
     *
     * This allows cloning the app so it can be installed alongside the original official package.
     */
    fun modifyPackageName(
        manifestBytes: ByteArray,
        oldPackageName: String,
        newPackageName: String
    ): ByteArray {
        if (oldPackageName == newPackageName || newPackageName.isBlank()) {
            return manifestBytes
        }

        val buf = ByteBuffer.wrap(manifestBytes).order(ByteOrder.LITTLE_ENDIAN)

        val rootType = buf.short.toInt() and 0xFFFF
        val rootHdr = buf.short.toInt() and 0xFFFF
        val rootSize = buf.int

        if (rootType != 0x0003) {
            // Not a valid RES_XML_TYPE chunk
            return manifestBytes
        }

        val spType = buf.short.toInt() and 0xFFFF
        val spHdr = buf.short.toInt() and 0xFFFF
        val spSize = buf.int

        if (spType != 0x0001) {
            // Not a valid RES_STRING_POOL_TYPE chunk
            return manifestBytes
        }

        val stringCount = buf.int
        val styleCount = buf.int
        val flags = buf.int
        val stringsStartOff = buf.int
        val stylesStartOff = buf.int

        val isUtf8 = (flags and (1 shl 8)) != 0

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = buf.int
        }

        val baseStringsPos = 8 + stringsStartOff
        val strings = ArrayList<String>(stringCount)

        for (i in 0 until stringCount) {
            var pos = baseStringsPos + offsets[i]
            if (pos >= manifestBytes.size) break

            if (isUtf8) {
                var l1 = manifestBytes[pos].toInt() and 0xFF
                pos++
                if ((l1 and 0x80) != 0) pos++
                var l2 = manifestBytes[pos].toInt() and 0xFF
                pos++
                if ((l2 and 0x80) != 0) pos++
                val strBytes = manifestBytes.copyOfRange(pos, (pos + l2).coerceAtMost(manifestBytes.size))
                strings.add(String(strBytes, Charsets.UTF_8))
            } else {
                val u16Len = ((manifestBytes[pos + 1].toInt() and 0xFF) shl 8) or (manifestBytes[pos].toInt() and 0xFF)
                pos += 2
                val byteCount = u16Len * 2
                val strBytes = manifestBytes.copyOfRange(pos, (pos + byteCount).coerceAtMost(manifestBytes.size))
                strings.add(String(strBytes, Charsets.UTF_16LE))
            }
        }

        val modifiedStrings = strings.map { s ->
            s.replace(oldPackageName, newPackageName)
        }

        // Re-serialize string pool
        val strData = ByteArrayOutputStream()
        val newOffsets = IntArray(stringCount)

        for (i in 0 until stringCount) {
            newOffsets[i] = strData.size()
            val s = modifiedStrings[i]

            if (isUtf8) {
                val b = s.toByteArray(Charsets.UTF_8)
                strData.write(s.length and 0x7F)
                strData.write(b.size and 0x7F)
                strData.write(b)
                strData.write(0)
            } else {
                val b = s.toByteArray(Charsets.UTF_16LE)
                strData.write(s.length and 0xFF)
                strData.write((s.length shr 8) and 0xFF)
                strData.write(b)
                strData.write(0)
                strData.write(0)
            }
        }

        // Pad strData to 4-byte boundary
        while (strData.size() % 4 != 0) {
            strData.write(0)
        }

        var newSpHdrAndOffsetsLen = spHdr + stringCount * 4
        while (newSpHdrAndOffsetsLen % 4 != 0) {
            newSpHdrAndOffsetsLen++
        }

        val newStrStartOff = newSpHdrAndOffsetsLen
        val newSpSize = newStrStartOff + strData.size()

        val newSp = ByteArrayOutputStream()
        val spHeaderBuf = ByteBuffer.allocate(spHdr).order(ByteOrder.LITTLE_ENDIAN)
        spHeaderBuf.putShort(spType.toShort())
        spHeaderBuf.putShort(spHdr.toShort())
        spHeaderBuf.putInt(newSpSize)
        spHeaderBuf.putInt(stringCount)
        spHeaderBuf.putInt(styleCount)
        spHeaderBuf.putInt(flags)
        spHeaderBuf.putInt(newStrStartOff)
        spHeaderBuf.putInt(stylesStartOff)
        newSp.write(spHeaderBuf.array())

        val offsetsBuf = ByteBuffer.allocate(stringCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (o in newOffsets) {
            offsetsBuf.putInt(o)
        }
        newSp.write(offsetsBuf.array())

        while (newSp.size() < newStrStartOff) {
            newSp.write(0)
        }
        newSp.write(strData.toByteArray())

        val remainderStart = 8 + spSize
        val remainder = if (remainderStart < manifestBytes.size) {
            manifestBytes.copyOfRange(remainderStart, manifestBytes.size)
        } else {
            ByteArray(0)
        }

        val newRootSize = 8 + newSp.size() + remainder.size
        val rootHeaderBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        rootHeaderBuf.putShort(rootType.toShort())
        rootHeaderBuf.putShort(rootHdr.toShort())
        rootHeaderBuf.putInt(newRootSize)

        val result = ByteArrayOutputStream(newRootSize)
        result.write(rootHeaderBuf.array())
        result.write(newSp.toByteArray())
        result.write(remainder)

        return result.toByteArray()
    }
}
