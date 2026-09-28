package dev.sleepy.app

import dev.sleepy.app.engine.ResourceTableMerger
import dev.sleepy.app.engine.SplitMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * [ResourceTableMerger], against the real base and configuration splits this app patches.
 *
 * The claim under test is the one the merge exists to make: that a table built from a base and its
 * splits names exactly the entries those tables named, at the same indexes under the same
 * configurations, and that the file paths it ends up naming are the files the APK actually holds.
 * The first is checked against the sources' own slot sets; the last is checked against the APKs'
 * entry lists, and then against aapt2 as an independent reader.
 */
class ResourceTableMergeTest {

    private val extracted =
        File("/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted")

    private val baseApk = File(extracted, "base.apk")

    /**
     * The configuration splits, in the order the source lists them: the ABI split carries no
     * resource table, so the density and the two language splits are what this merges.
     */
    private val splitApks = listOf("config.hdpi.apk", "config.de.apk", "config.en.apk").map { File(extracted, it) }

    private val aapt2Candidates = listOf(
        File("/home/sleepy/portable-tools/android-sdk/build-tools/36.0.0/aapt2"),
        File(System.getenv("ANDROID_HOME") ?: "/nonexistent", "build-tools/36.0.0/aapt2")
    )

    /**
     * An entry of a fixture APK.
     *
     * The fixtures are build outputs that live outside the repository, so a machine without one
     * reports the test as skipped — an early `return` would have reported it as a pass instead.
     */
    private fun entryOf(apk: File, name: String): ByteArray {
        assumeTrue("${apk.path} is not on this machine", apk.exists())
        return ZipFile(apk).use { zip ->
            val entry = requireNotNull(zip.getEntry(name)) { "${apk.name} has no $name" }
            zip.getInputStream(entry).readBytes()
        }
    }

    private fun tableOf(apk: File): ByteArray = entryOf(apk, "resources.arsc")

