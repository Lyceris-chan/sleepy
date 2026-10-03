package dev.sleepy.app.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renames an `AndroidManifest.xml`'s package, in the one sense a rename can mean.
 *
 * A manifest does not hold one kind of occurrence of the package name, and a rename that treats
 * them alike is how a clone came to launch a class that does not exist. The three kinds:
 *
 * - **Identity.** `package`, a provider's `android:authorities` and this app's own `<permission>`
 *   declarations are the application ID and the identifiers the platform keeps unique per device.
 *   They have to move: a second installation that declares `com.discord.fileprovider` is rejected
 *   outright (`INSTALL_FAILED_CONFLICTING_PROVIDER`), and the runtime value of each of them is
 *   *derived* from the package name—`Context.getPackageName() + ".fileprovider"`,
 *   `getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"`—so a manifest that leaves
 *   them behind names identifiers the app does not ask for. The `DYNAMIC_RECEIVER_NOT_EXPORTED_`
 *   permission is the case that makes this fail visibly: `ContextCompat.registerReceiver` throws
 *   when the app does not hold the name it builds from its own package.
 * - **Literals.** A class name, an `<action>`, a `<meta-data android:name>` key, the package a
 *   `<queries>` entry looks for. Each is a string that something else matches, and for the DEX that
 *   something else is Discord's own code, which this rename does not touch. Rewriting one of them
 *   renames nothing; it points at code, components and other apps that do not exist. The rewrite
 *   this replaces replaced every string beginning with the package, which is why a clone launched
 *   `com.discord.sleepy.MainApplication` while the DEX still held `com.discord.MainApplication`.
 * - **Relative class names.** `android:name=".main.MainActivity"` is resolved against `package`, so
 *   a component declared that way changes which class it names when `package` changes,
 *   though the string itself looks untouched. Those are written out in full against the *old*
 *   package before it moves, which is the only way the class they name can stay the one the build
 *   compiled.
 *
 * ## Why the string pool is appended to rather than rewritten in place
 *
 * A pool entry is shared by every attribute that spells the same string, so the decision cannot be
 * made per entry—and in this build it is not even the same decision twice: the pool holds one
 * `com.discord`, referred to by `<manifest package>`, which is identity and must move, and by a
 * `<queries><package>` and a `<meta-data android:value>`, which are not and must not. Replacing the
 * entry's text in place cannot express that. Appending a new entry and repointing the one attribute
 * that asked for the rename can, and it leaves every other byte of the document—every other
 * entry, the resource map, every node—where the build put it.
 */
object BinaryXmlModifier {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_STRING_POOL_TYPE = 0x0001

    private const val ROOT_HEADER_SIZE = 8
    private const val CHUNK_HEADER_SIZE = 8
    private const val POOL_HEADER_SIZE = 28
    private const val NODE_HEADER_SIZE = 16

    /** Where `ResXMLTree_node.name` sits: the node header, then the namespace. */
    private const val ELEMENT_NAME_OFFSET = NODE_HEADER_SIZE + 4

    /** `ResXMLTree_attribute` is a 12-byte header, then a `Res_value`. */
    private const val ATTRIBUTE_SIZE = 20
    private const val ATTRIBUTE_RAW_VALUE_OFFSET = 8
    private const val ATTRIBUTE_VALUE_OFFSET = 16

    private const val TYPE_STRING = 0x03
    private const val UTF8_FLAG = 0x100

    /**
     * The attribute holding the application ID.
     *
     * It is not namespaced, so it has no resource ID and is matched by name instead—and only on
     * `<manifest>`, because `<queries>` declares its own `<package>` elements, whose `android:name`
     * asks whether *another* package is installed and must go on asking about that one.
     */
    private const val ATTRIBUTE_PACKAGE = "package"

    /**
     * The elements whose `android:name` is the class a component is loaded from rather than an
     * identifier. Every one of them keeps the name it has.
     */
    private val COMPONENT_ELEMENTS = setOf(
        "application",
        "activity",
        "activity-alias",
        "service",
        "receiver",
        "provider",
        "instrumentation"
    )

