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
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_STRING_POOL_TYPE = 0x0001

    /** `AndroidManifest.xml` attribute resource IDs (`android` namespace). */
    const val ATTR_NAME = 0x01010003
    const val ATTR_EXPORTED = 0x01010010
    const val ATTR_REQUIRED_SPLIT_TYPES = 0x0101064e
    const val ATTR_SPLIT_TYPES = 0x0101064f
    const val ATTR_EXTRACT_NATIVE_LIBS = 0x010104ea

    /** The manifest element a declared permission lives in. */
    const val ELEMENT_USES_PERMISSION = "uses-permission"

    /** The other manifest elements this editor is asked to match. */
    const val ELEMENT_PROVIDER = "provider"
    const val ELEMENT_META_DATA = "meta-data"
    const val ELEMENT_SERVICE = "service"
    const val ELEMENT_INTENT = "intent"
    const val ELEMENT_ACTION = "action"

    private const val TYPE_NULL = 0x00
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12

    private const val NODE_HEADER_SIZE = 16
    private const val ATTRIBUTE_EXT_SIZE = 20
    private const val ATTRIBUTE_SIZE = 20
    private const val ROOT_HEADER_SIZE = 8
    private const val CHUNK_HEADER_SIZE = 8

    /** Where `ResXMLTree_node.name` sits: the node header, then the namespace. */
    private const val ELEMENT_NAME_OFFSET = NODE_HEADER_SIZE + 4

    /** No resource ID: the attribute is not a framework attribute. */
    private const val NO_RESOURCE_ID = 0

    /**
     * One element to delete from a document: the element's name (matched as a prefix), one
     * attribute of it that must carry a given string value, and — when that is not enough to
     * name it — an element it must contain.
     *
     * Matching on the name alone would be wrong for the case this exists for: the manifest
     * declares a permission with `<uses-permission android:name="..."/>`, so the element name is
     * shared by every permission and only the attribute says which one. The attribute is named by
     * resource ID rather than by string for the same reason attributes are removed by ID — the
     * `name` field on the wire is a string-pool index, not an identity.
     *
     * [attributeId] is null for an element the attribute test cannot single out — `<intent>` under
     * `<queries>` carries no attributes at all, and what tells the AppsFlyer one from the others is
     * the `<action>` inside it, which is what [contains] is for. A contained selector is matched
     * anywhere in the element's subtree, so it can itself carry a [contains].
     */
    data class ElementSelector(
        val namePrefix: String,
        val attributeId: Int? = null,
        val attributeValue: String = "",
        val contains: ElementSelector? = null
    ) {
        /**
         * What a caller names this selector by when reporting it back.
         *
         * The attribute value is the identity in the permission case, and it is what the caller
         * asked about. A selector that carries no attribute is identified by the element it was
         * distinguished by, and one that matches on its name alone falls back to the name.
         */
        val label: String get() = attributeValue.ifEmpty { contains?.label ?: namePrefix }
    }

    /**
     * One attribute to rewrite on the elements [element] matches, and nowhere else.
     *
     * [edit]'s `booleanOverrides` is keyed by attribute ID alone, which is right when every
     * occurrence of an attribute should change — `extractNativeLibs` sits on a single
     * `<application>`. It is wrong when one element out of many should change: `android:exported`
     * appears on dozens of components, and only one of them is being closed off. So this carries
     * the same selector an element removal does, matched the same way.
     */
    data class AttributeOverride(
        val element: ElementSelector,
        val attributeId: Int,
        val value: Boolean
    )

    /**
     * Result of an edit pass, describing what actually changed so the caller can report it
     * truthfully instead of assuming the edit landed.
     *
     * [elementsRemoved] and [elementsMissing] are keyed by [ElementSelector.label] — the permission
     * name, in the case this exists for — because that is the thing the caller asked about and the
     * thing it has to name back to the user. Which selector removed an element is the whole reason
     * the label is reported rather than the element: one pass can carry removals for several
     * unrelated reasons, and the caller reports each of them separately.
     *
     * [elementOverridesApplied] names, the same way, the selectors in `elementOverrides` that found
     * the element they name. It says the element was there, not that its attribute changed:
     * [attributesRewritten] says that, and the two together are what tells a caller "closed it"
     * from "it was already closed" from "this build does not declare it".
     */
    data class EditResult(
        val bytes: ByteArray,
        val attributesRemoved: List<Int>,
        val attributesRewritten: List<Int>,
        val missing: List<Int>,
        val elementsRemoved: List<String> = emptyList(),
        val elementsMissing: List<String> = emptyList(),
        val elementOverridesApplied: List<String> = emptyList()
    )

    /**
     * Removes every attribute in [stripAttributeIds], rewrites every attribute in
     * [booleanOverrides] to the given boolean value and deletes every element matched by
     * [removeElements], rebuilding the document with corrected chunk sizes.
     *
     * Attributes that were requested but not found are reported in [EditResult.missing]
     * rather than silently ignored; elements likewise in [EditResult.elementsMissing].
     */
    fun edit(
        xml: ByteArray,
        stripAttributeIds: Set<Int> = emptySet(),
        booleanOverrides: Map<Int, Boolean> = emptyMap(),
        removeElements: List<ElementSelector> = emptyList(),
        elementOverrides: List<AttributeOverride> = emptyList()
    ): EditResult {
        val requested = stripAttributeIds + booleanOverrides.keys
        if (xml.size < ROOT_HEADER_SIZE) {
            return EditResult(
                xml, emptyList(), emptyList(), requested.toList(),
                elementsMissing = removeElements.map { it.label }
            )
        }

        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        if ((buf.getShort(0).toInt() and 0xFFFF) != RES_XML_TYPE) {
            return EditResult(
                xml, emptyList(), emptyList(), requested.toList(),
                elementsMissing = removeElements.map { it.label }
            )
        }

        val resourceIds = readResourceMap(buf, xml)
        // Only read the pool when an element is being matched by name or value: it is the one
        // thing here that has to resolve a string, and a document nothing is matched against
        // never needs it.
        val strings = if (removeElements.isEmpty() && elementOverrides.isEmpty()) {
            emptyList()
        } else {
            readStringPool(buf, xml)
        }
        val out = ByteArrayOutputStream(xml.size)
        out.write(ByteArray(ROOT_HEADER_SIZE)) // root header rewritten once the size is known

        val removed = mutableListOf<Int>()
        val rewritten = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()
        val elementsRemoved = mutableListOf<String>()
        val removedSelectors = mutableSetOf<ElementSelector>()
        val overridesApplied = mutableListOf<String>()

        var offset = buf.getShort(2).toInt() and 0xFFFF
        while (offset + CHUNK_HEADER_SIZE <= xml.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < CHUNK_HEADER_SIZE || offset + chunkSize > xml.size) break

            if (type == RES_XML_START_ELEMENT_TYPE) {
                val selector = matchElement(buf, xml, offset, resourceIds, strings, removeElements)
                if (selector != null) {
                    // Skip the whole element, children included, by jumping past its END_TAG. A
                    // malformed document where no END_TAG closes it is left alone rather than
                    // truncated: a manifest that parses is worth more than one edit.
                    val afterElement = endOfElement(buf, xml, offset, chunkSize)
                    if (afterElement > offset) {
                        elementsRemoved.add(selector.label)
                        removedSelectors.add(selector)
                        offset = afterElement
                        continue
                    }
                }
                // A scoped override wins over a global one: it names the element it belongs to,
                // and the global map names only an attribute.
                val applicable = if (elementOverrides.isEmpty()) {
                    booleanOverrides
                } else {
                    val scoped = elementOverrides.filter {
                        matchElement(buf, xml, offset, resourceIds, strings, listOf(it.element)) != null
                    }
                    for (override in scoped) {
                        val label = override.element.label
                        if (label !in overridesApplied) overridesApplied.add(label)
                    }
                    if (scoped.isEmpty()) {
                        booleanOverrides
                    } else {
                        booleanOverrides + scoped.associate { it.attributeId to it.value }
                    }
                }
                out.write(
                    editStartElement(
                        xml, buf, offset, chunkSize, resourceIds,
                        stripAttributeIds, applicable, removed, rewritten, seen
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

        return EditResult(
            bytes = body,
            attributesRemoved = removed,
            attributesRewritten = rewritten,
            missing = requested.filter { it !in seen },
            elementsRemoved = elementsRemoved,
            elementsMissing = removeElements
                .filter { it !in removedSelectors }
                .map { it.label },
            elementOverridesApplied = overridesApplied
        )
    }

    /**
     * Convenience wrapper for the split-merge case: drop the split declarations and force
     * `android:extractNativeLibs="true"` so merged, DEFLATE-compressed native libraries are
     * extracted at install time instead of being mapped out of the APK.
     *
     * [removeElements] is offered here rather than as a second pass so that a manifest is edited
     * once, with one corrected root size: two passes would each rewrite the document and the
     * second would have to re-read what the first produced.
     */
    fun makeStandaloneManifest(
        manifestBytes: ByteArray,
        removeElements: List<ElementSelector> = emptyList(),
        elementOverrides: List<AttributeOverride> = emptyList()
    ): EditResult = edit(
        xml = manifestBytes,
        stripAttributeIds = setOf(ATTR_REQUIRED_SPLIT_TYPES, ATTR_SPLIT_TYPES),
        booleanOverrides = mapOf(ATTR_EXTRACT_NATIVE_LIBS to true),
        removeElements = removeElements,
        elementOverrides = elementOverrides
    )

    /**
     * The string values of [attributeId] on every element whose name starts with [namePrefix], in
     * document order.
     *
     * This is how a build's own declarations are read rather than assumed: the manifest the user
     * selected is the authority on what it declares, and a list written down here would be wrong
     * the first time the app updates.
     */
    fun readElementAttributeValues(
        xml: ByteArray,
        namePrefix: String,
        attributeId: Int
    ): List<String> {
        if (xml.size < ROOT_HEADER_SIZE) return emptyList()
        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        if ((buf.getShort(0).toInt() and 0xFFFF) != RES_XML_TYPE) return emptyList()

        val resourceIds = readResourceMap(buf, xml)
        val strings = readStringPool(buf, xml)
        if (strings.isEmpty()) return emptyList()

        val values = mutableListOf<String>()
        var offset = buf.getShort(2).toInt() and 0xFFFF
        while (offset + CHUNK_HEADER_SIZE <= xml.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < CHUNK_HEADER_SIZE || offset + chunkSize > xml.size) break
            if (type == RES_XML_START_ELEMENT_TYPE &&
                elementName(buf, xml, offset, strings).startsWith(namePrefix)
            ) {
                val value = findStringAttribute(buf, xml, offset, chunkSize, resourceIds, strings, attributeId)
                if (value != null) values.add(value)
            }
            offset += chunkSize
        }
        return values
    }

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
     * The first selector in [selectors] that [chunkStart] matches, or null when none does.
     *
     * A selector matches on the element's name as a *prefix* — `uses-permission` covers
     * `uses-permission-sdk-23` and `uses-permission-sdk-m`, the variants the platform reads for
     * their own SDK ranges — and on the string value of one attribute, which is what tells one
     * permission declaration from another. A selector with no attribute matches on the name
     * alone; one that also carries a `contains` has to hold that element somewhere inside it.
     */
    private fun matchElement(
        buf: ByteBuffer,
        xml: ByteArray,
        chunkStart: Int,
        resourceIds: IntArray,
        strings: List<String>,
        selectors: List<ElementSelector>
    ): ElementSelector? {
        if (selectors.isEmpty()) return null
        val name = elementName(buf, xml, chunkStart, strings)
        if (name.isEmpty()) return null

        val chunkSize = buf.getInt(chunkStart + 4)
        for (selector in selectors) {
            if (!name.startsWith(selector.namePrefix)) continue
            val attributeId = selector.attributeId
            if (attributeId != null) {
                val value = findStringAttribute(buf, xml, chunkStart, chunkSize, resourceIds, strings, attributeId)
                if (value != selector.attributeValue) continue
            }
            if (selector.contains != null && !containsElement(buf, xml, chunkStart, resourceIds, strings, selector)) {
                continue
            }
            return selector
        }
        return null
    }

    /**
     * Whether the element at [chunkStart] holds an element matching the selector's `contains`
     * anywhere below it.
     *
     * "Anywhere below" rather than "as a direct child": how deeply a producer nests an element is
     * its own business, and a selector that had to say which depth it meant would break the first
     * time that changed. The walk stops at the element's own `END_TAG`, so an element matched
     * inside a *sibling* cannot satisfy it.
     */
    private fun containsElement(
        buf: ByteBuffer,
        xml: ByteArray,
        chunkStart: Int,
        resourceIds: IntArray,
        strings: List<String>,
        selector: ElementSelector
    ): Boolean {
        val contained = selector.contains ?: return false
        val chunkSize = buf.getInt(chunkStart + 4)
        val end = endOfElement(buf, xml, chunkStart, chunkSize)
        if (end < 0) return false

        var cursor = chunkStart + chunkSize
        while (cursor + CHUNK_HEADER_SIZE <= end) {
            val type = buf.getShort(cursor).toInt() and 0xFFFF
            val size = buf.getInt(cursor + 4)
            if (size < CHUNK_HEADER_SIZE || cursor + size > end) return false
            if (type == RES_XML_START_ELEMENT_TYPE &&
                matchElement(buf, xml, cursor, resourceIds, strings, listOf(contained)) != null
            ) {
                return true
            }
            cursor += size
        }
        return false
    }

    /**
     * The offset just past the `END_TAG` that closes the element starting at [chunkStart], or -1
     * when nothing closes it.
     *
     * Android writes a self-closing `<x/>` as `START_TAG` immediately followed by `END_TAG` — which
     * is what every `<uses-permission/>` in the manifests this was written against looks like — so
     * the loop below usually runs once. It counts depth rather than assuming that, because the
     * format does allow children and a document where it does must not be cut in half.
     */
    private fun endOfElement(buf: ByteBuffer, xml: ByteArray, chunkStart: Int, chunkSize: Int): Int {
        var depth = 1
        var cursor = chunkStart + chunkSize
        while (cursor + CHUNK_HEADER_SIZE <= xml.size) {
            val type = buf.getShort(cursor).toInt() and 0xFFFF
            val size = buf.getInt(cursor + 4)
            if (size < CHUNK_HEADER_SIZE || cursor + size > xml.size) return -1
            when (type) {
                RES_XML_START_ELEMENT_TYPE -> depth++
                RES_XML_END_ELEMENT_TYPE -> {
                    depth--
                    if (depth == 0) return cursor + size
                }
            }
            cursor += size
        }
        return -1
    }

    /** The name of the element whose `START_TAG` begins at [chunkStart], or "" when unreadable. */
    private fun elementName(buf: ByteBuffer, xml: ByteArray, chunkStart: Int, strings: List<String>): String {
        if (chunkStart + ELEMENT_NAME_OFFSET + 4 > xml.size) return ""
        val index = buf.getInt(chunkStart + ELEMENT_NAME_OFFSET)
        if (index < 0 || index >= strings.size) return ""
        return strings[index]
    }

    /**
     * The string value of [attributeId] on the element at [chunkStart], or null when the element
     * has no such attribute or it does not hold a string.
     *
     * Only `TYPE_STRING` counts. A `name` attribute written as anything else — a reference, a raw
     * integer — is not the literal the caller is comparing against, and reading its `data` field as
     * a pool index would compare a number to a permission name.
     */
    private fun findStringAttribute(
        buf: ByteBuffer,
        xml: ByteArray,
        chunkStart: Int,
        chunkSize: Int,
        resourceIds: IntArray,
        strings: List<String>,
        attributeId: Int
    ): String? {
        if (chunkSize < ATTRIBUTE_EXT_SIZE) return null
        val attributeStart = buf.getShort(chunkStart + 24).toInt() and 0xFFFF
        val attributeSize = buf.getShort(chunkStart + 26).toInt() and 0xFFFF
        val attributeCount = buf.getShort(chunkStart + 28).toInt() and 0xFFFF
        if (attributeStart < ATTRIBUTE_EXT_SIZE) return null
        if (attributeSize != ATTRIBUTE_SIZE) return null

        val attributesOffset = chunkStart + NODE_HEADER_SIZE + attributeStart
        for (i in 0 until attributeCount) {
            val attrStart = attributesOffset + i * attributeSize
            if (attrStart + ATTRIBUTE_SIZE > xml.size) return null
            if (attributeIdAt(buf, resourceIds, attrStart) != attributeId) continue
            if ((buf.get(attrStart + 15).toInt() and 0xFF) != TYPE_STRING) return null
            val index = buf.getInt(attrStart + 16)
            if (index < 0 || index >= strings.size) return null
            return strings[index]
        }
        return null
    }

    /**
     * Reads the document's `RES_STRING_POOL_TYPE` chunk into a list indexed the way every string
     * reference in the document indexes it.
     *
     * Both encodings are handled: the platform writes UTF-8 pools (the flag whose absence means
     * UTF-16), and an aapt2 build can be told to write either. Length prefixes are variable-width
     * in both, and out-of-range entries are left empty rather than throwing — this runs over a file
     * someone else produced, and a mis-sized pool must not take the patch run down with it.
     */
    private fun readStringPool(buf: ByteBuffer, xml: ByteArray): List<String> {
        var offset = buf.getShort(2).toInt() and 0xFFFF
        while (offset + CHUNK_HEADER_SIZE <= xml.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val headerSize = buf.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < CHUNK_HEADER_SIZE || offset + chunkSize > xml.size) break

            if (type == RES_STRING_POOL_TYPE) {
                if (headerSize < 28 || offset + headerSize > xml.size) return emptyList()
                val count = buf.getInt(offset + 8)
                val flags = buf.getInt(offset + 16)
                val stringsStart = buf.getInt(offset + 20)
                if (count <= 0 || count > (chunkSize - headerSize) / 4) return emptyList()
                val utf8 = (flags and 0x100) != 0
                val dataStart = offset + stringsStart

                val strings = ArrayList<String>(count)
                for (i in 0 until count) {
                    val entryStart = offset + headerSize + i * 4
                    if (entryStart + 4 > xml.size) return strings
                    strings.add(readPoolString(buf, xml, dataStart + buf.getInt(entryStart), utf8))
                }
                return strings
            }
            offset += chunkSize
        }
        return emptyList()
    }

    /** One string out of a pool, decoded from whichever of the two encodings the pool uses. */
    private fun readPoolString(buf: ByteBuffer, xml: ByteArray, start: Int, utf8: Boolean): String {
        if (start < 0 || start >= xml.size) return ""
        return try {
            if (utf8) {
                var cursor = start
                val charCount = readPoolLength(buf, xml, cursor) ?: return ""
                cursor = charCount.second
                val byteCount = readPoolLength(buf, xml, cursor) ?: return ""
                cursor = byteCount.second
                val end = cursor + byteCount.first
                if (end > xml.size) "" else String(xml, cursor, byteCount.first, Charsets.UTF_8)
            } else {
                val charCount = readPoolLength16(buf, xml, start) ?: return ""
                val byteCount = charCount.first * 2
                val end = charCount.second + byteCount
                if (end > xml.size) "" else String(xml, charCount.second, byteCount, Charsets.UTF_16LE)
            }
        } catch (e: IndexOutOfBoundsException) {
            // A pool whose offsets run past the chunk is not one this editor can reason about; the
            // element it would have named simply reads as unnamed and is left where it is.
            ""
        }
    }

    /** A UTF-8 pool's leading length: one byte, or two when the high bit is set. */
    private fun readPoolLength(buf: ByteBuffer, xml: ByteArray, start: Int): Pair<Int, Int>? {
        if (start + 1 > xml.size) return null
        val first = xml[start].toInt() and 0xFF
        if (first and 0x80 == 0) return first to (start + 1)
        if (start + 2 > xml.size) return null
        return (((first and 0x7F) shl 8) or (xml[start + 1].toInt() and 0xFF)) to (start + 2)
    }

    /**
     * A UTF-16 pool's leading length: one 16-bit unit, or two when its high bit is set.
     *
     * The pool is little-endian on every platform this runs on, so the units are read through the
     * buffer's own byte order rather than assembled by hand.
     */
    private fun readPoolLength16(buf: ByteBuffer, xml: ByteArray, start: Int): Pair<Int, Int>? {
        if (start + 2 > xml.size) return null
        val first = buf.getShort(start).toInt() and 0xFFFF
        if (first and 0x8000 == 0) return first to (start + 2)
        if (start + 4 > xml.size) return null
        val second = buf.getShort(start + 2).toInt() and 0xFFFF
        return (((first and 0x7FFF) shl 16) or second) to (start + 4)
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
