package dev.sleepy.app

import dev.sleepy.app.engine.BinaryXmlEditor
import dev.sleepy.app.model.DeclarationMismatch
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.PermissionCheck
import dev.sleepy.app.patches.DeclaredPermissions
import dev.sleepy.app.patches.PermissionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * The permission lists shipped with the app, against the builds they say they describe.
 *
 * The list a user chooses from is written down here rather than read from the archive, which is
 * only honest while it says exactly what the build at the source declares. That claim is checked
 * against the real builds rather than against another list: the fixtures are the same APKs the
 * patch runs use, read through the same reader the patch run uses, and a list that drifts from one
 * of them fails here — permission for permission, and in the order the manifest declares them,
 * because the order is what the rows are shown in.
 *
 * The other half is what happens when they do not match: a difference is reported in both
 * directions and neither direction changes the list, so a permission the build declares and the
 * list does not name stays a permission nobody was shown.
 */
class DeclaredPermissionsTest {

    /**
     * The directory these tests run in, named so that the lookup below has a value to start from.
     *
     * `System.getProperty` hands back a platform type and `File`'s constructor takes a non-null
     * `String`, so the two cannot be joined without either asserting it here or leaving the
     * compiler to decide.
     */
    private val workingDirectory: String = requireNotNull(System.getProperty("user.dir")) {
        "user.dir is not set, so sources.json cannot be looked for from the working directory"
    }

    /** The directory the desktop build's extracted Discord 348.5 fixture sits in. */
    private val discordBuild = File(
        "/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted"
    )

    private val discordApk = File(discordBuild, "base.apk")

    private val octoGramApk =
        File("/home/sleepy/Documents/antigravity/telegram/OctoGram_361_arm64.apk")

    /** Each shipped list against the build it was read from: the package name, and that APK. */
    private val builds: List<Triple<String, String, File>> = listOf(
        Triple("Discord", "com.discord", discordApk),
        Triple("OctoGram", "it.octogram.android", octoGramApk)
    )

    /**
     * What the build itself declares.
     *
     * The fixtures are build outputs that live outside the repository, so a machine without one
     * reports the test as skipped — an early `return` would have reported it as a pass instead.
     */
    private fun declaredIn(apk: File): List<String> {
        assumeTrue("${apk.path} is not on this machine", apk.exists())
        return ZipFile(apk).use { zip ->
            val entry = requireNotNull(zip.getEntry("AndroidManifest.xml")) {
                "${apk.name} has no AndroidManifest.xml"
            }
            BinaryXmlEditor.readElementAttributeValues(
                xml = zip.getInputStream(entry).readBytes(),
                namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
                attributeId = BinaryXmlEditor.ATTR_NAME
            )
        }
    }

