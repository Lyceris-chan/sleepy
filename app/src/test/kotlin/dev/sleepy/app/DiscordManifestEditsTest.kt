package dev.sleepy.app

import dev.sleepy.app.engine.BinaryXmlEditor
import dev.sleepy.app.engine.DiscordManifestEdits
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.patches.DeclaredPermissions
import dev.sleepy.app.patches.DiscordNativePatches
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.PermissionCatalog
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
 * only removed when the thing it belongs to is being disabled, the declarations this build has no
 * code behind are removed on every run against it and on no run against another app, and the RPC
 * service is closed whatever the switches say. The second is the effect: on the real Discord base
 * manifest, the plan deletes exactly the elements it names and rewrites exactly the attributes it
 * names, which is checked at the chunk level and then again through `aapt2` as an independent
 * reader.
 */
class DiscordManifestEditsTest {

    private val discordApk =
        File("/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted/base.apk")

    private val octoGramApk = File("/home/sleepy/Documents/antigravity/telegram/OctoGram_361_arm64.apk")

    /** The application the edits were written for, and one they were not. */
    private val DISCORD = DiscordManifestEdits.PACKAGE_NAME
    private val OCTOGRAM = "it.octogram.android"

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
            packageName = DISCORD,
            activePatchIds = setOf(DiscordPatches.SENTRY.id, DiscordNativePatches.DEEP_LINKS.id),
            mergedLibraries = mergedLibraries,
            removedPermissions = listOf("android.permission.CAMERA")
        )

    // --- the decision -------------------------------------------------------------------

    /**
     * With nothing switched on, nothing the switches control is removed. The two edits that carry
     * no switch are still there, and that is the point of them: they are not things a run can
     * decline.
     */
    @Test
    fun aRunThatDisablesNothingRemovesOnlyTheDeadDeclarations() {
        val plan = DiscordManifestEdits.plan(DISCORD, emptySet(), mergedLibraries = 0, removedPermissions = emptyList())
        assertTrue("nothing the user chose is removed", plan.permissions.isEmpty())
        assertEquals(
            "the declarations this build has no code behind go anyway",
            DiscordPatches.DEAD_PERMISSIONS,
            plan.removals.map { it.attributeValue }
        )
        assertEquals("the RPC service is closed regardless", DiscordPatches.RPC_SERVICE_NAME, plan.rpcService.element.attributeValue)
        assertEquals("and the analytics components are switched off", 3, plan.googleAnalytics.size)
        assertFalse("neither is a removal", plan.isEmpty)
    }

    /**
     * The two groups that are facts about one build are asked for on that build and on no other.
     *
     * Both are lists of names read off Discord 348.5, and names do not travel: OctoGram declares
     * `READ_CONTACTS` and syncs the address book through it, so a dead-permission removal carried
     * across would take away a permission that app is using. What a job against another app asks
     * for is therefore the groups that are decided by the run itself — its switches, its merge and
     * the service that is closed unconditionally — and not the two that were read from this build.
     */
    @Test
    fun theGroupsReadOffOneBuildAreAskedForOnThatBuildAlone() {
        val other = DiscordManifestEdits.plan(
            OCTOGRAM, emptySet(), mergedLibraries = 8, removedPermissions = emptyList()
        )
        assertTrue("a dead declaration from another app's list is not removed", other.deadPermissions.isEmpty())
        assertTrue("and its analytics components are not touched", other.googleAnalytics.isEmpty())
        assertEquals("the edits the run decides for itself still apply", 3, other.playSplitMarkers.size)
        assertEquals(DiscordPatches.RPC_SERVICE_NAME, other.rpcService.element.attributeValue)
    }

    /**
     * The two halves of "this build removes it anyway" are one answer: the declarations the pass
     * strips are exactly the declarations the permission list fixes its rows on.
     *
     * They are read from the same function rather than written out twice — see
     * [DiscordManifestEdits.deadPermissionsIn] — so what this rules out is a later edit that goes
     * back to spelling one of the two lists out by hand. Either direction is the bug the rows were
     * fixed for: a list offering a live switch over a declaration the pass is about to delete, or a
     * switch fixed over a declaration the build keeps.
     */
    @Test
    fun theDeclarationsTheListFixesAreTheOnesThePassRemoves() {
        val declared = requireNotNull(DeclaredPermissions.forPackage(DISCORD)) { "Discord's list is shipped" }
        val selection = PatchSelection().with(PermissionCatalog.itemsOf(declared))
        val plan = fullPlan()

        val fixedByTheList = PermissionCatalog.rows(declared, selection, DISCORD)
            .filter { !it.switchable && !it.kept }
            .map { it.permission.name }
        assertEquals(
            "the pass and the list have to remove the same declarations, in the same order",
            plan.deadPermissions.map { it.attributeValue },
            fixedByTheList
        )
        // And the pass's own group is not the user's: the two carry different reasons and are
        // reported separately, so a name in both would be one element asked for twice.
        val theUsersOwn = plan.permissions.map { it.attributeValue }
        assertEquals(listOf("android.permission.CAMERA"), theUsersOwn)
        assertTrue(
            "no declaration may be in both groups, found: ${fixedByTheList.filter { it in theUsersOwn }}",
            fixedByTheList.none { it in theUsersOwn }
        )
    }

    /** The crash reporter's providers go with the crash reporter's switch, and only with it. */
    @Test
    fun theSentryProvidersGoOnlyWithTheCrashReporterSwitch() {
        val off = DiscordManifestEdits.plan(DISCORD, emptySet(), 0, emptyList())
        assertTrue("a build keeping the crash reporter keeps its providers", off.sentryProviders.isEmpty())

        val on = DiscordManifestEdits.plan(DISCORD, setOf(DiscordPatches.SENTRY.id), 0, emptyList())
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
        val notMerged = DiscordManifestEdits.plan(DISCORD, emptySet(), mergedLibraries = 0, removedPermissions = emptyList())
        assertTrue(notMerged.playSplitMarkers.isEmpty())

        val merged = DiscordManifestEdits.plan(DISCORD, emptySet(), mergedLibraries = 8, removedPermissions = emptyList())
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
        val off = DiscordManifestEdits.plan(DISCORD, setOf(DiscordPatches.SENTRY.id), 8, emptyList())
        assertTrue(off.attributionQuery.isEmpty())

        val on = DiscordManifestEdits.plan(DISCORD, setOf(DiscordNativePatches.DEEP_LINKS.id), 8, emptyList())
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
            DISCORD, emptySet(), 0, listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
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
            "every element the plan names must be the elements that went",
            buildSet {
                addAll(DiscordPatches.DEAD_PERMISSIONS)
                add("android.permission.CAMERA")
                addAll(DiscordPatches.SENTRY_PROVIDERS)
                addAll(DiscordPatches.PLAY_SPLIT_MARKERS)
                add(DiscordPatches.APPSFLYER_INSTALL_PROVIDER_ACTION)
            },
            result.elementsRemoved.toSet()
        )
        assertEquals(
            "the edit must say which elements it closed and switched off",
            setOf(
                DiscordPatches.RPC_SERVICE_NAME,
                DiscordPatches.GOOGLE_ANALYTICS_COMPONENTS[0].name,
                DiscordPatches.GOOGLE_ANALYTICS_COMPONENTS[1].name,
                DiscordPatches.GOOGLE_ANALYTICS_COMPONENTS[2].name
            ),
            result.elementOverridesApplied.toSet()
        )
        assertEquals(
            "one attribute per override may be rewritten, and no more",
            listOf(
                BinaryXmlEditor.ATTR_EXPORTED,
                BinaryXmlEditor.ATTR_ENABLED,
                BinaryXmlEditor.ATTR_ENABLED,
                BinaryXmlEditor.ATTR_ENABLED
            ),
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
        val plan = DiscordManifestEdits.plan(DISCORD, emptySet(), mergedLibraries = 8, removedPermissions = emptyList())

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
     * The attribute rewrites land on the elements they name and leave every other chunk of the
     * document alone.
     *
     * The manifest has dozens of components that are exported and dozens that are enabled, so
     * "one attribute per override changed" is a statement about the whole file rather than about
     * the four components: the chunks are compared one for one, and exactly the four that carry an
     * override may differ.
     *
     * The removals are applied in a pass of their own first. The chunk lists are compared index by
     * index, and a deletion shifts every index after it, so a test that removed and rewrote in one
     * pass could not tell a rewrite that reached too far from a rewrite the walk had drifted past.
     */
    @Test
    fun theAttributeRewritesTouchNoOtherComponent() {
        val original = manifestOf(discordApk)
        val plan = DiscordManifestEdits.plan(DISCORD, emptySet(), 0, emptyList())

        val withoutElements = BinaryXmlEditor.edit(xml = original, removeElements = plan.removals).bytes
        val result = BinaryXmlEditor.edit(xml = withoutElements, elementOverrides = plan.overrides)

        assertEquals(
            "every override must land, and on the component it names",
            plan.overrides.map { it.element.attributeValue }.toSet(),
            result.elementOverridesApplied.toSet()
        )
        assertEquals(
            "one attribute each",
            listOf(
                BinaryXmlEditor.ATTR_EXPORTED,
                BinaryXmlEditor.ATTR_ENABLED,
                BinaryXmlEditor.ATTR_ENABLED,
                BinaryXmlEditor.ATTR_ENABLED
            ),
            result.attributesRewritten
        )

        val before = chunksOf(withoutElements)
        val after = chunksOf(result.bytes)
        assertEquals("no chunk may be added or dropped", before.size, after.size)
        val differing = before.indices.filter { !sameChunk(before[it], after[it]) }
        assertEquals("only the four overridden elements may differ", plan.overrides.size, differing.size)
        for (index in differing) {
            assertEquals("the difference must be in a START_TAG", 0x0102, after[index].first)
        }

        // `android:enabled` and `android:exported` are *typed* booleans on the wire: a four-byte
        // Res_value whose data is 0xFFFFFFFF for true and 0 for false. So each change is those
        // four bytes and nothing else. Where they sit in their element is not asserted, because
        // it is not the same in all four — `AnalyticsReceiver` declares `exported` after
        // `enabled`, `AnalyticsJobService` declares a permission before it — and what matters is
        // that the rewrite touched one attribute rather than that it touched a particular offset.
        val runs = differingOffsets(before, after).groupBy { it.first }
            .mapValues { (_, offsets) -> offsets.map { it.second }.sorted() }
        assertEquals("one differing run per override, and no other chunk touched", plan.overrides.size, runs.size)
        for ((index, offsets) in runs) {
            assertEquals("chunk $index must change in one attribute's Res_value", 4, offsets.size)
            assertEquals(
                "and the four bytes must be contiguous",
                (offsets.first() until offsets.first() + 4).toList(),
                offsets
            )
        }
    }

    /**
     * A build none of these patches is running against keeps its manifest byte for byte.
     *
     * The RPC override is applied on every run, so on a build with no such service it has to match
     * nothing and leave nothing behind — not a rewritten attribute, not a re-serialised document
     * whose string pool was rebuilt.
     *
     * This is also where the two groups read off Discord are held to their scope. OctoGram
     * declares `READ_CONTACTS` and keeps the address book in step through it, so a dead-permission
     * removal that travelled would take a permission this app is using; and if the analytics
     * components were switched off without checking the package first, a build that happens to
     * declare a component of the same name would be edited for a reason that was never about it.
     */
    @Test
    fun aBuildWithNoneOfTheseComponentsKeepsItsManifestByteForByte() {
        val original = manifestOf(octoGramApk)
        val plan = DiscordManifestEdits.plan(OCTOGRAM, emptySet(), mergedLibraries = 0, removedPermissions = emptyList())

        assertTrue("no dead permission may be asked for", plan.deadPermissions.isEmpty())
        assertTrue("no analytics component may be asked for", plan.googleAnalytics.isEmpty())

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
        assertEquals("one flag per override may differ, and no other line", plan.overrides.size, differing.size)
        for (index in differing) {
            assertTrue("the flag was not set before: ${expected[index]}", expected[index].endsWith("=true"))
            assertTrue("the flag was not cleared: ${after[index]}", after[index].endsWith("=false"))
        }
        assertEquals(
            "each flag must belong to the component its override names",
            setOf(
                DiscordPatches.RPC_SERVICE_NAME to "exported(0x01010010)",
                DiscordPatches.GOOGLE_ANALYTICS_COMPONENTS[0].name to "enabled(0x0101000e)",
                DiscordPatches.GOOGLE_ANALYTICS_COMPONENTS[1].name to "enabled(0x0101000e)",
                DiscordPatches.GOOGLE_ANALYTICS_COMPONENTS[2].name to "enabled(0x0101000e)"
            ),
            differing.map { index ->
                val line = expected[index]
                val flag = listOf("exported(0x01010010)", "enabled(0x0101000e)").firstOrNull { line.contains(it) }
                assertTrue("${line.trim()} is neither an export nor an enabled flag", flag != null)
                requireNotNull(enclosingName(expected, index)) { "no element owns ${line.trim()}" } to flag
            }.toSet()
        )
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