    /**
     * Renames the package of a binary manifest from [oldPackageName] to [newPackageName], leaving
     * every string that is not the package identity where it is.
     *
     * A document that is not binary XML, or whose string pool cannot be read, is returned unchanged
     * rather than half-renamed: leaving it unchanged is preferable to producing a document that
     * does not parse.
     *
     * An attribute spells its string twice, and both copies are moved: `Res_value.data`, which
     * indexes the pool, and the `rawValue` beside it, which indexes it too as the attribute's raw
     * text. Updating only the first one is not enough: an installation of the clone was rejected
     * with `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.discord signatures do not
     * match newer version`, because the package the platform read out of the manifest was still
     * the old one, and `rawValue` was the only field that still held it. `aapt2` reads the `data`
     * field, so tool and device disagreed about the same file until both fields moved together.
     */
    fun modifyPackageName(
        manifestBytes: ByteArray,
        oldPackageName: String,
        newPackageName: String
    ): ByteArray {
        if (oldPackageName == newPackageName || newPackageName.isBlank()) {
            return manifestBytes
        }
        if (manifestBytes.size < ROOT_HEADER_SIZE) return manifestBytes

        val buf = ByteBuffer.wrap(manifestBytes).order(ByteOrder.LITTLE_ENDIAN)
        if ((buf.getShort(0).toInt() and 0xFFFF) != RES_XML_TYPE) return manifestBytes

        val poolOffset = stringPoolOffset(buf, manifestBytes)
        if (poolOffset < 0) return manifestBytes
        val resourceIds = BinaryXmlEditor.readResourceMap(buf, manifestBytes)
        val strings = BinaryXmlEditor.readStringPool(buf, manifestBytes)
        if (strings.isEmpty()) return manifestBytes

        val sites = packageNameSites(buf, manifestBytes, poolOffset, resourceIds, strings)
        // A permission this build declares itself moves with the package, and so does every request
        // for it: a request left behind asks the device for a permission this installation no
        // longer declares. A permission *another* package declares—`com.discord.permission.X`
        // requested from the official app—is not this build's identity and stays as it is, which
        // is why the requests are matched against the declarations rather than against the prefix.
        val ownPermissions = sites
            .filter { it.element == "permission" && it.attributeId == BinaryXmlEditor.ATTR_NAME }
            .map { it.value }
            .filter { derivedFrom(it, oldPackageName, newPackageName) != null }
            .toSet()

        val renames = sites.mapNotNull { site ->
            val renamed = renameOf(site, ownPermissions, oldPackageName, newPackageName) ?:
                return@mapNotNull null
            site to renamed
        }
        if (renames.isEmpty()) return manifestBytes

        val pool = readPoolChunk(buf, poolOffset)
        // The appended entries are numbered from the end of the pool as the *header* counts it, so
        // a pool that did not decode in full puts them on top of entries that are still
        // referenced. Nothing is renamed until every index is accounted for.
        if (strings.size != pool.count) return manifestBytes
        val replacements = renames.map { it.second }.distinct()
        val newPool = appendToStringPool(manifestBytes, pool, replacements)
        // The new entries go on the end of the pool, so every index the document already refers to
        // still means what it meant, and only the rewritten attributes have to be repointed.
        val newIndex = { value: String -> pool.count + replacements.indexOf(value) }

        val tailStart = poolOffset + pool.size
        val tail = manifestBytes.copyOfRange(tailStart, manifestBytes.size)
        val tailBuf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        for ((site, value) in renames) {
            val index = newIndex(value)
            val dataOffset = site.attributeOffset + ATTRIBUTE_VALUE_OFFSET - tailStart
            if (dataOffset < 0 || dataOffset + 4 > tail.size) return manifestBytes
            tailBuf.putInt(dataOffset, index)
            if (site.rawValueIsValue) {
                tailBuf.putInt(
                    dataOffset + ATTRIBUTE_RAW_VALUE_OFFSET - ATTRIBUTE_VALUE_OFFSET, index
                )
            }
        }

        val result = ByteArrayOutputStream(manifestBytes.size + newPool.size)
        result.write(manifestBytes, 0, poolOffset)
        result.write(newPool)
        result.write(tail)

        val renamed = result.toByteArray()
        ByteBuffer.wrap(renamed).order(ByteOrder.LITTLE_ENDIAN).putInt(4, renamed.size)
        return renamed
    }

    /**
     * Whether [value] is [oldPackageName] or an identifier derived from it, and what it becomes if
     * so.
     *
     * `com.discord` and `com.discord.fileprovider` are the package and something named after it;
     * `com.discordX` and `com.discordintents` are neither, and a prefix test that stops at the
     * text rather than at the package separator renames them.
     */
    private fun derivedFrom(
        value: String,
        oldPackageName: String,
        newPackageName: String
    ): String? = when {
        value == oldPackageName -> newPackageName
        value.startsWith("$oldPackageName.") -> newPackageName + value.removePrefix(oldPackageName)
        else -> null
    }

