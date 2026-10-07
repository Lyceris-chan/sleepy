package dev.sleepy.app.engine

import dev.sleepy.app.engine.BinaryXmlEditor.ElementSelector
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.assertTilesExactly
import dev.sleepy.app.testing.chunksOf
import dev.sleepy.app.testing.endTag
import dev.sleepy.app.testing.manifestOf
import dev.sleepy.app.testing.sameChunk
import dev.sleepy.app.testing.startTag
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Permission removal in [dev.sleepy.app.engine.BinaryXmlEditor]: exactly the requested elements
 * go, and the document still tiles.
 *
 * The tests run against the real manifests this app patches and against a synthetic manifest for
 * element shapes the real builds do not contain. A binary XML document tiles when every chunk
 * ends where the next one begins and the root chunk is sized to the file; removal must preserve
 * that property while deleting no byte outside the removed elements.
 */
class ManifestPermissionRemovalTest {

    private val discordApk = ComparisonApks.discordBaseApk

    private val octoGramApk = ComparisonApks.octoGram361Arm64

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

    /** Byte-array pairs compare by identity, so the chunk comparison is spelled out. */
    private fun sameChunk(a: Pair<Int, ByteArray>, b: Pair<Int, ByteArray>): Boolean =
        a.first == b.first && a.second.contentEquals(b.second)

    @Test
    fun removesExactlyTheRequestedPermissionsFromTheRealManifest() {
        val original = manifestOf(discordApk)
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
        val result = BinaryXmlEditor.edit(
            xml = original,
            removeElements = selectorsFor(*removed.toTypedArray())
        )

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
     * same order and byte for byte, and the only chunk whose content changed is the root header—
     * which carries the document's size.
     */
    @Test
    fun removalDeletesChunksAndTouchesNothingElse() {
        val original = manifestOf(discordApk)
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
            while (cursor < before.size && !sameChunk(before[cursor], chunk)) {
                cursor++
            }
            assertTrue(
                "a chunk of the edited manifest is not in the original",
                cursor < before.size
            )
            cursor++
        }

        val droppedBytes = original.size - result.bytes.size
        assertEquals("the deleted chunks are one START_TAG and its END_TAG", 56 + 24, droppedBytes)
        assertTrue(
            "the string pool is the first chunk and must be untouched",
            before.first { it.first == 0x0001 }
                .second.contentEquals(after.first { it.first == 0x0001 }.second)
        )
    }

    /**
     * A declaration may carry more than `android:name`—this build's two storage permissions add
     * `android:maxSdkVersion`—and the whole element has to go, not just its first attribute.
     */
    @Test
    fun removesEveryAttributeOfTheElementItDeletes() {
        val original = manifestOf(discordApk)
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
        val original = manifestOf(octoGramApk)
        val before = declared(original)
        assertTrue("the OctoGram manifest should declare permissions", before.size > 5)

        val result = BinaryXmlEditor.edit(
            xml = original,
            removeElements = selectorsFor(*before.toTypedArray())
        )

        assertEquals(before.size, result.elementsRemoved.size)
        assertTrue(declared(result.bytes).isEmpty())
        assertTrue(result.elementsMissing.isEmpty())
        assertTilesExactly(result.bytes, "octogram manifest with every permission removed")
        assertTrue("removal must shrink the document", result.bytes.size < original.size)
    }

    @Test
    fun aPermissionTheBuildDoesNotDeclareIsReportedRatherThanSilentlyIgnored() {
        val original = manifestOf(discordApk)
        val result = BinaryXmlEditor.edit(
            xml = original,
            removeElements = selectorsFor("android.permission.NOT_DECLARED_BY_THIS_BUILD")
        )
        assertTrue(result.elementsRemoved.isEmpty())
        assertEquals(
            listOf("android.permission.NOT_DECLARED_BY_THIS_BUILD"),
            result.elementsMissing
        )
        assertTrue(
            "an unmatched selector must leave the document alone",
            original.contentEquals(result.bytes)
        )
    }

