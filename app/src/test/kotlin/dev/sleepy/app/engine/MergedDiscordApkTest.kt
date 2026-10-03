package dev.sleepy.app.engine

import dev.sleepy.app.testing.ReferenceApks
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The archive rebuilt from the real Discord base and its splits verifies as an APK.
 *
 * The merge and repack are the last steps before signing, and they run over the shipped 349.5
 * build: the result has to carry the density split's resources, align every stored entry the way
 * the platform's loader requires, and pass an independent alignment check.
 */
class MergedDiscordApkTest {

    /**
     * The full path that produced the alignment report: merge the real Discord ABI and density
     * splits into the real base split and repack, all of it the way the pipeline does it—the
     * libraries merged out to files, the archive rebuilt file to file, and the result checked
     * where it lies.
     *
     * That is also what makes this runnable in a phone-sized heap: the base split is 96 MB and
     * the libraries are 74 MB, so neither can be a `ByteArray` while the 131 MB output is being
     * written. This test is the one that caught the repack holding all three at once, so it is
     * deliberately the path that has to keep fitting.
     *
     * The output is left in `/tmp` so it can be passed to the real `zipalign` binary, which is
     * the reference for whether the alignment is right—see
     * [theRealZipalignAcceptsTheMergedArchive], which makes that second claim on its own.
     */
    @Test
    fun mergedDiscordApkPassesAlignment() {
        val extracted = ReferenceApks.discordExtracted
        val base = File(extracted, "base.apk")
        val splits = discordSplits(extracted)
        // An absent fixture skips the test, which JUnit reports as skipped. A `return` here
        // is a pass, and the merge path then stops being covered.
        assumeTrue(
            "the Discord splits are not on this machine (${extracted.path})",
            base.exists() && splits.all { it.exists() }
        )

        val workDir = Files.createTempDirectory("sleepy-merge-test").toFile()
        try {
            val out = File("/tmp/sleepy-merged-test.apk")
            val merged = repackMergedDiscordApk(base, splits, workDir, out)

            val result = ApkVerifier.verify(out)
            assertNull(
                "the merged archive must be readable: ${result.directoryError}",
                result.directoryError
            )
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
     * carry, and merging the density split puts them back—at the paths the desktop build uses.
     *
     * What does not come back with them is the table that names them. Every split ships a
     * partial table naming only the files it carries: this base's names its own 3,640, the
     * density split's names its 1,246, and the two sets share not one path. So the merged
     * archive's entries and size come to match the desktop build's while those resources stay
     * unreachable—the desktop relinks the tables with aapt2, and this repack copies files. The
     * test below pins the file set and says nothing about resolution, because that is all the
     * merge can claim.
     */
    @Test
    fun mergedDiscordApkGainsTheDensitySplitsResources() {
        val extracted = ReferenceApks.discordExtracted
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
            // The repack always leaves out two things the base carries, and both are entries no
            // rebuilt archive may hold rather than files this merge lost: Discord's own JS patch
            // file, superseded by the "locked bundle" patch, and `stamp-cert-sha256`, the Play
            // source stamp, which describes the signed build this one was derived from—a
            // provenance an APK signed with a key of our own does not have, and one the desktop
            // reference build does not carry either. Nothing else may go missing.
            assertEquals(
                "only the artifacts the repack drops may leave the archive",
                setOf("assets/index.android.bundle.patch", "stamp-cert-sha256"),
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
                "Base ${base.length()} bytes / ${before.size} entries -> merged " +
                    "${out.length()} bytes / " +
                    "${after.size} entries, +${gained.size} entries " +
                    "(+${merged.libraries} libraries, +${merged.resources} resources)"
            )
        } finally {
            workDir.deleteRecursively()
            out.delete()
        }
    }

    /**
     * The merged archive read by the real `zipalign`, which is the reference for alignment.
     *
     * Its own test because the binary is not part of this repository: written as a branch inside
     * the test above, a machine without the SDK prints a line nobody reads and reports the
     * external check as having passed. Here it is reported as skipped, and the alignment report
     * above keeps its own verdict whether or not the tool is installed.
     */
    @Test
    fun theRealZipalignAcceptsTheMergedArchive() {
        val extracted = ReferenceApks.discordExtracted
        val base = File(extracted, "base.apk")
        val splits = discordSplits(extracted)
        assumeTrue(
            "the Discord splits are not on this machine (${extracted.path})",
            base.exists() && splits.all { it.exists() }
        )
        val zipalign = ReferenceApks.buildTool("zipalign")
        assumeTrue("zipalign is not on this machine", zipalign != null)

        val workDir = Files.createTempDirectory("sleepy-zipalign-test").toFile()
        val out = File.createTempFile("sleepy-merged-zipalign-", ".apk")
        try {
            repackMergedDiscordApk(base, splits, workDir, out)

            val check = ProcessBuilder(zipalign!!.absolutePath, "-c", "-v", "4", out.absolutePath)
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
     * Both tests above need the same artifact and make different claims about it, so they build
     * it the same way rather than each assembling their own version of it.
     */
    private fun repackMergedDiscordApk(
        base: File,
        splits: List<File>,
        workDir: File,
        out: File
    ): MergedCounts {
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

    /** What went into a merged APK, so each test can make its claim about the same artifact. */
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
}
