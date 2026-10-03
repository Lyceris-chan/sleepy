package dev.sleepy.app.engine

import java.io.ByteArrayOutputStream

/**
 * Builds one `resources.arsc` from the partial tables that an App Bundle ships as a base and a
 * set of configuration splits.
 *
 * ## The problem this exists for
 *
 * An App Bundle assigns resource ids once, at bundle build time, and then distributes them: the
 * base split carries the entries whose configurations stayed with the base, a density split
 * carries the ones that did not, and a language split carries one locale's strings. Each split
 * therefore ships a `resources.arsc` naming only what it carries—this base names 11,802
 * entries and the density split names 1,247, and the two sets of file paths do not intersect at
 * all. Merging a split's *files* into the base, which [SplitMerger] does, leaves those files at
 * the right paths with nothing in the merged APK referring to them: present but unreachable.
 *
 * ## Why this is a chunk merge and not a relink
 *
 * The desktop reference relinks with apktool: decode every split, merge the trees, rebuild with
 * aapt2. That build's `resources.arsc` cannot be spliced into this APK, for a measurable reason.
 * apktool's *decode* drops resource-configuration qualifiers that are redundant for the build's
 * `minSdkVersion`, so `res/drawable-xhdpi-v4/icon.png` comes out of the decoder as
 * `res/drawable-xhdpi/icon.png` and is written back under that spelling. The desktop build's
 * table matches the APK it was built into, because both came out of the same decode; the base
 * APK's own entries use the original spelling, and so do the files [SplitMerger] copies. A
 * desktop-generated table dropped into this repack names 1,925 files that do not exist in the
 * archive being written, which is worse than the unreachable files it replaces.
 *
 * A chunk merge avoids that problem, because it re-derives nothing. The tables of a base and its
 * own configuration splits already agree on package id, on type ids, on entry indexes and on the
 * bytes of every configuration: the bundle build fixed all of those before it split the tables
 * apart. So [merge] walks the tables and copies each entry across verbatim, keeping the
 * configuration it was written with, which is what makes the paths it names the paths
 * [SplitMerger] copies.
 *
 * ## The two things that do have to move
 *
 * An entry's identity is its index, but two of the fields it holds index pools that are
 * per-table:
 *
 * - `ResTable_entry.key` indexes the package's `keyStrings` pool, where the entry's name lives.
 * - A `Res_value` of type `TYPE_STRING` holds an index into the table's global string pool. An
 *   entry holds one of those after its header, or—when it is a bag—one inside each of its
 *   maps, and both places are the same field with the same index in it.
 *
 * Both pools are merged by **appending**, not by rebuilding: every string keeps its position, so
 * every index that already referred to one still does. A split's index is therefore rewritten by
 * a single addition—the offset of its pool in the merged pool—and the rest of the entry is
 * copied untouched. That is the whole of the rewrite, which is why the merge can copy entry
 * bytes it does not otherwise interpret.
 *
 * ## What it drops, and what it does not merge
 *
 * [merge] rebuilds a package's type and type-spec chunks from the merged entries, carries over
 * the package's two name pools and the table's global pool, and carries over the base's package
 * header whole, so the base's type-id offset and public-name counts keep the values the base
 * declares. A table carrying anything else—an RRO overlayable block, a shared-library
 * declaration, a staged alias—is not merged, rather than merged with that part missing.
 *
 * Where the merge cannot check a structure, it returns a refusal instead. The caller responds by
 * keeping the base's own table: a resource table naming files the APK does not have is worse
 * than one naming none of them. So [merge] checks the structures it relies on, and then reads
 * back what it built—every entry against the entry it came from, field by field—before
 * returning anything.
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

    /** A sparse type's index-array slot for a missing entry. */
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

    /**
     * `ResTable_package::name`: 128 UTF-16 code units at a fixed offset, NUL-padded, ending where
     * `typeStrings` begins.
     *
     * It is a fixed field rather than an offset into a pool, which is what makes renaming a
     * package a write in place: a name that fits leaves every other byte of the table in place,
     * so no offset in any chunk moves.
     */
    private const val PACKAGE_NAME_OFFSET = 12
    private const val PACKAGE_NAME_BYTES = PACKAGE_TYPE_STRINGS_OFFSET - PACKAGE_NAME_OFFSET

    /**
     * The longest name [renamePackage] accepts: the field's bytes, less the two bytes the
     * terminator uses. A longer name leaves no room for the terminator, so [renamePackage]
     * returns null instead of writing past the field.
     */
    const val PACKAGE_NAME_MAX_LENGTH = (PACKAGE_NAME_BYTES - 2) / 2

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

    /** Where a `ResTable_map`'s `Res_value` sits inside it, behind the map's `name`. */
    private const val MAP_VALUE_OFFSET = 4

    /** `ResTable_typeSpec`'s fixed header; its flags follow it. */
    private const val SPEC_HEADER_SIZE = 16

    /** Field offsets inside a `Res_value`. */
    private const val VALUE_TYPE_OFFSET = 3
    private const val VALUE_DATA_OFFSET = 4

    /** The directory a value must name to count as a file path rather than a plain string. */
    private const val PATH_PREFIX = "res/"

    /**
     * What a merge produced, or why it produced nothing.
     *
     * A refusal is not a failure of the run. The caller keeps the base's own table—which is
     * what the APK already holds—and reports the reason instead of shipping a table whose
     * contents it has not read back.
     */
    sealed class Result {
        /**
         * The merged table.
         *
         * [sourceCount] is the number of tables merged, [resourceCount] the number of entries
         * produced across every configuration, and [typeCount] the number of distinct type ids
         * those entries use.
         */
        data class Merged(
            val table: ByteArray,
            val sourceCount: Int,
            val resourceCount: Int,
            val typeCount: Int,
            /**
             * The requested paths whose entries were left out.
             *
             * This set records what the merge did rather than what it was asked for: a path the
             * base's table does not name has no entry to leave out, so it is not here. A caller
             * holding the files the table names—the archive builder above all—drops this
             * set, which is the set the table no longer names.
             */
            val droppedPaths: Set<String> = emptySet()
        ) : Result() {
            // A data class holding a ByteArray needs these spelled out, or two equal tables
            // compare unequal and hash differently.
            override fun equals(other: Any?): Boolean =
                other is Merged &&
                    sourceCount == other.sourceCount &&
                    resourceCount == other.resourceCount &&
                    typeCount == other.typeCount &&
                    droppedPaths == other.droppedPaths &&
                    table.contentEquals(other.table)

            override fun hashCode(): Int =
                (((sourceCount * 31 + resourceCount) * 31 + typeCount) * 31 +
                    droppedPaths.hashCode()) * 31 +
                    table.contentHashCode()
        }

        /** The merge was not attempted, or what it built did not read back. */
        data class Refused(val reason: String) : Result()
    }

    /**
     * Merges [base] with every table in [splits], or returns a refusal with the reason.
     *
     * With no splits and nothing to drop there is nothing to merge, and the base's own table is
     * returned unchanged: a caller that fetched no split does not end up with a different table
     * than it started with.
     *
     * Each of [droppedPaths] is a `res/` file path whose entries are left out of the result,
     * because the archive this table is being built for does not hold that file. A compiled file
     * resource's value *is* the path of the file it resolves to, so an entry naming an absent
     * file is a resource that resolves to nothing, which is what the caller is building the table
     * to avoid. The paths the base's table does not name are reported in
     * [Result.Merged.droppedPaths] as not dropped, so a caller can distinguish what was asked for
     * from what happened.
     */
    fun merge(
        base: ByteArray,
        splits: List<ByteArray>,
        droppedPaths: Set<String> = emptySet()
    ): Result {
        val baseSource = try {
            parse(base)
        } catch (e: TableFormatException) {
            return Result.Refused("the base's resources.arsc could not be read: ${e.message}")
        }
        if (splits.isEmpty() && droppedPaths.isEmpty()) {
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
                    ("$what declares package 0x%08x and the base declares 0x%08x, so the two " +
                        "do not ").format(source.packageId, baseSource.packageId) +
                        "share a resource-id space"
                )
            }
            if (source.typeCount > baseSource.typeCount) {
                return Result.Refused(
                    "$what declares type id ${source.typeCount} and the base's type " +
                        "names stop at ${baseSource.typeCount}, so that type has no name " +
                        "in the merged table"
                )
            }
            sources.add(source)
        }

        // Pools are placed in source order, so a source's shift is the running total of the pool
        // counts before it. Set each source's delta before rewriting any entry.
        var keyCount = baseSource.keyStrings.count
        var stringCount = baseSource.globalPool.count
        for (index in 1 until sources.size) {
            val source = sources[index]
            source.keyDelta = keyCount
            source.stringDelta = stringCount
            keyCount += source.keyStrings.count
            stringCount += source.globalPool.count
        }

        // Which entries name a file the archive does not hold. The pools are read for this and
        // nothing else in the merge reads them: an entry's value is otherwise just bytes to be
        // moved, and a merge that drops nothing does not look inside one.
        val omissionSlots = ArrayList<Map<TypeConfig, Set<Int>>>(sources.size)
        val droppedFound = LinkedHashSet<String>()
        if (droppedPaths.isNotEmpty()) {
            for (source in sources) {
                val omission = entriesNaming(source, droppedPaths) ?: return Result.Refused(
                    "a string pool could not be read, so the entries naming the files to " +
                        "leave out could not be found"
                )
                omissionSlots.add(omission.slots)
                droppedFound.addAll(omission.paths)
            }
        }

        val entries = LinkedHashMap<TypeConfig, MutableMap<Int, Placed>>()
        val specs = LinkedHashMap<Int, IntArray>()
        for ((sourceIndex, source) in sources.withIndex()) {
            for ((key, byIndex) in source.entries) {
                val merged = entries.getOrPut(key) { LinkedHashMap() }
                for ((index, entryBytes) in byIndex) {
                    if (index in omissionSlots.getOrNull(sourceIndex)?.get(key).orEmpty()) continue
                    if (merged.containsKey(index)) {
                        return Result.Refused(
                            ("the tables disagree about resource 0x%08x: two of them carry an " +
                                "entry ").format(
                                (baseSource.packageId shl 24) or (key.typeId shl 16) or index
                            ) +
                                "at the same index in type 0x%02x under the same configuration"
                                    .format(key.typeId)
                        )
                    }
                    merged[index] = Placed(
                        bytes = rewrite(entryBytes, source.keyDelta, source.stringDelta),
                        source = entryBytes,
                        keyDelta = source.keyDelta,
                        stringDelta = source.stringDelta
                    )
                }
                // A type and configuration whose every entry was left out carries nothing, and an
                // empty map is not a shape the writer that follows can lay out: it is dropped here rather
                // than written as a type chunk with no entries in it.
                if (merged.isEmpty()) entries.remove(key)
            }
            for ((typeId, flags) in source.specFlags) {
                val merged = specs[typeId]
                if (merged == null) {
                    specs[typeId] = flags.copyOf()
                } else {
                    val target = if (merged.size < flags.size) merged.copyOf(flags.size) else merged
                    for (i in flags.indices) {
                        target[i] = target[i] or flags[i]
                    }
                    specs[typeId] = target
                }
            }
        }

        val resourceCount = entries.values.sumOf { it.size }
        val table = build(sources, entries, specs)
        val problem = validate(
            table,
            entries,
            expectedStrings = stringCount,
            expectedKeys = keyCount,
            expectedEntries = resourceCount
        )
        if (problem != null) {
            return Result.Refused("the merged resources.arsc did not read back: $problem")
        }
        return Result.Merged(
            table = table,
            sourceCount = sources.size,
            resourceCount = resourceCount,
            typeCount = entries.keys.map { it.typeId }.toSet().size,
            droppedPaths = droppedFound
        )
    }

    /**
     * What a drop removed from one source's table: the indexes of the entries left out, by the
     * type and configuration they sit under, and which of the requested paths those entries named.
     *
     * The returned path set is not necessarily the requested set: a path the table does not name
     * has no entry to leave out, so it is not reported as dropped. The caller drops files from an
     * archive based on this set, so the set records only what an entry actually named.
     */
    private class Omission(val slots: Map<TypeConfig, Set<Int>>, val paths: Set<String>)

    /**
     * The entries of [source] that name one of [paths], or null when its string pool cannot be
     * read.
     *
     * This is [namedPaths] asked one entry at a time rather than of a whole table: a caller
     * dropping files from the archive needs to identify which entries to leave out, not which
     * paths exist somewhere in the table.
     */
    private fun entriesNaming(source: TableSource, paths: Set<String>): Omission? {
        val strings = try {
            readPool(source.globalPool.chunk)
        } catch (e: TableFormatException) {
            return null
        }
        val slots = LinkedHashMap<TypeConfig, MutableSet<Int>>()
        val named = LinkedHashSet<String>()
        for ((key, byIndex) in source.entries) {
            for ((index, entry) in byIndex) {
                for (stringIndex in stringIndexes(entry)) {
                    val value = strings.getOrNull(stringIndex) ?: continue
                    if (value !in paths) continue
                    named.add(value)
                    slots.getOrPut(key) { LinkedHashSet() }.add(index)
                }
            }
        }
        return Omission(slots, named)
    }

    /**
     * Every occupied slot of [table] as the type id, configuration and index it sits at, or null
     * if [table] is not a table this reader can walk.
     *
     * This is a resource entry's identity without its name: two tables that agree on every one of
     * these carry the same entries in the same places under the same configurations. It is what
     * lets a caller—or a test—check a merge without a resource compiler.
     */
    fun slotsOf(table: ByteArray): Set<Slot>? {
        val source = try {
            parse(table)
        } catch (e: TableFormatException) {
            return null
        }
        val slots = LinkedHashSet<Slot>()
        for ((key, byIndex) in source.entries) {
            for (index in byIndex.keys) {
                slots.add(Slot(key.typeId, key.config, index))
            }
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
     * agree—the difference between resources being present and resources resolving.
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

    /**
     * The package name [table]'s package chunk declares, or null if [table] is not a table this
     * reader can walk.
     *
     * This is [renamePackage]'s read-back: it reads the name from the field the platform reads
     * rather than from the string that went in.
     */
    fun packageName(table: ByteArray): String? {
        val at = packageChunkAt(table) ?: return null
        if (at + PACKAGE_TYPE_STRINGS_OFFSET > table.size) return null
        val field = table.copyOfRange(at + PACKAGE_NAME_OFFSET, at + PACKAGE_TYPE_STRINGS_OFFSET)
        var units = 0
        while ((units + 1) * 2 <= field.size) {
            if (field[units * 2].toInt() == 0 && field[units * 2 + 1].toInt() == 0) break
            units++
        }
        return String(field, 0, units * 2, Charsets.UTF_16LE)
    }

    /**
     * [table] with its package renamed to [name], or null when there is no package chunk to
     * rename or the name does not fit the field it goes in.
     *
     * The name in the package chunk is what a *name-based* lookup is matched against. An app asks
     * for its own sounds and files by name rather than by id—
     * `Resources.getIdentifier(name, type, getPackageName())`—and the platform resolves that
     * third argument against the package names the loaded tables declare. A build whose
     * `getPackageName()` is `com.discord.sleepy` asking a table that declares `com.discord` gets 0
     * back for every one of those lookups, and then reads resource id 0. An installation of this
     * app did this 72 times on a single startup, each one the platform's
     * `Invalid resource ID 0x00000000.`; the manifest is not the only place a package renames,
     * and this is the other one.
     *
     * Nothing else moves, and [slotsOf] and [namedPaths] report the same slots and paths for the
     * result as for the input: the name is a fixed field written in place, so the result is the
     * same size and holds the same bytes everywhere else.
     */
    fun renamePackage(table: ByteArray, name: String): ByteArray? {
        val at = packageChunkAt(table) ?: return null
        if (at + PACKAGE_TYPE_STRINGS_OFFSET > table.size) return null
        // The field is NUL-terminated, so a name that leaves no room for the terminator is not
        // written: the function returns null rather than writing over the end of the field.
        val encoded = name.toByteArray(Charsets.UTF_16LE)
        if (encoded.size + 2 > PACKAGE_NAME_BYTES) return null
        val renamed = table.copyOf()
        renamed.fill(0, at + PACKAGE_NAME_OFFSET, at + PACKAGE_TYPE_STRINGS_OFFSET)
        encoded.copyInto(renamed, at + PACKAGE_NAME_OFFSET)
        return renamed
    }

    /**
     * Where [table]'s package chunk starts, or null if it is not a table this reader can walk.
     *
     * The walk stops at the first package chunk, which is the one [merge] carries over: a table
     * with a second one is outside what this reader supports.
     */
    private fun packageChunkAt(table: ByteArray): Int? {
        if (table.size < TABLE_HEADER_SIZE) return null
        if (u16(table, 0) != TYPE_TABLE) return null
        var offset = u16(table, 2)
        while (offset + CHUNK_HEADER_SIZE <= table.size) {
            val type = u16(table, offset)
            val size = u32(table, offset + 4)
            if (size < CHUNK_HEADER_SIZE || offset + size > table.size) return null
            if (type == TYPE_PACKAGE) return offset
            offset += size
        }
        return null
    }

    /** One occupied entry slot: a type, a configuration, and an index within that type. */
    data class Slot(val typeId: Int, val config: ByteArray, val index: Int) {
        override fun equals(other: Any?): Boolean =
            other is Slot &&
                typeId == other.typeId &&
                index == other.index &&
                config.contentEquals(other.config)

        override fun hashCode(): Int = (typeId * 31 + index) * 31 + config.contentHashCode()
    }

    /**
     * A type id and a configuration, compared by the configuration's bytes rather than by
     * identity.
     */
    private class TypeConfig(val typeId: Int, val config: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is TypeConfig && typeId == other.typeId && config.contentEquals(other.config)

        override fun hashCode(): Int = typeId * 31 + config.contentHashCode()
    }

    /** A string pool carried by a [TableSource], with its count read once. */
    private class Pool(val chunk: ByteArray, val count: Int)

    /** A table that could not be read, recording what was wrong with it. */
    private class TableFormatException(message: String) : Exception(message)

    /**
     * One parsed table, plus where its two pools sit in the merged one.
     *
     * [keyDelta] and [stringDelta] are filled in by [merge] rather than [parse], because a
     * table's pool offset depends on the tables before it.
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
     * One entry as it goes into the merged table, kept beside the entry it was made from.
     *
     * [bytes] is what the writer lays down and [source] is what went in. Keeping both lets
     * [validate] compare the rewrite against its input rather than check the arithmetic that
     * produced it. The two deltas are kept with them so the comparison does not have to find the
     * table each entry came from again.
     */
    private class Placed(
        val bytes: ByteArray,
        val source: ByteArray,
        val keyDelta: Int,
        val stringDelta: Int
    )

    /**
     * Reads [table] into the pieces the merge needs, or throws [TableFormatException] naming the
     * part that could not be read.
     *
     * Only the pools, the package header and the entries themselves are kept. An entry's bytes
     * are copied to the merged table verbatim apart from two indexes; this function does not
     * interpret them, which keeps the copy unchanged outside those two fields.
     */
    private fun parse(table: ByteArray): TableSource {
        if (table.size < TABLE_HEADER_SIZE) {
            throw TableFormatException("it is ${table.size} bytes long")
        }
        if (u16(table, 0) != TYPE_TABLE) {
            throw TableFormatException(
                "its first chunk is type 0x%04x, not a table".format(u16(table, 0))
            )
        }
        if (u32(table, 4) != table.size) {
            throw TableFormatException(
                "it declares ${u32(table, 4)} bytes and holds ${table.size}"
            )
        }

        // The table-level chunks: the global string pool, the package, and nothing else this
        // merge can carry. [build] does not carry another table-level chunk, so this function
        // throws here rather than dropping the chunk later.
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
            throw TableFormatException(
                "it carries a 0x%04x chunk, which a merge would drop".format(other)
            )
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
        // header, so the walk steps over them rather than reading them as unknown structure.
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
                else -> {
                    throw TableFormatException(
                        "its package carries a 0x%04x chunk, which a merge would drop"
                            .format(type)
                    )
                }
            }
            cursor += size
        }

        // A split's type-name pool holds placeholders for the types it does not carry, so its
        // *length* indicates which type ids it could have named, not its non-empty entries.
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
     * Requires a pool whose strings can be appended to another's: no styles, and offsets that
     * land inside the pool.
     *
     * The encoding is checked when the pools are concatenated, because a mix of UTF-8 and UTF-16
     * pools cannot share one offset table.
     */
    private fun checkPool(pool: ByteArray, what: String) {
        if (pool.size < POOL_HEADER_SIZE) {
            throw TableFormatException("its $what pool is ${pool.size} bytes")
        }
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
            throw TableFormatException(
                "type 0x%02x has a $configSize-byte configuration".format(typeId)
            )
        }
        if (entriesStart < 0 || entriesStart > chunkSize) {
            throw TableFormatException(
                "type 0x%02x puts its entries past its own chunk".format(typeId)
            )
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
     * because the header is the only place a bag's length is recorded; a wrong length copies the
     * wrong bytes into the merged table.
     */
    private fun readEntry(table: ByteArray, offset: Int, limit: Int, typeId: Int): ByteArray {
        if (offset < 0 || offset + ENTRY_HEADER_SIZE > limit ||
            offset + ENTRY_HEADER_SIZE > table.size
        ) {
            throw TableFormatException(
                "type 0x%02x has an entry past the end of its chunk".format(typeId)
            )
        }
        val headerSize = u16(table, offset)
        val flags = u16(table, offset + 2)
        val length = if (flags and ENTRY_COMPLEX != 0) {
            if (headerSize < MAP_ENTRY_HEADER_SIZE) {
                throw TableFormatException(
                    "type 0x%02x has a $headerSize-byte bag entry".format(typeId)
                )
            }
            val count = u32(table, offset + 12)
            if (count < 0) {
                throw TableFormatException(
                    "type 0x%02x has a bag of $count entries".format(typeId)
                )
            }
            MAP_ENTRY_HEADER_SIZE + count * MAP_SIZE
        } else {
            headerSize + VALUE_SIZE
        }
        if (length <= 0 || offset + length > limit || offset + length > table.size) {
            throw TableFormatException(
                "type 0x%02x has a $length-byte entry that runs past the end of its chunk"
                    .format(typeId)
            )
        }
        return table.copyOfRange(offset, offset + length)
    }

    /** Reads one `ResTable_typeSpec` chunk's per-entry configuration flags. */
    private fun readSpec(
        table: ByteArray,
        offset: Int,
        chunkSize: Int,
        specs: MutableMap<Int, IntArray>
    ) {
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
        // they cover are still the same entries, so their flags combine rather than one
        // overwriting the other.
        val merged = if (existing.size < flags.size) existing.copyOf(flags.size) else existing
        for (i in flags.indices) {
            merged[i] = merged[i] or flags[i]
        }
        specs[typeId] = merged
    }

    /**
     * One entry's bytes with its two pool indexes moved to where its table's pools sit in the
     * merged one.
     *
     * Nothing else is touched. The entry keeps its size, its flags and its key's position within
     * its own table—offset by where that table starts—and every value that is not a string,
     * because a reference is a resource id and those are already global. A bag's values are
     * rewritten through the same [valueOffsets] a simple entry's is, which is what puts the write
     * at the `Res_value` inside each map rather than at the map's name.
     */
    private fun rewrite(entry: ByteArray, keyDelta: Int, stringDelta: Int): ByteArray {
        val out = entry.copyOf()
        putU32(out, 4, u32(out, 4) + keyDelta)
        if (stringDelta != 0) {
            for (at in valueOffsets(out)) {
                if (out[at + VALUE_TYPE_OFFSET].toInt() and 0xFF == TYPE_STRING) {
                    putU32(
                        out,
                        at + VALUE_DATA_OFFSET,
                        u32(out, at + VALUE_DATA_OFFSET) + stringDelta
                    )
                }
            }
        }
        return out
    }

    /**
     * Where each `Res_value` in an entry starts, relative to the entry: the field that
     * [VALUE_TYPE_OFFSET] and [VALUE_DATA_OFFSET] are then read and written at.
     *
     * A simple entry is one `ResTable_entry` followed by its value, so that value starts at the
     * entry header's own size. A complex entry is a `ResTable_map_entry` followed by its maps,
     * and each map is a `name` *followed by* the value it names—so a map's value is
     * [MAP_VALUE_OFFSET] bytes into the map, not at its start. Returning the map's start instead
     * points every caller at the name: this merge used to do this, and rewrote nothing for a bag
     * while reporting success, leaving the split-local pool index it should have rebased in
     * place.
     *
     * Both shapes are the same thing to every caller—the position of a `Res_value`—which is
     * why they share one function: the difference between the two shapes belongs here rather than
     * duplicated in each caller, or the two paths diverge.
     */
    private fun valueOffsets(entry: ByteArray): List<Int> {
        if (u16(entry, 2) and ENTRY_COMPLEX == 0) return listOf(ENTRY_HEADER_SIZE)
        val count = u32(entry, 12)
        return (0 until count).map { MAP_ENTRY_HEADER_SIZE + it * MAP_SIZE + MAP_VALUE_OFFSET }
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
        entries: Map<TypeConfig, Map<Int, Placed>>,
        specs: Map<Int, IntArray>
    ): ByteArray {
        val base = sources.first()
        val globalPool = concatPools(sources.map { it.globalPool.chunk })
        val keyStrings = concatPools(sources.map { it.keyStrings.chunk })
        val body = packageBody(entries, specs)

        // The base's package header is carried over whole, so its package id, its name, its
        // type-id offset and its public-name counts keep the values the base declares. Only the
        // three fields describing this package's new contents are written.
        //
        // The base's type-name pool is carried over whole too: a split cannot introduce a type id
        // the base could not already name—[merge] returns a refusal for that—so the base's
        // pool names every type the merged table uses, with the spelling the base gave it.
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
     * Type ids and configurations are both written in order, so the same inputs produce the same
     * bytes; a table that differed between runs could not be compared across builds.
     */
    private fun packageBody(
        entries: Map<TypeConfig, Map<Int, Placed>>,
        specs: Map<Int, IntArray>
    ): ByteArray {
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
     * The source chunk can be sparse or 16-bit, but copying that shape across copies a decision
     * made for one table's contents onto another's. Dense is valid for any contents, so a merge
     * writes dense chunks.
     */
    private fun typeChunk(typeId: Int, config: ByteArray, entries: Map<Int, Placed>): ByteArray {
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
                body.write(entry.bytes)
            }
        }
        val written = body.toByteArray()
        val chunk = ByteArray(headerSize + offsets.size + written.size)
        putU16(chunk, 0, TYPE_TYPE)
        putU16(chunk, 2, headerSize)
        putU32(chunk, 4, chunk.size)
        chunk[8] = typeId.toByte()
        // chunk[9] is the flags byte, left zero: dense 32-bit offsets, as described earlier.
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
        for (i in flags.indices) {
            putU32(chunk, SPEC_HEADER_SIZE + i * 4, flags[i])
        }
        return chunk
    }

    /**
     * Every pool in [pools] as one pool, by appending.
     *
     * The whole merge depends on this property: string *i* of the *k*th pool keeps index
     * `(the counts of the pools before k) + i`, so every index that already pointed at a string
     * still points at the same string. Nothing is sorted, re-encoded or deduplicated, and the
     * sorted flag is cleared, because an appended offset array is not sorted and the flag states
     * that it is.
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
            if (at < 0 || at >= pool.size) {
                throw TableFormatException("a string starts past the pool")
            }
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
                if (at + bytes > pool.size) {
                    throw TableFormatException("a string runs past the pool")
                }
                strings.add(String(pool, at, bytes, Charsets.UTF_8))
            } else {
                var characters = u16(pool, at)
                if (characters and 0x8000 != 0) {
                    characters = ((characters and 0x7FFF) shl 16) or u16(pool, at + 2)
                    at += 4
                } else {
                    at += 2
                }
                if (at + characters * 2 > pool.size) {
                    throw TableFormatException("a string runs past the pool")
                }
                strings.add(String(pool, at, characters * 2, Charsets.UTF_16LE))
            }
        }
        return strings
    }

    /**
     * What is wrong with [table] as a merged result, or null if nothing is.
     *
     * This re-reads the bytes that were produced rather than relying on the writer: the chunk tree
     * has to tile, every entry's name index has to land inside the key pool, and every
     * string-valued field has to land inside the global pool.
     *
     * Those are the checks the table can answer about itself, and on their own they are not
     * enough. A rebase that was not applied leaves a *valid* index—one that is in range in the
     * merged pool because the split's segment sits above it—pointing at the wrong string, and
     * reading the merged table alone does not reveal the difference. So each entry is also
     * compared against the entry it was made from ([difference]), which a rewrite that skipped a
     * field, or wrote at the wrong offset, does not survive.
     *
     * The comparison deliberately does not go through the arithmetic that produced the entry:
     * [valueOffsets] determines where a value sits, and a validator that asked the same function
     * where to look misses the mistake that function makes—which is how the bag-map defect in
     * an earlier version of this merge passed the check. The offsets the
     * comparison uses are checked against the bytes that are actually there first ([checkValue]),
     * against the source entry rather than anything this merge wrote, so an offset that names some
     * other field fails the build instead of passing both the writer and the reader.
     *
     * Last, the entries are counted: the number the caller receives as how many came out must
     * match the number the table holds.
     */
    private fun validate(
        table: ByteArray,
        entries: Map<TypeConfig, Map<Int, Placed>>,
        expectedStrings: Int,
        expectedKeys: Int,
        expectedEntries: Int
    ): String? {
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
        var counted = 0
        for ((key, byIndex) in source.entries) {
            val placed = entries[key] ?:
                return "type 0x%02x carries a configuration no entry was placed under"
                    .format(key.typeId)
            for ((index, entry) in byIndex) {
                val origin = placed[index] ?:
                    return "type 0x%02x holds an entry at index %d that no source carried"
                        .format(key.typeId, index)
                val nameIndex = u32(entry, 4)
                if (nameIndex < 0 || nameIndex >= source.keyStrings.count) {
                    return "type 0x%02x has an entry naming key %d of %d".format(
                        key.typeId, nameIndex, source.keyStrings.count
                    )
                }
                for (string in stringIndexes(entry)) {
                    if (string < 0 || string >= strings.size) {
                        return "type 0x%02x has an entry pointing at string %d of %d".format(
                            key.typeId, string, strings.size
                        )
                    }
                }
                val difference = difference(entry, origin)
                if (difference != null) {
                    return (
                        "type 0x%02x index %d is not its source entry with the pool " +
                            "indexes moved: $difference"
                    ).format(key.typeId, index)
                }
                counted++
            }
        }
        // The caller receives this count as [Result.Merged.resourceCount], so it must be the
        // count the table holds rather than the count the merge meant to place.
        if (counted != expectedEntries) {
            return "it holds $counted entries where the merge placed $expectedEntries"
        }
        return null
    }

    /**
     * The difference between [written] and the entry it was made from, or null if [written] is
     * that entry with its two pool indexes moved and nothing else.
     *
     * The rule is the merge's whole contract, checked against the bytes rather than against the
     * intent: the key must hold its source's key plus the delta that table's key pool was placed
     * at, every string-valued field must hold its source's index plus that table's string delta,
     * and no other byte of the entry must not differ from the source's at all.
     *
     * Nothing here consults the offsets the writer used. [valueOffsets] gives the position of a
     * value, [checkValue] requires the source's bytes at each of those places to *be* a
     * `Res_value`, and then every remaining byte is compared one for one—so a rewrite that wrote
     * where it should not have, or failed to write where it should, is a difference this function
     * returns rather than one that goes unreported.
     */
    private fun difference(written: ByteArray, origin: Placed): String? {
        val source = origin.source
        if (written.size != source.size) {
            return "it is ${written.size} bytes where its source's entry is ${source.size}"
        }
        if (u32(written, 4) != u32(source, 4) + origin.keyDelta) {
            return "its key index is %d where its source's %d moved by %d is %d".format(
                u32(written, 4), u32(source, 4), origin.keyDelta, u32(source, 4) + origin.keyDelta
            )
        }

        // The key field is the first four bytes after the entry header; a value's data is its last
        // four. Every other byte of the entry has to come through untouched.
        val rewritten = HashSet<Int>()
        for (at in 4 until ENTRY_HEADER_SIZE) {
            rewritten.add(at)
        }
        for (at in valueOffsets(source)) {
            val shape = checkValue(source, at)
            if (shape != null) return shape
            if (source[at + VALUE_TYPE_OFFSET].toInt() and 0xFF != TYPE_STRING) continue
            if (written[at + VALUE_TYPE_OFFSET].toInt() and 0xFF != TYPE_STRING) {
                return "the value at +$at came out typed ${
                    written[at + VALUE_TYPE_OFFSET].toInt() and 0xFF
                } where its source's is a string"
            }
            val stored = u32(written, at + VALUE_DATA_OFFSET)
            val expected = u32(source, at + VALUE_DATA_OFFSET) + origin.stringDelta
            if (stored != expected) {
                return (
                    "the string value at +$at holds pool index %d where its source's %d " +
                        "moved by %d is %d"
                ).format(
                    stored,
                    u32(source, at + VALUE_DATA_OFFSET),
                    origin.stringDelta,
                    expected
                )
            }
            for (i in VALUE_DATA_OFFSET until VALUE_SIZE) {
                rewritten.add(at + i)
            }
        }
        for (i in written.indices) {
            if (written[i] != source[i] && i !in rewritten) {
                return "its byte $i was written where its source holds " +
                    "${source[i].toInt() and 0xFF}"
            }
        }
        return null
    }

    /**
     * Requires the field at [at] in [entry] to be a `Res_value`: eight bytes long, with the
     * reserved byte the format requires to be zero.
     *
     * This check does not rely on [valueOffsets] alone for the position of a value. A bag's value
     * sits four bytes into its `ResTable_map`, behind the map's `name`, and an offset that lands
     * on that name instead reads a length from the low half of a resource id—`0x01000001` reads
     * as a one-byte value, not the eight bytes a `Res_value` declares—so the mistake fails the
     * build here rather than being written into a table.
     */
    private fun checkValue(entry: ByteArray, at: Int): String? {
        if (at < 0 || at + VALUE_SIZE > entry.size) {
            return "a value at +$at runs past the entry's ${entry.size} bytes"
        }
        val size = u16(entry, at)
        if (size != VALUE_SIZE) {
            return "the field at +$at declares $size bytes and a Res_value declares $VALUE_SIZE"
        }
        if (entry[at + 2].toInt() != 0) {
            return "the field at +$at has a reserved byte where a Res_value's is zero"
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
