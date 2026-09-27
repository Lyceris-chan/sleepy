package dev.sleepy.app

import dev.sleepy.app.engine.BinaryXmlEditor
import dev.sleepy.app.engine.DiscordManifestEdits
import dev.sleepy.app.patches.DiscordNativePatches
import dev.sleepy.app.patches.DiscordPatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The manifest edits a Discord run asks for: which ones it asks for and when, and what applying
 * them to a real manifest actually does.
 *
 * Two claims are under test, and they are separate. The first is the decision — a component is
 * only removed when the thing it belongs to is being disabled, and the RPC service is closed
 * whatever the switches say. The second is the effect: on the real Discord base manifest, the plan
 * deletes exactly the elements it names and changes exactly one attribute, which is checked at the
 * chunk level and then again through `aapt2` as an independent reader.
 */
class DiscordManifestEditsTest {

    private val discordApk =
        File("/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted/base.apk")

    private val octoGramApk = File("/home/sleepy/Documents/antigravity/telegram/OctoGram_361_arm64.apk")

    /** Where `aapt2` lives when the Android SDK build tools are installed beside this checkout. */
    private val aapt2Candidates = listOf(
        File("/home/sleepy/portable-tools/android-sdk/build-tools/36.0.0/aapt2"),
        File(System.getenv("ANDROID_HOME") ?: "/nonexistent", "build-tools/36.0.0/aapt2")
    )

    /** A manifest from a fixture APK. */
    private fun manifestOf(apk: File): ByteArray {
        assumeTrue("${apk.path} is not on this machine", apk.exists())
        return ZipFile(apk).use { zip ->
            val entry = requireNotNull(zip.getEntry("AndroidManifest.xml")) {
                "${apk.name} has no AndroidManifest.xml"
            }
            zip.getInputStream(entry).readBytes()
        }
    }

    /** The plan a run that has switched on everything this suite is about would ask for. */
    private fun fullPlan(mergedLibraries: Int = 8): DiscordManifestEdits.Plan =
        DiscordManifestEdits.plan(
            activePatchIds = setOf(DiscordPatches.SENTRY.id, DiscordNativePatches.DEEP_LINKS.id),
            mergedLibraries = mergedLibraries,
            removedPermissions = listOf("android.permission.READ_CONTACTS")
        )

    // --- the decision -------------------------------------------------------------------

    /**
     * With nothing switched on, nothing is removed. The RPC override is still there, and that is
     * the point of it: it is not a thing a run can decline.
     */
    @Test
    fun aRunThatDisablesNothingRemovesNothing() {
        val plan = DiscordManifestEdits.plan(emptySet(), mergedLibraries = 0, removedPermissions = emptyList())
        assertTrue("no elements may be removed", plan.removals.isEmpty())
        assertEquals("the RPC service is closed regardless", 1, plan.overrides.size)
        assertEquals(DiscordPatches.RPC_SERVICE_NAME, plan.overrides.first().element.attributeValue)
        assertFalse("closing it is not a removal", plan.isEmpty)
    }

    /** The crash reporter's providers go with the crash reporter's switch, and only with it. */
    @Test
    fun theSentryProvidersGoOnlyWithTheCrashReporterSwitch() {
        val off = DiscordManifestEdits.plan(emptySet(), 0, emptyList())
        assertTrue("a build keeping the crash reporter keeps its providers", off.sentryProviders.isEmpty())

        val on = DiscordManifestEdits.plan(setOf(DiscordPatches.SENTRY.id), 0, emptyList())
        assertEquals(DiscordPatches.SENTRY_PROVIDERS, on.sentryProviders.map { it.attributeValue })
        // The element name is the framework's, and what tells one provider from another is the
        // android:name attribute — the same shape the permission removal uses.
        for (selector in on.sentryProviders) {
            assertEquals(BinaryXmlEditor.ELEMENT_PROVIDER, selector.namePrefix)
            assertEquals(BinaryXmlEditor.ATTR_NAME, selector.attributeId)
        }
    }

    /** The Play markers describe a split, so they go exactly when there are no longer splits. */
    @Test
    fun thePlaySplitMarkersGoOnlyWhenTheSplitsWereMergedIn() {
        val notMerged = DiscordManifestEdits.plan(emptySet(), mergedLibraries = 0, removedPermissions = emptyList())
        assertTrue(notMerged.playSplitMarkers.isEmpty())

        val merged = DiscordManifestEdits.plan(emptySet(), mergedLibraries = 8, removedPermissions = emptyList())
        assertEquals(DiscordPatches.PLAY_SPLIT_MARKERS, merged.playSplitMarkers.map { it.attributeValue })
        assertEquals(BinaryXmlEditor.ELEMENT_META_DATA, merged.playSplitMarkers.first().namePrefix)
    }