    /**
     * The `<uses-permission-sdk-23>` and `<uses-permission-sdk-m>` forms are matched by the same
     * selector as the plain one, because the platform reads a permission out of any of them.
     *
     * Neither real build declares one, so this uses a document built here—one that also carries
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
            listOf(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_CONTACTS"
            ),
            result.elementsRemoved
        )
        // The app's own `<permission>` declaration is not a request for a permission, and the
        // selector—which matches `uses-permission` and its SDK variants—leaves it alone.
        assertEquals(
            listOf("com.example.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            result.elementsMissing
        )
        assertTrue(declared(result.bytes).isEmpty())
        assertEquals(
            "only the three requests were deleted",
            3 * (56 + 24),
            xml.size - result.bytes.size
        )
        assertTilesExactly(result.bytes, "synthetic manifest after removal")
    }

    /**
     * An element that is *not* self-closing: the removal has to take its children with it, and the
     * walk has to carry on at the right place afterward rather than at the first END_TAG it meets.
     *
     * Neither real build contains such an element, so this covers the shape where assuming
     * immediacy fails.
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
            listOf(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.INTERNET"
            ),
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
     * The chunk walk above checks the document against itself; this checks it against an
     * independent reader—that aapt2 accepts the edited manifest, that it no longer lists the
     * permissions that were removed, and that the ones that remain are still there.
     */
    @Test
    fun aapt2ReadsTheEditedManifestAndThePermissionsAreGone() {
        val original = manifestOf(discordApk)
        // The external parser is a tool rather than a fixture of this repository: without it
        // the test is reported as skipped. The check it makes is the only independent reading
        // of the edited document, so "did not run" must not look like "passed".
        val aapt2 = ComparisonApks.buildTool("aapt2")
        assumeTrue("aapt2 is not installed on this machine", aapt2 != null)

        val removed = listOf("android.permission.CAMERA", "android.permission.READ_CONTACTS")
        val edited = BinaryXmlEditor.edit(
            xml = original,
            removeElements = selectorsFor(*removed.toTypedArray())
        )
        assertTilesExactly(edited.bytes, "discord manifest before the aapt2 check")

        val apk = File.createTempFile("sleepy-manifest-check", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(edited.bytes)
                zip.closeEntry()
            }

            val parsed = aapt2PermissionNames(aapt2!!, apk)
            for (name in removed) {
                assertFalse("aapt2 still lists $name", parsed.contains(name))
            }
            assertEquals(
                "aapt2 must list exactly the permissions that are left",
                declared(edited.bytes),
                parsed
            )
            assertTrue(
                "the untouched manifest must still be readable too",
                declared(original).containsAll(removed)
            )
        } finally {
            apk.delete()
        }
    }

    /**
     * The permission names `aapt2` reads out of the `<uses-permission>` elements of the manifest
     * in [apk]—collected from the element it saw, so a `<permission>` declaration of the app's
     * own is not mistaken for one.
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
    private fun permission(element: String, value: String) =
        SyntheticElement(element, listOf(value))

    /** A `<manifest>` containing the given elements. */
    private fun syntheticManifest(vararg elements: SyntheticElement): ByteArray =
        syntheticDocument(SyntheticElement("manifest"), elements.toList())

    /**
     * A minimal but real binary-XML document: a UTF-8 string pool, a resource map that resolves
     * `android:name`, and the given element tree under a root element.
     *
     * The resource map is what makes the attribute matcher work at all—an attribute's `name`
     * field is a string-pool index, and only the map turns it into `0x01010003`. It is also what
     * makes this document exercise the real path rather than a shortcut.
     *
     * [root] is the document element and its attributes; [children] is written inside it, with one
     * deeper level per list so a nested element can be expressed.
     */
    private fun syntheticDocument(
        root: SyntheticElement,
        children: List<SyntheticElement>
    ): ByteArray {
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

        // Resource map: the entry at index 1—the string "name"—resolves to android:name. Index
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
            out.write(
                startTag(pool(element.name), element.values.map { pool(NAME_STRING) to pool(it) })
            )
            writeElements(out, element.children, pool)
            out.write(endTag(pool(element.name)))
        }
    }





    private companion object {
        /** The attribute name every element in the synthetic documents carries. */
        const val NAME_STRING = "name"
    }
}
