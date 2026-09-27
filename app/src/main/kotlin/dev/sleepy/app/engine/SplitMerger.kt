package dev.sleepy.app.engine

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Merges Android App Bundle configuration splits into the base APK.
 *
 * A `split_base` download is only the master split: it carries the DEX, the resources and
 * the JS bundle, but no native library entries at all — every shared object lives in the
 * ABI configuration split. Installing the base alone gives an APK whose first
 * `System.loadLibrary` call throws `UnsatisfiedLinkError`, and whose manifest still
 * declares `android:requiredSplitTypes`, so the platform refuses it outright.
 *
 * [mergeNativeLibraries] pulls the `lib/` tree out of each configuration split so the
 * repacked APK can carry it inline.
 */
object SplitMerger {

    /** A file lifted out of a split APK, ready to be written into the merged APK. */
    data class MergedEntry(
        val name: String,
        val data: ByteArray,
        val storedInSplit: Boolean
    )

    /** ABI directory name -> number of libraries merged from it, in encounter order. */
    data class MergeReport(
        val entries: List<MergedEntry>,
        val abis: Map<String, Int>
    ) {
        val libraryCount: Int get() = entries.size
        val totalBytes: Long get() = entries.sumOf { it.data.size.toLong() }
    }

    private val ABI_DIR = Regex("^lib/([^/]+)/.+")

    /**
     * Extracts every shared object under `lib/<abi>/` from [splitApkBytes], sorted by name
     * so the merged result is deterministic.
     */
    fun mergeNativeLibraries(splitApkBytes: ByteArray): MergeReport {
        val entries = mutableListOf<MergedEntry>()
        val abis = linkedMapOf<String, Int>()

        ZipInputStream(ByteArrayInputStream(splitApkBytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val match = ABI_DIR.matchEntire(name)
                if (match != null && name.endsWith(".so")) {
                    val abi = match.groupValues[1]
                    entries.add(
                        MergedEntry(
                            name = name,
                            data = zis.readBytes(),
                            storedInSplit = entry.method == ZipEntry.STORED
                        )
                    )
                    abis[abi] = (abis[abi] ?: 0) + 1
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        entries.sortBy { it.name }
        return MergeReport(entries, abis)
    }
}
