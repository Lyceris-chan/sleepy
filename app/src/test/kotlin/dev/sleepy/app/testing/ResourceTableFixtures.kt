package dev.sleepy.app.testing

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals

/**
 * A synthetic `resources.arsc` for the resource-table merge tests, built and read at the byte
 * level.
 *
 * A merge moves two indexes in a copied entry: the entry's name index into the package key pool
 * and every string value's index into the table's global pool. Checking that needs a table the
 * test controls down to the byte, which a real APK does not offer: this one holds exactly the
 * pools, package header and type chunks a merge reads, so a wrongly rebased index still falls
 * inside the table and resolves to a different string, and the test can tell the difference.
 */

/** `RES_TABLE_TYPE`. */
private const val TABLE = 0x0002

/** `RES_STRING_POOL_TYPE`. */
private const val STRING_POOL = 0x0001

/** `RES_TABLE_PACKAGE_TYPE`. */
private const val PACKAGE = 0x0200

/** `RES_TABLE_TYPE_SPEC_TYPE`. */
private const val TYPE_SPEC = 0x0202

/** `RES_TABLE_TYPE_TYPE`. */
private const val TYPE_CHUNK = 0x0201

/** `ResTable_entry` flag: the entry is a bag of name/value pairs. */
private const val COMPLEX = 0x0001

/** `Res_value.dataType` for a value that indexes the global string pool. */
const val TYPE_STRING = 3

/** The attribute a bag's map names. Nothing reads the id; a merge must not move it. */
const val BAG_ATTRIBUTE = 0x01000001

private const val CHUNK_HEADER = 8
private const val TABLE_HEADER = 12
private const val POOL_HEADER = 28
private const val PACKAGE_HEADER = 288
private const val TYPE_FIXED_HEADER = 20
private const val SPEC_HEADER = 16
private const val ENTRY_HEADER = 8
private const val MAP_ENTRY_HEADER = 16
private const val MAP_SIZE = 12

/** Where `ResTable_map`'s `Res_value` starts, behind the map's `name`. */
private const val MAP_VALUE_OFFSET = 4

/** A `Res_value` is eight bytes: a size, a reserved byte, a type and four of data. */
private const val VALUE_SIZE = 8

/** The empty configuration that a base's own entries use. */
val DEFAULT_CONFIG = ByteArray(4).also { putU32(it, 0, 4) }

/** A `de` configuration, which is what keeps a split's entries out of the base's slots. */
val GERMAN_CONFIG = ByteArray(12).also {
    putU32(it, 0, 12)
    it[4] = 'd'.code.toByte()
    it[5] = 'e'.code.toByte()
}

/** One type chunk of a fixture: the configuration it is under and the entries it holds. */
class TableType(val config: ByteArray, val entries: Map<Int, ByteArray>)

/** One entry of a table read back: where it sits, its name index, and the values it holds. */
class TableEntry(
    val config: ByteArray,
    val index: Int,
    val key: Int,
    val values: List<TableValue>
)

/** One `Res_value` read back: the map name that introduced it, its type, and its data. */
class TableValue(val name: Int, val type: Int, val data: Int)

/**
 * A whole `resources.arsc` holding [strings], entry names [keys] and the type chunks [types],
 * with package 0x7f and one type named `array`.
 *
 * Everything a merge reads is written, and nothing else: the pools, the package header and the
 * type chunks. The layout is the platform's, so what [readTableEntries] finds in the merged table
 * is what any other reader of it finds.
 */
fun resourceTable(
    strings: List<String>,
    keys: List<String>,
    types: List<TableType>
): ByteArray {
    val global = pool(strings)
    val typeNames = pool(listOf("array"))
    val keyPool = pool(keys)
    val body = body(types)

    val header = ByteArray(PACKAGE_HEADER)
    putU16(header, 0, PACKAGE)
    putU16(header, 2, PACKAGE_HEADER)
    putU32(header, 4, PACKAGE_HEADER + typeNames.size + keyPool.size + body.size)
    putU32(header, 8, 0x7f)
    putU32(header, 268, PACKAGE_HEADER)
    putU32(header, 276, PACKAGE_HEADER + typeNames.size)

    val table = ByteArray(
        TABLE_HEADER + global.size + header.size + typeNames.size + keyPool.size + body.size
    )
    putU16(table, 0, TABLE)
    putU16(table, 2, TABLE_HEADER)
    putU32(table, 4, table.size)
    putU32(table, 8, 1)
    var at = TABLE_HEADER
    for (part in listOf(global, header, typeNames, keyPool, body)) {
        part.copyInto(table, at)
        at += part.size
    }
    return table
}

