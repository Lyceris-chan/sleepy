package dev.sleepy.app.engine

import java.io.ByteArrayOutputStream

/**
 * Builds one `resources.arsc` from the partial tables an App Bundle ships as a base and a set of
 * configuration splits.
 *
 * ## The problem this exists for
 *
 * An App Bundle assigns resource ids once, at bundle build time, and then *deals them out*: the
 * base split keeps the entries whose configurations stayed with the base, a density split keeps
 * the ones that did not, a language split keeps one locale's strings. Each split therefore ships
 * a `resources.arsc` naming only what it carries — this base's names 11,024 entries and the
 * density split's 1,249, and the two sets of file paths do not intersect at all. Merging a
 * split's *files* into the base, which [SplitMerger] does, leaves those files at the right paths
 * with nothing in the merged APK referring to them: present, but unreachable.
 *
 * ## Why this is a chunk merge and not a relink
 *
 * The desktop reference relinks with apktool: decode every split, merge the trees, rebuild with
 * aapt2. That build's `resources.arsc` cannot be spliced into this APK, and the reason is
 * measurable rather than theoretical. apktool's *decode* drops resource-configuration qualifiers
 * that are redundant for the build's `minSdkVersion`, so `res/drawable-xhdpi-v4/icon.png` comes
 * out of the decoder as `res/drawable-xhdpi/icon.png` and is written back under that spelling.
 * The desktop build's table agrees with the APK it was built into, because both came out of the
 * same decode; the base APK's own entries use the original spelling, and so do the files
 * [SplitMerger] copies. A desktop-generated table dropped into this repack names 4,854 files of
 * which none exist in the archive being written — strictly worse than the unreachable files it
 * would replace.
 *
 * A chunk merge has none of that, because nothing is re-derived. The tables of a base and its own
 * configuration splits already agree on package id, on type ids, on entry indexes and on the
 * bytes of every configuration: the bundle build fixed all of it before it split them apart. So
 * this walks the tables and copies each entry across verbatim, keeping the configuration it was
 * written with, which is what makes the paths it names the paths [SplitMerger] copies.
 *
 * ## The two things that do have to move
 *
 * An entry's identity is its index, but two of the fields it holds index pools that are
 * per-table:
 *
 * - `ResTable_entry.key` indexes the package's `keyStrings` pool, where the entry's name lives.
 * - A `Res_value` of type `TYPE_STRING` holds an index into the table's global string pool.
 *
 * Both pools are merged by **appending**, never by rebuilding: every string keeps its position,
 * so every index that already referred to one still does. A split's index is therefore rewritten
 * by a single addition — the offset of its pool in the merged pool — and everything else in the
 * entry is copied untouched. That is the whole of the rewrite, which is why this can be trusted
 * with entry bytes it does not otherwise understand.
 *
 * ## What it drops, and what it refuses
 *
 * A package's type and type-spec chunks are rebuilt from the merged entries; its two name pools
 * and the table's global pool are carried over; and the base's package header is carried over
 * whole, so whatever the base said about its own type-id offset and public-name counts still
 * holds. A table carrying anything else — an RRO overlayable block, a shared-library declaration,
 * a staged alias — is refused rather than merged with that part silently missing.
 *
 * Refusing is the general answer to anything this merge cannot show to be sound, and the caller
 * responds by keeping the base's own table: a resource table naming files the APK does not have
 * is worse than one naming none of them. So [merge] checks the structures it relies on, and then
 * reads back what it built, before returning anything.
 */
object ResourceTableMerger {

    /** `RES_STRING_POOL_TYPE`. */
    private const val TYPE_STRING_POOL = 0x0001

    /** `RES_TABLE_TYPE`. */
    private const val TYPE_TABLE = 0x0002

    /** `RES_TABLE_PACKAGE_TYPE`. */
    private const val TYPE_PACKAGE = 0x0200

    /** `RES_TABLE_TYPE_TYPE`. */
    private const val TYPE_TYPE = 0x0201

    /** `RES_TABLE_TYPE_SPEC_TYPE`. */
    private const val TYPE_TYPE_SPEC = 0x0202

    /** `ResTable_type` flag: only present entries are stored, behind an index array. */
    private const val FLAG_SPARSE = 0x01

    /** `ResTable_type` flag: entry offsets are 16-bit rather than 32-bit. */
    private const val FLAG_OFFSET16 = 0x02

    /** `ResTable_entry` flag: the entry is a bag of name/value pairs rather than one value. */
    private const val ENTRY_COMPLEX = 0x0001

    /** `Res_value.dataType` for a value that indexes the global string pool. */
    private const val TYPE_STRING = 0x03

    /** A `ResTable_type` entry offset meaning "this type has no entry at this index". */
    private const val NO_ENTRY = -1

    /** A sparse type's index-array slot meaning the same. */
    private const val SPARSE_NONE = 0xFFFF

    /** `ResStringPool_header.flags`: strings are UTF-8 rather than UTF-16. */
    private const val POOL_UTF8 = 0x100

    /** `ResStringPool_header.flags`: the offset array is sorted. Appending breaks that. */
    private const val POOL_SORTED = 0x1