    /**
     * What a class name has to become to name the same class once the package it resolves against
     * has moved: nothing, when it is already absolute, and the class it means when it is not.
     *
     * The platform resolves a component's class name against the manifest's `package`—a leading
     * dot is replaced by it, and a name with no dot at all is prefixed with it. Those two forms are
     * the reason this function exists: left alone, they come to mean
     * `com.discord.sleepy.main.MainActivity`, a class the DEX does not contain.
     */
    private fun absoluteClassName(value: String, oldPackageName: String): String? = when {
        value.startsWith(".") -> oldPackageName + value
        '.' !in value -> "$oldPackageName.$value"
        else -> null
    }

    /**
     * The value [site] has to be rewritten to, or null when it must stay as it is.
     *
     * The three rules are the three kinds in the class doc, and their order is significant: a
     * component is a class before it is a name, and an `android:name` that is not a class name is
     * not a candidate at all unless the element makes it one.
     */
    private fun renameOf(
        site: Site,
        ownPermissions: Set<String>,
        oldPackageName: String,
        newPackageName: String
    ): String? {
        val element = site.element
        return when {
            // The application ID itself.
            element == "manifest" && site.attributeName == ATTRIBUTE_PACKAGE -> newPackageName
            // A component's class, and the class an `<activity-alias>` resolves to.
            site.attributeId == BinaryXmlEditor.ATTR_NAME && element in COMPONENT_ELEMENTS -> {
                absoluteClassName(site.value, oldPackageName)
            }
            site.attributeId == BinaryXmlEditor.ATTR_APP_COMPONENT_FACTORY &&
                element == "application" -> {
                absoluteClassName(site.value, oldPackageName)
            }
            site.attributeId == BinaryXmlEditor.ATTR_TARGET_ACTIVITY &&
                element == "activity-alias" -> {
                absoluteClassName(site.value, oldPackageName)
            }
            // Identity the platform and the app's own code derive from the package name.
            site.attributeId == BinaryXmlEditor.ATTR_AUTHORITIES && element == "provider" -> {
                derivedFrom(site.value, oldPackageName, newPackageName)
            }
            site.attributeId == BinaryXmlEditor.ATTR_NAME && element == "permission" -> {
                derivedFrom(site.value, oldPackageName, newPackageName)
            }
            site.attributeId == BinaryXmlEditor.ATTR_NAME &&
                element.startsWith(BinaryXmlEditor.ELEMENT_USES_PERMISSION) &&
                site.value in ownPermissions -> {
                derivedFrom(site.value, oldPackageName, newPackageName)
            }
            else -> null
        }
    }

    /**
     * One attribute of the document that is a candidate for the rename: the element it sits on,
     * what it is, and where its value is stored.
     *
     * [attributeOffset] is the attribute's own start, not the value's, because that is what the
     * document gives the walk; the value is a fixed distance inside it, and the raw text another.
     */
    private class Site(
        val element: String,
        val attributeId: Int,
        val attributeName: String,
        val value: String,
        val attributeOffset: Int,
        val rawValueIsValue: Boolean
    )

