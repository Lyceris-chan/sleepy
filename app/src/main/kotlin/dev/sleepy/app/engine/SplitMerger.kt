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
 * the JS bundle, but no native library entries at all—every shared object is in the
 * ABI configuration split. Installing the base alone gives an APK whose first
 * `System.loadLibrary` call throws `UnsatisfiedLinkError`, and whose manifest still
 * declares `android:requiredSplitTypes`, so the platform rejects it outright.
 *
 * The base is not the whole app either. A density split holds the bitmaps that suit one
 * screen density and a language split the strings of one locale, and both are as absent
 * from the base as the libraries are: a base-only APK has none of the resources only those
 * splits carry. So a split contributes two things here—every
 * shared object under `lib/`, and every file under `res/` except the split's own `values/`
 * directory. That last exception comes from the reference merge and is required: a
 * configuration split's `values/` holds placeholder stubs for the values it replaces rather
 * than resources of its own, and copying those in replaces resources that the base already has.
 *
 * What is deliberately *not* merged is the splits' `resources.arsc`, and that is the whole of
 * this object's limitation rather than a detail. Every split contains a partial table naming
 * only the files that split carries—this base's names its own 3,606, the density split's
 * names its 1,249, and the two sets share no path string at all—so the files merged in here
 * are written at the right paths, but nothing in the merged APK refers to them. Making them
 * resolve means relinking the tables, which is aapt2's job and more than a repack; what this
 * produces is the file set and the size the desktop build has, and no more than that.
 *
 * [mergeSplit] reads those entries into memory; [mergeSplitToDir] writes them to files: a
 * configuration split is tens of megabytes, which is more than a phone heap can hold next to
 * the base APK and the archive being rebuilt.
 */
object SplitMerger {

    /** The information a merged entry provides about itself, whichever way its bytes are held. */
    interface MergedSplitEntry {
        /** The entry name that the entry is written under in the merged APK. */
        val name: String

        /** Bytes this entry contributes. */
        val size: Long

        /** Whether the split stored it uncompressed. */
        val storedInSplit: Boolean
    }

    /** An entry read from a split APK into memory. */
    data class MergedEntry(
        override val name: String,
        val data: ByteArray,
        override val storedInSplit: Boolean
    ) : MergedSplitEntry {
        override val size: Long get() = data.size.toLong()
    }

    /**
     * The same entry, materialized on disk. The split is walked once and each entry is written
     * out as it is read, so the largest thing in memory is one entry rather than all of
     * them: a 74 MB ABI split does not fit in the heap twice.
     */
    data class MergedFileEntry(
        override val name: String,
        val file: File,
        override val storedInSplit: Boolean
    ) : MergedSplitEntry {
        override val size: Long get() = file.length()
    }

    /**
     * What a split contributed, in name order.
     *
     * [abis] is ABI directory name -> number of libraries merged from it, in encounter order.
     */
    data class MergeReport<E : MergedSplitEntry>(
        val entries: List<E>,
        val abis: Map<String, Int>
    ) {
        /** How many of [entries] are shared objects. */
        val libraryCount: Int get() = entries.count { ABI_ENTRY.matches(it.name) }

        /** How many of [entries] are resources. */
        val resourceCount: Int get() = entries.count { it.name.startsWith(RES_DIR) }

        /** What the shared objects add to the archive. */
        val libraryBytes: Long
            get() = entries.filter { ABI_ENTRY.matches(it.name) }.sumOf { it.size }

        /** What the resources add to the archive. */
        val resourceBytes: Long
            get() = entries.filter { it.name.startsWith(RES_DIR) }.sumOf { it.size }

        /** What the split adds to the archive in total. */
        val totalBytes: Long get() = entries.sumOf { it.size }
    }

    /** Captures the ABI directory of a library's entry name. */
    private val ABI_DIR = Regex("^lib/([^/]+)/.+")

    /** Matches an entry under `lib/`, in the merged APK as in the split. */
    private val ABI_ENTRY = Regex("^lib/.+")

    /** The resource directory, in the base split as in every configuration split. */
    const val RES_DIR = "res/"