    private const val CHUNK_HEADER_SIZE = 8
    private const val TABLE_HEADER_SIZE = 12
    private const val POOL_HEADER_SIZE = 28

    /** Offsets of the package header's fields, from the start of that header. */
    private const val PACKAGE_ID_OFFSET = 8
    private const val PACKAGE_TYPE_STRINGS_OFFSET = 268
    private const val PACKAGE_KEY_STRINGS_OFFSET = 276

    /** The smallest package header that still holds the pool offsets this reads. */
    private const val PACKAGE_MIN_HEADER_SIZE = 288

    /** `ResTable_type`'s fixed header, which is followed immediately by its configuration. */
    private const val TYPE_FIXED_HEADER_SIZE = 20

    /** `ResTable_entry`'s own header, and the `Res_value` a simple entry carries after it. */
    private const val ENTRY_HEADER_SIZE = 8
    private const val VALUE_SIZE = 8

    /** `ResTable_map_entry`: the entry header plus its `parent` and `count` fields. */
    private const val MAP_ENTRY_HEADER_SIZE = 16

    /** `ResTable_map`: a name reference and a `Res_value`. */
    private const val MAP_SIZE = 12

    /** `ResTable_typeSpec`'s fixed header; its flags follow it. */
    private const val SPEC_HEADER_SIZE = 16

    /** Field offsets inside a `Res_value`. */
    private const val VALUE_TYPE_OFFSET = 3
    private const val VALUE_DATA_OFFSET = 4

    /** The directory a value must name to count as a file path rather than a plain string. */
    private const val PATH_PREFIX = "res/"

    /**
     * What a merge produced, or why it declined to produce anything.
     *
     * A refusal is not a failure of the run. The caller keeps the base's own table — which is what
     * the APK already had — and reports the reason instead of shipping something it could not
     * verify.
     */
    sealed class Result {
        /**
         * The merged table.
         *
         * [sourceCount] is how many tables went in, [resourceCount] how many entries came out
         * counted across every configuration, and [typeCount] how many distinct type ids those
         * entries use.
         */
        data class Merged(
            val table: ByteArray,
            val sourceCount: Int,
            val resourceCount: Int,
            val typeCount: Int
        ) : Result() {
            // A data class holding a ByteArray needs these spelled out, or two equal tables would
            // compare unequal and hash differently.
            override fun equals(other: Any?): Boolean =
                other is Merged &&
                    sourceCount == other.sourceCount &&
                    resourceCount == other.resourceCount &&
                    typeCount == other.typeCount &&
                    table.contentEquals(other.table)

            override fun hashCode(): Int =
                ((sourceCount * 31 + resourceCount) * 31 + typeCount) * 31 + table.contentHashCode()
        }

        /** The merge was not attempted, or what it built did not read back. */
        data class Refused(val reason: String) : Result()
    }

    /**
     * Merges [base] with every table in [splits], or refuses with the reason it could not.
     *
     * With no splits there is nothing to merge and the base's own table comes back unchanged: a
     * caller that fetched no split should not end up with a different table than it started with.
     */
    fun merge(base: ByteArray, splits: List<ByteArray>): Result {
        val baseSource = try {
            parse(base)
        } catch (e: TableFormatException) {
            return Result.Refused("the base's resources.arsc could not be read: ${e.message}")
        }
        if (splits.isEmpty()) {
            return Result.Merged(
                table = base,
                sourceCount = 1,
                resourceCount = baseSource.entries.values.sumOf { it.size },
                typeCount = baseSource.entries.keys.map { it.typeId }.toSet().size
            )
        }

        val sources = ArrayList<TableSource>(splits.size + 1)
        sources.add(baseSource)
        for ((index, split) in splits.withIndex()) {
            val what = "split ${index + 1}'s resources.arsc"
            val source = try {
                parse(split)
            } catch (e: TableFormatException) {
                return Result.Refused("$what could not be read: ${e.message}")
            }
            if (source.packageId != baseSource.packageId) {
                return Result.Refused(
                    "$what declares package 0x%08x and the base declares 0x%08x, so the two do not ".format(
                        source.packageId, baseSource.packageId
                    ) + "share a resource-id space"
                )
            }
            if (source.typeCount > baseSource.typeCount) {
                return Result.Refused(
                    "$what declares type id ${source.typeCount} and the base's type names stop at " +
                        "${baseSource.typeCount}, so that type has no name in the merged table"
                )
            }
            sources.add(source)
        }

        // Pools are placed in source order, so a source's shift is the running total of the pool
        // counts before it. This has to be settled before any entry is rewritten.
        var keyCount = baseSource.keyStrings.count
        var stringCount = baseSource.globalPool.count
        for (index in 1 until sources.size) {
            val source = sources[index]
            source.keyDelta = keyCount
            source.stringDelta = stringCount
            keyCount += source.keyStrings.count
            stringCount += source.globalPool.count
        }

        val entries = LinkedHashMap<TypeConfig, MutableMap<Int, ByteArray>>()
        val specs = LinkedHashMap<Int, IntArray>()
        for (source in sources) {
            for ((key, byIndex) in source.entries) {
                val merged = entries.getOrPut(key) { LinkedHashMap() }
                for ((index, entryBytes) in byIndex) {
                    if (merged.containsKey(index)) {
                        return Result.Refused(
                            "the tables disagree about resource 0x%08x: two of them carry an entry ".format(
                                (baseSource.packageId shl 24) or (key.typeId shl 16) or index
                            ) + "at the same index in type 0x%02x under the same configuration".format(key.typeId)
                        )
                    }
                    merged[index] = rewrite(entryBytes, source.keyDelta, source.stringDelta)
                }
            }
            for ((typeId, flags) in source.specFlags) {
                val merged = specs[typeId]
                if (merged == null) {
                    specs[typeId] = flags.copyOf()
                } else {
                    val target = if (merged.size < flags.size) merged.copyOf(flags.size) else merged
                    for (i in flags.indices) target[i] = target[i] or flags[i]
                    specs[typeId] = target
                }
            }
        }

        val table = build(sources, entries, specs)
        val problem = validate(table, expectedStrings = stringCount, expectedKeys = keyCount)
        if (problem != null) {
            return Result.Refused("the merged resources.arsc did not read back: $problem")
        }
        return Result.Merged(
            table = table,
            sourceCount = sources.size,
            resourceCount = entries.values.sumOf { it.size },
            typeCount = entries.keys.map { it.typeId }.toSet().size
        )
    }