    /** Every attribute of the document that the rename inspects, in document order. */
    private fun packageNameSites(
        buf: ByteBuffer,
        xml: ByteArray,
        poolOffset: Int,
        resourceIds: IntArray,
        strings: List<String>
    ): List<Site> {
        val sites = mutableListOf<Site>()
        val stack = ArrayDeque<String>()

        var offset = poolOffset
        while (offset + CHUNK_HEADER_SIZE <= xml.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < CHUNK_HEADER_SIZE || offset + chunkSize > xml.size) break

            when (type) {
                RES_XML_START_ELEMENT_TYPE -> {
                    val element = elementName(buf, xml, offset, strings)
                    stack.addLast(element)
                    val attributes = attributesOf(buf, xml, offset, chunkSize, resourceIds, strings)
                    for (attribute in attributes) {
                        sites.add(
                            Site(
                                element = element,
                                attributeId = attribute.id,
                                attributeName = attribute.name,
                                value = attribute.value,
                                attributeOffset = attribute.offset,
                                rawValueIsValue = attribute.rawValueIsValue
                            )
                        )
                    }
                }
                // An element that is not closed leaves the stack in an unknown state, and the
                // elements below it can no longer be matched against the right parent. Nothing
                // further is collected; what was collected still has to pass the same rules.
                RES_XML_END_ELEMENT_TYPE -> {
                    if (stack.isEmpty()) return sites else stack.removeLast()
                }
            }
            offset += chunkSize
        }
        return sites
    }

    private class Attribute(
        val id: Int,
        val name: String,
        val value: String,
        val offset: Int,
        val rawValueIsValue: Boolean
    )

    /**
     * The string attributes of one `START_TAG`, resolved: the resource ID that identifies a
     * framework attribute, the local name that identifies one outside the framework's namespace
     * (`package`), and the value.
     *
     * Only `TYPE_STRING` attributes are read. A value written as a reference or a number is not a
     * literal this rename could rewrite, and reading one as a pool index repoints it at an
     * unrelated string.
     */
    private fun attributesOf(
        buf: ByteBuffer,
        xml: ByteArray,
        chunkStart: Int,
        chunkSize: Int,
        resourceIds: IntArray,
        strings: List<String>
    ): List<Attribute> {
        if (chunkSize < NODE_HEADER_SIZE + 20) return emptyList()
        val attributeStart = buf.getShort(chunkStart + 24).toInt() and 0xFFFF
        val attributeSize = buf.getShort(chunkStart + 26).toInt() and 0xFFFF
        val attributeCount = buf.getShort(chunkStart + 28).toInt() and 0xFFFF
        if (attributeSize != ATTRIBUTE_SIZE) return emptyList()
        val attributesOffset = chunkStart + NODE_HEADER_SIZE + attributeStart
        if (attributesOffset + attributeCount * attributeSize > xml.size) return emptyList()

        val attributes = ArrayList<Attribute>(attributeCount)
        for (i in 0 until attributeCount) {
            val attrStart = attributesOffset + i * attributeSize
            if ((buf.get(attrStart + 15).toInt() and 0xFF) != TYPE_STRING) continue
            val nameIndex = buf.getInt(attrStart + 4)
            val valueIndex = buf.getInt(attrStart + ATTRIBUTE_VALUE_OFFSET)
            val rawValueIndex = buf.getInt(attrStart + 8)
            if (nameIndex < 0 || nameIndex >= strings.size) continue
            if (valueIndex < 0 || valueIndex >= strings.size) continue
            attributes.add(
                Attribute(
                    id = if (nameIndex < resourceIds.size) resourceIds[nameIndex] else 0,
                    name = strings[nameIndex],
                    value = strings[valueIndex],
                    offset = attrStart,
                    // `rawValue` is the attribute's raw text, and the build writes the same string
                    // there as in the value. When it does, the two have to keep holding the same
                    // string; when it does not—a value that is a reference, or one with no raw
                    // text at all—it is not this value's text and is not rewritten.
                    rawValueIsValue = rawValueIndex == valueIndex
                )
            )
        }
        return attributes
    }

    /** The name of the element whose `START_TAG` begins at [chunkStart], or "" when unreadable. */
    private fun elementName(
        buf: ByteBuffer,
        xml: ByteArray,
        chunkStart: Int,
        strings: List<String>
    ): String {
        if (chunkStart + ELEMENT_NAME_OFFSET + 4 > xml.size) return ""
        val index = buf.getInt(chunkStart + ELEMENT_NAME_OFFSET)
        if (index < 0 || index >= strings.size) return ""
        return strings[index]
    }

    /** The offset of the document's string pool chunk, or -1 when there is none. */
    private fun stringPoolOffset(buf: ByteBuffer, xml: ByteArray): Int {
        var offset = buf.getShort(2).toInt() and 0xFFFF
        while (offset + CHUNK_HEADER_SIZE <= xml.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < CHUNK_HEADER_SIZE || offset + chunkSize > xml.size) return -1
            if (type == RES_STRING_POOL_TYPE) return offset
            offset += chunkSize
        }
        return -1
    }

    /**
     * The geometry of one string pool chunk: where it is, how big it is, and the layout of the
     * string data inside it that [appendToStringPool] has to reproduce.
     */
    private class Pool(
        val offset: Int,
        val headerSize: Int,
        val size: Int,
        val count: Int,
        val styleCount: Int,
        val flags: Int,
        val stringsStart: Int,
        val stylesStart: Int
    )

    private fun readPoolChunk(buf: ByteBuffer, offset: Int): Pool = Pool(
        offset = offset,
        headerSize = buf.getShort(offset + 2).toInt() and 0xFFFF,
        size = buf.getInt(offset + 4),
        count = buf.getInt(offset + 8),
        styleCount = buf.getInt(offset + 12),
        flags = buf.getInt(offset + 16),
        stringsStart = buf.getInt(offset + 20),
        stylesStart = buf.getInt(offset + 24)
    )

    /**
     * The pool chunk with [additions] written on the end of it.
     *
     * No entry already in the pool changes: the entries keep their text and their indices, which
     * is what lets an attribute be repointed at a new entry without disturbing any other
     * reference in the document. The string data is carried over verbatim, padding included, so
     * the offsets that point into it stay valid; the new entries follow it, and the whole data
     * region is padded once more so that whatever chunk comes next still starts on a 4-byte
     * boundary.
     */
    private fun appendToStringPool(xml: ByteArray, pool: Pool, additions: List<String>): ByteArray {
        val utf8 = (pool.flags and UTF8_FLAG) != 0
        val dataEnd = if (pool.stylesStart > 0) pool.stylesStart else pool.size
        val data = xml.copyOfRange(pool.offset + pool.stringsStart, pool.offset + dataEnd)

        val added = ByteArrayOutputStream()
        val newOffsets = IntArray(additions.size)
        for ((i, value) in additions.withIndex()) {
            newOffsets[i] = data.size + added.size()
            writePoolString(added, value, utf8)
        }
        while ((data.size + added.size()) % 4 != 0) {
            added.write(0)
        }

        val stringsStart = pool.stringsStart + additions.size * 4
        val styles = if (pool.stylesStart > 0) {
            xml.copyOfRange(pool.offset + pool.stylesStart, pool.offset + pool.size)
        } else {
            ByteArray(0)
        }
        val stylesStart =
            if (styles.isEmpty()) pool.stylesStart else stringsStart + data.size + added.size()
        val size = if (styles.isEmpty()) {
            stringsStart + data.size + added.size()
        } else {
            stylesStart + styles.size
        }

        val chunk = ByteArrayOutputStream(size)
        val header = ByteBuffer.allocate(pool.headerSize).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(RES_STRING_POOL_TYPE.toShort())
        header.putShort(pool.headerSize.toShort())
        header.putInt(size)
        header.putInt(pool.count + additions.size)
        header.putInt(pool.styleCount)
        header.putInt(pool.flags)
        header.putInt(stringsStart)
        header.putInt(stylesStart)
        chunk.write(header.array())
        // The old offsets first, in place, then the new ones: the pool's indices are the order of
        // this array, so appending is the only way to add an entry without renumbering the pool.
        chunk.write(xml, pool.offset + pool.headerSize, pool.count * 4)
        if (pool.styleCount > 0) {
            chunk.write(xml, pool.offset + pool.headerSize + pool.count * 4, pool.styleCount * 4)
        }
        val newOffsetBytes = ByteBuffer.allocate(newOffsets.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        newOffsets.forEach { newOffsetBytes.putInt(it) }
        chunk.write(newOffsetBytes.array())
        while (chunk.size() < stringsStart) {
            chunk.write(0)
        }
        chunk.write(data)
        chunk.write(added.toByteArray())
        chunk.write(styles)
        return chunk.toByteArray()
    }

    /**
     * One string, encoded the way the pool it is going into encodes its own.
     *
     * A UTF-8 entry carries the character count and the byte count, and a UTF-16 entry only the
     * character count; both are one byte (or unit) wide until the high bit indicates that a
     * second one follows. Android writes the character count as UTF-16 code units in either
     * case, which is what `String.length` counts.
     */
    private fun writePoolString(out: ByteArrayOutputStream, value: String, utf8: Boolean) {
        if (utf8) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writePoolLength(out, value.length)
            writePoolLength(out, bytes.size)
            out.write(bytes)
            out.write(0)
        } else {
            writePoolLength16(out, value.length)
            out.write(value.toByteArray(Charsets.UTF_16LE))
            out.write(0)
            out.write(0)
        }
    }

    /** A UTF-8 pool's leading length: one byte, or two when the high bit is set. */
    private fun writePoolLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) {
            out.write(length)
        } else {
            out.write(0x80 or (length ushr 8))
            out.write(length and 0xFF)
        }
    }

    /** A UTF-16 pool's leading length: one 16-bit unit, or two when the high bit is set. */
    private fun writePoolLength16(out: ByteArrayOutputStream, length: Int) {
        if (length < 0x8000) {
            out.write(length and 0xFF)
            out.write((length ushr 8) and 0xFF)
        } else {
            out.write((0x8000 or (length ushr 16)) and 0xFF)
            out.write((length ushr 8) and 0xFF)
            out.write(length and 0xFF)
            out.write((length ushr 24) and 0xFF)
        }
    }
}
