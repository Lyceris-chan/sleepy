package dev.sleepy.app.engine

import dev.sleepy.app.testing.zipOf
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A configuration split contributes its native libraries and its resources to the base, and
 * nothing else.
 *
 * A base split alone carries no native libraries and none of the density splits' resource files,
 * so a patch run merges both back. What the merge must not copy is the split's own manifest, its
 * `resources.arsc` and its `values/` stand-ins: those describe the split rather than the base, and
 * their values would blank out what the base already carries.
 */
class SplitMergerTest {

    /**
     * A configuration split contributes its shared objects and its resources, and nothing
     * else: its manifest describes the split, its `resources.arsc` is a table this merge
     * cannot graft onto the base's, and its `values/` holds stand-ins for values the base
     * already has—copying those in blanks out what the base carries.
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
     * readable back at the size it reports and inside the directory it was given—the entry
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
                assertTrue(
                    "${entry.file.path} must be written into ${into.path}",
                    entry.file.canonicalPath.startsWith(root)
                )
                assertTrue("${entry.file.name} must have been written", entry.file.isFile)
                assertEquals(
                    "the report must state the size on disk",
                    entry.file.length(),
                    entry.size
                )
                assertTrue("${entry.file.name} must not be empty", entry.file.length() > 0)
            }
        } finally {
            splitApk.delete()
            into.deleteRecursively()
        }
    }
}
