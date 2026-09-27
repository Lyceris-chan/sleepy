package dev.sleepy.app

import dev.sleepy.app.engine.ApkVerifier
import dev.sleepy.app.engine.BinaryXmlEditor
import dev.sleepy.app.engine.SplitMerger
import dev.sleepy.app.engine.ZipRepacker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Tests for the split-APK merge path.
 *
 * These cover the failure that made a patched Discord build crash on launch: an App Bundle
 * base split carries no native libraries at all and declares `requiredSplitTypes`, so an
 * APK built from the base alone dies on its first `System.loadLibrary` call — and would be
 * refused by the platform even before that.
 *
 * They also cover the second half of that gap, which the user noticed from the other end:
 * the base split carries none of the density split's resource files either, so a base-only
 * APK is short the ~8 MB of drawables the desktop build's merged APK carries. The merge puts
 * those files back at the desktop's paths; what it cannot put back is the table that names
 * them, which is why the assertions here are about file names and sizes rather than about
 * whether the app can reach what it now contains.
 */
class SplitMergeTest {

    // ---- SplitMerger -------------------------------------------------------------------

    /**
     * A configuration split contributes its shared objects and its resources, and nothing
     * else: its manifest describes the split, its `resources.arsc` is a table this merge
     * cannot graft onto the base's, and its `values/` holds stand-ins for values the base
     * already has — copying those in would blank out what the base carries.
     */
    @Test
    fun mergeSplitTakesLibrariesAndResourcesButNotTheSplitsOwnFiles() {
        val split = zipOf(
            "lib/arm64-v8a/libfoo.so" to ByteArray(64) { 1 },
            "lib/arm64-v8a/libbar.so" to ByteArray(32) { 2 },
            "lib/armeabi-v7a/libfoo.so" to ByteArray(16) { 3 },
            "AndroidManifest.xml" to byteArrayOf(1, 2, 3),
            "resources.arsc" to byteArrayOf(6, 7, 8, 9),
            "res/drawable-hdpi-v4/logo.png" to ByteArray(8) { 4 },
            "res/values/strings.xml" to byteArrayOf(4, 5)
        )

        val report = SplitMerger.mergeSplit(split)

        assertEquals(3, report.libraryCount)
        assertEquals(1, report.resourceCount)
        assertEquals(2, report.abis["arm64-v8a"])
        assertEquals(1, report.abis["armeabi-v7a"])
        assertEquals(120L, report.totalBytes)
        assertEquals("libraries and resources are weighed apart", 112L, report.libraryBytes)
        assertEquals(8L, report.resourceBytes)
        // Sorted so the merged archive is deterministic.
        assertEquals(
            listOf(
                "lib/arm64-v8a/libbar.so",
                "lib/arm64-v8a/libfoo.so",
                "lib/armeabi-v7a/libfoo.so",
                "res/drawable-hdpi-v4/logo.png"
            ),
            report.entries.map { it.name }
        )
    }

    @Test
    fun mergeSplitOnSplitWithNothingToMergeYieldsEmptyReport() {
        val split = zipOf("AndroidManifest.xml" to byteArrayOf(1))
        val report = SplitMerger.mergeSplit(split)
        assertEquals(0, report.libraryCount)
        assertEquals(0, report.resourceCount)
        assertEquals(0L, report.totalBytes)
        assertTrue(report.abis.isEmpty())
    }

    /**
     * The file-backed merge is the one the pipeline runs, so what it writes has to be
     * readable back at the size it claims and inside the directory it was given — the entry
     * names come from the split, and a flattened name is what keeps them from pointing
     * anywhere else.
     */
    @Test
    fun mergeSplitToDirWritesEveryEntryUnderTheDirectoryItWasGiven() {
        val split = zipOf(
            "lib/arm64-v8a/libfoo.so" to ByteArray(64) { 1 },
            "res/drawable-hdpi-v4/logo.png" to ByteArray(8) { 4 }
        )
        val splitApk = File.createTempFile("sleepy-merge-split-", ".apk")
        val into = Files.createTempDirectory("sleepy-merge-dir").toFile()
        try {
            splitApk.writeBytes(split)
            val report = SplitMerger.mergeSplitToDir(splitApk, into)

            assertEquals(1, report.libraryCount)
            assertEquals(1, report.resourceCount)
            assertEquals(
                listOf("lib/arm64-v8a/libfoo.so", "res/drawable-hdpi-v4/logo.png"),
                report.entries.map { it.name }
            )
            val root = into.canonicalPath + File.separator
            for (entry in report.entries) {
                assertTrue("${entry.file.path} must be written into ${into.path}", entry.file.canonicalPath.startsWith(root))
                assertTrue("${entry.file.name} must have been written", entry.file.isFile)
                assertEquals("the report must state the size on disk", entry.file.length(), entry.size)
                assertTrue("${entry.file.name} must not be empty", entry.file.length() > 0)
            }
        } finally {
            splitApk.delete()
            into.deleteRecursively()
        }
    }