    /**
     * Reads `package_name` out of the checked-in manifest, which is plain JSON of a stable shape.
     */
    private fun packagesInTheManifest(): Set<String> {
        val manifest = File(workingDirectory).let { directory ->
            generateSequence(directory) { it.parentFile }
                .map { File(it, "sources.json") }
                .firstOrNull { it.isFile }
        }
        requireNotNull(manifest) { "sources.json was not found above $workingDirectory" }
        return Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * Every shipped list is what its build declares, in the manifest's own order.
     *
     * Order included, because it is not a detail: the rows are shown in the list's order, so a list
     * that says the same permissions in a different order is a manifest read out of order.
     */
    @Test
    fun everyShippedListIsExactlyWhatItsBuildDeclares() {
        var checked = 0
        for ((name, packageName, apk) in builds) {
            val declared = declaredIn(apk)
            val shipped = DeclaredPermissions.forPackage(packageName)
            assertTrue("$name declares no permissions, which cannot be right", declared.size > 5)
            assertEquals("$name's shipped list is not what the build declares", declared, shipped)
            checked += declared.size
        }
        println("$checked declarations matched their shipped lists across ${builds.size} builds")
    }

    /**
     * Every package the manifest offers has a list shipped for it.
     *
     * A source whose package is not listed here still works — the section reads that build
     * instead — but it works by making the user download a whole APK before they can see a single
     * permission, which is the thing shipping the lists is for. So it is a test failure rather than
     * a fallback quietly taken.
     */
    @Test
    fun everySourceTheManifestOffersHasAListShippedForIt() {
        val packages = packagesInTheManifest()
        assertFalse("no package_name was read out of sources.json", packages.isEmpty())
        for (packageName in packages) {
            assertTrue(
                "$packageName is offered as a source but no permission list is shipped for it",
                DeclaredPermissions.forPackage(packageName) != null
            )
        }
        assertEquals(packages, DeclaredPermissions.packages)
    }

    /**
     * A package nothing is shipped for answers `null`, which is not the same as "declares nothing".
     */
    @Test
    fun aPackageNothingIsShippedForHasNoListRatherThanAnEmptyOne() {
        assertNull(DeclaredPermissions.forPackage("com.example.unknown"))
        assertNull(DeclaredPermissions.forPackage(null))
        assertTrue(DeclaredPermissions.forPackage("com.discord")!!.isNotEmpty())
    }

    /**
     * A difference in either direction is reported, in the direction it is in.
     *
     * The two are different problems: a declaration the list does not name has no row, so it stays
     * whatever the user does, while a listed permission the build does not declare means a row
     * governs nothing. Reordering is not a difference — the lists are compared by what they name.
     */
    @Test
    fun aDifferenceIsReportedRatherThanResolved() {
        val shipped = listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")

        assertEquals(
            "the same permissions in another order are the same permissions",
            PermissionCheck.Agrees,
            PermissionCheck.of(shipped, shipped.reversed())
        )

        val check = PermissionCheck.of(
            shipped,
            listOf("android.permission.CAMERA", "com.example.MY_PRIVATE_PERMISSION")
        )
        assertEquals(
            listOf(
                DeclarationMismatch.Unlisted("com.example.MY_PRIVATE_PERMISSION"),
                DeclarationMismatch.Absent("android.permission.RECORD_AUDIO")
            ),
            (check as PermissionCheck.Disagrees).mismatches
        )
    }

    /** A permission declared twice is one difference, not two: the report is about permissions. */
    @Test
    fun aDeclarationTheManifestRepeatsIsOneDifference() {
        val check = PermissionCheck.of(
            listOf("android.permission.CAMERA"),
            listOf("android.permission.CAMERA", "android.permission.CAMERA", "com.example.ONCE")
        )
        assertEquals(
            listOf(DeclarationMismatch.Unlisted("com.example.ONCE")),
            (check as PermissionCheck.Disagrees).mismatches
        )
    }

    /**
     * A permission the build declares and the list does not name is never removed and never a row.
     *
     * This is the case a shipped list can introduce and a read could not, and the reason the check
     * exists: the selection cannot express a choice about a permission with no row, so nothing here
     * may delete that declaration — not an empty selection, and not a selection that names every
     * permission the list does have.
     */
    @Test
    fun aPermissionTheListDoesNotNameCannotBeRemoved() {
        val shipped = listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        val declaredByTheBuild = shipped + "com.example.MY_PRIVATE_PERMISSION"
        val everythingKept = PatchSelection().with(PermissionCatalog.itemsOf(shipped))

        assertEquals(
            shipped,
            PermissionCatalog.rows(shipped, everythingKept).map { it.permission.name }
        )
        assertTrue(PermissionCatalog.removals(shipped, everythingKept).isEmpty())
        assertTrue(PermissionCatalog.removals(shipped, PatchSelection()).isEmpty())
        // And the check is what says so, rather than nothing saying anything.
        val check = PermissionCheck.of(shipped, declaredByTheBuild) as PermissionCheck.Disagrees
        assertEquals(
            listOf(DeclarationMismatch.Unlisted("com.example.MY_PRIVATE_PERMISSION")),
            check.mismatches
        )
    }
}