    /**
     * The split-install metadata that a bundle's base split ships, and no other split does.
     *
     * It lists the configuration splits that an installation has and the flags that go with them,
     * and the platform's split installer is what reads it—Play's, on a bundle install. An APK
     * with every one of its splits inside it is not a split, so the file describes an installation
     * that does not exist: it is the resource-side counterpart of the Play split markers removed
     * from the manifest, and the reference merge removes it in the same step as them.
     *
     * It cannot be removed on its own. The row that names it is in the resource table, and a table
     * resolving to a file the archive does not hold is worse than an archive holding a file that no
     * entry refers to—so the file is removed only when the table that named it was rebuilt
     * without it. See [ResourceTableMerger.merge]'s `droppedPaths`.
     */
    const val SPLIT_INSTALL_METADATA = "res/xml/splits0.xml"

    /**
     * A split's own default resources, which it carries only as replacements for the base's.
     * The reference merge skips these and so does this one: they are placeholder stubs for
     * table-backed values, not files the base is missing.
     */
    private const val SPLIT_DEFAULT_VALUES = "res/values/"

    /** Prefix for the flattened file names [mergeSplitToDir] writes. */
    private const val ENTRY_FILE_PREFIX = "split_"

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Extracts everything [splitApkBytes] contributes—its shared objects and its resources
     * —sorted by name so the merged result is deterministic.
     */
    fun mergeSplit(splitApkBytes: ByteArray): MergeReport<MergedEntry> {
        val entries = mutableListOf<MergedEntry>()
        walkSplit(ByteArrayInputStream(splitApkBytes)) { name, stored, content ->
            entries.add(
                MergedEntry(name = name, data = content.readBytes(), storedInSplit = stored)
            )
        }
        entries.sortBy { it.name }
        return MergeReport(entries, abisOf(entries))
    }

    /**
     * Extracts the same entries, writing each one into [into] and reporting it by file.
     *
     * The file names are flattened repeats of the entry names (`split_lib_arm64-v8a_libfoo.so`,
     * `split_res_drawable-hdpi-v4_logo.png`): the name an entry is written into the APK under
     * stays in [MergedFileEntry.name], and a flattened name stays within [into] however the
     * split names its entries.
     */
    fun mergeSplitToDir(splitApk: File, into: File): MergeReport<MergedFileEntry> {
        require(into.isDirectory || into.mkdirs()) { "cannot create ${into.absolutePath}" }
        val entries = mutableListOf<MergedFileEntry>()
        BufferedInputStream(splitApk.inputStream(), BUFFER_SIZE).use { input ->
            walkSplit(input) { name, stored, content ->
                val target = File(into, ENTRY_FILE_PREFIX + name.replace('/', '_'))
                target.outputStream().use { out -> content.copyTo(out, BUFFER_SIZE) }
                entries.add(MergedFileEntry(name = name, file = target, storedInSplit = stored))
            }
        }
        // Written in the order the split listed them, reported in name order so the archive
        // built from the report is deterministic regardless of how the split was laid out.
        entries.sortBy { it.name }
        return MergeReport(entries, abisOf(entries))
    }

    /**
     * Walks the entries [splitApk] contributes to the merged APK, in encounter order.
     *
     * [onEntry] receives the entry's name, whether the split stored it uncompressed, and a
     * stream positioned at its contents.
     */
    private inline fun walkSplit(
        splitApk: InputStream,
        onEntry: (name: String, storedInSplit: Boolean, content: InputStream) -> Unit
    ) {
        ZipInputStream(splitApk).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (isMerged(name)) {
                    onEntry(name, entry.method == ZipEntry.STORED, zis)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Whether an entry of a configuration split belongs in the merged APK: a shared object,
     * or a resource the base could be missing. Everything else a split carries—its
     * manifest, its own `resources.arsc`, its default `values/`—belongs to the split, not
     * to the app, and is not copied into the merged APK.
     */
    private fun isMerged(name: String): Boolean = when {
        name.endsWith(".so") && ABI_ENTRY.matches(name) -> true
        name.startsWith(RES_DIR) && !name.startsWith(SPLIT_DEFAULT_VALUES) -> true
        else -> false
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
