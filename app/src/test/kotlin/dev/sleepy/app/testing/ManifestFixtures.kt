package dev.sleepy.app.testing

import dev.sleepy.app.engine.BinaryXmlEditor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A synthetic binary manifest, and the readers a manifest edit is checked with.
 *
 * A package rename moves the strings the package owns and leaves the rest, so the tests need
 * documents carrying exactly the strings at issue and a reader that reports every attribute with
 * both copies of its value. [documentOf] builds such a document from an element tree; the
 * resource map it writes is the one the platform's attribute matcher uses, so the editor follows
 * the same path it follows over a real manifest.
 *
 * [ATTR_VALUE], [ATTR_PROCESS] and [ATTR_TASK_AFFINITY] are framework attributes the rename never
 * reads: their values are the ones that must come out of the edit untouched.
 */

/**
 * One attribute of one element: the element it sits on, what it spells, and the raw text it
 * spells beside that.
 *
 * [raw] is the attribute's other copy of its string—a pool index of its own, not a view of
 * [value]—or `""` where the attribute carries none. A rename that moves one and not the other
 * leaves the old package name in the file, which is the failure this field exists to catch.
 */
data class ManifestAttribute(
    val element: String,
    val attribute: String,
    val value: String,
    val raw: String,
)

/**
 * Every attribute of the document, in document order, with the element it sits on.
 *
 * The reader identifies attributes by resource ID where they have one and by their local name
 * where they do not, which is the same distinction the code under test makes—`package` has no
 * resource ID, so two different attributes called `name` and `package` cannot be confused for
 * each other. A value that is not a string is reported by its bytes; repointing one is a change
 * to something the rename never read.
 */
fun manifestAttributes(xml: ByteArray): List<ManifestAttribute> {
    val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
    val resourceIds = BinaryXmlEditor.readResourceMap(buf, xml)
    val strings = BinaryXmlEditor.readStringPool(buf, xml)
    val attributes = mutableListOf<ManifestAttribute>()
    val stack = ArrayDeque<String>()

    var offset = buf.getShort(2).toInt() and 0xFFFF
    while (offset + 8 <= xml.size) {
        val type = buf.getShort(offset).toInt() and 0xFFFF
        val chunkSize = buf.getInt(offset + 4)
        if (chunkSize < 8 || offset + chunkSize > xml.size) break
        if (type == 0x0102) {
            val element = strings[buf.getInt(offset + 20)]
            stack.addLast(element)
            val attributeStart = buf.getShort(offset + 24).toInt() and 0xFFFF
            val attributeCount = buf.getShort(offset + 28).toInt() and 0xFFFF
            for (i in 0 until attributeCount) {
                val start = offset + 16 + attributeStart + i * 20
                val nameIndex = buf.getInt(start + 4)
                val id = if (nameIndex < resourceIds.size) resourceIds[nameIndex] else 0
                val name = strings[nameIndex]
                val dataType = buf.get(start + 15).toInt() and 0xFF
                val data = buf.getInt(start + 16)
                val value = if (dataType == 0x03 && data < strings.size) {
                    strings[data]
                } else {
                    "type=0x%02x data=0x%08x".format(dataType, data)
                }
                val rawIndex = buf.getInt(start + 8)
                val raw = if (rawIndex in strings.indices) strings[rawIndex] else ""
                attributes.add(
                    ManifestAttribute(
                        element,
                        if (id != 0) "android:$name" else name,
                        value,
                        raw
                    )
                )
            }
        }
        if (type == 0x0103) stack.removeLast()
        offset += chunkSize
    }
    return attributes
}

/** The values of [element]'s [attributeId] attributes, in document order. */
fun attributeValues(xml: ByteArray, element: String, attributeId: Int): List<String> {
    val id = if (attributeId == 0) "package" else "android:${androidAttributeName(attributeId)}"
    return manifestAttributes(xml).filter { it.element == element && it.attribute == id }
        .map { it.value }
}

