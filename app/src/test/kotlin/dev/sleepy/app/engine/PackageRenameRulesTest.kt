package dev.sleepy.app.engine

import dev.sleepy.app.testing.ATTR_PROCESS
import dev.sleepy.app.testing.ATTR_TASK_AFFINITY
import dev.sleepy.app.testing.ATTR_VALUE
import dev.sleepy.app.testing.XmlAttribute
import dev.sleepy.app.testing.XmlElement
import dev.sleepy.app.testing.androidName
import dev.sleepy.app.testing.attributeValues
import dev.sleepy.app.testing.authorities
import dev.sleepy.app.testing.documentOf
import dev.sleepy.app.testing.manifestClassNames
import dev.sleepy.app.testing.manifestPackageName
import dev.sleepy.app.testing.packageAttribute
import dev.sleepy.app.testing.stringPoolSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which strings a package rename moves, and which belong to something else.
 *
 * Each test builds the smallest manifest that isolates one rule: a pool entry shared with a
 * string that must not move, a class name that was relative to the package, an authority or a
 * permission the package owns, the strings that only look like it, and a document the editor
 * cannot read at all.
 */
class PackageRenameRulesTest {

    /**
     * The string pool's sharing rule: one entry, two attributes, two different answers.
     *
     * `aapt2` writes one pool entry per distinct string, so `<manifest package>` and a
     * `<queries><package android:name>` that spell the same package share an entry. A rename that
     * edits the entry's text cannot move the first without moving the second—and the second is a
     * question about *another* installation, whose value must remain `com.discord`.
     */
    @Test
    fun movesAPoolEntryThatIsSharedWithAStringThatMustNotMove() {
        val xml = documentOf(
            XmlElement(
                name = "manifest",
                attributes = listOf(packageAttribute(ORIGINAL_PACKAGE)),
                children = listOf(
                    XmlElement(
                        name = "queries",
                        children = listOf(
                            XmlElement("package", listOf(androidName(ORIGINAL_PACKAGE))),
                            XmlElement("package", listOf(androidName("$ORIGINAL_PACKAGE.debug")))
                        )
                    )
                )
            )
        )
        assertEquals(
            "the fixture must share one pool entry",
            ORIGINAL_PACKAGE,
            manifestPackageName(xml)
        )

        val renamed = BinaryXmlModifier.modifyPackageName(xml, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        assertEquals(CLONE_PACKAGE, manifestPackageName(renamed))
        assertEquals(
            listOf(ORIGINAL_PACKAGE, "$ORIGINAL_PACKAGE.debug"),
            attributeValues(renamed, "package", BinaryXmlEditor.ATTR_NAME)
        )
        // Appending an entry rather than rewriting one is what makes that possible, so the pool has
        // to have grown by exactly the one new string.
        assertEquals(stringPoolSize(xml) + 1, stringPoolSize(renamed))
    }

    /** The four places a manifest holds a class name, across the three forms one can take. */
    @Test
    fun writesOutAClassNameThatWasRelativeToThePackage() {
        val xml = documentOf(
            XmlElement(
                name = "manifest",
                attributes = listOf(packageAttribute(ORIGINAL_PACKAGE)),
                children = listOf(
                    XmlElement(
                        name = "application",
                        attributes = listOf(
                            androidName(ORIGINAL_PACKAGE + ".MainApplication"),
                            XmlAttribute(
                                BinaryXmlEditor.ATTR_APP_COMPONENT_FACTORY,
                                "appComponentFactory",
                                "$ORIGINAL_PACKAGE.TTIComponentFactory"
                            )
                        ),
                        children = listOf(
                            XmlElement("activity", listOf(androidName(".main.MainActivity"))),
                            XmlElement("activity", listOf(androidName("MainActivity"))),
                            XmlElement("activity", listOf(androidName("com.example.Other"))),
                            XmlElement(
                                name = "activity-alias",
                                attributes = listOf(
                                    androidName(".main.MainAlias"),
                                    XmlAttribute(
                                        BinaryXmlEditor.ATTR_TARGET_ACTIVITY,
                                        "targetActivity",
                                        ".main.MainActivity"
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
            setOf(
                "$ORIGINAL_PACKAGE.MainApplication",
                "$ORIGINAL_PACKAGE.TTIComponentFactory",
                "$ORIGINAL_PACKAGE.main.MainActivity",
                "$ORIGINAL_PACKAGE.MainActivity",
                "com.example.Other"
            ),
            manifestClassNames(renamed)
        )
        // An `<activity-alias>` is named after a component rather than a class, and is resolved
        // against the package like any other component name—so it is written out in full too, or
        // the alias would come to mean `com.discord.sleepy.main.MainAlias`.
        assertEquals(
            listOf("$ORIGINAL_PACKAGE.main.MainAlias"),
            attributeValues(renamed, "activity-alias", BinaryXmlEditor.ATTR_NAME)
        )
        assertEquals(
            listOf("$ORIGINAL_PACKAGE.main.MainActivity"),
            attributeValues(renamed, "activity-alias", BinaryXmlEditor.ATTR_TARGET_ACTIVITY)
        )
        assertEquals(
            listOf("$ORIGINAL_PACKAGE.TTIComponentFactory"),
            attributeValues(renamed, "application", BinaryXmlEditor.ATTR_APP_COMPONENT_FACTORY)
        )
    }

    /**
     * The authorities move because the platform rejects two applications that declare one, and
     * because the app asks for its own providers by a name it builds from its package.
     *
     * An authority that only looks similar—`com.discordX.provider`, or one belonging to another
     * application—is not this build's identity and is left alone.
     */
    @Test
    fun movesAnAuthorityOnlyWhenItIsNamedAfterThePackage() {
        val xml = documentOf(
            XmlElement(
                name = "manifest",
                attributes = listOf(packageAttribute(ORIGINAL_PACKAGE)),
                children = listOf(
                    XmlElement("provider", listOf(authorities(ORIGINAL_PACKAGE))),
                    XmlElement("provider", listOf(authorities("$ORIGINAL_PACKAGE.fileprovider"))),
                    XmlElement("provider", listOf(authorities("${ORIGINAL_PACKAGE}X.provider"))),
                    XmlElement("provider", listOf(authorities("com.example.provider")))
                )
            )
        )

        val renamed = BinaryXmlModifier.modifyPackageName(xml, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        assertEquals(
            listOf(
                "$CLONE_PACKAGE",
                "$CLONE_PACKAGE.fileprovider",
                "${ORIGINAL_PACKAGE}X.provider",
                "com.example.provider"
            ),
            attributeValues(renamed, "provider", BinaryXmlEditor.ATTR_AUTHORITIES)
        )
    }

    /**
     * A permission this build declares is its own identity and moves; a request for it moves with
     * it, because a request left behind asks the device for a permission this installation no
     * longer declares.
     *
     * A permission declared by *another* application—the official build's, whose name begins with
     * the same package—is not this build's to move. Requests are matched against this manifest's
     * own declarations, not against the package prefix.
     */
    @Test
    fun movesAPermissionRequestOnlyWhenThisBuildDeclaresIt() {
        val own = "$ORIGINAL_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val theirs = "$ORIGINAL_PACKAGE.permission.SOMETHING_ELSE"
        val xml = documentOf(
            XmlElement(
                name = "manifest",
                attributes = listOf(packageAttribute(ORIGINAL_PACKAGE)),
                children = listOf(
                    XmlElement("permission", listOf(androidName(own))),
                    XmlElement("uses-permission", listOf(androidName(own))),
                    XmlElement("uses-permission", listOf(androidName(theirs))),
                    XmlElement(
                        "uses-permission",
                        listOf(androidName("android.permission.INTERNET"))
                    )
                )
            )
        )

        val renamed = BinaryXmlModifier.modifyPackageName(xml, ORIGINAL_PACKAGE, CLONE_PACKAGE)

        assertEquals(
            listOf("$CLONE_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            attributeValues(renamed, "permission", BinaryXmlEditor.ATTR_NAME)
        )
        assertEquals(
            listOf(
                "$CLONE_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                theirs,
                "android.permission.INTERNET"
            ),
            attributeValues(renamed, "uses-permission", BinaryXmlEditor.ATTR_NAME)
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
            XmlElement(
                name = "manifest",
                attributes = listOf(packageAttribute(ORIGINAL_PACKAGE)),
                children = listOf(
                    XmlElement(
                        name = "application",
                        attributes = listOf(androidName("$ORIGINAL_PACKAGE.MainApplication")),
                        children = listOf(
                            XmlElement(
                                name = "meta-data",
                                attributes = listOf(
                                    androidName("$ORIGINAL_PACKAGE.features.FLAG"),
                                    XmlAttribute(ATTR_VALUE, "value", ORIGINAL_PACKAGE)
                                )
                            ),
                            XmlElement(
                                name = "activity",
                                attributes = listOf(
                                    XmlAttribute(ATTR_PROCESS, "process", ":phoenix"),
                                    XmlAttribute(
                                        ATTR_TASK_AFFINITY,
                                        "taskAffinity",
                                        "$ORIGINAL_PACKAGE.share"
                                    )
                                ),
                                children = listOf(
                                    XmlElement(
                                        name = "intent-filter",
                                        children = listOf(
                                            XmlElement(
                                                "action",
                                                listOf(
                                                    androidName(
                                                        "$ORIGINAL_PACKAGE.intent.action.CONNECT"
                                                    )
                                                )
                                            )
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
            attributeValues(renamed, "meta-data", BinaryXmlEditor.ATTR_NAME)
        )
        assertEquals(listOf(ORIGINAL_PACKAGE), attributeValues(renamed, "meta-data", ATTR_VALUE))
        assertEquals(
            listOf("$ORIGINAL_PACKAGE.intent.action.CONNECT"),
            attributeValues(renamed, "action", BinaryXmlEditor.ATTR_NAME)
        )
        assertEquals(listOf(":phoenix"), attributeValues(renamed, "activity", ATTR_PROCESS))
        assertEquals(
            listOf("$ORIGINAL_PACKAGE.share"),
            attributeValues(renamed, "activity", ATTR_TASK_AFFINITY)
        )
        assertEquals(
            listOf("$ORIGINAL_PACKAGE.MainApplication"),
            attributeValues(renamed, "application", BinaryXmlEditor.ATTR_NAME)
        )
        assertEquals(listOf(CLONE_PACKAGE), attributeValues(renamed, "manifest", 0))
    }

    /**
     * A document of the wrong kind, or one that does not decode, comes back untouched: a manifest
     * that still parses is worth more to the caller than a half-renamed one.
     */
    @Test
    fun leavesADocumentItCannotReadAlone() {
        val notXml =
            "<?xml version=\"1.0\"?><manifest package=\"$ORIGINAL_PACKAGE\"/>".toByteArray()
        assertUnchanged(
            notXml,
            BinaryXmlModifier.modifyPackageName(notXml, ORIGINAL_PACKAGE, CLONE_PACKAGE)
        )

        val xml = documentOf(XmlElement("manifest", listOf(packageAttribute(ORIGINAL_PACKAGE))))
        assertUnchanged(
            xml.copyOfRange(0, 12),
            BinaryXmlModifier.modifyPackageName(
                xml.copyOfRange(0, 12),
                ORIGINAL_PACKAGE,
                CLONE_PACKAGE
            )
        )

        val empty = ByteArray(0)
        assertUnchanged(
            empty,
            BinaryXmlModifier.modifyPackageName(empty, ORIGINAL_PACKAGE, CLONE_PACKAGE)
        )

        // A rename to the name that is already there is not a rename.
        assertUnchanged(
            xml,
            BinaryXmlModifier.modifyPackageName(xml, ORIGINAL_PACKAGE, ORIGINAL_PACKAGE)
        )
    }

    private fun assertUnchanged(expected: ByteArray, actual: ByteArray) {
        assertTrue(
            "the document was modified: ${actual.size} bytes against ${expected.size}",
            expected.contentEquals(actual)
        )
    }

    // --- the classes inside the APK -------------------------------------------------------

    private companion object {
        const val ORIGINAL_PACKAGE = "com.discord"
        const val CLONE_PACKAGE = "com.discord.sleepy"
    }
}