    /**
     * Every occupied slot of [table] as the type id, configuration and index it sits at, or null
     * if [table] is not a table this reader can walk.
     *
     * This is a resource entry's identity without its name: two tables that agree on every one of
     * these carry the same entries in the same places under the same configurations. It is what
     * lets a caller — or a test — check a merge without a resource compiler.
     */
    fun slotsOf(table: ByteArray): Set<Slot>? {
        val source = try {
            parse(table)
        } catch (e: TableFormatException) {
            return null
        }
        val slots = LinkedHashSet<Slot>()
        for ((key, byIndex) in source.entries) {
            for (index in byIndex.keys) slots.add(Slot(key.typeId, key.config, index))
        }
        return slots
    }

    /**
     * Every `res/` file path [table] names, or null if [table] is not a table this reader can
     * walk.
     *
     * A compiled file resource's value *is* the path of the file it resolves to, so the set of
     * paths a table names is the set of files an app can ask it for. Comparing that set against
     * the files an APK actually holds is the check that a merged table and a merged file set
     * agree — the difference between resources being present and resources resolving.
     */
    fun namedPaths(table: ByteArray): Set<String>? {
        val source = try {
            parse(table)
        } catch (e: TableFormatException) {
            return null
        }
        val strings = try {
            readPool(source.globalPool.chunk)
        } catch (e: TableFormatException) {
            return null
        }
        val paths = LinkedHashSet<String>()
        for (byIndex in source.entries.values) {
            for (entry in byIndex.values) {
                for (index in stringIndexes(entry)) {
                    val value = strings.getOrNull(index) ?: continue
                    if (value.startsWith(PATH_PREFIX)) paths.add(value)
                }
            }
        }
        return paths
    }

    /** One occupied entry slot: a type, a configuration, and an index within that type. */
    data class Slot(val typeId: Int, val config: ByteArray, val index: Int) {
        override fun equals(other: Any?): Boolean =
            other is Slot && typeId == other.typeId && index == other.index && config.contentEquals(other.config)

        override fun hashCode(): Int = (typeId * 31 + index) * 31 + config.contentHashCode()
    }

    /** A type id and a configuration, compared by the configuration's bytes rather than identity. */
    private class TypeConfig(val typeId: Int, val config: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is TypeConfig && typeId == other.typeId && config.contentEquals(other.config)

        override fun hashCode(): Int = typeId * 31 + config.contentHashCode()
    }

    /** A string pool this merger holds on to, with its count read once. */
    private class Pool(val chunk: ByteArray, val count: Int)

    /** A table that could not be read, saying what was wrong with it. */
    private class TableFormatException(message: String) : Exception(message)

    /**
     * One parsed table, plus where its two pools will sit in the merged one.
     *
     * [keyDelta] and [stringDelta] are filled in by [merge] rather than [parse], because a table
     * cannot know its own offset until the tables before it are known.
     */
    private class TableSource(
        val packageId: Int,
        val packageHeader: ByteArray,
        val typeStrings: ByteArray,
        val keyStrings: Pool,
        val globalPool: Pool,
        val typeCount: Int,
        val entries: Map<TypeConfig, Map<Int, ByteArray>>,
        val specFlags: Map<Int, IntArray>
    ) {
        var keyDelta: Int = 0
        var stringDelta: Int = 0
    }

