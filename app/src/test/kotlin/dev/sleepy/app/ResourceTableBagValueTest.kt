package dev.sleepy.app

import dev.sleepy.app.engine.ResourceTableMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The string-pool rebase for a bag's map values, checked at the byte level.
 *
 * A merge copies an entry across and moves two kinds of index in it: the entry's name index into
 * the package's key pool, and every `TYPE_STRING` value's index into the table's global pool. The
 * second is where a bag differs from a simple entry — a simple entry's value follows its entry
 * header, a bag's sits *inside* a `ResTable_map`, four bytes past the map's own `name` — and the
 * merge used to compute both positions with one formula that only held for the first of them. It
 * tested the byte three past the map's name for `TYPE_STRING` and wrote the byte four past it,
 * which is a byte of the map's `name` field: `0x01000001` holds `0x01` there, never the `0x03` the
 * test looked for, so no bag value was ever rebased and every string-valued map entry kept the
 * index it had in its own split's pool.
 *
 * ## Why this test builds its own tables
 *
 * The defect only shows where a split's value index and its merged index differ, and it is silent
 * rather than fatal: a split-local index is *in range* in the merged pool — the split's strings sit
 * above the base's — so the table reads back clean and resolves the wrong string. What makes it
 * detectable here is that the fixture is built so that the two indexes name different things: the
 * split's bag value points at its own index 0, the base's pool has two strings, and the merged
 * index the value must hold is therefore 2. Base and split are the smallest tables a merge accepts,
 * written field by field, so the string the index resolves to is known exactly.
 *
 * A test that merged the real fixtures would pass with the defect present. This one cannot: the
 * value it reads out of the merged table is either 2 or it is the split-local 0, and 0 resolves to
 * a string the merge was never allowed to leave there.
 */
class ResourceTableBagValueTest {

    private companion object {
        /** `RES_TABLE_TYPE`. */
        const val TABLE = 0x0002

        /** `RES_STRING_POOL_TYPE`. */
        const val STRING_POOL = 0x0001

        /** `RES_TABLE_PACKAGE_TYPE`. */
        const val PACKAGE = 0x0200

        /** `RES_TABLE_TYPE_SPEC_TYPE`. */
        const val TYPE_SPEC = 0x0202

        /** `RES_TABLE_TYPE_TYPE`. */
        const val TYPE_CHUNK = 0x0201

        /** `ResTable_entry` flag: the entry is a bag of name/value pairs. */
        const val COMPLEX = 0x0001

        /** `Res_value.dataType` for a value that indexes the global string pool. */
        const val TYPE_STRING = 3

        /** The attribute a bag's map names. Nothing reads the id; the merge must not move it. */
        const val ATTRIBUTE = 0x01000001

        const val CHUNK_HEADER = 8
        const val TABLE_HEADER = 12
        const val POOL_HEADER = 28
        const val PACKAGE_HEADER = 288
        const val TYPE_FIXED_HEADER = 20
        const val SPEC_HEADER = 16
        const val ENTRY_HEADER = 8
        const val MAP_ENTRY_HEADER = 16
        const val MAP_SIZE = 12

        /** `ResTable_map`'s `Res_value` starts here, behind the map's `name`. */
        const val MAP_VALUE_OFFSET = 4

        /** A `Res_value` is eight bytes: a size, a reserved byte, a type and four of data. */
        const val VALUE_SIZE = 8

        /** The empty configuration a base's own entries sit under. */
        val DEFAULT_CONFIG = ByteArray(4).also { putU32(it, 0, 4) }

        /** A `de` configuration, which is what keeps the split's entries out of the base's slots. */
        val GERMAN_CONFIG = ByteArray(12).also {
            putU32(it, 0, 12)
            it[4] = 'd'.code.toByte()
            it[5] = 'e'.code.toByte()
        }
    }

