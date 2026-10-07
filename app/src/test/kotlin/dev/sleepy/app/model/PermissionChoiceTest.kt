package dev.sleepy.app.model

import dev.sleepy.app.engine.BinaryXmlEditor
import dev.sleepy.app.patches.DeclaredPermissions
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.PermissionCatalog
import dev.sleepy.app.testing.ComparisonApks
import dev.sleepy.app.testing.manifestOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The permission catalog and the choice a user makes through it.
 *
 * The catalog describes every permission the patched builds declare, states what removal costs,
 * and locks the two permissions the platform requires. A choice is a
 * [dev.sleepy.app.model.PatchSelection]; the tests pin that a catalog-unknown permission is still
 * offered honestly, that a locked permission and the last declaration standing cannot be removed,
 * and that declarations the Discord pass removes itself are not offered as choices.
 */
class PermissionChoiceTest {

    private val discordApk = ComparisonApks.discordBaseApk

    private val octoGramApk = ComparisonApks.octoGram361Arm64

    /** The package names the two builds answer to, as the sources name them. */
    private val DISCORD = "com.discord"
    private val OCTOGRAM = "it.octogram.android"

    private fun declared(xml: ByteArray): List<String> = BinaryXmlEditor.readElementAttributeValues(
        xml = xml,
        namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
        attributeId = BinaryXmlEditor.ATTR_NAME
    )

    /**
     * Every permission that either real build declares has an entry of its own.
     *
     * The two builds are not a list of what the catalog has to cover—they are the evidence
     * that it covers what real builds declare, which is the failure a table like this can hide:
     * an entry that is missing is a permission the user sees described as "no entry", and one
     * written for a name no build uses is unused.
     */
    @Test
    fun everyPermissionTheRealBuildsDeclareIsDescribed() {
        // Every fixture that is here gets checked; the test skips only when none is, so a machine
        // holding one of the two builds still checks it rather than skipping both.
        val fixtures = listOf(discordApk, octoGramApk).filter { it.exists() }
        assumeTrue("neither fixture APK is on this machine", fixtures.isNotEmpty())

        var checked = 0
        for (apk in fixtures) {
            val permissions = declared(manifestOf(apk))
            assertTrue("${apk.name} should declare permissions", permissions.size > 5)
            for (name in permissions) {
                // The one name that is built from the application id, so it is matched by its
                // suffix rather than listed: it is the same declaration in every app.
                val described = PermissionCatalog.all.any { it.name == name } ||
                    name.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
                assertTrue(
                    "${apk.name} declares $name, which the catalog does not describe",
                    described
                )
                checked++
            }
        }
        println(
            "$checked declared permissions checked against the catalog, across " +
                "${fixtures.size} builds"
        )
    }

    /** Every entry states what removing it costs, and that the cost is permanent. */
    @Test
    fun everyEntrySaysWhatRemovalCostsAndThatItCannotBeUndone() {
        assertTrue("the catalog should cover both apps", PermissionCatalog.all.size > 50)
        for (entry in PermissionCatalog.all) {
            assertTrue("${entry.name} has no label", entry.label.isNotBlank())
            assertTrue(
                "${entry.name}'s description does not say removal is permanent",
                entry.description.endsWith(PermissionCatalog.REMOVAL_CONSEQUENCE)
            )
            assertTrue(
                "${entry.name}'s description does not say the app can never ask again",
                entry.description.contains("can never ask for this permission again")
            )
        }
    }