    /**
     * Reads [table] into the pieces the merge needs, or throws [TableFormatException] naming the
     * part that did not make sense.
     *
     * Only the pools, the package header and the entries themselves are kept. An entry's bytes
     * are copied to the merged table verbatim apart from two indexes, so nothing here has to
     * understand what they mean — and nothing here has to, which is what keeps the copy exact.
     */
    private fun parse(table: ByteArray): TableSource {
        if (table.size < TABLE_HEADER_SIZE) throw TableFormatException("it is ${table.size} bytes long")
        if (u16(table, 0) != TYPE_TABLE) {
            throw TableFormatException("its first chunk is type 0x%04x, not a table".format(u16(table, 0)))
        }
        if (u32(table, 4) != table.size) {
            throw TableFormatException("it declares ${u32(table, 4)} bytes and holds ${table.size}")
        }

        // The table-level chunks: the global string pool, the package, and nothing this merge can
        // carry. A table-level chunk other than those two would be dropped by [build], so it is
        // refused here rather than lost later.
        var offset = u16(table, 2)
        var globalPoolAt = -1
        var packageAt = -1
        var other = 0
        while (offset + CHUNK_HEADER_SIZE <= table.size) {
            val type = u16(table, offset)
            val size = u32(table, offset + 4)
            if (size < CHUNK_HEADER_SIZE || offset + size > table.size) {
                throw TableFormatException("the chunk at $offset overruns the table")
            }
            when {
                type == TYPE_STRING_POOL && globalPoolAt < 0 -> globalPoolAt = offset
                type == TYPE_PACKAGE && packageAt < 0 -> packageAt = offset
                else -> if (other == 0) other = type
            }
            offset += size
        }
        if (offset != table.size) throw TableFormatException("its chunks do not tile it")
        if (globalPoolAt < 0) throw TableFormatException("it has no global string pool")
        if (packageAt < 0) throw TableFormatException("it has no package chunk")
        if (other != 0) {
            throw TableFormatException("it carries a 0x%04x chunk, which a merge would drop".format(other))
        }

        val globalPool = chunkAt(table, globalPoolAt, table.size, "global string")
        val packageSize = u32(table, packageAt + 4)
        val packageHeaderSize = u16(table, packageAt + 2)
        if (packageSize <= 0 || packageAt + packageSize > table.size) {
            throw TableFormatException("its package overruns the table")
        }
        if (packageHeaderSize < PACKAGE_MIN_HEADER_SIZE) {
            throw TableFormatException("its package header is $packageHeaderSize bytes")
        }
        val packageEnd = packageAt + packageSize
        val typeStringsOffset = u32(table, packageAt + PACKAGE_TYPE_STRINGS_OFFSET)
        val keyStringsOffset = u32(table, packageAt + PACKAGE_KEY_STRINGS_OFFSET)
        if (typeStringsOffset == 0 || keyStringsOffset == 0) {
            throw TableFormatException("its package has no type or entry name pool")
        }

        val typeStrings = chunkAt(table, packageAt + typeStringsOffset, packageEnd, "type name")
        val keyStrings = chunkAt(table, packageAt + keyStringsOffset, packageEnd, "entry name")
        checkPool(globalPool, "global string")
        checkPool(typeStrings, "type name")
        checkPool(keyStrings, "entry name")

        // A package's two name pools are chunks inside it as well as being pointed at by its
        // header, so the walk has to step over them rather than treat them as structure it does
        // not understand.
        val typeStringsAt = packageAt + typeStringsOffset
        val keyStringsAt = packageAt + keyStringsOffset
        val entries = LinkedHashMap<TypeConfig, MutableMap<Int, ByteArray>>()
        val specs = LinkedHashMap<Int, IntArray>()
        var cursor = packageAt + packageHeaderSize
        while (cursor + CHUNK_HEADER_SIZE <= packageEnd) {
            val type = u16(table, cursor)
            val size = u32(table, cursor + 4)
            if (size < CHUNK_HEADER_SIZE || cursor + size > packageEnd) {
                throw TableFormatException("the package chunk at $cursor overruns the package")
            }
            when {
                cursor == typeStringsAt || cursor == keyStringsAt -> Unit
                type == TYPE_TYPE -> readType(table, cursor, size, entries)
                type == TYPE_TYPE_SPEC -> readSpec(table, cursor, size, specs)
                else -> throw TableFormatException(
                    "its package carries a 0x%04x chunk, which a merge would drop".format(type)
                )
            }
            cursor += size
        }

        // A split's type-name pool holds placeholders for the types it does not carry, so its
        // *length* is what says which type ids it could have named — not its non-empty entries.
        return TableSource(
            packageId = u32(table, packageAt + PACKAGE_ID_OFFSET),
            packageHeader = table.copyOfRange(packageAt, packageAt + packageHeaderSize),
            typeStrings = typeStrings,
            keyStrings = Pool(keyStrings, u32(keyStrings, 8)),
            globalPool = Pool(globalPool, u32(globalPool, 8)),
            typeCount = u32(typeStrings, 8),
            entries = entries,
            specFlags = specs
        )
    }

    /** The chunk starting at [start], required to fit inside [limit]. */
    private fun chunkAt(table: ByteArray, start: Int, limit: Int, what: String): ByteArray {
        if (start < 0 || start + CHUNK_HEADER_SIZE > limit) {
            throw TableFormatException("its $what pool starts past the end of its container")
        }
        val size = u32(table, start + 4)
        if (size < CHUNK_HEADER_SIZE || start + size > limit) {
            throw TableFormatException("its $what pool overruns its container")
        }
        return table.copyOfRange(start, start + size)
    }

