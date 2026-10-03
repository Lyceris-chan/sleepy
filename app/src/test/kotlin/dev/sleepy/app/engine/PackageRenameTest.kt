package dev.sleepy.app.engine

import dev.sleepy.app.testing.ReferenceApks
import dev.sleepy.app.testing.assertTilesExactly
import dev.sleepy.app.testing.manifestAttributes
import dev.sleepy.app.testing.manifestClassNames
import dev.sleepy.app.testing.manifestOf
import dev.sleepy.app.testing.manifestPackageName
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The package rename on the shipped Discord manifest: every spelling of the old name moves, and
 * nothing else does.
 *
 * `BinaryXmlModifier.modifyPackageName` rewrites the manifest's package, the strings built from
 * it and the classes named after it. The tests read the edited document back attribute by
 * attribute and resolve every class name it carries against the DEX, and the last one has
 * `aapt2` read the result as an independent reader.
 */
class PackageRenameTest {

    private val discordApk = ReferenceApks.discordBaseApk

    @Test
    fun movesTheApplicationIdAndLeavesEveryOtherStringWhereItWas() {
        val original = manifestOf(discordApk)
        val renamed = BinaryXmlModifier.modifyPackageName(original, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        assertTilesExactly(renamed, "renamed discord manifest")
        assertEquals(
            "the package attribute is the one string that always moves",
            CLONE_PACKAGE,
            manifestPackageName(renamed)
        )

        val before = manifestAttributes(original)
        val after = manifestAttributes(renamed)
        assertEquals("the rename must not add or drop an attribute", before.size, after.size)

        val moved = mutableListOf<String>()
        for ((index, was) in before.withIndex()) {
            val now = after[index]
            assertEquals("element $index is not the element it was", was.element, now.element)
            assertEquals(
                "attribute $index is not the attribute it was",
                was.attribute,
                now.attribute
            )
            if (was.value == now.value) continue
            moved.add("${now.element}/${now.attribute}")
        }

        // The exact set of strings a package rename is allowed to touch, spelled as the rule
        // rather than as a list: the application ID, the authorities providers are installed
        // under, and the permissions this build declares and asks for itself. A class name, an
        // action, a `<meta-data>` key, a `<queries>` package—any of those appearing here is the
        // bug this test exists for.
        val allowed = moved.all { key ->
            key == "manifest/package" ||
                key == "provider/android:authorities" ||
                key == "permission/android:name" ||
                key == "uses-permission/android:name"
        }
        assertTrue("the rename moved strings that are not the package identity: $moved", allowed)
        assertTrue("the rename moved nothing at all", moved.contains("manifest/package"))
        assertTrue(
            "the providers' authorities did not move: $moved",
            moved.count { it.endsWith("authorities") } > 0
        )
    }

    /**
     * The raw text beside a renamed string moves with it.
     *
     * An attribute spells its string in two fields, and they are two pool indices rather than one
     * value read twice: the `Res_value` the platform resolves, and the `rawValue` that holds the
     * text the tool which built the file wrote down. Moving only the first leaves the old package
     * name in the document—the platform read it out of the manifest and rejected the
     * installation with `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.discord
     * signatures do not match newer version`, while `aapt2` printed the new name, because `aapt2`
     * reads the other field. Two readers of one file disagreed until both fields moved together.
     *
     * The check runs over every attribute rather than the one that was renamed: a rename that
     * repointed some other attribute's raw text at an appended string would be just as wrong, and
     * just as invisible.
     */
    @Test
    fun movesTheRawTextOfARenamedAttributeWithIt() {
        val original = manifestOf(discordApk)
        val renamed = BinaryXmlModifier.modifyPackageName(original, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        val before = manifestAttributes(original)
        val after = manifestAttributes(renamed)
        assertEquals("the rename must not add or drop an attribute", before.size, after.size)
        assertTrue(
            "the fixture does not carry a raw text for the strings it spells, so there is " +
                "nothing here to catch; if aapt2 stopped writing them this test has to be " +
                "rewritten, not deleted",
            before.count { it.raw == it.value } > 10,
        )

        var moved = 0
        for ((index, was) in before.withIndex()) {
            val now = after[index]
            assertEquals(
                "attribute $index is not the attribute it was",
                was.attribute,
                now.attribute
            )
            if (was.value == now.value) {
                assertEquals(
                    "${now.element}/${now.attribute} moved its raw text on its own",
                    was.raw,
                    now.raw
                )
                continue
            }
            assertEquals(
                "${now.element}/${now.attribute} still says '${now.raw}' in its raw text",
                now.value,
                now.raw
            )
            moved++
        }
        assertTrue("the rename moved no string, so nothing was checked", moved > 0)
    }

    /**
     * Every class the renamed manifest names is a class the APK's DEX files contain—or was
     * already one the DEX did not contain before the rename.
     *
     * A manifest names a class in four places—`<application android:name>`, its
     * `android:appComponentFactory`, a component's `android:name` and an `<activity-alias>`'s
     * `android:targetActivity`—and the platform loads each of them by looking the name up in the
     * DEX. A rename that rewrites them, as replacing every string that begins with the package
     * does, produces names no DEX has ever held: that is exactly how this build came to fail with
     * `ClassNotFoundException: Didn't find class "com.discord.sleepy.MainApplication"`.
     *
     * The check is that the rename does not *turn* a resolvable name into an unresolvable one, so
     * the set of names the DEX cannot resolve has to come out of the rename unchanged. The
     * assertion on the name the old rule produced is what keeps the check meaningful: this is a
     * check that a rewrite fails, not one that passes because the fixture is forgiving.
     */
    @Test
    fun everyClassTheRenamedManifestNamesExistsInTheDex() {
        val original = manifestOf(discordApk)
        val renamed = BinaryXmlModifier.modifyPackageName(original, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        val classes = dexClassNames(discordApk)
        assertTrue("the DEX reader found no classes at all", classes.size > 1000)
        assertTrue(
            "the DEX reader did not find the application class",
            classes.contains("com.discord.MainApplication")
        )
        assertFalse(
            "the fixture is expected not to contain the class the old rename pointed at; if it " +
                "does, this test can no longer tell a correct rename from a rewriting one",
            classes.contains("$CLONE_PACKAGE.MainApplication")
        )

        fun unresolvable(xml: ByteArray): Set<String> =
            manifestClassNames(xml).filterNot { classes.contains(it) }.toSet()

        val before = unresolvable(original)
        val after = unresolvable(renamed)
        assertTrue(
            "the original manifest names no classes at all",
            manifestClassNames(original).size > 10
        )
        assertEquals("the rename left class names the DEX cannot resolve: $after", before, after)
        // One name this build declares was never in its DEX: a Google Play services component the
        // platform only loads when Play services is installed, and which the app itself never
        // touches. Pinning the whole set is what stops the check above from passing for the wrong
        // reason—a second name appearing here is either a broken rename or a component of the
        // same kind, and the two are worth telling apart by hand.
        assertEquals(
            "the manifest names classes the DEX does not have: $after",
            setOf("com.google.android.gms.metadata.ModuleDependencies"),
            after
        )
    }

    /**
     * The renamed manifest, put back into a copy of the APK, read by the platform's own tool.
     *
     * The chunk walk above checks the document against itself; this checks it against an
     * independent binary-XML reader—that aapt2 accepts it, and that the strings which had to move
     * moved for that reader too. The pool is rewritten by hand, and a hand-written pool that a
     * parser reads differently than this code does is invisible to every other test here.
     */
    @Test
    fun aapt2ReadsTheRenamedManifest() {
        // The external parser is a tool rather than a fixture of this repository: without it the
        // test is reported as skipped, because "did not run" must not look like "passed".
        val aapt2 = ReferenceApks.buildTool("aapt2")
        assumeTrue("aapt2 is not installed on this machine", aapt2 != null)

        val renamed = BinaryXmlModifier.modifyPackageName(
            manifestOf(discordApk),
            ORIGINAL_PACKAGE,
            CLONE_PACKAGE
        )
        assertTilesExactly(renamed, "renamed discord manifest before the aapt2 check")

        val apk = File.createTempFile("sleepy-package-check", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(renamed)
                zip.closeEntry()
            }

            val process = ProcessBuilder(
                aapt2!!.absolutePath,
                "dump",
                "xmltree",
                "--file",
                "AndroidManifest.xml",
                apk.absolutePath
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals("aapt2 could not read the renamed manifest: $output", 0, process.waitFor())

            assertTrue(
                "aapt2 did not read the new package: $output",
                output.contains("package=\"$CLONE_PACKAGE\"")
            )
            assertTrue(
                "aapt2 lost the application class: $output",
                output.contains("=\"com.discord.MainApplication\"")
            )
            assertFalse(
                "aapt2 read a class name that no DEX holds: $output",
                output.contains("$CLONE_PACKAGE.MainApplication")
            )
            assertTrue(
                "aapt2 lost the providers' authorities: $output",
                output.contains("=\"$CLONE_PACKAGE.fileprovider\"")
            )
        } finally {
            apk.delete()
        }
    }

    /**
     * Every class name the APK's DEX files define.
     *
     * The DEX's class table holds *type* indices, not string indices, so a class name is two hops
     * from the table: the class's `class_idx` selects a `type_id_item`, whose descriptor index
     * selects the string. (Reading the string with the type index directly lands on an unrelated
     * entry, which is what a DEX reader that skips the hop reports as classes with impossible
     * names.) The descriptors come out as `Lcom/discord/Foo;` and are converted to the form the
     * manifest uses.
     */
    private fun dexClassNames(apk: File): Set<String> {
        assumeTrue("${apk.path} is not on this machine", apk.exists())
        val classes = mutableSetOf<String>()
        ZipFile(apk).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.endsWith(".dex")) continue
                classes += dexClassNames(zip.getInputStream(entry).readBytes())
            }
        }
        return classes
    }

