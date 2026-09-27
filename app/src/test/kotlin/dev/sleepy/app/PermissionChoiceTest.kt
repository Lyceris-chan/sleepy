package dev.sleepy.app

import dev.sleepy.app.engine.BinaryXmlEditor
import dev.sleepy.app.model.BlocklistCoverage
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.PermissionCoverage
import dev.sleepy.app.patches.PermissionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * The permission catalogue and the choice a user makes through it.
 *
 * Three claims are under test. The catalogue describes every permission the builds this app
 * patches actually declare, and every description says what removal costs — including that it is
 * permanent. A choice is a [PatchSelection] like every other choice in the app, so it is made and
 * unmade with the same calls the rows use. And two things can never be removed however the
 * selection is built: a permission the app cannot work without, and the last declaration standing.
 */
class PermissionChoiceTest {

    private val discordApk =
        File("/home/sleepy/Documents/antigravity/quirky-noether/discord/build/alpha3482/apk/extracted/base.apk")

    private val octoGramApk = File("/home/sleepy/Documents/antigravity/telegram/OctoGram_361_arm64.apk")

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

    private fun declared(xml: ByteArray): List<String> = BinaryXmlEditor.readElementAttributeValues(
        xml = xml,
        namePrefix = BinaryXmlEditor.ELEMENT_USES_PERMISSION,
        attributeId = BinaryXmlEditor.ATTR_NAME
    )

    /**
     * Every permission either real build declares has an entry of its own.
     *
     * The two builds are not a list of what the catalogue has to cover — they are the evidence
     * that it covers what real builds declare, which is the thing a table like this gets wrong
     * quietly: an entry nobody wrote is a permission the user sees described as "no entry", and
     * one written for a name no build uses is dead weight.
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
                assertTrue("${apk.name} declares $name, which the catalogue does not describe", described)
                checked++
            }
        }
        println("$checked declared permissions checked against the catalogue, across ${fixtures.size} builds")
    }

    /** Every entry says what removing it costs, and that the cost is permanent. */
    @Test
    fun everyEntrySaysWhatRemovalCostsAndThatItCannotBeUndone() {
        assertTrue("the catalogue should cover both apps", PermissionCatalog.all.size > 50)
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
     * Exactly two permissions are locked, and both say why in the vocabulary the blocklist's gates
     * established.
     *
     * The list is asserted rather than sampled because a lock is the one thing here the user cannot
     * argue with: what is locked is a decision, and a decision that changes silently is a decision
     * nobody reviewed.
     */
    @Test
    fun onlyTheTwoPermissionsThePlatformEnforcesWithAnExceptionAreLocked() {
        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"),
            PermissionCatalog.all.filter { it.essential }.map { it.name }
        )
        for (entry in PermissionCatalog.all.filter { it.essential }) {
            val reason = entry.lockReason
            assertTrue("${entry.name} is locked without a reason", reason != null && reason.isNotBlank())
            assertTrue(
                "${entry.name}'s reason is not phrased as the gates' reasons are",
                reason!!.startsWith(BlocklistCoverage.REQUIRED_REASON_PREFIX)
            )
        }
    }

    /**
     * A permission the catalogue has never heard of is described as unknown rather than hidden.
     *
     * A build may declare anything, and a list that only showed what it recognises would leave
     * declarations a user cannot see and cannot choose about — which is the same as saying the app
     * keeps a permission the user was never told about.
     */
    @Test
    fun aPermissionTheCatalogueDoesNotKnowIsStillOfferedAndLabelledHonestly() {
        val unknown = PermissionCatalog.entryFor("com.example.MY_PRIVATE_PERMISSION")
        assertEquals("My private permission", unknown.label)
        assertFalse(unknown.essential)
        assertTrue(unknown.description.contains("no entry"))
        assertTrue(unknown.description.endsWith(PermissionCatalog.REMOVAL_CONSEQUENCE))

        // The per-package one is not unknown: its name is built from the application id, so it is
        // matched by its suffix and described like any other.
        val receiver = PermissionCatalog.entryFor("com.discord.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        assertEquals("Private broadcast receivers", receiver.label)
        assertFalse(receiver.description.contains("no entry"))
    }

    /** A build's declarations become items in the order its manifest lists them, duplicates and all. */
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
        assertTrue("an item's description is its entry's", items.first().description.endsWith(PermissionCatalog.REMOVAL_CONSEQUENCE))
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

        val patchItemsOnly = PatchSelection.ofKeys("discord_hermes:fn73760", "discord_blocklist:api:/typing")
        assertFalse("a patch selection names no permission", PermissionCatalog.isEngaged(patchItemsOnly))
        assertTrue(PermissionCatalog.removals(declared, patchItemsOnly).isEmpty())
    }