/** The name an `android:` attribute of the manifest is spelled with. */
private fun androidAttributeName(attributeId: Int): String = when (attributeId) {
    BinaryXmlEditor.ATTR_NAME -> "name"
    BinaryXmlEditor.ATTR_AUTHORITIES -> "authorities"
    BinaryXmlEditor.ATTR_APP_COMPONENT_FACTORY -> "appComponentFactory"
    BinaryXmlEditor.ATTR_TARGET_ACTIVITY -> "targetActivity"
    ATTR_VALUE -> "value"
    ATTR_PROCESS -> "process"
    ATTR_TASK_AFFINITY -> "taskAffinity"
    else -> error("no name known for attribute 0x%08x".format(attributeId))
}

/** The package the document declares. */
fun manifestPackageName(xml: ByteArray): String =
    manifestAttributes(xml).first { it.element == "manifest" && it.attribute == "package" }.value

/**
 * The class names the manifest names: the application class, its component factory, every
 * component's class, and what an `<activity-alias>` resolves to.
 *
 * These are the strings the platform looks up in the DEX, which is the lookup a rename must
 * not break. An `<activity-alias>`'s own `android:name` is deliberately not among them: the
 * platform never loads an alias as a class—it resolves the alias to its `targetActivity` and
 * loads that—so an alias is free to be named after something that was never compiled.
 * Discord's launcher entry is one: `com.discord.main.MainDefault` is an alias for
 * `com.discord.main.MainActivity`, and no class of the former name exists.
 */
fun manifestClassNames(xml: ByteArray): Set<String> {
    val components =
        setOf("application", "activity", "service", "receiver", "provider", "instrumentation")
    val names = manifestAttributes(xml)
        .filter {
            (it.element in components &&
                (it.attribute == "android:name" ||
                    it.attribute == "android:appComponentFactory")) ||
                (it.element == "activity-alias" && it.attribute == "android:targetActivity")
        }
        .map { it.value }
        .toSet()
    return names
}

/** The number of entries in the document's string pool. */
fun stringPoolSize(xml: ByteArray): Int {
    val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
    var offset = buf.getShort(2).toInt() and 0xFFFF
    while (offset + 8 <= xml.size) {
        val type = buf.getShort(offset).toInt() and 0xFFFF
        val chunkSize = buf.getInt(offset + 4)
        if (chunkSize < 8 || offset + chunkSize > xml.size) break
        if (type == 0x0001) return buf.getInt(offset + 8)
        offset += chunkSize
    }
    return -1
}

/**
 * One attribute of a synthetic element: its resource ID (0 for a non-framework one), and what
 * it spells.
 */
data class XmlAttribute(val id: Int, val name: String, val value: String)

/** One element of a synthetic document. */
data class XmlElement(
    val name: String,
    val attributes: List<XmlAttribute> = emptyList(),
    val children: List<XmlElement> = emptyList()
)

/** An `android:name` attribute spelled [value]. */
fun androidName(value: String) = XmlAttribute(BinaryXmlEditor.ATTR_NAME, "name", value)

/** An `android:authorities` attribute spelled [value]. */
fun authorities(value: String) =
    XmlAttribute(BinaryXmlEditor.ATTR_AUTHORITIES, "authorities", value)

/** The non-framework `package` attribute spelled [value]. */
fun packageAttribute(value: String) = XmlAttribute(0, "package", value)

/** The document [root] serialized as binary XML. */
fun documentOf(root: XmlElement): ByteArray = syntheticDocument(root)