    // ---- ZipRepacker alignment ---------------------------------------------------------

    @Test
    fun paddingNeverProducesAnUnrepresentableExtraField() {
        // An extra field costs 4 header bytes, so a non-zero pad below 4 could not be encoded.
        for (remainder in 0..3) {
            val base = 1000L + remainder
            val pad = ZipRepacker.paddingFor(base, 4)
            assertEquals("base=$base must stay aligned", 0L, (base + pad) % 4)
            assertTrue("base=$base produced pad=$pad", pad == 0 || pad >= 4)
        }
    }

    @Test
    fun repackKeepsEveryUncompressedEntryFourByteAlignedAfterSizesChange() {
        // A large stored entry followed by more entries: replacing the first with a much
        // smaller one shifts everything after it, which is what breaks naive repacking.
        val original = zipOfStored(
            "resources.arsc" to ByteArray(4096) { 7 },
            "AndroidManifest.xml" to ByteArray(512) { 8 },
            "classes.dex" to ByteArray(2048) { 9 }
        )

        val result = ZipRepacker.repack(
            inputApkBytes = original,
            replacements = mapOf("resources.arsc" to ByteArray(37) { 1 })
        )

        val misaligned = mutableListOf<String>()
        ApkVerifier.readCentralDirectory(result.bytes) { name, dataOffset, _ ->
            if (dataOffset % 4 != 0L) misaligned.add(name)
        }
        assertTrue("entries must be 4-byte aligned, misaligned: $misaligned", misaligned.isEmpty())
        assertEquals(listOf("resources.arsc"), result.replacedEntries)
    }

    @Test
    fun repackAppendsMergedLibrariesAndPreservesStorageMethodOfUntouchedEntries() {
        val original = zipOfStored("resources.arsc" to ByteArray(128) { 1 })

        val result = ZipRepacker.repack(
            inputApkBytes = original,
            replacements = emptyMap(),
            additionalEntries = mapOf(
                "lib/arm64-v8a/libfoo.so" to ZipRepacker.AdditionalEntry(ByteArray(300) { 5 }, ZipEntry.DEFLATED)
            )
        )

        val methods = entryMethods(result.bytes)
        assertEquals(ZipEntry.STORED, methods["resources.arsc"])
        assertNotNull("the merged library must be present", methods["lib/arm64-v8a/libfoo.so"])
        assertTrue(result.addedEntries.contains("lib/arm64-v8a/libfoo.so"))
    }

    /**
     * A **replaced** entry keeps the method its source had, which is the case that matters for
     * the two entries the pipeline actually replaces: the resource table the merge rebuilds and
     * the JavaScript bundle the patch rewrites. Both are STORED in a Discord base split, and the
     * platform maps both rather than unpacking them.
     *
     * The method of a replaced entry is not carried by the replacement, so it has to be read off
     * the source as the source is walked — and that read has to happen before the entry is dropped
     * from the copied stream, which is the one thing about a replaced entry that is easy to get
     * wrong and impossible to see from the outside afterwards.
     */
    @Test
    fun repackKeepsTheStorageMethodOfAnEntryItReplaces() {
        val original = zipOfStored(
            "resources.arsc" to ByteArray(4096) { 7 },
            "AndroidManifest.xml" to ByteArray(512) { 8 }
        )

        val result = ZipRepacker.repack(
            inputApkBytes = original,
            replacements = mapOf("resources.arsc" to ByteArray(128) { 1 })
        )

        val methods = entryMethods(result.bytes)
        assertEquals("the rebuilt table must stay uncompressed, as its source was", ZipEntry.STORED, methods["resources.arsc"])
        assertEquals("the entry it replaced must not be left beside it", 2, methods.size)

        // Uncompressed means aligned: the smaller replacement moved the entry, so the padding
        // has to have moved with it, and the verifier's own reading is what says so.
        val offsets = mutableMapOf<String, Long>()
        ApkVerifier.readCentralDirectory(result.bytes) { name, dataOffset, _ -> offsets[name] = dataOffset }
        assertEquals("a stored entry's data must land on a 4-byte boundary", 0L, offsets.getValue("resources.arsc") % 4)
    }

