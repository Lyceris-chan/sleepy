package dev.sleepy.app.engine

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
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
 * repacked APK can carry it inline. [mergeNativeLibrariesToDir] does the same with the
 * libraries landing in files: an ABI split is tens of megabytes, which is more than a phone
 * heap can hold next to the base APK and the archive being rebuilt.
 */
object SplitMerger {

    /** What a merged entry says about itself, whichever way its bytes are held. */
    interface MergedSplitEntry {
        /** Entry name it is written into the merged APK under. */
        val name: String

        /** Bytes this entry contributes. */
        val size: Long

        /** Whether the split stored it uncompressed. */
        val storedInSplit: Boolean
    }

    /** A file lifted out of a split APK and held in memory. */
    data class MergedEntry(
        override val name: String,
        val data: ByteArray,
        override val storedInSplit: Boolean
    ) : MergedSplitEntry {
        override val size: Long get() = data.size.toLong()
    }

    /**
     * The same, materialised on disk. The split is walked once and each library is written
     * out as it is read, so the largest thing in memory is one library rather than all of
     * them: a 74 MB ABI split does not fit in the heap twice over.
     */
    data class MergedFileEntry(
        override val name: String,
        val file: File,
        override val storedInSplit: Boolean
    ) : MergedSplitEntry {
        override val size: Long get() = file.length()
    }

    /** ABI directory name -> number of libraries merged from it, in encounter order. */
    data class MergeReport<E : MergedSplitEntry>(
        val entries: List<E>,
        val abis: Map<String, Int>
    ) {
        val libraryCount: Int get() = entries.size
        val totalBytes: Long get() = entries.sumOf { it.size }
    }

    private val ABI_DIR = Regex("^lib/([^/]+)/.+")

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Extracts every shared object under `lib/<abi>/` from [splitApkBytes], sorted by name
     * so the merged result is deterministic.
     */
    fun mergeNativeLibraries(splitApkBytes: ByteArray): MergeReport<MergedEntry> {
        val entries = mutableListOf<MergedEntry>()
        walkLibraries(ByteArrayInputStream(splitApkBytes)) { name, abi, stored, content ->
            entries.add(MergedEntry(name = name, data = content.readBytes(), storedInSplit = stored))
        }
        entries.sortBy { it.name }
        return MergeReport(entries, abisOf(entries))
    }

    /**
     * The same extraction, writing each shared object into [into] and reporting it by file.
     *
     * The file names are flattened repeats of the entry names (`lib_arm64-v8a_libfoo.so`): the
     * name an entry is written into the APK under stays in [MergedFileEntry.name], and a
     * flattened name cannot escape [into] however the split names its entries.
     */
    fun mergeNativeLibrariesToDir(splitApk: File, into: File): MergeReport<MergedFileEntry> {
        require(into.isDirectory || into.mkdirs()) { "cannot create ${into.absolutePath}" }
        val entries = mutableListOf<MergedFileEntry>()
        BufferedInputStream(splitApk.inputStream(), BUFFER_SIZE).use { input ->
            walkLibraries(input) { name, _, stored, content ->
                val target = File(into, "lib_" + name.replace('/', '_'))
                target.outputStream().use { out -> content.copyTo(out, BUFFER_SIZE) }
                entries.add(MergedFileEntry(name = name, file = target, storedInSplit = stored))
            }
        }
        // Written in the order the split listed them, reported in name order so the archive
        // this feeds is deterministic no matter how the split was laid out.
        entries.sortBy { it.name }
        return MergeReport(entries, abisOf(entries))
    }

    /**
     * Walks the shared objects under `lib/<abi>/` in [splitApk], in encounter order.
     *
     * [onLibrary] receives the entry's name, its ABI directory, whether the split stored it
     * uncompressed, and a stream positioned at its contents.
     */
    private inline fun walkLibraries(
        splitApk: InputStream,
        onLibrary: (name: String, abi: String, storedInSplit: Boolean, content: InputStream) -> Unit
    ) {
        ZipInputStream(splitApk).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val match = ABI_DIR.matchEntire(name)
                if (match != null && name.endsWith(".so")) {
                    onLibrary(name, match.groupValues[1], entry.method == ZipEntry.STORED, zis)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun abisOf(entries: List<MergedSplitEntry>): Map<String, Int> {
        val abis = linkedMapOf<String, Int>()
        for (entry in entries) {
            val abi = ABI_DIR.matchEntire(entry.name)?.groupValues?.get(1) ?: continue
            abis[abi] = (abis[abi] ?: 0) + 1
        }
        return abis
    }
}
