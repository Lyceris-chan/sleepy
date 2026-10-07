package dev.sleepy.app.engine

import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.entryMethods
import dev.sleepy.app.testing.zipOf
import dev.sleepy.app.testing.zipOfStored
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A repacked archive keeps every invariant the platform's loader checks.
 *
 * The repack writes a patched APK over the base's bytes, so every uncompressed entry has to stay
 * four-byte aligned, an entry it replaces has to keep its compression method, and the entries a
 * patch run removes—signature files, the caller's own artifacts, the merged split's Sentry
 * libraries and the Play source stamp—must not be written.
 */
class ZipRepackerTest {

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
                "lib/arm64-v8a/libfoo.so" to
                    ZipRepacker.AdditionalEntry(ByteArray(300) { 5 }, ZipEntry.DEFLATED)
            )
        )

        val methods = entryMethods(result.bytes)
        assertEquals(ZipEntry.STORED, methods["resources.arsc"])
        assertNotNull("the merged library must be present", methods["lib/arm64-v8a/libfoo.so"])
        assertTrue(result.addedEntries.contains("lib/arm64-v8a/libfoo.so"))
    }

    /**
     * A **replaced** entry keeps the method its source had, which is the case that applies to
     * the two entries the pipeline replaces: the resource table the merge rebuilds and
     * the JavaScript bundle the patch rewrites. Both are STORED in a Discord base split, and the
     * platform maps both rather than unpacking them.
     *
     * The method of a replaced entry is not carried by the replacement, so it has to be read off
     * the source as the source is walked—and that read has to happen before the entry is dropped
     * from the copied stream, which is the one thing about a replaced entry that is easy to get
     * wrong and impossible to see from the outside afterward.
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
        assertEquals(
            "the rebuilt table must stay uncompressed, as its source was",
            ZipEntry.STORED,
            methods["resources.arsc"]
        )
        assertEquals("the entry it replaced must not be left beside it", 2, methods.size)

        // Uncompressed means aligned: the smaller replacement moved the entry, so the padding
        // has to have moved with it, and the verifier's own reading is what says so.
        val offsets = mutableMapOf<String, Long>()
        ApkVerifier.readCentralDirectory(result.bytes) { name, dataOffset, _ ->
            offsets[name] = dataOffset
        }
        assertEquals(
            "a stored entry's data must land on a 4-byte boundary",
            0L,
            offsets.getValue("resources.arsc") % 4
        )
    }

    /**
     * Compressed entries carry no alignment requirement, and treating them as if they did
     * is what made the patcher report "zipalign failed" on a valid APK. The real
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
        ApkVerifier.readCentralDirectory(repacked.bytes) { _, dataOffset, _ ->
            offsets.add(dataOffset)
        }
        assertTrue(
            "expected at least one unaligned compressed entry, got $offsets",
            offsets.any { it % 4 != 0L }
        )
    }

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
        assertTrue(
            "non-signature META-INF entries must survive",
            names.contains("META-INF/services/keep.me")
        )
    }

    @Test
    fun repackDropsCallerSuppliedArtifacts() {
        val original = zipOfStored(
            "classes.dex" to byteArrayOf(1),
            "lib/arm64-v8a/libsentry.so" to byteArrayOf(2),
            "lib/arm64-v8a/libkeep.so" to byteArrayOf(3),
            "META-INF/sentry-android-replay_release.kotlin_module" to byteArrayOf(4)
        )

        val result = ZipRepacker.repack(
            inputApkBytes = original,
            replacements = emptyMap(),
            droppedEntries = DiscordPatches.SENTRY_ARTEFACTS
        )
        val names = entryMethods(result.bytes).keys

        assertFalse(
            "the Sentry shared object must go",
            names.contains("lib/arm64-v8a/libsentry.so")
        )
        assertFalse(
            "the Sentry metadata must go",
            names.contains("META-INF/sentry-android-replay_release.kotlin_module")
        )
        assertTrue("unrelated libraries must survive", names.contains("lib/arm64-v8a/libkeep.so"))
        assertTrue("unrelated classes must survive", names.contains("classes.dex"))
    }

    /**
     * The drop reaches the entries the **split merge** contributes, which are the ones the source
     * archive cannot answer for.
     *
     * A configuration split's libraries are entries the base APK never held: they arrive at the
     * repack as [ZipRepacker.AdditionalEntry]s, after the source archive has been walked and left
     * behind. A drop list matched against that walk's entries alone therefore says nothing about
     * them, and a build that reports the crash reporter's artifacts as removed keeps carrying them
     * —724 KB of `libsentry.so` and its 16 KB Android shim, loaded from the APK the moment any
     * code path that survived the stubbing touches the SDK.
     *
     * The ABI split is the real one, because that is where the names came from, and the fixture
     * asserts it carries them before the drop is tested: a rename upstream otherwise turns this
     * into a test that passes by having nothing to check. The base archive is synthetic and
     * holds none of the dropped names, so the only way one of them reaches the output is through
     * the merge—which is exactly the path under test.
     */
    @Test
    fun repackDropsTheMergedSplitsSentryLibraries() {
        val split = File(ComparisonApks.discordExtracted, "config.arm64_v8a.apk")
        assumeTrue("the Discord ABI split is not on this machine (${split.path})", split.exists())

        val workDir = Files.createTempDirectory("sleepy-merged-drop-test").toFile()
        try {
            val merged = SplitMerger.mergeSplitToDir(split, workDir)
            val arriving = merged.entries.filter { it.name in DiscordPatches.SENTRY_ARTEFACTS }
            assertTrue(
                "the ABI split must carry the artifacts this test is about, it carries " +
                    "${merged.entries.map { it.name }.take(8)}",
                arriving.isNotEmpty()
            )
            val surviving = merged.entries.filter { it.name !in DiscordPatches.SENTRY_ARTEFACTS }
            assertTrue(
                "the split must contribute more than the dropped artifacts",
                surviving.isNotEmpty()
            )

            val result = ZipRepacker.repack(
                inputApkBytes = zipOfStored(
                    "classes.dex" to byteArrayOf(1),
                    "lib/arm64-v8a/libkeep.so" to byteArrayOf(2)
                ),
                replacements = emptyMap(),
                additionalEntries = merged.entries.associate {
                    it.name to ZipRepacker.AdditionalEntry(it.file, ZipEntry.DEFLATED)
                },
                droppedEntries = DiscordPatches.SENTRY_ARTEFACTS
            )
            val names = entryMethods(result.bytes).keys

            for (entry in arriving) {
                assertFalse(
                    "${entry.name} came from the split and must not be written",
                    names.contains(entry.name)
                )
            }
            // The drop is a list of names, not "leave the split out": the other libraries the
            // merge contributed are what the merged APK is for and all of them must arrive.
            for (entry in surviving) {
                assertTrue(
                    "${entry.name} was not dropped and must be written",
                    names.contains(entry.name)
                )
            }
            assertEquals(
                "the report must name exactly the artifacts that arrived",
                arriving.map { it.name }.toSet(),
                result.droppedEntries.toSet()
            )
            assertTrue("the base's own entries must survive", names.contains("classes.dex"))
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * The same claim for the artifact that is dropped for every build rather than for a caller's
     * reason: `stamp-cert-sha256`, the Play source stamp, records which signed build this APK was
     * derived from and does not belong in an archive signed with a key of our own.
     */
    @Test
    fun repackDropsThePlaySourceStamp() {
        val result = ZipRepacker.repack(
            inputApkBytes = zipOfStored(
                "classes.dex" to byteArrayOf(1),
                "stamp-cert-sha256" to byteArrayOf(2)
            ),
            replacements = emptyMap()
        )

        val names = entryMethods(result.bytes).keys
        assertFalse("the source stamp must go", names.contains("stamp-cert-sha256"))
        assertTrue("nothing else may be affected", names.contains("classes.dex"))
        assertEquals(listOf("stamp-cert-sha256"), result.droppedEntries)
    }
}