    /**
     * The attribution query goes with the switch that no-ops its only initialiser.
     *
     * The selector names no attribute of its own — `<intent>` has none — and is told apart from the
     * dozens of other `<intent>` elements by the `<action>` inside it.
     */
    @Test
    fun theAttributionQueryGoesOnlyWithTheDeepLinkSwitch() {
        val off = DiscordManifestEdits.plan(setOf(DiscordPatches.SENTRY.id), 8, emptyList())
        assertTrue(off.attributionQuery.isEmpty())

        val on = DiscordManifestEdits.plan(setOf(DiscordNativePatches.DEEP_LINKS.id), 8, emptyList())
        assertEquals(1, on.attributionQuery.size)
        val selector = on.attributionQuery.first()
        assertEquals(BinaryXmlEditor.ELEMENT_INTENT, selector.namePrefix)
        assertEquals("an intent has no attribute to match on", null, selector.attributeId)
        val contained = requireNotNull(selector.contains)
        assertEquals(BinaryXmlEditor.ELEMENT_ACTION, contained.namePrefix)
        assertEquals(BinaryXmlEditor.ATTR_NAME, contained.attributeId)
        assertEquals(DiscordPatches.APPSFLYER_INSTALL_PROVIDER_ACTION, contained.attributeValue)
    }