    /**
     * Requires a pool whose strings can simply be appended to another's: no styles, and offsets
     * that land inside the pool.
     *
     * The encoding is checked when two pools are put together, because that is where a mismatch
     * would corrupt something rather than merely surprise.
     */
    private fun checkPool(pool: ByteArray, what: String) {
        if (pool.size < POOL_HEADER_SIZE) throw TableFormatException("its $what pool is ${pool.size} bytes")
        if (u16(pool, 0) != TYPE_STRING_POOL) {
            throw TableFormatException("its $what pool is type 0x%04x".format(u16(pool, 0)))
        }
        if (u16(pool, 2) < POOL_HEADER_SIZE) {
            throw TableFormatException("its $what pool has a ${u16(pool, 2)}-byte header")
        }
        if (u32(pool, 12) != 0) throw TableFormatException("its $what pool carries styles")
        val count = u32(pool, 8)
        val headerSize = u16(pool, 2)
        if (count < 0 || headerSize + count * 4 > pool.size) {
            throw TableFormatException("its $what pool has a truncated offset array")
        }
        val stringsStart = u32(pool, 20)
        if (stringsStart < 0 || stringsStart > pool.size) {
            throw TableFormatException("its $what pool starts its strings past its end")
        }
        for (i in 0 until count) {
            val stringAt = stringsStart + u32(pool, headerSize + i * 4)
            if (stringAt < stringsStart || stringAt >= pool.size) {
                throw TableFormatException("its $what pool has an offset past its end")
            }
        }
    }

    /**
     * Reads one `ResTable_type` chunk's occupied slots into [entries], keyed by type id and
     * configuration so that the same type under two configurations stays two chunks.
     */
    private fun readType(
        table: ByteArray,
        offset: Int,
        chunkSize: Int,
        entries: MutableMap<TypeConfig, MutableMap<Int, ByteArray>>
    ) {
        val typeId = u16(table, offset + 8)
        val flags = table[offset + 9].toInt() and 0xFF
        val entryCount = u32(table, offset + 12)
        val entriesStart = u32(table, offset + 16)
        val configSize = u32(table, offset + TYPE_FIXED_HEADER_SIZE)
        if (configSize <= 0 || TYPE_FIXED_HEADER_SIZE + configSize > chunkSize) {
            throw TableFormatException("type 0x%02x has a $configSize-byte configuration".format(typeId))
        }
        if (entriesStart < 0 || entriesStart > chunkSize) {
            throw TableFormatException("type 0x%02x puts its entries past its own chunk".format(typeId))
        }
        val config = table.copyOfRange(
            offset + TYPE_FIXED_HEADER_SIZE,
            offset + TYPE_FIXED_HEADER_SIZE + configSize
        )
        // A sparse type's index array follows the configuration directly; a type with 16-bit
        // offsets pads the configuration out to a four-byte boundary first.
        val offsetsAt = when {
            flags and FLAG_SPARSE != 0 -> offset + TYPE_FIXED_HEADER_SIZE + configSize
            flags and FLAG_OFFSET16 != 0 -> offset + TYPE_FIXED_HEADER_SIZE + align4(configSize)
            else -> offset + TYPE_FIXED_HEADER_SIZE + configSize
        }
        val slot = entries.getOrPut(TypeConfig(typeId, config)) { LinkedHashMap() }
        val chunkEnd = offset + chunkSize
        val dataStart = offset + entriesStart

        for (i in 0 until entryCount) {
            if (flags and FLAG_SPARSE != 0) {
                val pair = offsetsAt + i * 4
                requireInside(table, pair + 4, chunkEnd, typeId)
                val index = u16(table, pair)
                val relative = u16(table, pair + 2)
                if (relative == SPARSE_NONE) continue
                slot[index] = readEntry(table, dataStart + relative, chunkEnd, typeId)
                continue
            }
            val relative = if (flags and FLAG_OFFSET16 != 0) {
                val at = offsetsAt + i * 2
                requireInside(table, at + 2, chunkEnd, typeId)
                u16(table, at)
            } else {
                val at = offsetsAt + i * 4
                requireInside(table, at + 4, chunkEnd, typeId)
                u32(table, at)
            }
            if (relative == SPARSE_NONE || relative == NO_ENTRY) continue
            slot[i] = readEntry(table, dataStart + relative, chunkEnd, typeId)
        }
    }

    private fun requireInside(table: ByteArray, end: Int, limit: Int, typeId: Int) {
        if (end > limit || end > table.size) {
            throw TableFormatException("type 0x%02x has a truncated index array".format(typeId))
        }
    }