    /**
     * A bag whose one map value is a string: the base points at index 1 of its own pool and the
     * split at index 0 of its own, so the split's value has to come out as 2.
     */
    @Test
    fun aBagsStringValueIsRebasedFromTheSplitsPoolToTheMergedOne() {
        val base = fixture(
            strings = listOf("base-first", "res/anim/base_thing.xml"),
            keys = listOf("base_bag", "base_plain"),
            types = listOf(
                Type(DEFAULT_CONFIG, linkedMapOf(
                    0 to bagEntry(key = 0, poolIndex = 1),
                    1 to simpleEntry(key = 1, poolIndex = 0)
                ))
            )
        )
        val split = fixture(
            strings = listOf("res/anim/split_thing.xml", "split-other"),
            keys = listOf("split_bag", "split_plain"),
            types = listOf(
                Type(GERMAN_CONFIG, linkedMapOf(
                    0 to bagEntry(key = 0, poolIndex = 0),
                    1 to simpleEntry(key = 1, poolIndex = 1)
                ))
            )
        )

        val result = ResourceTableMerger.merge(base, listOf(split))
        assertTrue(
            "the merge refused: ${(result as? ResourceTableMerger.Result.Refused)?.reason}",
            result is ResourceTableMerger.Result.Merged
        )
        val merged = (result as ResourceTableMerger.Result.Merged).table
        val entries = readEntries(merged)

        // The base's own slots: the merge adds to a table, it does not move what was in it.
        val baseBag = entries.single { it.config.contentEquals(DEFAULT_CONFIG) && it.index == 0 }
        assertEquals("the base's bag value must not move", 1, baseBag.values.single().data)
        assertEquals("the map's name must not be touched", ATTRIBUTE, baseBag.values.single().name)

        // The split's bag, under the configuration the split carried it with. Its value is the
        // split-local 0 moved up by the base's two strings, and 0 left in place would resolve to
        // "base-first" — a string from another table entirely.
        val bag = entries.single { it.config.contentEquals(GERMAN_CONFIG) && it.index == 0 }
        val value = bag.values.single()
        assertEquals("a bag's value is a string", TYPE_STRING, value.type)
        assertNotEquals("the split-local index must not survive the merge", 0, value.data)
        assertEquals("the split's value must be its own index rebased by the base's pool", 2, value.data)
        assertEquals("the map's name must not be touched", ATTRIBUTE, value.name)
        assertEquals("the split's key index must move to the merged key pool", 2, bag.key)

        // The simple entry beside it, whose value follows its entry header: the plain-value path
        // has to move by the same delta, and this is what says it did not move *positions*.
        val plain = entries.single { it.config.contentEquals(GERMAN_CONFIG) && it.index == 1 }
        assertEquals("a simple entry's value must be rebased too", 3, plain.values.single().data)
        assertEquals("a simple entry's value is a string", TYPE_STRING, plain.values.single().type)

        // And what the indexes mean, read back the way the rest of the app reads them: the merged
        // table names the split's file path, which it can only do through the bag's value.
        val named = ResourceTableMerger.namedPaths(merged)!!
        assertTrue("the base's path must still be named", "res/anim/base_thing.xml" in named)
        assertTrue(
            "the split's path is only reachable through the bag's rebased value: $named",
            "res/anim/split_thing.xml" in named
        )
    }

    /** One type chunk of a fixture: the configuration it is under and the entries it holds. */
    private class Type(val config: ByteArray, val entries: Map<Int, ByteArray>)