    /**
     * Compressed entries carry no alignment requirement, and treating them as if they did
     * is what made the patcher report "zipalign failed" on a perfectly valid APK. The real
     * `zipalign -c -v 4` labels them "(OK - compressed)"; this asserts the verifier agrees.
     */
    @Test
    fun compressedEntriesAreExemptFromAlignment() {
        // Data lengths chosen so the deflated payloads land off any 4-byte boundary.
        val original = zipOf(
            "assets/one.bin" to ByteArray(1001) { 1 },
            "assets/two.bin" to ByteArray(5003) { 2 },
            "assets/three.bin" to ByteArray(7001) { 3 }
        )

        val repacked = ZipRepacker.repack(original, replacements = emptyMap())
        val result = ApkVerifier.verify(repacked.bytes)
        assertNull("the archive must be readable: ${result.directoryError}", result.directoryError)
        assertEquals(
            "compressed entries must not be treated as misaligned: ${result.misalignedEntries}",
            true,
            result.zipalignPassed
        )

        // And the raw offsets really are unaligned, so the check above is not vacuous.
        val offsets = mutableListOf<Long>()
        ApkVerifier.readCentralDirectory(repacked.bytes) { _, dataOffset, _ -> offsets.add(dataOffset) }
        assertTrue("expected at least one unaligned compressed entry, got $offsets", offsets.any { it % 4 != 0L })
    }