    /**
     * The bytes of the entry at [offset], which is a `ResTable_entry` followed by either one
     * `Res_value` or, when the entry is a bag, a map of them.
     *
     * The length comes from the entry's own header rather than from the next entry's offset,
     * because that is the only way a bag's length is knowable — and a bag whose tail is read
     * wrong is a table copied across wrong.
     */
    private fun readEntry(table: ByteArray, offset: Int, limit: Int, typeId: Int): ByteArray {
        if (offset < 0 || offset + ENTRY_HEADER_SIZE > limit || offset + ENTRY_HEADER_SIZE > table.size) {
            throw TableFormatException("type 0x%02x has an entry past the end of its chunk".format(typeId))
        }
        val headerSize = u16(table, offset)
        val flags = u16(table, offset + 2)
        val length = if (flags and ENTRY_COMPLEX != 0) {
            if (headerSize < MAP_ENTRY_HEADER_SIZE) {
                throw TableFormatException("type 0x%02x has a $headerSize-byte bag entry".format(typeId))
            }
            val count = u32(table, offset + 12)
            if (count < 0) throw TableFormatException("type 0x%02x has a bag of $count entries".format(typeId))
            MAP_ENTRY_HEADER_SIZE + count * MAP_SIZE
        } else {
            headerSize + VALUE_SIZE
        }
        if (length <= 0 || offset + length > limit || offset + length > table.size) {
            throw TableFormatException(
                "type 0x%02x has a $length-byte entry that runs past the end of its chunk".format(typeId)
            )
        }
        return table.copyOfRange(offset, offset + length)
    }

    /** Reads one `ResTable_typeSpec` chunk's per-entry configuration flags. */
    private fun readSpec(table: ByteArray, offset: Int, chunkSize: Int, specs: MutableMap<Int, IntArray>) {
        val typeId = u16(table, offset + 8)
        val entryCount = u32(table, offset + 12)
        if (entryCount < 0 || SPEC_HEADER_SIZE + entryCount * 4 > chunkSize) {
            throw TableFormatException("type 0x%02x has a truncated spec".format(typeId))
        }
        val flags = IntArray(entryCount) { u32(table, offset + SPEC_HEADER_SIZE + it * 4) }
        val existing = specs[typeId]
        if (existing == null) {
            specs[typeId] = flags
            return
        }
        // Two specs for one type within one package should not happen; if it does, the entries
        // they cover are still the same entries, so their flags combine rather than one silently
        // winning.
        val merged = if (existing.size < flags.size) existing.copyOf(flags.size) else existing
        for (i in flags.indices) merged[i] = merged[i] or flags[i]
        specs[typeId] = merged
    }

    /**
     * One entry's bytes with its two pool indexes moved to where its table's pools will sit in
     * the merged one.
     *
     * Nothing else is touched. The entry keeps its size, its flags and its key's position within
     * its own table — offset by where that table starts — and every value that is not a string,
     * because a reference is a resource id and those are already global.
     */
    private fun rewrite(entry: ByteArray, keyDelta: Int, stringDelta: Int): ByteArray {
        val out = entry.copyOf()
        putU32(out, 4, u32(out, 4) + keyDelta)
        if (stringDelta != 0) {
            for (at in valueOffsets(out)) {
                if (out[at + VALUE_TYPE_OFFSET].toInt() and 0xFF == TYPE_STRING) {
                    putU32(out, at + VALUE_DATA_OFFSET, u32(out, at + VALUE_DATA_OFFSET) + stringDelta)
                }
            }
        }
        return out
    }

    /** Where each `Res_value` in an entry starts, relative to the entry. */
    private fun valueOffsets(entry: ByteArray): List<Int> {
        if (u16(entry, 2) and ENTRY_COMPLEX == 0) return listOf(ENTRY_HEADER_SIZE)
        val count = u32(entry, 12)
        return (0 until count).map { MAP_ENTRY_HEADER_SIZE + it * MAP_SIZE }
    }

    /** The global-pool indexes an entry's string-valued fields hold. */
    private fun stringIndexes(entry: ByteArray): List<Int> {
        val indexes = ArrayList<Int>(2)
        for (at in valueOffsets(entry)) {
            if (entry[at + VALUE_TYPE_OFFSET].toInt() and 0xFF == TYPE_STRING) {
                indexes.add(u32(entry, at + VALUE_DATA_OFFSET))
            }
        }
        return indexes
    }

    /** Writes the merged table: the base's names, the appended pools, then the rebuilt types. */
    private fun build(
        sources: List<TableSource>,
        entries: Map<TypeConfig, Map<Int, ByteArray>>,
        specs: Map<Int, IntArray>
    ): ByteArray {
        val base = sources.first()
        val globalPool = concatPools(sources.map { it.globalPool.chunk })
        val keyStrings = concatPools(sources.map { it.keyStrings.chunk })
        val body = packageBody(entries, specs)

        // The base's package header is carried over whole, so its package id, its name, its
        // type-id offset and its public-name counts are whatever the base said they were. Only
        // the three fields describing this package's new contents are written.
        //
        // The base's type-name pool is carried over whole too: a split cannot have introduced a
        // type id the base could not already name — [merge] refuses that — so the base's pool
        // already names every type the merged table uses, with the spelling the base gave it.
        val header = base.packageHeader
        val packageSize = header.size + base.typeStrings.size + keyStrings.size + body.size
        putU32(header, 4, packageSize)
        putU32(header, PACKAGE_TYPE_STRINGS_OFFSET, header.size)
        putU32(header, PACKAGE_KEY_STRINGS_OFFSET, header.size + base.typeStrings.size)

        val table = ByteArray(TABLE_HEADER_SIZE + globalPool.size + packageSize)
        putU16(table, 0, TYPE_TABLE)
        putU16(table, 2, TABLE_HEADER_SIZE)
        putU32(table, 4, table.size)
        putU32(table, 8, 1)
        var at = TABLE_HEADER_SIZE
        globalPool.copyInto(table, at)
        at += globalPool.size
        header.copyInto(table, at)
        at += header.size
        base.typeStrings.copyInto(table, at)
        at += base.typeStrings.size
        keyStrings.copyInto(table, at)
        at += keyStrings.size
        body.copyInto(table, at)
        return table
    }