/**
 * A minimal but real binary-XML document: a UTF-8 string pool, a resource map that resolves the
 * framework attributes used, and the given element tree under a root element.
 *
 * The resource map is what makes the attribute matcher work at all—an attribute's `name`
 * field is a string-pool index, and only the map turns it into `0x01010003`. It is also what
 * makes this document exercise the real path rather than a shortcut. XmlAttribute names
 * are pooled before anything else so that the map, which is indexed by pool position, can be
 * built over a known range; index 0 is a string no attribute resolves to, so the map is not
 * trivially aligned with the pool.
 *
 * Identical strings share a pool entry, as `aapt2` writes them—which is what makes the
 * sharing case above a real one rather than a staged one.
 */
private fun syntheticDocument(root: XmlElement): ByteArray {
    val names = mutableListOf<String>()
    fun pool(name: String): Int {
        val existing = names.indexOf(name)
        if (existing >= 0) return existing
        names.add(name)
        return names.size - 1
    }

    pool("unmapped")
    val attributeIds = mutableMapOf<Int, Int>()
    fun poolAttributes(element: XmlElement) {
        for (attribute in element.attributes) {
            val index = pool(attribute.name)
            if (attribute.id != 0) attributeIds[index] = attribute.id
        }
        element.children.forEach(::poolAttributes)
    }
    poolAttributes(root)

    fun poolTree(element: XmlElement) {
        pool(element.name)
        element.attributes.forEach { pool(it.value) }
        element.children.forEach(::poolTree)
    }
    poolTree(root)

    val document = ByteArrayOutputStream()
    document.write(ByteArray(8)) // root header, written once the size is known

    // String pool: headerSize 28, UTF-8 strings, no styles.
    val data = ByteArrayOutputStream()
    val offsets = IntArray(names.size)
    for ((i, name) in names.withIndex()) {
        offsets[i] = data.size()
        val bytes = name.toByteArray(Charsets.UTF_8)
        data.write(bytes.size)
        data.write(bytes.size)
        data.write(bytes)
        data.write(0)
    }
    val poolChunkSize = 28 + names.size * 4 + data.size()
    val poolChunk = ByteBuffer.allocate(poolChunkSize).order(ByteOrder.LITTLE_ENDIAN)
    poolChunk.putShort(0x0001)
    poolChunk.putShort(28)
    poolChunk.putInt(poolChunkSize)
    poolChunk.putInt(names.size)
    poolChunk.putInt(0)      // styleCount
    poolChunk.putInt(0x100)  // UTF8_FLAG
    poolChunk.putInt(28 + names.size * 4)
    poolChunk.putInt(0)      // stylesStart
    offsets.forEach { poolChunk.putInt(it) }
    poolChunk.put(data.toByteArray())
    document.write(poolChunk.array())

    // Resource map: one entry per pool index, carrying the resource ID of the attribute name
    // that sits there—0 for a string no attribute resolves to.
    val map = ByteBuffer.allocate(8 + names.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    map.putShort(0x0180.toShort())
    map.putShort(8)
    map.putInt(8 + names.size * 4)
    for (i in names.indices) {
        map.putInt(attributeIds[i] ?: 0)
    }
    document.write(map.array())

    writeElement(document, root, ::pool)

    val body = document.toByteArray()
    val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    header.putShort(0x0003.toShort())
    header.putShort(8)
    header.putInt(body.size)
    System.arraycopy(header.array(), 0, body, 0, 8)
    return body
}

/** Writes one element, each closed around whatever it contains. */
private fun writeElement(out: ByteArrayOutputStream, element: XmlElement, pool: (String) -> Int) {
    out.write(
        startTag(
            pool(element.name),
            element.attributes.map { pool(it.name) to pool(it.value) }
        )
    )
    element.children.forEach { writeElement(out, it, pool) }
    out.write(endTag(pool(element.name)))
}

/** `android:value`, as the platform's resource map names it. */
const val ATTR_VALUE = 0x01010024

/** `android:process`, as the platform's resource map names it. */
const val ATTR_PROCESS = 0x01010011

/** `android:taskAffinity`, as the platform's resource map names it. */
const val ATTR_TASK_AFFINITY = 0x01010012
