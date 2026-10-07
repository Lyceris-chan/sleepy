package dev.sleepy.app.patches

import dev.sleepy.app.engine.BinaryXmlEditor
import dev.sleepy.app.model.DeclarationMismatch
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.PermissionCheck
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.manifestOf
import dev.sleepy.app.testing.sourceFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission lists shipped with the app, against the builds they describe.
 *
 * Each list states the permissions its build declares, in declaration order, and the tests read
 * both the shipped list and the real APK. A mismatch is reported in either direction without
 * changing the list, so a permission the build declares and the list omits stays out of the UI.
 */
class DeclaredPermissionsTest {

    private val discordApk = ComparisonApks.discordBaseApk

    private val octoGramApk = ComparisonApks.octoGram361Arm64

    /** Each shipped list against the build it was read from: the package name, and that APK. */
    private val builds: List<Triple<String, String, File>> = listOf(
        Triple("Discord", "com.discord", discordApk),
        Triple("OctoGram", "it.octogram.android", octoGramApk)
    )

    /**
     * What the build itself declares.
     *
     * The fixtures are build outputs stored outside the repository, so a machine without one
     * reports the test as skipped—an early `return` reports it as a pass instead.
     */
    private fun declaredIn(apk: File): List<String> =
        BinaryXmlEditor.readElementAttributeValues(
            xml = manifestOf(apk),
            namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
            attributeId = BinaryXmlEditor.ATTR_NAME
        )

    /**
     * Reads `package_name` out of the checked-in manifest, which is plain JSON of a stable shape.
     */
    private fun packagesInTheManifest(): Set<String> {
        val manifest = sourceFile("sources.json")
        return Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * Every shipped list is what its build declares, in the manifest's own order.
     *
     * Order included, because it is not a detail: the rows are shown in the list's order, so a
     * list that gives the same permissions in a different order records a manifest read out of
     * order.
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
     * A source whose package is not listed here still works—the section reads that build
     * instead—but it works by making the user download a whole APK before they can see a single
     * permission, which is the thing shipping the lists is for. So it is a test failure rather than
     * a fallback taken without a report.
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
     * A package nothing is shipped for returns `null`, which is not the same as "declares nothing".
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
     * governs nothing. Reordering is not a difference—the lists are compared by what they name.
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
     * A permission the build declares and the list does not name is not removed and produces no
     * row.
     *
     * This is the case a shipped list can introduce and a read could not, and the reason the check
     * exists: the selection cannot express a choice about a permission with no row, so nothing here
     * may delete that declaration—not an empty selection, and not a selection that names every
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
        // The check is what reports the difference, rather than the difference passing unreported.
        val check = PermissionCheck.of(shipped, declaredByTheBuild) as PermissionCheck.Disagrees
        assertEquals(
            listOf(DeclarationMismatch.Unlisted("com.example.MY_PRIVATE_PERMISSION")),
            check.mismatches
        )
    }
}
