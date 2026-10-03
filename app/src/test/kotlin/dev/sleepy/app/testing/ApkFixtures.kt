package dev.sleepy.app.testing

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assume.assumeTrue

/**
 * Reads entries out of the APK fixtures the suite uses.
 *
 * The entry readers assume the fixture exists and report the test as skipped when it does not,
 * because the fixtures are build outputs outside the repository.
 */

/** The bytes of the entry called [name] in [apk]. */
fun entryOf(apk: File, name: String): ByteArray {
    assumeTrue("${apk.path} is not on this machine", apk.exists())
    return ZipFile(apk).use { zip ->
        val entry = requireNotNull(zip.getEntry(name)) { "${apk.name} has no $name" }
        zip.getInputStream(entry).readBytes()
    }
}

/** The bytes of [apk]'s `AndroidManifest.xml`. */
fun manifestOf(apk: File): ByteArray = entryOf(apk, "AndroidManifest.xml")

/** The `classes*.dex` entries of [apk], by entry name. */
fun dexEntries(apk: File): Map<String, ByteArray> {
    assumeTrue("${apk.path} is not on this machine", apk.exists())
    val entries = mutableMapOf<String, ByteArray>()
    ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .filter { DEX_ENTRY_NAME.matches(it.name) }
            .forEach { entries[it.name] = zip.getInputStream(it).readBytes() }
    }
    return entries
}

/** The Hermes bundle `assets/index.android.bundle` of [apk]. */
fun bundleOf(apk: File): ByteArray = entryOf(apk, "assets/index.android.bundle")

private val DEX_ENTRY_NAME = Regex("classes\\d*\\.dex")