    /**
     * A whole `resources.arsc` holding [strings], entry names [keys] and the type chunks [types],
     * with package 0x7f and one type named `array`.
     *
     * Everything a merge reads is written, and nothing else: the pools, the package header and the
     * type chunks. The layout is the platform's, so what [readEntries] finds in the merged table
     * is what any other reader of it would.
     */
    private fun fixture(strings: List<String>, keys: List<String>, types: List<Type>): ByteArray {
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

        val table = ByteArray(TABLE_HEADER + global.size + header.size + typeNames.size + keyPool.size + body.size)
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

    /** The package's children: a type spec and a type chunk for every type [types] declares. */
    private fun body(types: List<Type>): ByteArray {
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
    private fun typeChunk(config: ByteArray, entryCount: Int, entries: Map<Int, ByteArray>): ByteArray {
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

    /** A `ResTable_entry` with key [key] holding one string-valued `Res_value`. */
    private fun simpleEntry(key: Int, poolIndex: Int): ByteArray {
        val entry = ByteArray(ENTRY_HEADER + VALUE_SIZE)
        putU16(entry, 0, ENTRY_HEADER)
        putU16(entry, 2, 0)
        putU32(entry, 4, key)
        putValue(entry, ENTRY_HEADER, poolIndex)
        return entry
    }

    /**
     * A `ResTable_map_entry` with key [key] holding one map: the attribute [ATTRIBUTE] naming a
     * string-valued `Res_value` at [poolIndex].
     */
    private fun bagEntry(key: Int, poolIndex: Int): ByteArray {
        val entry = ByteArray(MAP_ENTRY_HEADER + MAP_SIZE)
        putU16(entry, 0, MAP_ENTRY_HEADER)
        putU16(entry, 2, COMPLEX)
        putU32(entry, 4, key)
        putU32(entry, 8, 0)
        putU32(entry, 12, 1)
        putU32(entry, MAP_ENTRY_HEADER, ATTRIBUTE)
        putValue(entry, MAP_ENTRY_HEADER + MAP_VALUE_OFFSET, poolIndex)
        return entry
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
     * Nothing is deduplicated or sorted: index *i* is `strings[i]`, which is what the test's
     * expected indexes are written against.
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

    /** One entry of a table read back: where it sits, its name index, and the values it holds. */
    private class Read(
        val config: ByteArray,
        val index: Int,
        val key: Int,
        val values: List<Value>
    )

    /** One `Res_value` read back: the map name that introduced it, its type, and its data. */
    private class Value(val name: Int, val type: Int, val data: Int)

    /**
     * Every entry of type 1 in [table], read out of the bytes.
     *
     * This is deliberately a reader of the format rather than of anything the merge produced: the
     * map's value is read four bytes into the map, where the format puts it, so a merge that wrote
     * somewhere else is seen to have written somewhere else.
     */
    private fun readEntries(table: ByteArray): List<Read> {
        var at = u16(table, 2)
        var packageAt = -1
        while (at + CHUNK_HEADER <= table.size) {
            val size = u32(table, at + 4)
            check(size >= CHUNK_HEADER && at + size <= table.size) { "a chunk at $at overruns the table" }
            if (u16(table, at) == PACKAGE) packageAt = at
            at += size
        }
        check(packageAt > 0) { "the table holds no package" }

        val packageEnd = packageAt + u32(table, packageAt + 4)
        val found = ArrayList<Read>()
        at = packageAt + u16(table, packageAt + 2)
        while (at + CHUNK_HEADER <= packageEnd) {
            val size = u32(table, at + 4)
            if (u16(table, at) == TYPE_CHUNK) {
                val entryCount = u32(table, at + 12)
                val entriesStart = u32(table, at + 16)
                val configSize = u32(table, at + TYPE_FIXED_HEADER)
                val config = table.copyOfRange(at + TYPE_FIXED_HEADER, at + TYPE_FIXED_HEADER + configSize)
                for (i in 0 until entryCount) {
                    val offset = u32(table, at + TYPE_FIXED_HEADER + configSize + i * 4)
                    if (offset == -1) continue
                    val entryAt = at + entriesStart + offset
                    val headerSize = u16(table, entryAt)
                    val flags = u16(table, entryAt + 2)
                    val key = u32(table, entryAt + 4)
                    val values = ArrayList<Value>()
                    if (flags and COMPLEX != 0) {
                        val count = u32(table, entryAt + 12)
                        for (m in 0 until count) {
                            val mapAt = entryAt + MAP_ENTRY_HEADER + m * MAP_SIZE
                            values.add(readValue(table, u32(table, mapAt), mapAt + MAP_VALUE_OFFSET))
                        }
                    } else {
                        values.add(readValue(table, -1, entryAt + headerSize))
                    }
                    found.add(Read(config, i, key, values))
                }
            }
            at += size
        }
        return found
    }

    private fun readValue(table: ByteArray, name: Int, at: Int): Value {
        assertEquals("a value must declare its own size", VALUE_SIZE, u16(table, at))
        assertEquals("a value's reserved byte is zero", 0, table[at + 2].toInt())
        return Value(name, table[at + 3].toInt() and 0xFF, u32(table, at + 4))
    }

    private fun align4(value: Int): Int = (value + 3) and 3.inv()
}

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