    /**
     * The full path that produced the alignment report: merge the real Discord ABI and density
     * splits into the real base split and repack, all of it the way the pipeline does it — the
     * libraries merged out to files, the archive rebuilt file to file, and the result checked
     * where it lies.
     *
     * That is also what makes this runnable in a phone-sized heap: the base split is 96 MB and
     * the libraries are 74 MB, so neither can be a `ByteArray` while the 131 MB output is being
     * written. This test is the one that caught the repack holding all three at once, so it is
     * deliberately the path that has to keep fitting.
     *
     * The output is left in `/tmp` so it can be handed to the real `zipalign` binary, which is
     * the authority on whether the alignment is actually right — see
     * [theRealZipalignAcceptsTheMergedArchive], which makes that second claim on its own.
     */
    @Test
    fun mergedDiscordApkPassesAlignment() {
        val extracted = DISCORD_EXTRACTED
        val base = File(extracted, "base.apk")
        val splits = discordSplits(extracted)
        // An absent fixture skips the test, which JUnit reports as skipped. A `return` here
        // would be a pass, and the merge path would stop being covered without anyone noticing.
        assumeTrue(
            "the Discord splits are not on this machine (${extracted.path})",
            base.exists() && splits.all { it.exists() }
        )

        val workDir = Files.createTempDirectory("sleepy-merge-test").toFile()
        try {
            val out = File("/tmp/sleepy-merged-test.apk")
            val merged = repackMergedDiscordApk(base, splits, workDir, out)

            val result = ApkVerifier.verify(out)
            assertNull("the merged archive must be readable: ${result.directoryError}", result.directoryError)
            assertEquals(
                "merged APK must pass the alignment check, misaligned: ${result.misalignedEntries}",
                true,
                result.zipalignPassed
            )

            println(
                "Merged ${merged.libraries} libraries and ${merged.resources} resources -> " +
                    "${out.length()} bytes, alignment OK, wrote $out"
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * The user-visible claim: the merged APK is missing the resources the base split does not
     * carry, and merging the density split puts them back — at the paths the desktop build uses.
     *
     * What does not come back with them is the table that names them. Every split ships a
     * partial table naming only the files it carries: this base's names its own 3,606, the
     * density split's names its 1,249, and the two sets share not one path. So the merged
     * archive's entries and size come to match the desktop build's while those resources stay
     * unreachable — the desktop relinks the tables with aapt2, and this repack copies files. The
     * test below pins the file set and says nothing about resolution, because that is all the
     * merge can honestly claim.
     */
    @Test
    fun mergedDiscordApkGainsTheDensitySplitsResources() {
        val extracted = DISCORD_EXTRACTED
        val base = File(extracted, "base.apk")
        val splits = discordSplits(extracted)
        assumeTrue(
            "the Discord splits are not on this machine (${extracted.path})",
            base.exists() && splits.all { it.exists() }
        )

        val workDir = Files.createTempDirectory("sleepy-resource-test").toFile()
        val out = File.createTempFile("sleepy-merged-resources-", ".apk")
        try {
            val merged = repackMergedDiscordApk(base, splits, workDir, out)
            assertTrue("the density split must carry resources", merged.resources > 0)

            val before = entryNames(base)
            val after = entryNames(out)
            val gained = after.toSet() - before.toSet()
            val lost = before.toSet() - after.toSet()

            // Nothing is replaced: the base has none of these, so every merged entry has to
            // arrive as an addition, and every one of them has to be in the archive.
            assertEquals(
                "every merged entry must be an addition, never a replacement",
                merged.libraries + merged.resources,
                gained.size
            )
            // The repack always leaves out artefacts a later build supersedes, and the base
            // carries one of them: Discord's own JS patch file, dropped by the "locked bundle"
            // patch. Nothing else may go missing.
            assertEquals(
                "only the superseded artefact the repack drops may leave the archive",
                setOf("assets/index.android.bundle.patch"),
                lost
            )
            assertEquals(
                "and nothing else about the entry count may change",
                before.size - lost.size + gained.size,
                after.size
            )

            assertTrue(
                "a dense-screen drawable must be in the merged APK",
                gained.any { it.startsWith("res/drawable-xhdpi-v4/") }
            )
            assertTrue(
                "the anydpi ExoPlayer aliases the desktop build restores must be in the merged APK",
                gained.any { it.startsWith("res/drawable-anydpi-v21/exo_") }
            )

            println(
                "Base ${base.length()} bytes / ${before.size} entries -> merged ${out.length()} bytes / " +
                    "${after.size} entries, +${gained.size} entries " +
                    "(+${merged.libraries} libraries, +${merged.resources} resources)"
            )
        } finally {
            workDir.deleteRecursively()
            out.delete()
        }
    }

    /**
     * The merged archive read by the real `zipalign`, which is the last word on alignment.
     *
     * Its own test because the binary is not part of this repository: written as a branch inside
     * the test above, a machine without the SDK would print a line nobody reads and report the
     * external check as having passed. Here it is reported as skipped, and the alignment report
     * above keeps its own verdict whether or not the tool is installed.
     */
    @Test
    fun theRealZipalignAcceptsTheMergedArchive() {
        val extracted = DISCORD_EXTRACTED
        val base = File(extracted, "base.apk")
        val splits = discordSplits(extracted)
        assumeTrue(
            "the Discord splits are not on this machine (${extracted.path})",
            base.exists() && splits.all { it.exists() }
        )
        val zipalign = File("/home/sleepy/portable-tools/android-sdk/build-tools/36.0.0/zipalign")
        assumeTrue("${zipalign.path} is not on this machine", zipalign.canExecute())

        val workDir = Files.createTempDirectory("sleepy-zipalign-test").toFile()
        val out = File.createTempFile("sleepy-merged-zipalign-", ".apk")
        try {
            repackMergedDiscordApk(base, splits, workDir, out)

            val check = ProcessBuilder(zipalign.absolutePath, "-c", "-v", "4", out.absolutePath)
                .redirectErrorStream(true)
                .start()
            val report = check.inputStream.bufferedReader().readText()
            assertEquals("zipalign must accept the merged APK:\n$report", 0, check.waitFor())
        } finally {
            workDir.deleteRecursively()
            out.delete()
        }
    }

    /**
     * Merges the real ABI split's libraries into the real base split and repacks the result to
     * [out], returning how many libraries went in.
     *
     * Both tests above need the same artefact and make different claims about it, so they build
     * it the same way rather than each assembling their own version of it.
     */
    private fun repackMergedDiscordApk(base: File, splits: List<File>, workDir: File, out: File): MergedCounts {
        var libraries = 0
        var resources = 0
        val additional = linkedMapOf<String, ZipRepacker.AdditionalEntry>()
        for (split in splits) {
            val merge = SplitMerger.mergeSplitToDir(split, workDir)
            libraries += merge.libraryCount
            resources += merge.resourceCount
            for (entry in merge.entries) {
                additional[entry.name] = ZipRepacker.AdditionalEntry(entry.file, ZipEntry.DEFLATED)
            }
        }
        assertTrue("the ABI split must carry libraries", libraries > 0)

        FileOutputStream(out).use { stream ->
            ZipRepacker.repackTo(base, stream, emptyMap(), additional)
        }
        return MergedCounts(libraries, resources)
    }

    /** What went into a merged APK, so each test can make its claim about the same artefact. */
    private data class MergedCounts(val libraries: Int, val resources: Int)

    /** Entry names in an archive, read from its directory rather than by loading it. */
    private fun entryNames(apk: File): List<String> {
        val names = mutableListOf<String>()
        val error = ApkVerifier.readCentralDirectory(apk) { name, _, _ -> names.add(name) }
        assertNull("the archive's directory must be readable: $error", error)
        return names
    }

    /** The splits the merged fixture is built from: the ABI one, then the density one. */
    private fun discordSplits(extracted: File): List<File> = listOf(
        File(extracted, "config.arm64_v8a.apk"),
        File(extracted, "config.hdpi.apk")
    )

    @Test
    fun repackDropsSignatureFiles() {
        val original = zipOfStored(
            "classes.dex" to byteArrayOf(1),
            "META-INF/CERT.SF" to byteArrayOf(2),
            "META-INF/CERT.RSA" to byteArrayOf(3),
            "META-INF/MANIFEST.MF" to byteArrayOf(4),
            "META-INF/services/keep.me" to byteArrayOf(5)
        )

        val result = ZipRepacker.repack(original, emptyMap())
        val names = entryMethods(result.bytes).keys

        assertFalse(names.contains("META-INF/CERT.SF"))
        assertFalse(names.contains("META-INF/CERT.RSA"))
        assertFalse(names.contains("META-INF/MANIFEST.MF"))
        assertTrue("non-signature META-INF entries must survive", names.contains("META-INF/services/keep.me"))
    }

    @Test
    fun repackDropsCallerSuppliedArtefacts() {
        val original = zipOfStored(
            "classes.dex" to byteArrayOf(1),
            "lib/arm64-v8a/libsentry.so" to byteArrayOf(2),
            "lib/arm64-v8a/libkeep.so" to byteArrayOf(3),
            "META-INF/sentry-android-replay_release.kotlin_module" to byteArrayOf(4)
        )

        val result = ZipRepacker.repack(
            inputApkBytes = original,
            replacements = emptyMap(),
            droppedEntries = dev.sleepy.app.patches.DiscordPatches.SENTRY_ARTEFACTS
        )
        val names = entryMethods(result.bytes).keys

        assertFalse("the Sentry shared object must go", names.contains("lib/arm64-v8a/libsentry.so"))
        assertFalse("the Sentry metadata must go", names.contains("META-INF/sentry-android-replay_release.kotlin_module"))
        assertTrue("unrelated libraries must survive", names.contains("lib/arm64-v8a/libkeep.so"))
        assertTrue("unrelated classes must survive", names.contains("classes.dex"))
    }

    // ---- BinaryXmlEditor ---------------------------------------------------------------

    @Test
    fun editRemovesSplitAttributesAndRewritesExtractNativeLibs() {
        val manifest = manifestWith(
            intAttribute(BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES, 1),
            intAttribute(BinaryXmlEditor.ATTR_SPLIT_TYPES, 1),
            booleanAttribute(BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS, false),
            intAttribute(0x0101021b, 348205) // unrelated versionCode, must survive
        )

        assertEquals(
            "precondition: extractNativeLibs starts false",
            false,
            BinaryXmlEditor.readBooleanAttribute(manifest, BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS)
        )

        val result = BinaryXmlEditor.makeStandaloneManifest(manifest)

        assertEquals(
            listOf(BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES, BinaryXmlEditor.ATTR_SPLIT_TYPES),
            result.attributesRemoved
        )
        assertEquals(listOf(BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS), result.attributesRewritten)
        assertTrue("no requested attribute may go missing", result.missing.isEmpty())

        // The two split attributes must be gone, and extractNativeLibs must now read true.
        assertEquals(true, BinaryXmlEditor.readBooleanAttribute(result.bytes, BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS))
        assertEquals(
            348205,
            BinaryXmlEditor.readIntAttribute(result.bytes, 0x0101021b)
        )

        // Chunk sizes must stay self-consistent or the platform cannot parse the manifest.
        assertChunksAreConsistent(result.bytes)
    }

    /**
     * The synthetic documents above only prove the editor is self-consistent. This runs it
     * against the real Discord base split, whose manifest declares
     * `requiredSplitTypes="base__abi,base__density"` — the attribute that made a base-only
     * APK unlaunchable — and whose attribute names resolve through a resource map rather
     * than being resource IDs inline.
     */
    @Test
    fun standaloneManifestOnRealDiscordSplit() {
        val apkFile = File(DISCORD_EXTRACTED, "base.apk")
        assumeTrue("${apkFile.path} is not on this machine", apkFile.exists())

        val zip = ZipFile(apkFile)
        val entry = zip.getEntry("AndroidManifest.xml")
        assertNotNull("manifest must be present", entry)
        val manifest = zip.getInputStream(entry).readBytes()
        zip.close()

        assertEquals(
            "precondition: this build declares extractNativeLibs=false",
            false,
            BinaryXmlEditor.readBooleanAttribute(manifest, BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS)
        )
        assertNotNull(
            "precondition: requiredSplitTypes is present",
            BinaryXmlEditor.readAttributeValue(manifest, BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES)
        )

        val result = BinaryXmlEditor.makeStandaloneManifest(manifest)

        assertEquals(
            listOf(BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES, BinaryXmlEditor.ATTR_SPLIT_TYPES),
            result.attributesRemoved
        )
        assertEquals(listOf(BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS), result.attributesRewritten)
        assertTrue("every requested attribute must be found, missing=${result.missing}", result.missing.isEmpty())

        assertNull(
            "requiredSplitTypes must be gone",
            BinaryXmlEditor.readAttributeValue(result.bytes, BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES)
        )
        assertNull(
            "splitTypes must be gone",
            BinaryXmlEditor.readAttributeValue(result.bytes, BinaryXmlEditor.ATTR_SPLIT_TYPES)
        )
        assertEquals(
            "extractNativeLibs must now be true",
            true,
            BinaryXmlEditor.readBooleanAttribute(result.bytes, BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS)
        )
        assertEquals(
            "unrelated attributes must survive",
            348205,
            BinaryXmlEditor.readIntAttribute(result.bytes, 0x0101021b)
        )
        assertEquals(
            "exactly two 20-byte attributes should have been removed",
            manifest.size - 2 * 20,
            result.bytes.size
        )
        assertChunksAreConsistent(result.bytes)
        println("Real Discord manifest: split declarations removed, extractNativeLibs=true, chunks consistent")
    }

    @Test
    fun editIsANoOpWhenTheAttributesAreAbsent() {
        val manifest = manifestWith(intAttribute(0x0101021b, 7))
        val result = BinaryXmlEditor.makeStandaloneManifest(manifest)

        assertTrue(result.attributesRemoved.isEmpty())
        assertTrue(result.attributesRewritten.isEmpty())
        assertEquals(
            "both split attributes should be reported missing",
            2,
            result.missing.count { it == BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES || it == BinaryXmlEditor.ATTR_SPLIT_TYPES }
        )
        assertArrayEquals(manifest, result.bytes)
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun assertChunksAreConsistent(xml: ByteArray) {
        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0003, buf.getShort(0).toInt() and 0xFFFF)
        assertEquals("root chunk size must equal the document length", xml.size, buf.getInt(4))
        var offset = buf.getShort(2).toInt() and 0xFFFF
        var seen = 0
        while (offset + 8 <= xml.size) {
            val chunkSize = buf.getInt(offset + 4)
            assertTrue("chunk at $offset has size $chunkSize", chunkSize >= 8)
            assertTrue("chunk at $offset overruns the document", offset + chunkSize <= xml.size)
            offset += chunkSize
            seen++
        }
        assertEquals("chunks must tile the document exactly", xml.size, offset)
        assertTrue(seen > 0)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue("byte arrays must be identical", expected.contentEquals(actual))
    }

    private fun entryMethods(apkBytes: ByteArray): Map<String, Int> {
        val methods = mutableMapOf<String, Int>()
        ApkVerifier.readCentralDirectory(apkBytes) { name, _, method -> methods[name] = method }
        return methods
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Builds a ZIP whose entries are all STORED, as a real APK's resources.arsc is. */
    private fun zipOfStored(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, data) in entries) {
                val crc = CRC32().apply { update(data) }
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    this.crc = crc.value
                }
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // --- minimal binary-XML builder -----------------------------------------------------

    private fun intAttribute(resourceId: Int, value: Int): ByteArray =
        attribute(resourceId, 0x10, value) // TYPE_INT_DEC

    private fun booleanAttribute(resourceId: Int, value: Boolean): ByteArray =
        attribute(resourceId, 0x12, if (value) 1 else 0) // TYPE_INT_BOOLEAN

    /**
     * Builds one attribute. Its `name` field is a **string-pool index**, resolved through
     * the resource map exactly as the platform does — writing the resource ID there is the
     * mistake the real Discord manifest exposed.
     */
    private fun attribute(resourceId: Int, type: Int, data: Int): ByteArray {
        val nameIndex = RESOURCE_IDS.indexOf(resourceId)
        require(nameIndex >= 0) { "resource id ${Integer.toHexString(resourceId)} is not in the test resource map" }
        return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0)          // ns
            putInt(nameIndex)  // name: string-pool index, NOT the resource id
            putInt(0)          // rawValue
            putShort(8.toShort()) // Res_value.size
            put(0)                // res0
            put(type.toByte())    // Res_value.dataType
            putInt(data)          // Res_value.data
        }.array()
    }

    /**
     * Serialises a single `<manifest>` start tag carrying [attributes] inside a
     * RES_XML_TYPE document, with an empty string pool — enough for the editor to walk.
     */
    private fun manifestWith(vararg attributes: ByteArray): ByteArray {
        val attrBytes = attributes.fold(ByteArray(0)) { acc, a -> acc + a }
        val attrStart = 20
        val startElementSize = 16 + attrStart + attrBytes.size

        val startElement = ByteBuffer.allocate(startElementSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0102.toShort())               // RES_XML_START_ELEMENT_TYPE
            putShort(16.toShort())                   // headerSize
            putInt(startElementSize)                 // size
            putInt(1)                                // lineNumber
            putInt(0xFFFFFFFF.toInt())               // comment
            putInt(0xFFFFFFFF.toInt())               // ns
            putInt(0xFFFFFFFF.toInt())               // name
            putShort(attrStart.toShort())            // attributeStart
            putShort(20.toShort())                   // attributeSize
            putShort(attributes.size.toShort())      // attributeCount
            putShort(0.toShort())                    // idIndex
            putShort(0.toShort())                    // classIndex
            putShort(0.toShort())                    // styleIndex
            put(attrBytes)
        }.array()

        val resourceMap = ByteBuffer.allocate(8 + RESOURCE_IDS.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0180.toShort())            // RES_XML_RESOURCE_MAP_TYPE
            putShort(8.toShort())                 // headerSize
            putInt(8 + RESOURCE_IDS.size * 4)     // size
            RESOURCE_IDS.forEach { putInt(it) }
        }.array()

        val total = 8 + resourceMap.size + startElement.size
        val root = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0003.toShort()) // RES_XML_TYPE
            putShort(8.toShort())      // headerSize
            putInt(total)
        }.array()

        return root + resourceMap + startElement
    }

    private companion object {
        /** Where the Discord 348.5 base and ABI splits are unpacked, outside the repository. */
        val DISCORD_EXTRACTED = File(
            "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted"
        )

        /** Index i holds the resource ID that string-pool index i resolves to. */
        val RESOURCE_IDS = intArrayOf(
            0x0101021b, // versionCode, used as an unrelated attribute that must survive
            BinaryXmlEditor.ATTR_REQUIRED_SPLIT_TYPES,
            BinaryXmlEditor.ATTR_SPLIT_TYPES,
            BinaryXmlEditor.ATTR_EXTRACT_NATIVE_LIBS
        )
    }
}