    /**
     * The package's type chunks: for each type id, its spec followed by one type chunk per
     * configuration.
     *
     * Type ids and configurations are both written in order, so the same inputs always produce
     * the same bytes — a table that differed run to run would be one no two builds could compare.
     */
    private fun packageBody(entries: Map<TypeConfig, Map<Int, ByteArray>>, specs: Map<Int, IntArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val byType = entries.keys.groupBy { it.typeId }
        for (typeId in byType.keys.sorted()) {
            val ordered = byType.getValue(typeId).sortedWith(compareBy(CONFIG_ORDER) { it.config })
            val entryCount = ordered.maxOf { entries.getValue(it).keys.max() + 1 }
            val flags = specs[typeId]?.copyOf(entryCount) ?: IntArray(entryCount)
            out.write(specChunk(typeId, flags))
            for (config in ordered) {
                out.write(typeChunk(typeId, config.config, entries.getValue(config)))
            }
        }
        return out.toByteArray()
    }

    /**
     * One `ResTable_type` chunk, written dense: a 32-bit offset for every index up to the highest
     * one present, with `NO_ENTRY` for the gaps.
     *
     * The source chunk may have been sparse or 16-bit, and copying that shape across would be
     * copying a decision made for one table's contents onto another's. Dense is valid for any
     * contents, so dense is what a merge produces.
     */
    private fun typeChunk(typeId: Int, config: ByteArray, entries: Map<Int, ByteArray>): ByteArray {
        val entryCount = entries.keys.max() + 1
        val headerSize = align4(TYPE_FIXED_HEADER_SIZE + config.size)
        val offsets = ByteArray(entryCount * 4)
        val body = ByteArrayOutputStream()
        for (i in 0 until entryCount) {
            val entry = entries[i]
            if (entry == null) {
                putU32(offsets, i * 4, NO_ENTRY)
            } else {
                putU32(offsets, i * 4, body.size())
                body.write(entry)
            }
        }
        val written = body.toByteArray()
        val chunk = ByteArray(headerSize + offsets.size + written.size)
        putU16(chunk, 0, TYPE_TYPE)
        putU16(chunk, 2, headerSize)
        putU32(chunk, 4, chunk.size)
        chunk[8] = typeId.toByte()
        // chunk[9] is the flags byte, left zero: dense 32-bit offsets, as above.
        putU32(chunk, 12, entryCount)
        putU32(chunk, 16, headerSize + offsets.size)
        config.copyInto(chunk, TYPE_FIXED_HEADER_SIZE)
        offsets.copyInto(chunk, headerSize)
        written.copyInto(chunk, headerSize + offsets.size)
        return chunk
    }

    /** One `ResTable_typeSpec` chunk holding [flags], one per entry index. */
    private fun specChunk(typeId: Int, flags: IntArray): ByteArray {
        val chunk = ByteArray(SPEC_HEADER_SIZE + flags.size * 4)
        putU16(chunk, 0, TYPE_TYPE_SPEC)
        putU16(chunk, 2, SPEC_HEADER_SIZE)
        putU32(chunk, 4, chunk.size)
        chunk[8] = typeId.toByte()
        putU32(chunk, 12, flags.size)
        for (i in flags.indices) putU32(chunk, SPEC_HEADER_SIZE + i * 4, flags[i])
        return chunk
    }

    /**
     * Every pool in [pools] as one pool, by appending.
     *
     * This is the property the whole merge rests on: string *i* of the *k*th pool keeps index
     * `(the counts of the pools before k) + i`, so every index that already pointed at a string
     * still points at the same string. Nothing is sorted, re-encoded or deduplicated, and the
     * sorted flag is cleared, because an appended offset array is no longer sorted — claiming
     * otherwise would send a reader that trusts the flag to the wrong string.
     */
    private fun concatPools(pools: List<ByteArray>): ByteArray {
        val headerSize = pools.maxOf { u16(it, 2) }
        val flags = u32(pools[0], 16) and POOL_SORTED.inv()
        var stringCount = 0
        for (pool in pools) {
            if ((u32(pool, 16) and POOL_UTF8) != (flags and POOL_UTF8)) {
                throw TableFormatException("its string pools mix UTF-8 and UTF-16")
            }
            stringCount += u32(pool, 8)
        }

        val offsets = ByteArray(stringCount * 4)
        val data = ByteArrayOutputStream()
        var written = 0
        for (pool in pools) {
            val base = data.size()
            val poolHeaderSize = u16(pool, 2)
            val count = u32(pool, 8)
            val stringsStart = u32(pool, 20)
            data.write(pool, stringsStart, u32(pool, 4) - stringsStart)
            for (i in 0 until count) {
                putU32(offsets, written * 4, u32(pool, poolHeaderSize + i * 4) + base)
                written++
            }
        }

        val strings = data.toByteArray()
        val chunk = ByteArray(align4(headerSize + offsets.size + strings.size))
        putU16(chunk, 0, TYPE_STRING_POOL)
        putU16(chunk, 2, headerSize)
        putU32(chunk, 4, chunk.size)
        putU32(chunk, 8, stringCount)
        putU32(chunk, 12, 0)
        putU32(chunk, 16, flags)
        putU32(chunk, 20, headerSize + offsets.size)
        putU32(chunk, 24, 0)
        offsets.copyInto(chunk, headerSize)
        strings.copyInto(chunk, headerSize + offsets.size)
        return chunk
    }