/** A `ResTable_entry` with key [key] holding one string-valued `Res_value`. */
fun simpleEntry(key: Int, poolIndex: Int): ByteArray {
    val entry = ByteArray(ENTRY_HEADER + VALUE_SIZE)
    putU16(entry, 0, ENTRY_HEADER)
    putU16(entry, 2, 0)
    putU32(entry, 4, key)
    putValue(entry, ENTRY_HEADER, poolIndex)
    return entry
}

/**
 * A `ResTable_map_entry` with key [key] holding one map: the attribute [BAG_ATTRIBUTE] naming a
 * string-valued `Res_value` at [poolIndex].
 */
fun bagEntry(key: Int, poolIndex: Int): ByteArray {
    val entry = ByteArray(MAP_ENTRY_HEADER + MAP_SIZE)
    putU16(entry, 0, MAP_ENTRY_HEADER)
    putU16(entry, 2, COMPLEX)
    putU32(entry, 4, key)
    putU32(entry, 8, 0)
    putU32(entry, 12, 1)
    putU32(entry, MAP_ENTRY_HEADER, BAG_ATTRIBUTE)
    putValue(entry, MAP_ENTRY_HEADER + MAP_VALUE_OFFSET, poolIndex)
    return entry
}

/**
 * Every entry of type 1 in [table], read out of the bytes.
 *
 * This is deliberately a reader of the format rather than of anything a merge produced: the
 * map's value is read four bytes into the map, where the format puts it, so a merge that wrote
 * somewhere else is seen to have written somewhere else.
 */
fun readTableEntries(table: ByteArray): List<TableEntry> {
    var at = u16(table, 2)
    var packageAt = -1
    while (at + CHUNK_HEADER <= table.size) {
        val size = u32(table, at + 4)
        check(size >= CHUNK_HEADER && at + size <= table.size) {
            "a chunk at $at overruns the table"
        }
        if (u16(table, at) == PACKAGE) packageAt = at
        at += size
    }
    check(packageAt > 0) { "the table holds no package" }

    val packageEnd = packageAt + u32(table, packageAt + 4)
    val found = ArrayList<TableEntry>()
    at = packageAt + u16(table, packageAt + 2)
    while (at + CHUNK_HEADER <= packageEnd) {
        val size = u32(table, at + 4)
        if (u16(table, at) == TYPE_CHUNK) {
            val entryCount = u32(table, at + 12)
            val entriesStart = u32(table, at + 16)
            val configSize = u32(table, at + TYPE_FIXED_HEADER)
            val config =
                table.copyOfRange(at + TYPE_FIXED_HEADER, at + TYPE_FIXED_HEADER + configSize)
            for (i in 0 until entryCount) {
                val offset = u32(table, at + TYPE_FIXED_HEADER + configSize + i * 4)
                if (offset == -1) continue
                val entryAt = at + entriesStart + offset
                val headerSize = u16(table, entryAt)
                val flags = u16(table, entryAt + 2)
                val key = u32(table, entryAt + 4)
                val values = ArrayList<TableValue>()
                if (flags and COMPLEX != 0) {
                    val count = u32(table, entryAt + 12)
                    for (m in 0 until count) {
                        val mapAt = entryAt + MAP_ENTRY_HEADER + m * MAP_SIZE
                        values.add(
                            readValue(table, u32(table, mapAt), mapAt + MAP_VALUE_OFFSET)
                        )
                    }
                } else {
                    values.add(readValue(table, -1, entryAt + headerSize))
                }
                found.add(TableEntry(config, i, key, values))
            }
        }
        at += size
    }
    return found
}

