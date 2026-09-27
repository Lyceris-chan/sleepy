package dev.sleepy.app

import dev.sleepy.app.engine.BinaryXmlEditor
import dev.sleepy.app.engine.BinaryXmlEditor.ElementSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Element removal in [BinaryXmlEditor], against the real manifests this app patches and against a
 * synthetic one for the shapes the real builds happen not to contain.
 *
 * The claim under test is narrow and checkable: removing N declared permissions removes exactly
 * those N elements, leaves every other byte where it was, and leaves a document that still tiles —
 * every chunk ending exactly where the next one begins, and the root chunk sized to the file.
 */
class ManifestPermissionTest {

    private val discordApk =
        File("/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted/base.apk")

    private val octoGramApk = File("/home/sleepy/Documents/antigravity/telegram/OctoGram_361_arm64.apk")

    /** Where `aapt2` lives when the Android SDK build tools are installed beside this checkout. */
    private val aapt2Candidates = listOf(
        File("/home/sleepy/portable-tools/android-sdk/build-tools/36.0.0/aapt2"),
        File(System.getenv("ANDROID_HOME") ?: "/nonexistent", "build-tools/36.0.0/aapt2")
    )

    /** A manifest from a fixture APK, or null when that fixture is not on this machine. */
    private fun manifestOf(apk: File): ByteArray? {
        if (!apk.exists()) {
            println("${apk.name} not found, skipping test")
            return null
        }
        return ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return null
            zip.getInputStream(entry).readBytes()
        }
    }

    private fun declared(xml: ByteArray): List<String> = BinaryXmlEditor.readElementAttributeValues(
        xml = xml,
        namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
        attributeId = BinaryXmlEditor.ATTR_NAME
    )

    private fun selectorsFor(vararg permissionNames: String): List<ElementSelector> =
        permissionNames.map {
            ElementSelector(
                namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
                attributeId = BinaryXmlEditor.ATTR_NAME,
                attributeValue = it
            )
        }

    /**
     * Walks the chunk tree and requires it to tile the document exactly.
     *
     * This is the same walk the platform performs: a chunk that overruns the file, or a size
     * smaller than a header, is a document the platform would refuse to read.
     */
    private fun assertTilesExactly(xml: ByteArray, label: String) {
        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("$label: root chunk is not binary XML", 0x0003, buf.getShort(0).toInt() and 0xFFFF)
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

    /** Every chunk of a document, as (type, bytes), in the order the walk visits them. */
    private fun chunksOf(xml: ByteArray): List<Pair<Int, ByteArray>> {
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
    private fun sameChunk(a: Pair<Int, ByteArray>, b: Pair<Int, ByteArray>): Boolean =
        a.first == b.first && a.second.contentEquals(b.second)

    @Test
    fun removesExactlyTheRequestedPermissionsFromTheRealManifest() {
        val original = manifestOf(discordApk) ?: return
        val before = declared(original)
        assertTrue("the Discord base manifest should declare permissions", before.size > 5)
        assertTrue(before.contains("android.permission.READ_CONTACTS"))
        assertTrue(before.contains("android.permission.CAMERA"))
        assertTrue(before.contains("android.permission.INTERNET"))

        val removed = listOf(
            "android.permission.READ_CONTACTS",
            "android.permission.CAMERA",
            "android.permission.ACCESS_WIFI_STATE"
        )
        val result = BinaryXmlEditor.edit(xml = original, removeElements = selectorsFor(*removed.toTypedArray()))

        val after = declared(result.bytes)
        // Reported in the order the document lists them, not the order they were asked for.
        assertEquals(
            listOf(
                "android.permission.CAMERA",
                "android.permission.READ_CONTACTS",
                "android.permission.ACCESS_WIFI_STATE"
            ),
            result.elementsRemoved
        )
        assertTrue("nothing requested may go unreported", result.elementsMissing.isEmpty())
        assertEquals(before.size - removed.size, after.size)
        assertEquals(before.toSet() - removed.toSet(), after.toSet())

        // Each of the three is a self-closing element with one attribute: 16 header + 20 attribute
        // ext + 20 attribute for the START_TAG, and 16 + 8 for the END_TAG that follows it at once.
        assertEquals(original.size - removed.size * (56 + 24), result.bytes.size)
        assertTilesExactly(result.bytes, "discord manifest after removal")
    }

    /**
     * The removal is a pure deletion: the remaining chunks appear in the edited document in the
     * same order and byte for byte, and the only chunk whose content changed is the root header —
     * which carries the document's size.
     */
    @Test
    fun removalDeletesChunksAndTouchesNothingElse() {
        val original = manifestOf(discordApk) ?: return
        val result = BinaryXmlEditor.edit(
            xml = original,
            removeElements = selectorsFor("android.permission.RECORD_AUDIO")
        )

        val before = chunksOf(original)
        val after = chunksOf(result.bytes)
        val dropped = before.size - after.size
        assertEquals("one element is two chunks", 2, dropped)

        // The survivors are a subsequence of the original chunks, in order and byte for byte.
        var cursor = 0
        for (chunk in after) {
            while (cursor < before.size && !sameChunk(before[cursor], chunk)) cursor++
            assertTrue("a chunk of the edited manifest is not in the original", cursor < before.size)
            cursor++
        }

        val droppedBytes = original.size - result.bytes.size
        assertEquals("the deleted chunks are one START_TAG and its END_TAG", 56 + 24, droppedBytes)
        assertTrue(
            "the string pool is the first chunk and must be untouched",
            before.first { it.first == 0x0001 }.second.contentEquals(after.first { it.first == 0x0001 }.second)
        )
    }

    /**
     * A declaration may carry more than `android:name` — this build's two storage permissions add
     * `android:maxSdkVersion` — and the whole element has to go, not just its first attribute.
     */
    @Test
    fun removesEveryAttributeOfTheElementItDeletes() {
        val original = manifestOf(discordApk) ?: return
        val result = BinaryXmlEditor.edit(
            xml = original,
            removeElements = selectorsFor("android.permission.READ_EXTERNAL_STORAGE")
        )

        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), result.elementsRemoved)
        // 16 header + 20 attribute ext + 2 * 20 attributes for the START_TAG, plus the END_TAG.
        assertEquals(original.size - (76 + 24), result.bytes.size)
        assertTilesExactly(result.bytes, "discord manifest without READ_EXTERNAL_STORAGE")
    }

    @Test
    fun removingEveryDeclaredPermissionLeavesAManifestThatStillTiles() {
        val original = manifestOf(octoGramApk) ?: return
        val before = declared(original)
        assertTrue("the OctoGram manifest should declare permissions", before.size > 5)

        val result = BinaryXmlEditor.edit(xml = original, removeElements = selectorsFor(*before.toTypedArray()))

        assertEquals(before.size, result.elementsRemoved.size)
        assertTrue(declared(result.bytes).isEmpty())
        assertTrue(result.elementsMissing.isEmpty())
        assertTilesExactly(result.bytes, "octogram manifest with every permission removed")
        assertTrue("removal must shrink the document", result.bytes.size < original.size)
    }

    @Test
    fun aPermissionTheBuildDoesNotDeclareIsReportedRatherThanSilentlyIgnored() {
        val original = manifestOf(discordApk) ?: return
        val result = BinaryXmlEditor.edit(
            xml = original,
            removeElements = selectorsFor("android.permission.NOT_DECLARED_BY_THIS_BUILD")
        )
        assertTrue(result.elementsRemoved.isEmpty())
        assertEquals(listOf("android.permission.NOT_DECLARED_BY_THIS_BUILD"), result.elementsMissing)
        assertTrue("an unmatched selector must leave the document alone", original.contentEquals(result.bytes))
    }

    /**
     * The `<uses-permission-sdk-23>` and `<uses-permission-sdk-m>` forms are matched by the same
     * selector as the plain one, because the platform reads a permission out of any of them.
     *
     * Neither real build declares one, so this uses a document built here — one that also carries
     * a `<permission>` declaration, which must *not* be matched: it starts with `permission`, and
     * removing it would break the app's own permission rather than a request for someone else's.
     */
    @Test
    fun matchesEveryUsesPermissionVariantAndNothingElse() {
        val xml = syntheticManifest(
            permission("uses-permission", "android.permission.CAMERA"),
            permission("uses-permission-sdk-23", "android.permission.RECORD_AUDIO"),
            permission("uses-permission-sdk-m", "android.permission.READ_CONTACTS"),
            permission("permission", "com.example.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        )

        assertEquals(
            listOf(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_CONTACTS"
            ),
            declared(xml)
        )

        val result = BinaryXmlEditor.edit(
            xml = xml,
            removeElements = selectorsFor(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_CONTACTS",
                "com.example.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
            )
        )

        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.READ_CONTACTS"),
            result.elementsRemoved
        )
        // The app's own `<permission>` declaration is not a request for a permission, and the
        // selector — which matches `uses-permission` and its SDK variants — leaves it alone.
        assertEquals(listOf("com.example.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"), result.elementsMissing)
        assertTrue(declared(result.bytes).isEmpty())
        assertEquals("only the three requests were deleted", 3 * (56 + 24), xml.size - result.bytes.size)
        assertTilesExactly(result.bytes, "synthetic manifest after removal")
    }

    /**
     * An element that is *not* self-closing: the removal has to take its children with it, and the
     * walk has to carry on at the right place afterwards rather than at the first END_TAG it meets.
     *
     * Neither real build contains such an element, so this is the shape the immediacy assumption
     * would get wrong if it were an assumption.
     */
    @Test
    fun removesAWholeSubtreeWhenTheElementIsNotSelfClosing() {
        val xml = syntheticDocument(
            root = SyntheticElement("manifest"),
            children = listOf(
                permission("uses-permission", "android.permission.CAMERA"),
                SyntheticElement(
                    name = "feature",
                    values = listOf("android.hardware.camera"),
                    children = listOf(
                        permission("uses-permission-sdk-23", "android.permission.RECORD_AUDIO")
                    )
                ),
                permission("uses-permission", "android.permission.INTERNET")
            )
        )

        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.INTERNET"),
            declared(xml)
        )

        val result = BinaryXmlEditor.edit(
            xml = xml,
            removeElements = listOf(
                ElementSelector("feature", BinaryXmlEditor.ATTR_NAME, "android.hardware.camera")
            )
        )

        assertEquals(listOf("android.hardware.camera"), result.elementsRemoved)
        // The nested declaration went with its parent, and the elements on either side survived.
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.INTERNET"),
            declared(result.bytes)
        )
        assertTilesExactly(result.bytes, "synthetic manifest without the feature element")
    }

    /**
     * The edited manifest, put back into a copy of the APK, read by the platform's own tool.
     *
     * The chunk walk above proves the document is self-consistent; this proves something stronger —
     * that an independent binary-XML reader accepts it, and that the permissions that were removed
     * are ones aapt2 no longer lists while the rest are still there.
     */
    @Test
    fun aapt2ReadsTheEditedManifestAndThePermissionsAreGone() {
        val original = manifestOf(discordApk) ?: return
        val aapt2 = aapt2Candidates.firstOrNull { it.canExecute() }
        if (aapt2 == null) {
            println("aapt2 not found, skipping the external-parser check")
            return
        }

        val removed = listOf("android.permission.CAMERA", "android.permission.READ_CONTACTS")
        val edited = BinaryXmlEditor.edit(xml = original, removeElements = selectorsFor(*removed.toTypedArray()))
        assertTilesExactly(edited.bytes, "discord manifest before the aapt2 check")

        val apk = File.createTempFile("sleepy-manifest-check", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(edited.bytes)
                zip.closeEntry()
            }

            val parsed = aapt2PermissionNames(aapt2, apk)
            for (name in removed) {
                assertFalse("aapt2 still lists $name", parsed.contains(name))
            }
            assertEquals("aapt2 must list exactly the permissions that are left", declared(edited.bytes), parsed)
            assertTrue("the untouched manifest must still be readable too", declared(original).containsAll(removed))
        } finally {
            apk.delete()
        }
    }

    /**
     * The permission names `aapt2` reads out of the `<uses-permission>` elements of the manifest in
     * [apk] — collected from the element it saw, so a `<permission>` declaration of the app's own is
     * not mistaken for one.
     */
    private fun aapt2PermissionNames(aapt2: File, apk: File): List<String> {
        val process = ProcessBuilder(
            aapt2.absolutePath, "dump", "xmltree", "--file", "AndroidManifest.xml", apk.absolutePath
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals("aapt2 could not read the manifest: $output", 0, process.waitFor())
        return parsePermissionNames(output)
    }

    /** Pulls the names out of an `aapt2 dump xmltree` listing of a manifest. */
    private fun parsePermissionNames(dump: String): List<String> {
        val names = mutableListOf<String>()
        var insideUsesPermission = false
        for (line in dump.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("E: ")) {
                insideUsesPermission = trimmed.removePrefix("E: ").startsWith("uses-permission")
            } else if (insideUsesPermission && trimmed.contains(":name(0x01010003)=")) {
                names.add(trimmed.substringAfter("=\"").substringBefore('"'))
            }
        }
        return names
    }

    // --- synthetic binary XML ------------------------------------------------------------

    /** One element of a synthetic document: its name, its attribute values and what it contains. */
    private data class SyntheticElement(
        val name: String,
        val values: List<String> = emptyList(),
        val children: List<SyntheticElement> = emptyList()
    )

    /** A permission declaration: the element name, and the permission it names. */
    private fun permission(element: String, value: String) = SyntheticElement(element, listOf(value))

    /** A `<manifest>` containing the given elements. */
    private fun syntheticManifest(vararg elements: SyntheticElement): ByteArray =
        syntheticDocument(SyntheticElement("manifest"), elements.toList())

    /**
     * A minimal but real binary-XML document: a UTF-8 string pool, a resource map that resolves
     * `android:name`, and the given element tree under a root element.
     *
     * The resource map is what makes the attribute matcher work at all — an attribute's `name`
     * field is a string-pool index, and only the map turns it into `0x01010003`. It is also what
     * makes this document a fair test of the real path rather than of a shortcut.
     *
     * [root] is the document element and its attributes; [children] is written inside it, with one
     * deeper level per list so a nested element can be expressed.
     */
    private fun syntheticDocument(root: SyntheticElement, children: List<SyntheticElement>): ByteArray {
        val names = mutableListOf<String>()
        fun pool(name: String): Int {
            val existing = names.indexOf(name)
            if (existing >= 0) return existing
            names.add(name)
            return names.size - 1
        }
        // Index 0 is a string with no resource ID and index 1 is "name": the resource map below is
        // indexed the way the string pool is, so the position of "name" in the pool is what makes
        // the attribute resolve to android:name.
        pool("com.example.unmapped")
        pool(NAME_STRING)
        val rootName = pool(root.name)
        root.values.forEach { pool(it) }
        fun poolTree(elements: List<SyntheticElement>) {
            for (element in elements) {
                pool(element.name)
                element.values.forEach { pool(it) }
                poolTree(element.children)
            }
        }
        poolTree(children)

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
        val poolSize = 28 + names.size * 4 + data.size()
        val pool = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN)
        pool.putShort(0x0001)
        pool.putShort(28)
        pool.putInt(poolSize)
        pool.putInt(names.size)
        pool.putInt(0)      // styleCount
        pool.putInt(0x100)  // UTF8_FLAG
        pool.putInt(28 + names.size * 4)
        pool.putInt(0)      // stylesStart
        offsets.forEach { pool.putInt(it) }
        pool.put(data.toByteArray())
        document.write(pool.array())

        // Resource map: the entry at index 1 — the string "name" — resolves to android:name. Index
        // 0 is a string with no resource ID, which is what an attribute outside the framework's
        // namespace looks like.
        val map = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        map.putShort(0x0180.toShort())
        map.putShort(8)
        map.putInt(16)
        map.putInt(0)
        map.putInt(BinaryXmlEditor.ATTR_NAME)
        document.write(map.array())

        document.write(startTag(rootName, root.values.map { 0 to pool(it) }))
        writeElements(document, children, ::pool)
        document.write(endTag(rootName))

        val body = document.toByteArray()
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0x0003.toShort())
        header.putShort(8)
        header.putInt(body.size)
        System.arraycopy(header.array(), 0, body, 0, 8)
        return body
    }

    /** Writes one level of the tree, each element closed around whatever it contains. */
    private fun writeElements(
        out: ByteArrayOutputStream,
        elements: List<SyntheticElement>,
        pool: (String) -> Int
    ) {
        for (element in elements) {
            out.write(startTag(pool(element.name), element.values.map { pool(NAME_STRING) to pool(it) }))
            writeElements(out, element.children, pool)
            out.write(endTag(pool(element.name)))
        }
    }

    private fun startTag(nameIndex: Int, attributes: List<Pair<Int, Int>>): ByteArray {
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

    private fun endTag(nameIndex: Int): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0x0103)
        putShort(16)
        putInt(24)
        putInt(1)                  // lineNumber
        putInt(0xFFFFFFFF.toInt()) // comment
        putInt(-1)                 // ns
        putInt(nameIndex)
    }.array()

    private companion object {
        /** The attribute name every element in the synthetic documents carries. */
        const val NAME_STRING = "name"
    }
}