    /**
     * Exactly two permissions are locked, and both state why in the vocabulary the blocklist's
     * gates established.
     *
     * The list is asserted rather than sampled because a lock is the one thing here the user cannot
     * argue with: what is locked is a decision, and a decision that changes without a report is one
     * that was not reviewed.
     */
    @Test
    fun onlyTheTwoPermissionsThePlatformEnforcesWithAnExceptionAreLocked() {
        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"),
            PermissionCatalog.all.filter { it.essential }.map { it.name }
        )
        for (entry in PermissionCatalog.all.filter { it.essential }) {
            val reason = entry.lockReason
            assertTrue(
                "${entry.name} is locked without a reason",
                reason != null && reason.isNotBlank()
            )
            assertTrue(
                "${entry.name}'s reason is not phrased as the gates' reasons are",
                reason!!.startsWith(BlocklistCoverage.REQUIRED_REASON_PREFIX)
            )
        }
    }

    /**
     * A permission with no catalog entry is described as unknown rather than hidden.
     *
     * A build may declare anything, and a list that only showed what it recognizes leaves
     * declarations a user cannot see and cannot choose about—the same result as the app keeping
     * a permission that the user was not told about.
     */
    @Test
    fun aPermissionTheCatalogDoesNotKnowIsStillOfferedAndLabeledHonestly() {
        val unknown = PermissionCatalog.entryFor("com.example.MY_PRIVATE_PERMISSION")
        assertEquals("My private permission", unknown.label)
        assertFalse(unknown.essential)
        assertTrue(unknown.description.contains("no entry"))
        assertTrue(unknown.description.endsWith(PermissionCatalog.REMOVAL_CONSEQUENCE))

        // The per-package one is not unknown: its name is built from the application id, so it is
        // matched by its suffix and described like any other.
        val receiver = PermissionCatalog.entryFor(
            "com.discord.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        assertEquals("Private broadcast receivers", receiver.label)
        assertFalse(receiver.description.contains("no entry"))
    }

    /**
     * A build's declarations become items in the order its manifest lists them, duplicates and
     * all.
     */
    @Test
    fun aBuildsDeclarationsBecomeItemsInTheOrderTheManifestListsThem() {
        val declared = listOf(
            "android.permission.CAMERA",
            "android.permission.INTERNET",
            "android.permission.CAMERA"
        )
        val items = PermissionCatalog.itemsOf(declared)

        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.INTERNET"),
            items.map { it.identity }
        )
        assertEquals(
            listOf(
                "manifest_permissions:android.permission.CAMERA",
                "manifest_permissions:android.permission.INTERNET"
            ),
            items.map { it.key }
        )
        assertEquals(PermissionCatalog.SET_ID, items.first().setId)
        assertEquals(PermissionCatalog.DECLARED_GROUP, items.first().group)
        assertTrue(
            "an item's description is its entry's",
            items.first().description.endsWith(PermissionCatalog.REMOVAL_CONSEQUENCE)
        )
    }

    /**
     * A selection that names no permission removes none of them.
     *
     * This is the gate that makes reading the list safe to skip: the patch rows are a selection of
     * their own, and a run started from one must not be read as "the user switched every permission
     * off" merely because no permission key is in it.
     */
    @Test
    fun aSelectionThatNamesNoPermissionRemovesNothing() {
        val declared = listOf("android.permission.CAMERA", "android.permission.INTERNET")

        val empty = PatchSelection()
        assertFalse(PermissionCatalog.isEngaged(empty))
        assertTrue(PermissionCatalog.removals(declared, empty).isEmpty())

        val patchItemsOnly =
            PatchSelection.ofKeys("discord_hermes:fn83581", "discord_blocklist:api:/typing")
        assertFalse(
            "a patch selection names no permission",
            PermissionCatalog.isEngaged(patchItemsOnly)
        )
        assertTrue(PermissionCatalog.removals(declared, patchItemsOnly).isEmpty())
    }

    /**
     * A selection that names no permission renders as "every declaration kept", not as "all of
     * them switched off".
     *
     * The two are different claims and the difference is not cosmetic. A selection lists what is
     * *on*, so a selection made for the patch sets names no permission by construction, and the
     * list can be read before anything is chosen about it—reading is a download, so it is offered
     * first. Reading that as removals shows the user a manifest being emptied that no patch
     * changes, which a row about deletion must not state by accident.
     */
    @Test
    fun aSelectionThatNamesNoPermissionShowsEveryPermissionKept() {
        for (declared in listOf(
            listOf(
                "android.permission.CAMERA",
                "android.permission.INTERNET",
                "android.permission.RECORD_AUDIO"
            ),
            // No locked permission in this one, so the survivor rule does not apply and
            // "nothing has been switched off" has to hold on its own.
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        )) {
            val rows =
                PermissionCatalog.rows(declared, PatchSelection.ofKeys("discord_hermes:fn83581"))

            assertEquals(declared, rows.map { it.permission.name })
            assertTrue("nothing has been switched off", rows.all { it.kept })
            assertTrue(
                "and no row refuses for being the last one left",
                rows.none { it.lockedReason == PermissionCoverage.LAST_PERMISSION_REASON }
            )
            // A permission the app cannot work without is locked whatever the selection states, so
            // it is the one row that may still be fixed here—and the row repeats the catalog's
            // reason.
            for (row in rows.filter { !it.switchable }) {
                assertTrue(row.permission.essential)
                assertEquals(row.permission.lockReason, row.lockedReason)
            }
        }
    }

    /**
     * Reading the list seeds every declaration as kept, which is what the list does when a build is
     * read: the switches start on and the only way a permission goes is a user switching it off.
     */
    @Test
    fun readingTheListKeepsEveryDeclarationUntilSomethingIsSwitchedOff() {
        val declared = listOf(
            "android.permission.CAMERA",
            "android.permission.INTERNET",
            "android.permission.RECORD_AUDIO"
        )
        val selection = PatchSelection().with(PermissionCatalog.itemsOf(declared))

        assertTrue(PermissionCatalog.isEngaged(selection))
        assertTrue(PermissionCatalog.removals(declared, selection).isEmpty())

        val rows = PermissionCatalog.rows(declared, selection)
        assertEquals(declared, rows.map { it.permission.name })
        assertTrue("everything starts kept", rows.all { it.kept })
    }

    /**
     * A locked permission cannot be removed through the selection model, however the selection is
     * built—the switch the row cannot move, or a selection that does not name it.
     */
    @Test
    fun aLockedPermissionCannotBeDeselectedThroughTheSelectionModel() {
        val declared = listOf(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO"
        )
        val items = PermissionCatalog.itemsOf(declared)
        val internet = items.first { it.identity == "android.permission.INTERNET" }

        // The switch a row calls, and a selection that does not name it at all.
        val toggledOff = PatchSelection().with(items).toggle(internet)
        val neverNamed = PatchSelection().with(items.filter { it != internet })

        for (selection in listOf(toggledOff, neverNamed)) {
            assertTrue(
                "INTERNET is removable from a selection that does not name it",
                PermissionCatalog.removals(declared, selection).isEmpty()
            )
            val row = PermissionCatalog.rows(declared, selection).first { it.permission.essential }
            assertTrue("a locked row reads as kept", row.kept)
            assertFalse("a locked row offers no switch", row.switchable)
            assertTrue(row.lockedReason!!.startsWith(BlocklistCoverage.REQUIRED_REASON_PREFIX))
        }

        // The control: the same shape does remove an ordinary permission.
        val camera = items.first { it.identity == "android.permission.CAMERA" }
        assertEquals(
            listOf("android.permission.CAMERA"),
            PermissionCatalog.removals(declared, PatchSelection().with(items).toggle(camera))
        )
    }

    /**
     * The last declaration standing cannot be removed, and which one that is follows the choice
     * rather than being fixed: switching a different one off first moves the lock to it.
     */
    @Test
    fun theLastDeclarationStandingCannotBeRemoved() {
        val declared = listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        val items = PermissionCatalog.itemsOf(declared)
        val camera = items.first { it.identity == "android.permission.CAMERA" }
        val microphone = items.first { it.identity == "android.permission.RECORD_AUDIO" }
        val both = PatchSelection().with(items)

        val cameraOff = both.toggle(camera)
        assertEquals(
            listOf("android.permission.CAMERA"),
            PermissionCatalog.removals(declared, cameraOff)
        )
        val microphoneRow = PermissionCatalog.rows(declared, cameraOff)
            .first { it.permission.name == "android.permission.RECORD_AUDIO" }
        assertFalse("the last one left cannot be switched off", microphoneRow.switchable)
        assertEquals(PermissionCoverage.LAST_PERMISSION_REASON, microphoneRow.lockedReason)

        val microphoneOff = both.toggle(microphone)
        assertEquals(
            listOf("android.permission.RECORD_AUDIO"),
            PermissionCatalog.removals(declared, microphoneOff)
        )
        assertFalse(
            "and the refusal moved with the choice",
            PermissionCatalog.rows(declared, microphoneOff)
                .first { it.permission.name == "android.permission.CAMERA" }
                .switchable
        )

        // A build that declares a locked permission has one that cannot be removed, so the rule
        // above adds nothing: switching every other one off removes every other one.
        val withInternet = listOf(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO"
        )
        val onlyTheLockedOneKept = PatchSelection()
            .with(
                PermissionCatalog.itemsOf(withInternet)
                    .filter { it.identity.endsWith(".INTERNET") }
            )
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            PermissionCatalog.removals(withInternet, onlyTheLockedOneKept)
        )
    }

    /**
     * A selection made against a different build, naming permissions this one does not declare,
     * still cannot empty the list.
     */
    @Test
    fun aStaleSelectionCannotRemoveEveryDeclaration() {
        val declared = listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        val stale = PatchSelection.ofKeys(
            PermissionCatalog.itemKeyOf("android.permission.ACCESS_FINE_LOCATION")
        )

        assertTrue("the key does engage the set", PermissionCatalog.isEngaged(stale))
        val removals = PermissionCatalog.removals(declared, stale)
        assertEquals("one declaration must survive", declared.size - 1, removals.size)
        assertTrue(removals.contains("android.permission.RECORD_AUDIO"))
    }

    /**
     * A declaration that the build's own patch removes is not offered as a choice on that build:
     * the row reads as removed, states why, and its switch cannot be moved.
     *
     * The switch is the thing under test. Every other fixed row blocks a *removal* and reads as
     * kept; this one blocks the choice, because the declaration is not in the app the run
     * produces whatever the switch states. Showing it as kept states that the app has a permission
     * that the run removes, and the user acts on that statement.
     */
    @Test
    fun theDeclarationsThisBuildRemovesItselfAreNotAChoiceOnIt() {
        val declared = requireNotNull(DeclaredPermissions.forPackage(DISCORD)) {
            "Discord's list is shipped"
        }
        val dead = DiscordPatches.DEAD_PERMISSIONS.filter { it in declared.toSet() }
        assertEquals(
            "the list should still name every declaration this build strips",
            DiscordPatches.DEAD_PERMISSIONS,
            dead
        )

        // Every declaration named as kept, which is the state a read list starts in and the one
        // that used to put a live switch over each of these.
        val selection = PatchSelection().with(PermissionCatalog.itemsOf(declared))
        val rows = PermissionCatalog.rows(declared, selection, DISCORD)

        assertEquals(
            "the rows fixed on removed must be exactly the declarations this build removes",
            dead,
            rows.filter { !it.switchable && !it.kept }.map { it.permission.name }
        )
        for (name in dead) {
            assertEquals(
                "$name must say why its switch is not the user's to move",
                PermissionCoverage.ALWAYS_REMOVED_REASON,
                rows.first { it.permission.name == name }.lockedReason
            )
        }
        // And nothing else is fixed: every declaration that is not one of these, and not one the
        // platform enforces with an exception, is still the user's to move.
        val essential = rows.filter { it.permission.essential }.map { it.permission.name }.toSet()
        assertEquals(
            declared.toSet() - dead.toSet() - essential,
            rows.filter { it.switchable }.map { it.permission.name }.toSet()
        )
    }

    /**
     * A declaration that the build's own patch removes is not also in the user's own removal list,
     * however the selection was built—and the same list for another app is the user's to remove.
     *
     * The second half is what makes the first a claim about the build rather than about the name:
     * sleepy only takes READ_CONTACTS out of Discord, and a run against OctoGram has to leave the
     * choice to the user there, because OctoGram syncs the address book through it.
     *
     * The first half is the case that can fail unnoticed. A saved selection from before these rows
     * stopped offering a switch still names the declaration as switched off, and the build's own
     * patch removes it, in its own group—so a name reaching this list as well is one selector
     * that the manifest pass receives twice, reported the second time as an element the build does
     * not have.
     */
    @Test
    fun aDeclarationThisBuildRemovesIsNeverAlsoTheUsersToRemove() {
        val dead = DiscordPatches.DEAD_PERMISSIONS.first()
        val declared = listOf(dead, "android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        // The state a selection saved before this was fixed is in: the dead declaration is not
        // named, and the two ordinary ones are.
        val selection = PatchSelection()
            .with(PermissionCatalog.itemsOf(declared).filter { it.identity != dead })

        assertEquals(
            "a declaration this build removes on its own is not a removal of the user's",
            emptyList<String>(),
            PermissionCatalog.removals(declared, selection, DISCORD)
        )
        assertEquals(
            "the same list for another app leaves the choice with the user",
            listOf(dead),
            PermissionCatalog.removals(declared, selection, OCTOGRAM)
        )
    }

    /**
     * The whole choice, on the real manifest, through the calls the app makes: the build's
     * declarations are read from its own manifest, the switches are a [PatchSelection], and what
     * [PermissionCatalog.removals] returns is what the manifest edit is given.
     */
    @Test
    fun theRealManifestIsEditedByTheChoiceTheListProduces() {
        val original = manifestOf(discordApk)
        val before = declared(original)

        val items = PermissionCatalog.itemsOf(before)
        val switchedOff = listOf("android.permission.CAMERA", "android.permission.READ_CONTACTS")
        var selection = PatchSelection().with(items)
        for (name in switchedOff) {
            selection = selection.toggle(items.first { it.identity == name })
        }

        val removals = PermissionCatalog.removals(before, selection)
        // Document order, which is the order the manifest itself lists them in rather than the
        // order they were switched off in.
        assertEquals(before.filter { it in switchedOff.toSet() }, removals)
        assertEquals(switchedOff.toSet(), removals.toSet())

        val result = BinaryXmlEditor.edit(
            xml = original,
            removeElements = removals.map {
                BinaryXmlEditor.ElementSelector(
                    namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
                    attributeId = BinaryXmlEditor.ATTR_NAME,
                    attributeValue = it
                )
            }
        )

        assertEquals(switchedOff.toSet(), result.elementsRemoved.toSet())
        assertTrue(result.elementsMissing.isEmpty())
        assertEquals(before.size - switchedOff.size, declared(result.bytes).size)
        assertEquals(before.toSet() - switchedOff.toSet(), declared(result.bytes).toSet())

        // A locked permission is not in the list whatever the selection states, so switching it
        // off changes nothing at all: this is the same sequence a user performs.
        val internet = items.first { it.identity == "android.permission.INTERNET" }
        assertEquals(removals, PermissionCatalog.removals(before, selection.toggle(internet)))
    }
}