/** The package's children: a type spec and a type chunk for every type [types] declares. */
private fun body(types: List<TableType>): ByteArray {
    val out = ByteArrayOutputStream()
    for (type in types) {
        val count = type.entries.keys.max() + 1
        out.write(specChunk(count))
        out.write(typeChunk(type.config, count, type.entries))
    }
    return out.toByteArray()
}

/** A `ResTable_typeSpec` for type 1 carrying [entryCount] zero flags. */
private fun specChunk(entryCount: Int): ByteArray {
    val chunk = ByteArray(SPEC_HEADER + entryCount * 4)
    putU16(chunk, 0, TYPE_SPEC)
    putU16(chunk, 2, SPEC_HEADER)
    putU32(chunk, 4, chunk.size)
    chunk[8] = 1
    putU32(chunk, 12, entryCount)
    return chunk
}

/** A `ResTable_type` for type 1 under [config], holding [entries] by index. */
private fun typeChunk(
    config: ByteArray,
    entryCount: Int,
    entries: Map<Int, ByteArray>
): ByteArray {
    val headerSize = TYPE_FIXED_HEADER + config.size
    val offsets = ByteArray(entryCount * 4)
    val data = ByteArrayOutputStream()
    for (i in 0 until entryCount) {
        val entry = entries[i]
        if (entry == null) {
            putU32(offsets, i * 4, -1)
        } else {
            putU32(offsets, i * 4, data.size())
            data.write(entry)
        }
    }
    val written = data.toByteArray()
    val chunk = ByteArray(headerSize + offsets.size + written.size)
    putU16(chunk, 0, TYPE_CHUNK)
    putU16(chunk, 2, headerSize)
    putU32(chunk, 4, chunk.size)
    chunk[8] = 1
    putU32(chunk, 12, entryCount)
    putU32(chunk, 16, headerSize + offsets.size)
    config.copyInto(chunk, TYPE_FIXED_HEADER)
    offsets.copyInto(chunk, headerSize)
    written.copyInto(chunk, headerSize + offsets.size)
    return chunk
}

/** Writes a string-valued `Res_value` starting at [at]. */
private fun putValue(bytes: ByteArray, at: Int, poolIndex: Int) {
    putU16(bytes, at, VALUE_SIZE)
    bytes[at + 2] = 0
    bytes[at + 3] = TYPE_STRING.toByte()
    putU32(bytes, at + 4, poolIndex)
}

/**
 * A `ResStringPool` holding [strings] in that order, UTF-8, with one-byte length prefixes.
 *
 * Nothing is deduplicated or sorted: index *i* is `strings[i]`, which is what a test's expected
 * indexes are written against.
 */
private fun pool(strings: List<String>): ByteArray {
    val offsets = ByteArray(strings.size * 4)
    val data = ByteArrayOutputStream()
    for ((i, string) in strings.withIndex()) {
        putU32(offsets, i * 4, data.size())
        val bytes = string.toByteArray(Charsets.UTF_8)
        check(bytes.size < 0x80) { "a one-byte length prefix is all this writes" }
        data.write(bytes.size)
        data.write(bytes.size)
        data.write(bytes)
        data.write(0)
    }
    val content = data.toByteArray()
    val size = align4(POOL_HEADER + offsets.size + content.size)
    val chunk = ByteArray(size)
    putU16(chunk, 0, STRING_POOL)
    putU16(chunk, 2, POOL_HEADER)
    putU32(chunk, 4, size)
    putU32(chunk, 8, strings.size)
    putU32(chunk, 12, 0)
    putU32(chunk, 16, 0x100)
    putU32(chunk, 20, POOL_HEADER + offsets.size)
    putU32(chunk, 24, 0)
    offsets.copyInto(chunk, POOL_HEADER)
    content.copyInto(chunk, POOL_HEADER + offsets.size)
    return chunk
}

private fun readValue(table: ByteArray, name: Int, at: Int): TableValue {
    assertEquals("a value must declare its own size", VALUE_SIZE, u16(table, at))
    assertEquals("a value's reserved byte is zero", 0, table[at + 2].toInt())
    return TableValue(name, table[at + 3].toInt() and 0xFF, u32(table, at + 4))
}

private fun align4(value: Int): Int = (value + 3) and 3.inv()

private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

private fun u16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun u32(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
