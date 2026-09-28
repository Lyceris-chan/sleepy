package dev.sleepy.app

import dev.sleepy.app.engine.BinaryXmlEditor
import dev.sleepy.app.engine.BinaryXmlModifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The package rename: which strings of an `AndroidManifest.xml` are the application's identity and
 * which only spell its package.
 *
 * The distinction is the whole reason [BinaryXmlModifier] exists in this shape, so the tests hold
 * both ends of it at once. The identity — the `package`, a provider's authorities, the app's own
 * permissions — has to move, because the platform keeps those unique per device and the app's own
 * code derives them from its package name. Everything else has to stay exactly where it was,
 * because something outside this document matches it: the DEX matches class names and actions, and
 * the device matches `<queries>` entries against other installations.
 *
 * The class names get the sharpest test of the lot, against the real build: every class the renamed
 * manifest names is looked up in the classes its DEX files actually contain. A rename that rewrote
 * them — which is what this code used to do — cannot pass it, and that check is written so it could
 * not have passed before either.
 */
class PackageRenameTest {

    private val discordApk =
        File("/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted/base.apk")

    @Test
    fun movesTheApplicationIdAndLeavesEveryOtherStringWhereItWas() {
        val original = manifestOf(discordApk)
        val renamed = BinaryXmlModifier.modifyPackageName(original, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        assertTilesExactly(renamed, "renamed discord manifest")
        assertEquals("the package attribute is the one string that always moves", CLONE_PACKAGE, packageName(renamed))

        val before = attributesOf(original)
        val after = attributesOf(renamed)
        assertEquals("the rename must not add or drop an attribute", before.size, after.size)

        val moved = mutableListOf<String>()
        for ((index, was) in before.withIndex()) {
            val now = after[index]
            assertEquals("element $index is not the element it was", was.element, now.element)
            assertEquals("attribute $index is not the attribute it was", was.attribute, now.attribute)
            if (was.value == now.value) continue
            moved.add("${now.element}/${now.attribute}")
        }

        // The exact set of strings a package rename is allowed to touch, spelled as the rule rather
        // than as a list: the application ID, the authorities providers are installed under, and the
        // permissions this build declares and asks for itself. A class name, an action, a
        // `<meta-data>` key, a `<queries>` package — any of those appearing here is the bug this
        // test exists for.
        val allowed = moved.all { key ->
            key == "manifest/package" ||
                key == "provider/android:authorities" ||
                key == "permission/android:name" ||
                key == "uses-permission/android:name"
        }
        assertTrue("the rename moved strings that are not the package identity: $moved", allowed)
        assertTrue("the rename moved nothing at all", moved.contains("manifest/package"))
        assertTrue("the providers' authorities did not move: $moved", moved.count { it.endsWith("authorities") } > 0)
    }

    /**
     * The raw text beside a renamed string moves with it.
     *
     * An attribute spells its string in two fields, and they are two pool indices rather than one
     * value read twice: the `Res_value` the platform resolves, and the `rawValue` that holds the text
     * the tool which built the file wrote down. Moving only the first leaves the old package name in
     * the document — the platform read it out of the manifest and refused the installation with
     * `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.discord signatures do not match newer
     * version`, while `aapt2` printed the new name, because `aapt2` reads the other field. Two
     * readers of one file disagreed until both fields moved together.
     *
     * The check runs over every attribute rather than the one that was renamed: a rename that
     * repointed some other attribute's raw text at an appended string would be just as wrong, and
     * just as invisible.
     */
    @Test
    fun movesTheRawTextOfARenamedAttributeWithIt() {
        val original = manifestOf(discordApk)
        val renamed = BinaryXmlModifier.modifyPackageName(original, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        val before = attributesOf(original)
        val after = attributesOf(renamed)
        assertEquals("the rename must not add or drop an attribute", before.size, after.size)
        assertTrue(
            "the fixture does not carry a raw text for the strings it spells, so there is nothing here " +
                "to catch; if aapt2 stopped writing them this test has to be rewritten, not deleted",
            before.count { it.raw == it.value } > 10,
        )

        var moved = 0
        for ((index, was) in before.withIndex()) {
            val now = after[index]
            assertEquals("attribute $index is not the attribute it was", was.attribute, now.attribute)
            if (was.value == now.value) {
                assertEquals("${now.element}/${now.attribute} moved its raw text on its own", was.raw, now.raw)
                continue
            }
            assertEquals("${now.element}/${now.attribute} still says '${now.raw}' in its raw text", now.value, now.raw)
            moved++
        }
        assertTrue("the rename moved no string, so nothing was checked", moved > 0)
    }

    /**
     * Every class the renamed manifest names is a class the APK's DEX files contain — or was
     * already one the DEX did not contain before the rename.
     *
     * A manifest names a class in four places — `<application android:name>`, its
     * `android:appComponentFactory`, a component's `android:name` and an `<activity-alias>`'s
     * `android:targetActivity` — and the platform loads each of them by looking the name up in the
     * DEX. A rename that rewrites them, as replacing every string that begins with the package
     * does, produces names no DEX has ever held: that is exactly how this build came to fail with
     * `ClassNotFoundException: Didn't find class "com.discord.sleepy.MainApplication"`.
     *
     * The check is that the rename does not *turn* a resolvable name into an unresolvable one, so
     * the set of names the DEX cannot resolve has to come out of the rename unchanged. The
     * assertion on the name the old rule produced is what keeps it honest: this is a check that a
     * rewrite fails, not one that passes because the fixture is forgiving.
     */
    @Test
    fun everyClassTheRenamedManifestNamesExistsInTheDex() {
        val original = manifestOf(discordApk)
        val renamed = BinaryXmlModifier.modifyPackageName(original, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        val classes = dexClassNames(discordApk)
        assertTrue("the DEX reader found no classes at all", classes.size > 1000)
        assertTrue("the DEX reader did not find the application class", classes.contains("com.discord.MainApplication"))
        assertFalse(
            "the fixture is expected not to contain the class the old rename pointed at; if it does, " +
                "this test can no longer tell a correct rename from a rewriting one",
            classes.contains("$CLONE_PACKAGE.MainApplication")
        )

        fun unresolvable(xml: ByteArray): Set<String> = classNamesOf(xml).filterNot { classes.contains(it) }.toSet()

        val before = unresolvable(original)
        val after = unresolvable(renamed)
        assertTrue("the original manifest names no classes at all", classNamesOf(original).size > 10)
        assertEquals("the rename left class names the DEX cannot resolve: $after", before, after)
        // One name this build declares was never in its DEX: a Google Play services component the
        // platform only loads when Play services is installed, and which the app itself never
        // touches. Pinning the whole set is what stops the check above from passing for the wrong
        // reason — a second name appearing here is either a broken rename or a component of the
        // same kind, and the two are worth telling apart by hand.
        assertEquals(
            "the manifest names classes the DEX does not have: $after",
            setOf("com.google.android.gms.metadata.ModuleDependencies"),
            after
        )
    }

    /**
     * The trap the string pool sets: one entry, two attributes, two different answers.
     *
     * `aapt2` writes one pool entry per distinct string, so `<manifest package>` and a
     * `<queries><package android:name>` that spell the same package share an entry. A rename that
     * edits the entry's text cannot move the first without moving the second — and the second is a
     * question about *another* installation, which must go on asking about `com.discord`.
     */
    @Test
    fun movesAPoolEntryThatIsSharedWithAStringThatMustNotMove() {
        val xml = documentOf(
            Element(
                name = "manifest",
                attributes = listOf(packageAttribute(ORIGINAL_PACKAGE)),
                children = listOf(
                    Element(
                        name = "queries",
                        children = listOf(
                            Element("package", listOf(androidName(ORIGINAL_PACKAGE))),
                            Element("package", listOf(androidName("$ORIGINAL_PACKAGE.debug")))
                        )
                    )
                )
            )
        )
        assertEquals("the fixture must share one pool entry", ORIGINAL_PACKAGE, packageName(xml))

        val renamed = BinaryXmlModifier.modifyPackageName(xml, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        assertEquals(CLONE_PACKAGE, packageName(renamed))
        assertEquals(
            listOf(ORIGINAL_PACKAGE, "$ORIGINAL_PACKAGE.debug"),
            valuesOf(renamed, "package", BinaryXmlEditor.ATTR_NAME)
        )
        // Appending an entry rather than rewriting one is what makes that possible, so the pool has
        // to have grown by exactly the one new string.
        assertEquals(poolSize(xml) + 1, poolSize(renamed))
    }

    /** A class name is a class name: the four places a manifest holds one, in all three forms. */
    @Test
    fun writesOutAClassNameThatWasRelativeToThePackage() {
        val xml = documentOf(
            Element(
                name = "manifest",
                attributes = listOf(packageAttribute(ORIGINAL_PACKAGE)),
                children = listOf(
                    Element(
                        name = "application",
                        attributes = listOf(
                            androidName(ORIGINAL_PACKAGE + ".MainApplication"),
                            Attribute(BinaryXmlEditor.ATTR_APP_COMPONENT_FACTORY, "appComponentFactory", "$ORIGINAL_PACKAGE.TTIComponentFactory")
                        ),
                        children = listOf(
                            Element("activity", listOf(androidName(".main.MainActivity"))),
                            Element("activity", listOf(androidName("MainActivity"))),
                            Element("activity", listOf(androidName("com.example.Other"))),
                            Element(
                                name = "activity-alias",
                                attributes = listOf(
                                    androidName(".main.MainAlias"),
                                    Attribute(BinaryXmlEditor.ATTR_TARGET_ACTIVITY, "targetActivity", ".main.MainActivity")
                                )
                            )
                        )
                    )
                )
            )
        )

        val renamed = BinaryXmlModifier.modifyPackageName(xml, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        assertEquals(
            setOf(
                "$ORIGINAL_PACKAGE.MainApplication",
                "$ORIGINAL_PACKAGE.TTIComponentFactory",
                "$ORIGINAL_PACKAGE.main.MainActivity",
                "$ORIGINAL_PACKAGE.MainActivity",
                "com.example.Other"
            ),
            classNamesOf(renamed)
        )
        // An `<activity-alias>` is named after a component rather than a class, and is resolved
        // against the package like any other component name — so it is written out in full too, or
        // the alias would come to mean `com.discord.sleepy.main.MainAlias`.
        assertEquals(
            listOf("$ORIGINAL_PACKAGE.main.MainAlias"),
            valuesOf(renamed, "activity-alias", BinaryXmlEditor.ATTR_NAME)
        )
        assertEquals(
            listOf("$ORIGINAL_PACKAGE.main.MainActivity"),
            valuesOf(renamed, "activity-alias", BinaryXmlEditor.ATTR_TARGET_ACTIVITY)
        )
        assertEquals(
            listOf("$ORIGINAL_PACKAGE.TTIComponentFactory"),
            valuesOf(renamed, "application", BinaryXmlEditor.ATTR_APP_COMPONENT_FACTORY)
        )
    }

    /**
     * The authorities move because the platform refuses to install two applications that declare
     * one, and because the app asks for its own providers by a name it builds from its package.
     *
     * An authority that only looks similar — `com.discordX.provider`, or one belonging to another
     * application — is not this build's identity and is left alone.
     */
    @Test
    fun movesAnAuthorityOnlyWhenItIsNamedAfterThePackage() {
        val xml = documentOf(
            Element(
                name = "manifest",
                attributes = listOf(packageAttribute(ORIGINAL_PACKAGE)),
                children = listOf(
                    Element("provider", listOf(authorities(ORIGINAL_PACKAGE))),
                    Element("provider", listOf(authorities("$ORIGINAL_PACKAGE.fileprovider"))),
                    Element("provider", listOf(authorities("${ORIGINAL_PACKAGE}X.provider"))),
                    Element("provider", listOf(authorities("com.example.provider")))
                )
            )
        )

        val renamed = BinaryXmlModifier.modifyPackageName(xml, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        assertEquals(
            listOf("$CLONE_PACKAGE", "$CLONE_PACKAGE.fileprovider", "${ORIGINAL_PACKAGE}X.provider", "com.example.provider"),
            valuesOf(renamed, "provider", BinaryXmlEditor.ATTR_AUTHORITIES)
        )
    }

    /**
     * A permission this build declares is its own identity and moves; a request for it moves with
     * it, because a request left behind would ask the device for a permission this installation no
     * longer declares.
     *
     * A permission declared by *another* application — the official build's, whose name begins with
     * the same package — is not this build's to move. Requests are matched against this manifest's
     * own declarations, not against the package prefix.
     */
    @Test
    fun movesAPermissionRequestOnlyWhenThisBuildDeclaresIt() {
        val own = "$ORIGINAL_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val theirs = "$ORIGINAL_PACKAGE.permission.SOMETHING_ELSE"
        val xml = documentOf(
            Element(
                name = "manifest",
                attributes = listOf(packageAttribute(ORIGINAL_PACKAGE)),
                children = listOf(
                    Element("permission", listOf(androidName(own))),
                    Element("uses-permission", listOf(androidName(own))),
                    Element("uses-permission", listOf(androidName(theirs))),
                    Element("uses-permission", listOf(androidName("android.permission.INTERNET")))
                )
            )
        )

        val renamed = BinaryXmlModifier.modifyPackageName(xml, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        assertEquals(
            listOf("$CLONE_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            valuesOf(renamed, "permission", BinaryXmlEditor.ATTR_NAME)
        )
        assertEquals(
            listOf(
                "$CLONE_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                theirs,
                "android.permission.INTERNET"
            ),
            valuesOf(renamed, "uses-permission", BinaryXmlEditor.ATTR_NAME)
        )
    }

    /**
     * The strings that are not identity, in the shapes the real manifest holds them: a component
     * class, a `<meta-data>` key and value, an action name, and the process name.
     *
     * `android:process=":phoenix"` is relative like a class name, and unlike one it needs nothing
     * done to it: the platform prefixes a process name with the *current* package, which is the new
     * one by the time it is read.
     */
    @Test
    fun leavesTheStringsThatSomethingElseMatchesAlone() {
        val xml = documentOf(
            Element(
                name = "manifest",
                attributes = listOf(packageAttribute(ORIGINAL_PACKAGE)),
                children = listOf(
                    Element(
                        name = "application",
                        attributes = listOf(androidName("$ORIGINAL_PACKAGE.MainApplication")),
                        children = listOf(
                            Element(
                                name = "meta-data",
                                attributes = listOf(
                                    androidName("$ORIGINAL_PACKAGE.features.FLAG"),
                                    Attribute(ATTR_VALUE, "value", ORIGINAL_PACKAGE)
                                )
                            ),
                            Element(
                                name = "activity",
                                attributes = listOf(
                                    Attribute(ATTR_PROCESS, "process", ":phoenix"),
                                    Attribute(ATTR_TASK_AFFINITY, "taskAffinity", "$ORIGINAL_PACKAGE.share")
                                ),
                                children = listOf(
                                    Element(
                                        name = "intent-filter",
                                        children = listOf(
                                            Element("action", listOf(androidName("$ORIGINAL_PACKAGE.intent.action.CONNECT")))
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val renamed = BinaryXmlModifier.modifyPackageName(xml, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        assertEquals(
            listOf("$ORIGINAL_PACKAGE.features.FLAG"),
            valuesOf(renamed, "meta-data", BinaryXmlEditor.ATTR_NAME)
        )
        assertEquals(listOf(ORIGINAL_PACKAGE), valuesOf(renamed, "meta-data", ATTR_VALUE))
        assertEquals(
            listOf("$ORIGINAL_PACKAGE.intent.action.CONNECT"),
            valuesOf(renamed, "action", BinaryXmlEditor.ATTR_NAME)
        )
        assertEquals(listOf(":phoenix"), valuesOf(renamed, "activity", ATTR_PROCESS))
        assertEquals(listOf("$ORIGINAL_PACKAGE.share"), valuesOf(renamed, "activity", ATTR_TASK_AFFINITY))
        assertEquals(listOf("$ORIGINAL_PACKAGE.MainApplication"), valuesOf(renamed, "application", BinaryXmlEditor.ATTR_NAME))
        assertEquals(listOf(CLONE_PACKAGE), valuesOf(renamed, "manifest", 0))
    }

    /**
     * A document of the wrong kind, or one that does not decode, comes back untouched: a manifest
     * that still parses is worth more to the caller than a half-renamed one.
     */
    @Test
    fun leavesADocumentItCannotReadAlone() {
        val notXml = "<?xml version=\"1.0\"?><manifest package=\"$ORIGINAL_PACKAGE\"/>".toByteArray()
        assertUnchanged(notXml, BinaryXmlModifier.modifyPackageName(notXml, ORIGINAL_PACKAGE, CLONE_PACKAGE))

        val xml = documentOf(Element("manifest", listOf(packageAttribute(ORIGINAL_PACKAGE))))
        assertUnchanged(xml.copyOfRange(0, 12), BinaryXmlModifier.modifyPackageName(xml.copyOfRange(0, 12), ORIGINAL_PACKAGE, CLONE_PACKAGE))

        val empty = ByteArray(0)
        assertUnchanged(empty, BinaryXmlModifier.modifyPackageName(empty, ORIGINAL_PACKAGE, CLONE_PACKAGE))

        // A rename to the name that is already there is not a rename.
        assertUnchanged(xml, BinaryXmlModifier.modifyPackageName(xml, ORIGINAL_PACKAGE, ORIGINAL_PACKAGE))
    }

    /**
     * The renamed manifest, put back into a copy of the APK, read by the platform's own tool.
     *
     * The chunk walk above proves the document is self-consistent; this proves something stronger —
     * that an independent binary-XML reader accepts it, and that the strings which had to move
     * moved for it too. The pool was rewritten by hand, and a hand-written pool that the parser
     * reads differently than this code does would be invisible to every other test here.
     */
    @Test
    fun aapt2ReadsTheRenamedManifest() {
        // The external parser is a tool rather than a fixture of this repository: without it the
        // test is reported as skipped, because "did not run" must not look like "passed".
        val aapt2 = aapt2Candidates.firstOrNull { it.canExecute() }
        assumeTrue("aapt2 is not installed on this machine", aapt2 != null)

        val renamed = BinaryXmlModifier.modifyPackageName(manifestOf(discordApk), ORIGINAL_PACKAGE, CLONE_PACKAGE)
        assertTilesExactly(renamed, "renamed discord manifest before the aapt2 check")

        val apk = File.createTempFile("sleepy-package-check", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(renamed)
                zip.closeEntry()
            }

            val process = ProcessBuilder(
                aapt2!!.absolutePath, "dump", "xmltree", "--file", "AndroidManifest.xml", apk.absolutePath
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals("aapt2 could not read the renamed manifest: $output", 0, process.waitFor())

            assertTrue("aapt2 did not read the new package: $output", output.contains("package=\"$CLONE_PACKAGE\""))
            assertTrue("aapt2 lost the application class: $output", output.contains("=\"com.discord.MainApplication\""))
            assertFalse("aapt2 read a class name that no DEX holds: $output", output.contains("$CLONE_PACKAGE.MainApplication"))
            assertTrue("aapt2 lost the providers' authorities: $output", output.contains("=\"$CLONE_PACKAGE.fileprovider\""))
        } finally {
            apk.delete()
        }
    }

    // --- reading a document ---------------------------------------------------------------

    /**
     * A manifest from a fixture APK.
     *
     * The fixtures are build outputs that live outside the repository, so a machine without one
     * reports the test as skipped — an early `return` would have reported it as a pass instead.
     */
    private fun manifestOf(apk: File): ByteArray {
        assumeTrue("${apk.path} is not on this machine", apk.exists())
        return ZipFile(apk).use { zip ->
            val entry = requireNotNull(zip.getEntry("AndroidManifest.xml")) {
                "${apk.name} has no AndroidManifest.xml"
            }
            zip.getInputStream(entry).readBytes()
        }
    }

    /**
     * One attribute of one element: the element it sits on, what it spells, and the raw text it
     * spells beside that.
     *
     * [raw] is the attribute's other copy of its string — a pool index of its own, not a view of
     * [value] — or `""` where the attribute carries none. A rename that moved one and not the other
     * would leave the old package name in the file, which is the failure this field exists to catch.
     */
    private data class ManifestAttribute(
        val element: String,
        val attribute: String,
        val value: String,
        val raw: String,
    )

    /**
     * Every attribute of the document, in document order, with the element it sits on.
     *
     * Attributes are told apart by resource ID where they have one and by their local name where
     * they do not, which is the same distinction the code under test makes — `package` has no
     * resource ID, so two different attributes called `name` and `package` cannot be confused for
     * each other. A value that is not a string is reported by its bytes, because a rename that
     * repointed one would be a rename that repointed something it never read.
     */
    private fun attributesOf(xml: ByteArray): List<ManifestAttribute> {
        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        val resourceIds = BinaryXmlEditor.readResourceMap(buf, xml)
        val strings = BinaryXmlEditor.readStringPool(buf, xml)
        val attributes = mutableListOf<ManifestAttribute>()
        val stack = ArrayDeque<String>()

        var offset = buf.getShort(2).toInt() and 0xFFFF
        while (offset + 8 <= xml.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < 8 || offset + chunkSize > xml.size) break
            if (type == 0x0102) {
                val element = strings[buf.getInt(offset + 20)]
                stack.addLast(element)
                val attributeStart = buf.getShort(offset + 24).toInt() and 0xFFFF
                val attributeCount = buf.getShort(offset + 28).toInt() and 0xFFFF
                for (i in 0 until attributeCount) {
                    val start = offset + 16 + attributeStart + i * 20
                    val nameIndex = buf.getInt(start + 4)
                    val id = if (nameIndex < resourceIds.size) resourceIds[nameIndex] else 0
                    val name = strings[nameIndex]
                    val dataType = buf.get(start + 15).toInt() and 0xFF
                    val data = buf.getInt(start + 16)
                    val value = if (dataType == 0x03 && data < strings.size) {
                        strings[data]
                    } else {
                        "type=0x%02x data=0x%08x".format(dataType, data)
                    }
                    val rawIndex = buf.getInt(start + 8)
                    val raw = if (rawIndex in strings.indices) strings[rawIndex] else ""
                    attributes.add(ManifestAttribute(element, if (id != 0) "android:$name" else name, value, raw))
                }
            }
            if (type == 0x0103) stack.removeLast()
            offset += chunkSize
        }
        return attributes
    }

    /** The values of [element]'s [attributeId] attributes, in document order. */
    private fun valuesOf(xml: ByteArray, element: String, attributeId: Int): List<String> {
        val id = if (attributeId == 0) "package" else "android:${nameOfAttribute(attributeId)}"
        return attributesOf(xml).filter { it.element == element && it.attribute == id }.map { it.value }
    }

    /** The name an `android:` attribute of the manifest is spelled with. */
    private fun nameOfAttribute(attributeId: Int): String = when (attributeId) {
        BinaryXmlEditor.ATTR_NAME -> "name"
        BinaryXmlEditor.ATTR_AUTHORITIES -> "authorities"
        BinaryXmlEditor.ATTR_APP_COMPONENT_FACTORY -> "appComponentFactory"
        BinaryXmlEditor.ATTR_TARGET_ACTIVITY -> "targetActivity"
        ATTR_VALUE -> "value"
        ATTR_PROCESS -> "process"
        ATTR_TASK_AFFINITY -> "taskAffinity"
        else -> error("no name known for attribute 0x%08x".format(attributeId))
    }

    private fun packageName(xml: ByteArray): String =
        attributesOf(xml).first { it.element == "manifest" && it.attribute == "package" }.value

    /**
     * The class names the manifest names: the application class, its component factory, every
     * component's class, and what an `<activity-alias>` resolves to.
     *
     * These are the strings the platform looks up in the DEX, which is the lookup a rename must not
     * break. An `<activity-alias>`'s own `android:name` is deliberately not among them: the platform
     * never loads an alias as a class — it resolves the alias to its `targetActivity` and loads
     * that — so an alias is free to be named after something that was never compiled. Discord's
     * launcher entry is one: `com.discord.main.MainDefault` is an alias for
     * `com.discord.main.MainActivity`, and no class of the former name exists.
     */
    private fun classNamesOf(xml: ByteArray): Set<String> {
        val components = setOf("application", "activity", "service", "receiver", "provider", "instrumentation")
        val names = attributesOf(xml)
            .filter {
                (it.element in components && (it.attribute == "android:name" || it.attribute == "android:appComponentFactory")) ||
                    (it.element == "activity-alias" && it.attribute == "android:targetActivity")
            }
            .map { it.value }
            .toSet()
        return names
    }

    /** The number of entries in the document's string pool. */
    private fun poolSize(xml: ByteArray): Int {
        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        var offset = buf.getShort(2).toInt() and 0xFFFF
        while (offset + 8 <= xml.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize < 8 || offset + chunkSize > xml.size) break
            if (type == 0x0001) return buf.getInt(offset + 8)
            offset += chunkSize
        }
        return -1
    }

    /**
     * Walks the chunk tree and requires it to tile the document exactly.
     *
     * This is the same walk the platform performs: a chunk that overruns the file, or a size
     * smaller than a header, is a document the platform would refuse to read. It is the only check
     * that covers the pool chunk the rename writes by hand.
     */
    private fun assertTilesExactly(xml: ByteArray, label: String) {
        val buf = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("$label: root chunk is not binary XML", 0x0003, buf.getShort(0).toInt() and 0xFFFF)
        assertEquals("$label: root size does not match the file", xml.size, buf.getInt(4))

        var offset = buf.getShort(2).toInt() and 0xFFFF
        var starts = 0
        var ends = 0
        while (offset + 8 <= xml.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            assertTrue("$label: chunk at $offset has size $chunkSize", chunkSize >= 8)
            assertTrue("$label: chunk at $offset overruns the file", offset + chunkSize <= xml.size)
            if (type == 0x0102) starts++
            if (type == 0x0103) ends++
            offset += chunkSize
        }
        assertEquals("$label: the walk must end exactly at the end of the file", xml.size, offset)
        assertEquals("$label: every element must still be closed", starts, ends)
        assertTrue("$label: no elements were walked", starts > 0)
    }

    private fun assertUnchanged(expected: ByteArray, actual: ByteArray) {
        assertTrue("the document was modified: ${actual.size} bytes against ${expected.size}", expected.contentEquals(actual))
    }

    // --- the classes inside the APK -------------------------------------------------------

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
        if (classCount <= 0 || classOffset <= 0 || classOffset + classCount * 32 > dex.size) return emptyList()

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
        while (cursor < dex.size && (dex[cursor].toInt() and 0x80) != 0) cursor++
        cursor++
        val start = cursor
        while (cursor < dex.size && dex[cursor].toInt() != 0) cursor++
        return String(dex, start, cursor - start, Charsets.UTF_8)
    }

    // --- synthetic binary XML -------------------------------------------------------------

    /** One attribute of a synthetic element: its resource ID (0 for a non-framework one), and what it spells. */
    private data class Attribute(val id: Int, val name: String, val value: String)

    /** One element of a synthetic document. */
    private data class Element(
        val name: String,
        val attributes: List<Attribute> = emptyList(),
        val children: List<Element> = emptyList()
    )

    private fun androidName(value: String) = Attribute(BinaryXmlEditor.ATTR_NAME, "name", value)

    private fun authorities(value: String) = Attribute(BinaryXmlEditor.ATTR_AUTHORITIES, "authorities", value)

    private fun packageAttribute(value: String) = Attribute(0, "package", value)

    private fun documentOf(root: Element): ByteArray = syntheticDocument(root)

    /**
     * A minimal but real binary-XML document: a UTF-8 string pool, a resource map that resolves the
     * framework attributes used, and the given element tree under a root element.
     *
     * The resource map is what makes the attribute matcher work at all — an attribute's `name`
     * field is a string-pool index, and only the map turns it into `0x01010003`. It is also what
     * makes this document a fair test of the real path rather than of a shortcut. Attribute names
     * are pooled before anything else so that the map, which is indexed by pool position, can be
     * built over a known range; index 0 is a string no attribute resolves to, so the map is not
     * trivially aligned with the pool.
     *
     * Identical strings share a pool entry, as `aapt2` writes them — which is what makes the
     * sharing case above a real one rather than a staged one.
     */
    private fun syntheticDocument(root: Element): ByteArray {
        val names = mutableListOf<String>()
        fun pool(name: String): Int {
            val existing = names.indexOf(name)
            if (existing >= 0) return existing
            names.add(name)
            return names.size - 1
        }

        pool("unmapped")
        val attributeIds = mutableMapOf<Int, Int>()
        fun poolAttributes(element: Element) {
            for (attribute in element.attributes) {
                val index = pool(attribute.name)
                if (attribute.id != 0) attributeIds[index] = attribute.id
            }
            element.children.forEach(::poolAttributes)
        }
        poolAttributes(root)

        fun poolTree(element: Element) {
            pool(element.name)
            element.attributes.forEach { pool(it.value) }
            element.children.forEach(::poolTree)
        }
        poolTree(root)

        val document = ByteArrayOutputStream()
        document.write(ByteArray(8)) // root header, written once the size is known

        // String pool: headerSize 28, UTF-8 strings, no styles.
        val data = ByteArrayOutputStream()
        val offsets = IntArray(names.size)
        for ((i, name) in names.withIndex()) {
            offsets[i] = data.size()
            val bytes = name.toByteArray(Charsets.UTF_8)
            data.write(bytes.size)
            data.write(bytes.size)
            data.write(bytes)
            data.write(0)
        }
        val poolChunkSize = 28 + names.size * 4 + data.size()
        val poolChunk = ByteBuffer.allocate(poolChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        poolChunk.putShort(0x0001)
        poolChunk.putShort(28)
        poolChunk.putInt(poolChunkSize)
        poolChunk.putInt(names.size)
        poolChunk.putInt(0)      // styleCount
        poolChunk.putInt(0x100)  // UTF8_FLAG
        poolChunk.putInt(28 + names.size * 4)
        poolChunk.putInt(0)      // stylesStart
        offsets.forEach { poolChunk.putInt(it) }
        poolChunk.put(data.toByteArray())
        document.write(poolChunk.array())

        // Resource map: one entry per pool index, carrying the resource ID of the attribute name
        // that sits there — 0 for a string no attribute resolves to.
        val map = ByteBuffer.allocate(8 + names.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        map.putShort(0x0180.toShort())
        map.putShort(8)
        map.putInt(8 + names.size * 4)
        for (i in names.indices) map.putInt(attributeIds[i] ?: 0)
        document.write(map.array())

        writeElement(document, root, ::pool)

        val body = document.toByteArray()
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0x0003.toShort())
        header.putShort(8)
        header.putInt(body.size)
        System.arraycopy(header.array(), 0, body, 0, 8)
        return body
    }

    /** Writes one element, each closed around whatever it contains. */
    private fun writeElement(out: ByteArrayOutputStream, element: Element, pool: (String) -> Int) {
        out.write(startTag(pool(element.name), element.attributes.map { pool(it.name) to pool(it.value) }))
        element.children.forEach { writeElement(out, it, pool) }
        out.write(endTag(pool(element.name)))
    }

    private fun startTag(nameIndex: Int, attributes: List<Pair<Int, Int>>): ByteArray {
        val attributeStart = 20
        val size = 16 + attributeStart + attributes.size * 20
        val chunk = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        chunk.putShort(0x0102)
        chunk.putShort(16)
        chunk.putInt(size)
        chunk.putInt(1)                  // lineNumber
        chunk.putInt(0xFFFFFFFF.toInt()) // comment
        chunk.putInt(-1)                 // ns
        chunk.putInt(nameIndex)
        chunk.putShort(attributeStart.toShort())
        chunk.putShort(20)               // attributeSize
        chunk.putShort(attributes.size.toShort())
        chunk.putShort(0)                // idIndex
        chunk.putShort(0)                // classIndex
        chunk.putShort(0)                // styleIndex
        for ((attributeNameIndex, valueIndex) in attributes) {
            chunk.putInt(0)              // ns: the android namespace is resolved by resource map
            chunk.putInt(attributeNameIndex)
            chunk.putInt(0xFFFFFFFF.toInt()) // rawValue
            chunk.putShort(8)            // Res_value.size
            chunk.put(0)                 // res0
            chunk.put(0x03)              // TYPE_STRING
            chunk.putInt(valueIndex)
        }
        return chunk.array()
    }

    private fun endTag(nameIndex: Int): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0x0103)
        putShort(16)
        putInt(24)
        putInt(1)                  // lineNumber
        putInt(0xFFFFFFFF.toInt()) // comment
        putInt(-1)                 // ns
        putInt(nameIndex)
    }.array()

    private companion object {
        const val ORIGINAL_PACKAGE = "com.discord"
        const val CLONE_PACKAGE = "com.discord.sleepy"
        const val ATTR_VALUE = 0x01010024
        const val ATTR_PROCESS = 0x01010011
        const val ATTR_TASK_AFFINITY = 0x01010012

        /** Where `aapt2` lives when the Android SDK build tools are installed beside this checkout. */
        val aapt2Candidates = listOf(
            File("/home/sleepy/portable-tools/android-sdk/build-tools/36.0.0/aapt2"),
            File(System.getenv("ANDROID_HOME") ?: "/nonexistent", "build-tools/36.0.0/aapt2")
        )
    }
}