    private fun dexClassNames(dex: ByteArray): List<String> {
        if (dex.size < 112) return emptyList()
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != 0x0A786564) return emptyList() // "dex\n"
        val stringIdsSize = buf.getInt(0x38)
        val stringIdsOffset = buf.getInt(0x3c)
        val typeIdsSize = buf.getInt(0x40)
        val typeIdsOffset = buf.getInt(0x44)
        val classCount = buf.getInt(0x60)
        val classOffset = buf.getInt(0x64)
        if (classCount <= 0 || classOffset <= 0 || classOffset + classCount * 32 > dex.size) {
            return emptyList()
        }

        val names = ArrayList<String>(classCount)
        for (i in 0 until classCount) {
            val typeIndex = buf.getInt(classOffset + i * 32)
            if (typeIndex < 0 || typeIndex >= typeIdsSize) continue
            val stringIndex = buf.getInt(typeIdsOffset + typeIndex * 4)
            if (stringIndex < 0 || stringIndex >= stringIdsSize) continue
            val descriptor = dexString(buf, dex, stringIdsOffset, stringIndex)
            if (descriptor.length < 2 || descriptor[0] != 'L' || descriptor.last() != ';') continue
            names.add(descriptor.substring(1, descriptor.length - 1).replace('/', '.'))
        }
        return names
    }

    /** One string out of a DEX's string pool: a length that is a uleb128, then MUTF-8 bytes. */
    private fun dexString(buf: ByteBuffer, dex: ByteArray, idsOffset: Int, index: Int): String {
        var cursor = buf.getInt(idsOffset + index * 4)
        if (cursor <= 0 || cursor >= dex.size) return ""
        // The length is a uleb128 this reader does not need; skipping it only needs its last byte.
        while (cursor < dex.size && (dex[cursor].toInt() and 0x80) != 0) {
            cursor++
        }
        cursor++
        val start = cursor
        while (cursor < dex.size && dex[cursor].toInt() != 0) {
            cursor++
        }
        return String(dex, start, cursor - start, Charsets.UTF_8)
    }

    private companion object {
        const val ORIGINAL_PACKAGE = "com.discord"
        const val CLONE_PACKAGE = "com.discord.sleepy"
    }
}