    /** Permissions are carried into the plan as selectors, in the order the user's choices produced. */
    @Test
    fun thePermissionsTheUserSwitchedOffBecomeTheirOwnGroup() {
        val plan = DiscordManifestEdits.plan(
            emptySet(), 0, listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        )
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            plan.permissions.map { it.attributeValue }
        )
        assertEquals(BinaryXmlEditor.ELEMENT_USES_PERMISSION, plan.permissions.first().namePrefix)
    }

    // --- the effect ---------------------------------------------------------------------

    /**
     * Every edit the plan asks for lands on the real manifest, and the document it lands in is a
     * document that still tiles.
     *
     * "Tiles" is the same walk the platform performs: the chunks partition the file, and every
     * element opened is closed. A removal that took an element's `START_TAG` but not its `END_TAG`
     * would still be a file, and would be one the platform refuses.
     */
    @Test
    fun appliesEveryEditToTheRealManifest() {
        val original = manifestOf(discordApk)
        val plan = fullPlan()

        val result = BinaryXmlEditor.edit(
            xml = original,
            removeElements = plan.removals,
            elementOverrides = plan.overrides
        )

        assertTrue(
            "nothing the plan asked for may go unreported: ${result.elementsMissing}",
            result.elementsMissing.isEmpty()
        )
        assertEquals(
            setOf(
                "android.permission.READ_CONTACTS",
                DiscordPatches.SENTRY_PROVIDERS[0],
                DiscordPatches.SENTRY_PROVIDERS[1],
                DiscordPatches.PLAY_SPLIT_MARKERS[0],
                DiscordPatches.PLAY_SPLIT_MARKERS[1],
                DiscordPatches.PLAY_SPLIT_MARKERS[2],
                DiscordPatches.APPSFLYER_INSTALL_PROVIDER_ACTION
            ),
            result.elementsRemoved.toSet()
        )
        assertEquals(
            "the edit must say which element it closed",
            listOf(DiscordPatches.RPC_SERVICE_NAME),
            result.elementOverridesApplied
        )
        assertEquals(
            "exactly one attribute may be rewritten",
            listOf(BinaryXmlEditor.ATTR_EXPORTED),
            result.attributesRewritten
        )
        assertTilesExactly(result.bytes, "discord manifest after the manifest edits")
    }

    /**
     * The three Play markers sit among other `<meta-data>` elements, and removing them must take
     * those three and nothing beside them.
     *
     * The two that must survive — `com.android.stamp.source` and `com.android.stamp.type` — name
     * the distribution channel, and one of them sits *between* two of the markers, so a removal
     * that deleted a range rather than an element would take it too.
     */
    @Test
    fun removingThePlayMarkersLeavesTheStampMarkersBesideThem() {
        val original = manifestOf(discordApk)
        val plan = DiscordManifestEdits.plan(emptySet(), mergedLibraries = 8, removedPermissions = emptyList())

        val result = BinaryXmlEditor.edit(xml = original, removeElements = plan.removals)
        val names = BinaryXmlEditor.readElementAttributeValues(
            xml = result.bytes,
            namePrefix = BinaryXmlEditor.ELEMENT_META_DATA,
            attributeId = BinaryXmlEditor.ATTR_NAME
        )

        for (marker in DiscordPatches.PLAY_SPLIT_MARKERS) {
            assertFalse("$marker should be gone", names.contains(marker))
        }
        assertTrue("the stamp markers are not split markers", names.contains("com.android.stamp.source"))
        assertTrue("the stamp markers are not split markers", names.contains("com.android.stamp.type"))
        assertTilesExactly(result.bytes, "discord manifest without the split markers")
    }

    /**
     * Closing the RPC service rewrites one attribute of one element and leaves every other chunk
     * of the document alone.
     *
     * The manifest has dozens of components that are exported and dozens that are not, so "one
     * attribute changed" is a statement about the whole file rather than about the service: the
     * chunks are compared one for one, and exactly one of them may differ.
     */
    @Test
    fun closingTheRpcServiceTouchesNoOtherComponent() {
        val original = manifestOf(discordApk)
        val plan = DiscordManifestEdits.plan(emptySet(), 0, emptyList())

        val result = BinaryXmlEditor.edit(
            xml = original,
            removeElements = plan.removals,
            elementOverrides = plan.overrides
        )

        assertEquals(listOf(DiscordPatches.RPC_SERVICE_NAME), result.elementOverridesApplied)
        assertEquals(listOf(BinaryXmlEditor.ATTR_EXPORTED), result.attributesRewritten)

        val before = chunksOf(original)
        val after = chunksOf(result.bytes)
        assertEquals("no chunk may be added or dropped", before.size, after.size)
        val differing = before.indices.filter { !sameChunk(before[it], after[it]) }
        assertEquals("only the one START_TAG may differ", 1, differing.size)
        val changed = after[differing.single()]
        assertEquals("the difference must be in a START_TAG", 0x0102, changed.first)

        // The manifest's `android:exported="true"` is a *typed* boolean on the wire: a four-byte
        // Res_value whose data is 0xFFFFFFFF for true and 0 for false. So the whole change is
        // those four bytes, and they are the last four of the element — the Res_value of its last
        // attribute. Anything else differing, anywhere, would mean the rewrite reached past it.
        val offsets = differingOffsets(before, after)
        assertEquals(
            "the only change may be one attribute's four-byte Res_value",
            (changed.second.size - 4 until changed.second.size).toList(),
            offsets.map { it.second }
        )
    }

    /**
     * A build none of these patches is running against keeps its manifest byte for byte.
     *
     * The RPC override is applied on every run, so on a build with no such service it has to match
     * nothing and leave nothing behind — not a rewritten attribute, not a re-serialised document
     * whose string pool was rebuilt.
     */
    @Test
    fun aBuildWithNoneOfTheseComponentsKeepsItsManifestByteForByte() {
        val original = manifestOf(octoGramApk)
        val plan = DiscordManifestEdits.plan(emptySet(), mergedLibraries = 0, removedPermissions = emptyList())

        val result = BinaryXmlEditor.edit(
            xml = original,
            removeElements = plan.removals,
            elementOverrides = plan.overrides
        )

        assertTrue("nothing should have matched", result.elementOverridesApplied.isEmpty())
        assertTrue(result.attributesRewritten.isEmpty())
        assertTrue("the document must come back untouched", original.contentEquals(result.bytes))
    }

    /**
     * The edited manifest, read by the platform's own tool.
     *
     * The chunk walk proves the document is self-consistent. This proves something the editor
     * cannot prove about itself: that an independent binary-XML reader accepts it, that exactly the
     * named elements are gone, and — the part that matters most in a 1,300-line listing — that
     * nothing else moved.
     */
    @Test
    fun aapt2SeesExactlyTheseEditsAndNothingElse() {
        val aapt2 = aapt2Candidates.firstOrNull { it.canExecute() }
        assumeTrue("aapt2 is not installed on this machine", aapt2 != null)

        val original = manifestOf(discordApk)
        val plan = fullPlan()
        val edited = BinaryXmlEditor.edit(
            xml = original,
            removeElements = plan.removals,
            elementOverrides = plan.overrides
        ).bytes

        val before = aapt2XmlTree(aapt2!!, original)
        val after = aapt2XmlTree(aapt2, edited)
        val expected = dropPlannedElements(before, plan)

        assertTrue("the fixture must carry the elements this plan removes", expected.size < before.size)
        assertEquals("the listing must be the original with exactly those elements dropped", expected.size, after.size)
        val differing = expected.indices.filter { expected[it] != after[it] }
        assertEquals("exactly one line may differ, and it is the export flag", 1, differing.size)
        val index = differing.single()
        assertTrue("${expected[index]} is not an export flag", expected[index].contains("exported(0x01010010)="))
        assertEquals(
            "the flag must be the RPC service's, and it must only have been flipped",
            DiscordPatches.RPC_SERVICE_NAME,
            enclosingName(expected, index)
        )
        assertTrue("the flag was not flipped", after[index].endsWith("false"))
        assertTrue("the flag was not flipped from true", expected[index].endsWith("true"))
    }

    /**
     * The `aapt2` listing with the plan's edits applied in text form.
     *
     * The two kinds of selector need two rules, and each rule is the selector's own meaning spelled
     * out. An element named by an attribute goes, with everything under it — that is the
     * permission, provider and marker case. An `<intent>` that holds a named `<action>` goes, with
     * everything under it — that is the attribution query, which has no attribute of its own to be
     * named by.
     *
     * Written out rather than checked as a set of "contains" assertions because the claim is as
     * much about what did *not* change as about what did.
     */
    private fun dropPlannedElements(before: List<String>, plan: DiscordManifestEdits.Plan): List<String> {
        val named = plan.removals.map { it.label }.toSet() - DiscordPatches.APPSFLYER_INSTALL_PROVIDER_ACTION
        val dropped = mutableSetOf<Int>()
        for (index in before.indices) {
            val name = attributeName(before[index]) ?: continue
            val owner = when (name) {
                in named -> enclosing(before, index, "E: ")
                DiscordPatches.APPSFLYER_INSTALL_PROVIDER_ACTION ->
                    // The action's enclosing intent: the query the action identifies. The prefix
                    // carries the parenthesis so that an enclosing `<intent-filter>` is not
                    // mistaken for an `<intent>`.
                    enclosing(before, index, "E: ")?.let { enclosing(before, it, "E: intent (") }
                else -> null
            } ?: continue
            for (line in owner until subtreeEnd(before, owner)) dropped.add(line)
        }
        return before.filterIndexed { index, _ -> index !in dropped }
    }

    /** The value of `android:name` on an `aapt2` attribute line, or null when the line is not one. */
    private fun attributeName(line: String): String? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("A: ") || !trimmed.contains(":name(0x01010003)=")) return null
        return trimmed.substringAfter("=\"").substringBefore('"')
    }

    /**
     * The index of the nearest line above [index] that starts with [prefix] and is indented less —
     * the element that owns the line, in a listing where depth is indentation.
     */
    private fun enclosing(lines: List<String>, index: Int, prefix: String): Int? {
        val indent = lines[index].takeWhile { it == ' ' }.length
        var cursor = index - 1
        while (cursor >= 0) {
            if (lines[cursor].trimStart().startsWith(prefix) && lines[cursor].takeWhile { it == ' ' }.length < indent) {
                return cursor
            }
            cursor--
        }
        return null
    }

    /** The `android:name` of the innermost element whose subtree holds the line at [index]. */
    private fun enclosingName(lines: List<String>, index: Int): String? {
        var cursor = enclosing(lines, index, "E: ") ?: return null
        while (true) {
            val name = lines.subList(cursor, subtreeEnd(lines, cursor))
                .firstNotNullOfOrNull { attributeName(it) }
            if (name != null) return name
            cursor = enclosing(lines, cursor, "E: ") ?: return null
        }
    }


    /** The index just past the element whose line is [index], its attributes and children included. */
    private fun subtreeEnd(lines: List<String>, index: Int): Int {
        val indent = lines[index].takeWhile { it == ' ' }.length
        var end = index + 1
        while (end < lines.size && lines[end].takeWhile { it == ' ' }.length > indent) end++
        return end
    }

    /** Runs `aapt2 dump xmltree` over a manifest, returning its listing line by line. */
    private fun aapt2XmlTree(aapt2: File, manifest: ByteArray): List<String> {
        val apk = File.createTempFile("sleepy-manifest-edits", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(manifest)
                zip.closeEntry()
            }
            val process = ProcessBuilder(
                aapt2.absolutePath, "dump", "xmltree", "--file", "AndroidManifest.xml", apk.absolutePath
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals("aapt2 could not read the manifest: $output", 0, process.waitFor())
            return output.lineSequence().filter { it.isNotBlank() }.toList()
        } finally {
            apk.delete()
        }
    }

    /** Walks the chunk tree and requires it to tile the document exactly. */
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

    /**
     * Where two chunk lists differ, as (chunk index, offset within that chunk).
     *
     * The lists have to line up chunk for chunk; a chunk whose size changed is reported as a
     * difference at every one of its offsets, which is a failure whichever way it is read.
     */
    private fun differingOffsets(
        before: List<Pair<Int, ByteArray>>,
        after: List<Pair<Int, ByteArray>>
    ): List<Pair<Int, Int>> {
        val offsets = mutableListOf<Pair<Int, Int>>()
        for (index in before.indices) {
            val a = before[index].second
            val b = after[index].second
            if (a.size != b.size) return (0 until maxOf(a.size, b.size)).map { index to it }
            for (i in a.indices) if (a[i] != b[i]) offsets.add(index to i)
        }
        return offsets
    }
}