    /** Reads every string of [pool], or throws if the pool is not well formed. */
    private fun readPool(pool: ByteArray): List<String> {
        val count = u32(pool, 8)
        val headerSize = u16(pool, 2)
        val stringsStart = u32(pool, 20)
        val utf8 = (u32(pool, 16) and POOL_UTF8) != 0
        val strings = ArrayList<String>(count)
        for (i in 0 until count) {
            var at = stringsStart + u32(pool, headerSize + i * 4)
            if (at < 0 || at >= pool.size) throw TableFormatException("a string starts past the pool")
            if (utf8) {
                // A UTF-8 pool gives each string's character count and then its byte count, each
                // stored in one or two bytes depending on that length's high bit.
                var characters = pool[at].toInt() and 0xFF
                if (characters and 0x80 != 0) {
                    characters = ((characters and 0x7F) shl 8) or (pool[at + 1].toInt() and 0xFF)
                    at += 2
                } else {
                    at += 1
                }
                var bytes = pool[at].toInt() and 0xFF
                if (bytes and 0x80 != 0) {
                    bytes = ((bytes and 0x7F) shl 8) or (pool[at + 1].toInt() and 0xFF)
                    at += 2
                } else {
                    at += 1
                }
                if (at + bytes > pool.size) throw TableFormatException("a string runs past the pool")
                strings.add(String(pool, at, bytes, Charsets.UTF_8))
            } else {
                var characters = u16(pool, at)
                if (characters and 0x8000 != 0) {
                    characters = ((characters and 0x7FFF) shl 16) or u16(pool, at + 2)
                    at += 4
                } else {
                    at += 2
                }
                if (at + characters * 2 > pool.size) throw TableFormatException("a string runs past the pool")
                strings.add(String(pool, at, characters * 2, Charsets.UTF_16LE))
            }
        }
        return strings
    }

    /**
     * What is wrong with [table] as a merged result, or null if nothing is.
     *
     * This re-reads the bytes that were produced rather than trusting the writer: the chunk tree
     * has to tile, every entry's name index has to land inside the key pool, and every
     * string-valued field has to land inside the global pool. A writer and a reader that shared a
     * mistake would agree with each other, which is why the read-back goes through [parse] and
     * [readPool] rather than through anything the writer kept.
     */
    private fun validate(table: ByteArray, expectedStrings: Int, expectedKeys: Int): String? {
        val source = try {
            parse(table)
        } catch (e: TableFormatException) {
            return e.message
        } catch (e: IndexOutOfBoundsException) {
            return "a chunk runs off the end of the table"
        }
        if (source.globalPool.count != expectedStrings) {
            return "its string pool holds ${source.globalPool.count} strings, not $expectedStrings"
        }
        if (source.keyStrings.count != expectedKeys) {
            return "its key pool holds ${source.keyStrings.count} keys, not $expectedKeys"
        }
        val strings = try {
            readPool(source.globalPool.chunk)
        } catch (e: TableFormatException) {
            return e.message
        }
        for ((key, byIndex) in source.entries) {
            for (entry in byIndex.values) {
                val nameIndex = u32(entry, 4)
                if (nameIndex < 0 || nameIndex >= source.keyStrings.count) {
                    return "type 0x%02x has an entry naming key %d of %d".format(
                        key.typeId, nameIndex, source.keyStrings.count
                    )
                }
                for (index in stringIndexes(entry)) {
                    if (index < 0 || index >= strings.size) {
                        return "type 0x%02x has an entry pointing at string %d of %d".format(
                            key.typeId, index, strings.size
                        )
                    }
                }
            }
        }
        return null
    }

    /** Configurations compare as unsigned bytes, the way the platform orders them. */
    private val CONFIG_ORDER = Comparator<ByteArray> { left, right ->
        val shared = minOf(left.size, right.size)
        var order = 0
        for (i in 0 until shared) {
            order = (left[i].toInt() and 0xFF) - (right[i].toInt() and 0xFF)
            if (order != 0) break
        }
        if (order != 0) order else left.size - right.size
    }

    private fun align4(value: Int): Int = (value + 3) and 3.inv()

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

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
}