    /**
     * A selection that names no permission renders as "every declaration kept", never as "all of
     * them switched off".
     *
     * The two are different claims and the difference is not cosmetic. A selection lists what is
     * *on*, so a selection made for the patch sets names no permission by construction, and the
     * list can be read before anything is chosen about it — reading is a download, so it is offered
     * first. Reading that as removals would show the user a manifest being emptied that nothing is
     * going to touch, which is the one thing a row about deletion must never say by accident.
     */
    @Test
    fun aSelectionThatNamesNoPermissionShowsEveryPermissionKept() {
        for (declared in listOf(
            listOf(
                "android.permission.CAMERA",
                "android.permission.INTERNET",
                "android.permission.RECORD_AUDIO"
            ),
            // No locked permission in this one, so the survivor rule has nothing to stand on and
            // "nothing has been switched off" has to hold on its own.
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        )) {
            val rows = PermissionCatalog.rows(declared, PatchSelection.ofKeys("discord_hermes:fn73760"))

            assertEquals(declared, rows.map { it.permission.name })
            assertTrue("nothing has been switched off", rows.all { it.kept })
            assertTrue(
                "and no row refuses for being the last one left",
                rows.none { it.lockedReason == PermissionCoverage.LAST_PERMISSION_REASON }
            )
            // A permission the app cannot work without is locked whatever the selection says, so
            // it is the one row that may still be fixed here — and it says so in its own words.
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
     * built — the switch the row cannot move, or a selection that simply does not name it.
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

        // The switch a row would call, and a selection that never mentions it at all.
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
     * rather than being fixed: switching a different one off first moves the refusal to it.
     */
    @Test
    fun theLastDeclarationStandingCannotBeRemoved() {
        val declared = listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        val items = PermissionCatalog.itemsOf(declared)
        val camera = items.first { it.identity == "android.permission.CAMERA" }
        val microphone = items.first { it.identity == "android.permission.RECORD_AUDIO" }
        val both = PatchSelection().with(items)

        val cameraOff = both.toggle(camera)
        assertEquals(listOf("android.permission.CAMERA"), PermissionCatalog.removals(declared, cameraOff))
        val microphoneRow = PermissionCatalog.rows(declared, cameraOff).first { it.permission.name == "android.permission.RECORD_AUDIO" }
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

        // A build that declares a locked permission always has one it will not let go of, so the
        // rule above has nothing to add: switching every other one off removes every other one.
        val withInternet = listOf(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO"
        )
        val onlyTheLockedOneKept = PatchSelection()
            .with(PermissionCatalog.itemsOf(withInternet).filter { it.identity.endsWith(".INTERNET") })
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
        val stale = PatchSelection.ofKeys(PermissionCatalog.itemKeyOf("android.permission.ACCESS_FINE_LOCATION"))

        assertTrue("the key does engage the set", PermissionCatalog.isEngaged(stale))
        val removals = PermissionCatalog.removals(declared, stale)
        assertEquals("one declaration must survive", declared.size - 1, removals.size)
        assertTrue(removals.contains("android.permission.RECORD_AUDIO"))
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

        // A locked permission is not in the list whatever the selection says, so switching it off
        // changes nothing at all: this is the same sequence a user would perform.
        val internet = items.first { it.identity == "android.permission.INTERNET" }
        assertEquals(removals, PermissionCatalog.removals(before, selection.toggle(internet)))
    }
}