    /**
     * Every `res/` file a fixture APK holds, as the merge should see them.
     *
     * A split's own `res/values/` is excluded because [dev.sleepy.app.engine.SplitMerger] excludes
     * it: those files are placeholder stubs standing in for the base's, and the merged APK never
     * carries them, so a table naming one would name a file that is not there.
     */
    private fun resourceFiles(apk: File, isSplit: Boolean): Set<String> {
        assumeTrue("${apk.path} is not on this machine", apk.exists())
        return ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("res/") && !(isSplit && it.startsWith("res/values/")) }
                .toSet()
        }
    }

    private fun mergeFixtures(droppedPaths: Set<String> = emptySet()): ResourceTableMerger.Result.Merged {
        for (apk in listOf(baseApk) + splitApks) {
            assumeTrue("${apk.path} is not on this machine", apk.exists())
        }
        val result = ResourceTableMerger.merge(tableOf(baseApk), splitApks.map { tableOf(it) }, droppedPaths)
        assertTrue("the merge refused: ${(result as? ResourceTableMerger.Result.Refused)?.reason}", result is ResourceTableMerger.Result.Merged)
        return result as ResourceTableMerger.Result.Merged
    }

    /**
     * The merged table carries every slot its sources carried, in the same place, and no others.
     *
     * Slots are compared rather than resources because a slot is what an app resolves against: an
     * entry that arrives at a different index, or under a different configuration, is a different
     * resource however it is named.
     */
    @Test
    fun carriesEverySourceSlotAndInventsNone() {
        val merged = mergeFixtures()
        val sources = listOf(baseApk) + splitApks

        val expected = LinkedHashSet<ResourceTableMerger.Slot>()
        for (apk in sources) {
            val slots = ResourceTableMerger.slotsOf(tableOf(apk))
            assertNotNull("${apk.name} is not a table this reader can walk", slots)
            expected.addAll(slots!!)
        }
        val actual = ResourceTableMerger.slotsOf(merged.table)
        assertNotNull(actual)

        assertEquals("the merged table lost slots", 0, (expected - actual!!).size)
        assertEquals("the merged table invented slots", 0, (actual - expected).size)
        assertEquals("every source table's entry count should be the merged count", expected.size, merged.resourceCount)
        assertEquals("four tables went in", 4, merged.sourceCount)
    }

    /** A merge must not change what the base's own entries say, only add to them. */
    @Test
    fun keepsEveryEntryTheBaseAlreadyHad() {
        val merged = mergeFixtures()
        val base = ResourceTableMerger.slotsOf(tableOf(baseApk))!!
        val after = ResourceTableMerger.slotsOf(merged.table)!!
        assertTrue("the base's entries must all still be there", after.containsAll(base))

        // Slot identity is the type, the configuration and the index. A base entry that survived
        // under a re-derived configuration would still be present by index but not by slot.
        val configurations = after.map { it.typeId to it.config.toList() }.toSet()
        for (slot in base) {
            assertTrue(
                "resource at type 0x%02x index %d lost its configuration".format(slot.typeId, slot.index),
                configurations.contains(slot.typeId to slot.config.toList())
            )
        }
    }

    /**
     * The merge's whole point: the paths the merged table names are exactly the files an APK built
     * from the base and these splits would hold.
     *
     * A path named but absent is a resource that resolves to nothing; a file present but unnamed is
     * a resource nothing can ask for. Both are what "merged but unreachable" means, and both are
     * zero here.
     */
    @Test
    fun namesExactlyTheFilesTheMergeBringsIn() {
        val merged = mergeFixtures()
        val named = ResourceTableMerger.namedPaths(merged.table)
        assertNotNull("the merged table does not read back", named)

        val present = LinkedHashSet<String>()
        present.addAll(resourceFiles(baseApk, isSplit = false))
        for (apk in splitApks) present.addAll(resourceFiles(apk, isSplit = true))

        assertEquals("the table names files the APK would not hold", emptySet<String>(), named!! - present)
        assertEquals("the APK would hold files the table does not name", emptySet<String>(), present - named)
        // The density split is the reason any of this exists: without its entries merged in, the
        // named set is a subset of the base's own files.
        val baseOnly = ResourceTableMerger.namedPaths(tableOf(baseApk))!!
        assertTrue("nothing was added", named.size > baseOnly.size)
        assertTrue("no path came from a split", (named - baseOnly).isNotEmpty())
    }

    /** The base's own paths must still be named, not replaced by the split's. */
    @Test
    fun stillNamesEveryPathTheBaseNamed() {
        val merged = mergeFixtures()
        val base = ResourceTableMerger.namedPaths(tableOf(baseApk))!!
        val named = ResourceTableMerger.namedPaths(merged.table)!!
        assertEquals("the base lost paths", emptySet<String>(), base - named)
    }

    /**
     * A path the archive is not going to hold is left out of the table, and the merge says which
     * path that was.
     *
     * This is how the split-install metadata goes: the file and the row that names it are removed
     * together, and the row can only be left out of a table that is being rebuilt. So what is
     * checked here is that the entry is gone rather than blanked — a table with an entry still in
     * place, pointing at a pool index that still spells the dropped path, names a file the APK
     * does not hold, which is worse than the file being there unnamed.
     */
    @Test
    fun dropsTheEntriesNamingAPathTheArchiveWillNotHold() {
        val named = ResourceTableMerger.namedPaths(tableOf(baseApk))!!
        assertTrue(
            "the fixture's base should name the split-install metadata",
            SplitMerger.SPLIT_INSTALL_METADATA in named
        )

        val whole = mergeFixtures()
        val merged = mergeFixtures(setOf(SplitMerger.SPLIT_INSTALL_METADATA))
        assertEquals("the merge must report the path it left out", setOf(SplitMerger.SPLIT_INSTALL_METADATA), merged.droppedPaths)

        // The path goes, and nothing else goes with it.
        val after = ResourceTableMerger.namedPaths(merged.table)!!
        assertEquals("the dropped path is not the only difference", named - after, setOf(SplitMerger.SPLIT_INSTALL_METADATA))

        // And the entries that named it are gone rather than emptied: an entry left in place, or
        // left as a hole where the merged table would resolve nothing, is not a drop.
        val withoutDropped = ResourceTableMerger.slotsOf(whole.table)!! - ResourceTableMerger.slotsOf(merged.table)!!
        assertTrue("no entry was left out", withoutDropped.isNotEmpty())
        assertEquals(
            "every entry left out must have named the dropped path",
            whole.resourceCount - merged.resourceCount,
            withoutDropped.size
        )
    }

    /** A path no table names has no entry to leave out, so it is not reported as dropped. */
    @Test
    fun aPathTheTablesDoNotNameIsNotReportedAsDropped() {
        val merged = mergeFixtures(setOf("res/xml/not_a_resource_this_build_has.xml"))
        assertEquals("nothing was left out", emptySet<String>(), merged.droppedPaths)
        assertTrue(
            "a drop that matches nothing must not change the merge",
            mergeFixtures().table.contentEquals(merged.table)
        )
    }

    /**
     * A drop with no splits in it still drops: the base's own table is rebuilt without the entries
     * naming the path, because the archive being built will not hold the file.
     *
     * This is the shape the merge takes when the caller fetched no configuration splits at all —
     * the file is in the base, so it has to be droppable without a split to merge.
     */
    @Test
    fun aDropWithNoSplitsRebuildsTheBaseTableWithoutTheEntry() {
        val base = tableOf(baseApk)
        val named = ResourceTableMerger.namedPaths(base)!!
        val result = ResourceTableMerger.merge(base, emptyList(), setOf(SplitMerger.SPLIT_INSTALL_METADATA))
        assertTrue("the merge refused: ${(result as? ResourceTableMerger.Result.Refused)?.reason}", result is ResourceTableMerger.Result.Merged)
        val merged = result as ResourceTableMerger.Result.Merged

        assertEquals(setOf(SplitMerger.SPLIT_INSTALL_METADATA), merged.droppedPaths)
        assertEquals(
            "the base's other paths must all still be named",
            named - merged.droppedPaths,
            ResourceTableMerger.namedPaths(merged.table)
        )
    }

    /**
     * Every path the table names is the path of a file the merge copies, spelled the way the APK
     * spells it.
     *
     * This is what a relink with the desktop's toolchain could not do, and the reason this is a
     * chunk merge: apktool's decoder normalises `res/drawable-xhdpi-v4/` to `res/drawable-xhdpi/`,
     * which no file in the repacked APK is called.
     */
    @Test
    fun namesThePathsInTheirOriginalCompiledSpelling() {
        val merged = mergeFixtures()
        val named = ResourceTableMerger.namedPaths(merged.table)!!
        val versioned = named.filter { Regex("""^res/[a-z0-9-]+-v\d+/""").containsMatchIn(it) }
        assertTrue("the density split's version-qualified paths should be named", versioned.isNotEmpty())
        assertTrue(
            "the paths must keep the -vNN qualifier the compiled resources use",
            named.none { it.startsWith("res/drawable-xhdpi/") || it.startsWith("res/drawable-anydpi/") }
        )
    }

    @Test
    fun mergingTheSameTablesTwiceProducesTheSameBytes() {
        val first = mergeFixtures()
        val second = ResourceTableMerger.merge(tableOf(baseApk), splitApks.map { tableOf(it) })
        assertTrue(second is ResourceTableMerger.Result.Merged)
        assertTrue(
            "a merge that differed run to run could not be checked against anything",
            first.table.contentEquals((second as ResourceTableMerger.Result.Merged).table)
        )
    }

    /** With no splits there is nothing to merge, and the caller keeps the table it had. */
    @Test
    fun noSplitsReturnsTheBaseTableUnchanged() {
        val base = tableOf(baseApk)
        val result = ResourceTableMerger.merge(base, emptyList())
        assertTrue(result is ResourceTableMerger.Result.Merged)
        assertTrue(
            "an unmerged table must come back byte for byte",
            base.contentEquals((result as ResourceTableMerger.Result.Merged).table)
        )
        assertEquals(1, result.sourceCount)
    }

    /**
     * Two tables carrying an entry at the same index under the same configuration is not something
     * this merge can reconcile: one of them would have to lose. It refuses instead.
     *
     * Merging a table with itself is the simplest way to ask for exactly that.
     */
    @Test
    fun refusesWhenTwoTablesClaimTheSameEntry() {
        val base = tableOf(baseApk)
        val result = ResourceTableMerger.merge(base, listOf(base))
        assertTrue("a collision must be refused, not resolved", result is ResourceTableMerger.Result.Refused)
        val reason = (result as ResourceTableMerger.Result.Refused).reason
        assertTrue("the refusal must say what collided: $reason", reason.contains("same index"))
    }

    /** A split's ids only mean anything in the base's id space, so a different package is refused. */
    @Test
    fun refusesASplitFromAnotherPackage() {
        val split = tableOf(splitApks[0])
        // The package id is the first four bytes of the package chunk's own header, and the
        // package chunk is what follows the table header and its global string pool.
        val packageAt = findPackageChunk(split)
        assertTrue("the fixture should declare package 0x7f", packageAt > 0)
        split[packageAt + 8] = 0x7e

        val result = ResourceTableMerger.merge(tableOf(baseApk), listOf(split))
        assertTrue(result is ResourceTableMerger.Result.Refused)
        assertTrue((result as ResourceTableMerger.Result.Refused).reason.contains("package"))
    }

    /** A file that is not a table, or a table that is cut short, is refused rather than guessed at. */
    @Test
    fun refusesWhatItCannotRead() {
        val garbage = ResourceTableMerger.merge(ByteArray(64) { 0x5A }, emptyList())
        assertTrue(garbage is ResourceTableMerger.Result.Refused)

        val truncated = ResourceTableMerger.merge(tableOf(baseApk).copyOf(1024), emptyList())
        assertTrue(truncated is ResourceTableMerger.Result.Refused)

        val badSplit = ResourceTableMerger.merge(tableOf(baseApk), listOf(tableOf(splitApks[0]).copyOf(64)))
        assertTrue(badSplit is ResourceTableMerger.Result.Refused)
        assertTrue((badSplit as ResourceTableMerger.Result.Refused).reason.contains("split 1"))

        assertNull("a non-table has no slots", ResourceTableMerger.slotsOf(ByteArray(64)))
        assertNull("a non-table names no paths", ResourceTableMerger.namedPaths(ByteArray(64)))
    }

    /** The offset of the package chunk inside a table, found by walking its chunk headers. */
    private fun findPackageChunk(table: ByteArray): Int {
        var offset = u16(table, 2)
        while (offset + 8 <= table.size) {
            val type = u16(table, offset)
            val size = u32(table, offset + 4)
            if (size < 8 || offset + size > table.size) return -1
            if (type == 0x0200) return offset
            offset += size
        }
        return -1
    }

    /**
     * The merged table, put into an APK beside the base's manifest and read by the platform's own
     * tool.
     *
     * The checks above prove the table is internally consistent with its sources. This proves
     * something the merger cannot prove about itself: that an independent reader accepts it, that
     * the density split's file entries are visible with the configurations and paths they had, and
     * that the strings the two language splits carry are reachable.
     */
    @Test
    fun aapt2ReadsTheMergedTableAndTheSplitResourcesAreInIt() {
        // The external parser is a tool rather than a fixture of this repository: without it the
        // test is reported as skipped. It is the only independent reading of the merged table, so
        // "did not run" must not look like "passed".
        val aapt2 = aapt2Candidates.firstOrNull { it.canExecute() }
        assumeTrue("aapt2 is not installed on this machine", aapt2 != null)
        val merged = mergeFixtures()

        val apk = File.createTempFile("sleepy-resource-merge", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                // aapt2 reads the archive's resources through its manifest, so one has to be
                // present even though nothing here changes it.
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(entryOf(baseApk, "AndroidManifest.xml"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("resources.arsc"))
                zip.write(merged.table)
                zip.closeEntry()
            }

            val process = ProcessBuilder(
                aapt2!!.absolutePath, "dump", "resources", apk.absolutePath
            ).redirectErrorStream(true).start()
            val dump = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals("aapt2 could not read the merged table:\n$dump", 0, process.waitFor())

            // The density split's files, under the configuration and the path they were compiled
            // with. A table that had lost the split would have neither.
            assertTrue(
                "the density split's file entries are missing:\n${dump.take(400)}",
                dump.contains("(xhdpi) (file) res/drawable-xhdpi-v4/")
            )
            assertTrue(
                "a path must not be named without the -v4 qualifier the APK uses",
                !dump.contains("res/drawable-xhdpi/")
            )

            // The two language splits, by an entry each one carries and the base does not.
            assertTrue("the German strings are missing", dump.contains("(de) \"Zur Startseite\""))
            assertTrue("the English strings are missing", dump.contains("(en-rGB) \"Navigate home\""))

            // Every resource the merged table holds, and no more: aapt2 resolving an id means the
            // id survived the merge with a name.
            val ids = ResourceTableMerger.slotsOf(merged.table)!!
                .map { "0x%08x".format(0x7f shl 24 or (it.typeId shl 16) or it.index) }
                .toSet()
            val listed = Regex("""resource (0x[0-9a-f]{8})""").findAll(dump).map { it.groupValues[1] }.toSet()
            assertEquals("aapt2 must list exactly the merged resources", ids, listed)

            // The paths aapt2 reads out of the table are the paths the merged APK would hold.
            val fromAapt2 = Regex("""\(file\) (res/\S+)""").findAll(dump).map { it.groupValues[1] }.toSet()
            val named = ResourceTableMerger.namedPaths(merged.table)!!
            assertEquals("aapt2 and the merger disagree about the paths", named, fromAapt2)
        } finally {
            apk.delete()
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
