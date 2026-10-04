package dev.sleepy.app.ui

import dev.sleepy.app.model.BlocklistCoverage
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.PermissionCoverage
import dev.sleepy.app.model.groupedByFeature
import dev.sleepy.app.patches.DeclaredPermissions
import dev.sleepy.app.patches.DiscordBlocklistPatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.patches.PatchRegistry
import dev.sleepy.app.patches.PermissionCatalog
import dev.sleepy.app.ui.state.InertKind
import dev.sleepy.app.ui.state.PatchRow
import dev.sleepy.app.ui.state.PatchRows
import dev.sleepy.app.ui.state.PatchSetRows
import dev.sleepy.app.ui.state.TriState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows the selection screen renders: tri-state set switches, locked rows, and stable keys.
 *
 * A set switch reports partial selection rather than rounding to on or off, a row that cannot be
 * switched says which of the reasons applies, and every row carries a key that is stable and
 * unique. The permission section is rendered from the same rows.
 */
class PatchRowStateTest {

    private val hermes = requireNotNull(PatchRegistry.get(DiscordPatches.HERMES.id))
    private val blocklist =
        requireNotNull(PatchRegistry.get(DiscordBlocklistPatch.NETWORK_BLOCKLIST.id))
    private val hermesItems = PatchItemCatalog.itemsOf(DiscordPatches.HERMES.id)
    private val blocklistItems =
        PatchItemCatalog.itemsOf(DiscordBlocklistPatch.NETWORK_BLOCKLIST.id)

    @Test
    fun theSetSwitchIsTriStateAndCountsItemsRatherThanSets() {
        val off = PatchRows.of(hermes, PatchSelection())
        assertEquals("nothing selected is off", TriState.NONE, off.triState)
        assertEquals(37, off.itemCount)
        assertEquals(0, off.selectedItemCount)

        val everything = PatchSelection().setEnabled(hermesItems, true)
        assertEquals(TriState.ALL, PatchRows.of(hermes, everything).triState)
        assertEquals(37, PatchRows.of(hermes, everything).selectedItemCount)

        // The case the whole per-item split exists for: one feature on and everything else off
        // reads as partly on, not as on and not as off. The group is one with several items in
        // it, so the selection is a real part of the set rather than a single row.
        val giftOnly = PatchSelection().with(hermesItems.filter { it.group == MULTI_ITEM_GROUP })
        val partial = PatchRows.of(hermes, giftOnly)
        assertTrue(
            "the group has to hold more than one item for the case to mean anything",
            giftOnly.keys.size >= 2
        )
        assertEquals(TriState.PARTIAL, partial.triState)
        assertEquals(
            "the count is of items, not of sets",
            giftOnly.keys.size,
            partial.selectedItemCount
        )
        assertEquals(37, partial.itemCount)

        assertEquals(
            "an id that names nothing selects nothing rather than everything",
            TriState.NONE,
            PatchRows.triState(emptyList(), PatchSelection.ofKeys("discord_hermes:fn83581"))
        )
    }

    @Test
    fun aTapOnASetSwitchCompletesAPartialSetAndClearsAFullOne() {
        assertFalse(
            "a set that is not fully on reports off",
            PatchRows.headerChecked(TriState.NONE)
        )
        assertFalse("and so does a partly selected one", PatchRows.headerChecked(TriState.PARTIAL))
        assertTrue("only a fully selected set reports on", PatchRows.headerChecked(TriState.ALL))

        val partial = PatchSelection().with(listOf(hermesItems.first()))
        val completed = partial.setEnabled(
            hermesItems,
            !PatchRows.headerChecked(PatchRows.triState(hermesItems, partial))
        )
        assertEquals(
            "a tap on a partly selected set selects everything in it",
            37,
            completed.selected(hermesItems).size
        )
        assertEquals(TriState.ALL, PatchRows.triState(hermesItems, completed))

        val cleared = completed.setEnabled(
            hermesItems,
            !PatchRows.headerChecked(PatchRows.triState(hermesItems, completed))
        )
        assertEquals("a tap on a fully selected set clears it", emptySet<String>(), cleared.keys)
        assertEquals(TriState.NONE, PatchRows.triState(hermesItems, cleared))

        val emptied = PatchSelection().setEnabled(
            hermesItems,
            !PatchRows.headerChecked(PatchRows.triState(hermesItems, PatchSelection()))
        )
        assertEquals(
            "and a tap on an empty set selects everything in it",
            37,
            emptied.selected(hermesItems).size
        )
    }

    @Test
    fun everyItemIsItsOwnSwitchAndKeepsTheTextTheCatalogGivesIt() {
        val rows = PatchRows.of(hermes, PatchSelection().setEnabled(hermesItems, true))
        val flat = rows.groups.flatMap { it.rows }
        // Grouped, so the rows come back in the order the groups list them rather than in table
        // order—the catalog's own grouping, read through its own function.
        val expected = hermesItems.groupedByFeature().flatMap { it.items }

        assertEquals(37, flat.size)
        assertEquals(
            "the rows are the items, and nothing else",
            expected.map { it.key },
            flat.map { it.key }
        )
        assertEquals(expected.map { it.label }, flat.map { it.label })
        assertEquals(expected.map { it.description }, flat.map { it.description })
        assertEquals(
            "a group heading has to head a group",
            rows.groups.size,
            rows.groups.map { it.label }.distinct().size
        )
        assertTrue(
            "every patched function is switchable and on",
            flat.all { it.switchable && it.enabled }
        )
        assertTrue(
            "and every row says which function it rewrites",
            flat.all { !it.technicalTarget.isNullOrBlank() }
        )
    }

    @Test
    fun theJavaScriptSetIsListedUnderItsFeatureGroups() {
        val rows = PatchRows.of(hermes, PatchSelection())

        assertEquals(
            "two hundred and four rows in one list is the same problem as one switch",
            DiscordHermesFunctionCatalog.GROUPS,
            rows.groups.map { it.label }
        )
        assertEquals(37, rows.groups.sumOf { it.rows.size })
        assertTrue("a set with more than one item has something to expand into", rows.expandable)
        assertTrue(
            "a group heading has to group: one row per group is a list with extra steps",
            rows.groups.all { it.rows.isNotEmpty() }
        )
    }

    @Test
    fun theBlocklistKeepsItsRuleOrderAndItsTwoMatchingRegimes() {
        val groups = PatchRows.of(blocklist, PatchSelection()).groups.drop(1)

        assertEquals(blocklistItems.groupedByFeature().map { it.label }, groups.map { it.label })
        assertEquals(blocklistItems.map { it.key }, groups.flatMap { it.rows }.map { it.key })
        assertEquals(
            "every rule row is a rule the coverage table evaluated, so none lost its graying",
            81,
            groups.sumOf { it.rows.size }
        )
    }

    @Test
    fun aCoveredRuleIsGrayedWithThePatternThatCoversIt() {
        val rows = PatchRows.of(blocklist, PatchSelection().setEnabled(blocklistItems, true))
        val questHome = row(rows, "/quest-home")

        assertEquals(InertKind.COVERED, questHome.inertKind)
        assertFalse(questHome.switchable)
        assertTrue(
            "coverage does not switch anything off, it only fixes the switch",
            questHome.enabled
        )
        assertTrue(questHome.inertReason!!.startsWith(BlocklistCoverage.COVERED_REASON_PREFIX))
        assertTrue(
            "the reason has to name the rule that covers it, got: ${questHome.inertReason}",
            questHome.inertReason.contains("\"/quest\"")
        )
        assertEquals("and it is still a row that exists", "/quest-home", questHome.label)
        assertTrue("a grayed row keeps the description it had", questHome.description.isNotBlank())
    }

    @Test
    fun turningTheCovererOffMakesTheCoveredRowLiveAgain() {
        val quest = blocklistItems.first { it.label == "/quest" }
        val withEverything = PatchSelection().setEnabled(blocklistItems, true)
        assertFalse(row(PatchRows.of(blocklist, withEverything), "/quest-home").switchable)

        val rebuilt = PatchRows.of(blocklist, withEverything.without(listOf(quest)))
        val liveAgain = row(rebuilt, "/quest-home")

        assertNull("its coverer is off, so nothing covers it any more", liveAgain.inertKind)
        assertNull("and it has no reason to show", liveAgain.inertReason)
        assertTrue(liveAgain.switchable)
        assertTrue(
            "switching the coverer off does not switch the covered rule off",
            liveAgain.enabled
        )
        assertEquals(
            "the other three redundancies are untouched",
            listOf("/users/@me/activities/statistics", "/users/@me/billing", "/guilds/premium"),
            rebuilt.groups.flatMap { it.rows }
                .filter { it.inertKind == InertKind.COVERED }
                .map { it.label }
        )

        val withoutQuest = withEverything.without(listOf(quest))
        assertEquals(
            "only the rule that was switched off left the selection; the graying moved and " +
                "nothing else",
            80,
            withoutQuest.selected(blocklistItems).size
        )
        assertTrue(
            "the rule that became live again is still switched on, so it still compiles in",
            withoutQuest.contains(blocklistItems.first { it.label == "/quest-home" })
        )
    }

    @Test
    fun theGatesAreLockedRowsThatSayTheyAreRequired() {
        val rows = PatchRows.of(blocklist, PatchSelection())
        val gateGroup = rows.groups.first()

        assertEquals(PatchRows.GATE_GROUP_LABEL, gateGroup.label)
        assertEquals(
            "the gates are applied before the rules, so they are listed before them",
            listOf("const-string v2, \"/api/\"", "const-string v2, \"/external/\""),
            gateGroup.rows.map { it.technicalTarget }
        )
        gateGroup.rows.forEach { gate ->
            assertEquals(InertKind.REQUIRED, gate.inertKind)
            assertFalse("a gate has no switch to move", gate.switchable)
            assertNull("a gate is not an item: nothing can select or deselect it", gate.item)
            assertTrue(
                "a gate is applied whatever the user selected, so it reads as on",
                gate.enabled
            )
            assertTrue(gate.inertReason!!.startsWith(BlocklistCoverage.REQUIRED_REASON_PREFIX))
            assertTrue(gate.description.isNotBlank())
        }

        assertEquals("the gates are not items and are not counted as any", 81, rows.itemCount)
        assertEquals(83, rows.groups.sumOf { it.rows.size })
        assertEquals(0, rows.selectedItemCount)
    }

    @Test
    fun noRowIsGrayedWithoutSayingWhyAndNothingElseIsGrayed() {
        val states = listOf(PatchSelection(), PatchSelection().setEnabled(blocklistItems, true))

        PatchRegistry.all.forEach { set ->
            states.forEach { selection ->
                PatchRows.of(set, selection).groups.flatMap { it.rows }.forEach { row ->
                    if (row.inertKind != null) {
                        assertTrue(
                            "${set.id} ${row.label} is inert with no reason to read",
                            !row.inertReason.isNullOrBlank()
                        )
                    } else {
                        assertTrue(
                            "${set.id} ${row.key} is neither switchable nor inert",
                            row.switchable
                        )
                        assertNotNull("${set.id} ${row.key} is not inert and has no item", row.item)
                        assertNull(
                            "${set.id} ${row.key} has a reason without a kind",
                            row.inertReason
                        )
                    }
                }
            }
        }
    }

    @Test
    fun everyRowKeyIsStableUniqueAndItsOwn() {
        val keys = mutableListOf<String>()
        PatchRegistry.all.forEach { set ->
            val rows = PatchRows.of(
                set,
                PatchSelection().setEnabled(PatchItemCatalog.itemsOf(set.id), true)
            )
            keys += PatchRows.setKey(set.id)
            rows.groups.forEach { group ->
                keys += PatchRows.groupKey(set.id, group.label)
                keys += group.rows.map { it.key }
            }
        }
        assertEquals(
            "a lazy list keys rows by these, and two equal keys is a crash: " +
                "${keys.size - keys.distinct().size} duplicate(s)",
            keys.size,
            keys.distinct().size
        )

        val selection = PatchSelection().setEnabled(blocklistItems, true)
        val rowKeys = PatchRows.of(blocklist, selection).groups.flatMap { it.rows }.map { it.key }
        assertEquals(
            "the same selection renders the same keys, so nothing is rebuilt that did not change",
            rowKeys,
            PatchRows.of(blocklist, selection).groups.flatMap { it.rows }.map { it.key }
        )
        assertEquals(
            "an item's row is keyed by the item's own key, which is what a saved selection records",
            blocklistItems.map { it.key },
            rowKeys.drop(2)
        )
        assertTrue(
            "and no rule of the blocklist is keyed as a gate",
            rowKeys.drop(2).none { it.startsWith("gate:") }
        )
    }

    @Test
    fun aSetWithOneItemIsItsOwnItemAndAnOldSetIdStillSelectsIt() {
        val single = PatchRegistry.all.filter { PatchItemCatalog.itemsOf(it.id).size == 1 }
        assertTrue(
            "most sets have nothing to choose between, and they must keep working",
            single.isNotEmpty()
        )

        single.forEach { set ->
            val item = PatchItemCatalog.itemsOf(set.id).first()
            val rows = PatchRows.of(set, PatchSelection())

            assertEquals(1, rows.itemCount)
            assertFalse("a set with one item has nothing to expand into", rows.expandable)
            assertEquals(listOf(item.key), rows.groups.single().rows.map { it.key })
            assertTrue(
                "a saved set id has to select the item that now stands for the set",
                PatchSelection.fromSavedIds(listOf(set.id), PatchItemCatalog).contains(item)
            )
        }
    }

    @Test
    fun aPartialSelectionSwitchesOnExactlyTheItemsItNames() {
        val group = hermesItems.filter { it.group == MULTI_ITEM_GROUP }
        val rows = PatchRows.of(hermes, PatchSelection().with(group)).groups.flatMap { it.rows }

        assertEquals(group.size, rows.count { it.enabled })
        assertEquals(
            group.map { it.key }.sorted(),
            rows.filter { it.enabled }.map { it.key }.sorted()
        )
        assertEquals(TriState.PARTIAL, PatchRows.of(hermes, PatchSelection().with(group)).triState)
    }

    /**
     * A group heading's own switch reports the rows beneath it, so the two never disagree.
     *
     * The case that makes this its own function is the blocklist's gates: they are rows with no
     * item and no switch, so a heading that counted every row it drew would report a group of
     * gates as partly selected forever.
     */
    @Test
    fun aGroupHeadingReportsOnlyTheRowsUnderItThatCanBeSwitched() {
        val decorationRows = PatchRows.of(hermes, PatchSelection()).groups
            .first { it.label == MULTI_ITEM_GROUP }.rows
        assertEquals("nothing selected is off", TriState.NONE, PatchRows.triStateOfRows(decorationRows))

        val allOn = decorationRows.map { it.copy(enabled = it.switchable) }
        assertEquals(
            "every one of them on is on",
            TriState.ALL,
            PatchRows.triStateOfRows(allOn)
        )

        val oneOn = decorationRows.mapIndexed { index, row -> row.copy(enabled = index == 0) }
        assertEquals(
            "and one of them on is neither",
            TriState.PARTIAL,
            PatchRows.triStateOfRows(oneOn)
        )

        val gates = PatchRows.of(blocklist, PatchSelection()).groups
            .first { it.label == PatchRows.GATE_GROUP_LABEL }.rows
        assertTrue("the gates have to be rows without a switch", gates.all { !it.switchable })
        assertEquals(
            "a group with nothing switchable in it is not partly selected",
            TriState.NONE,
            PatchRows.triStateOfRows(gates)
        )
    }

    @Test
    fun theReferenceAuditNoteForAFunctionReachesItsRow() {
        val audited = DiscordPatches.HERMES.hermesPatches
        assertTrue(
            "the audit table is what makes the extracted bodies reviewable",
            audited.isNotEmpty()
        )

        val rows = PatchRows.of(hermes, PatchSelection().setEnabled(hermesItems, true))
            .groups.flatMap { it.rows }
            .associateBy { it.key }

        audited.forEach { patch ->
            // The note reaches the row of the feature the function belongs to, which is the item
            // a user sees; the function itself is listed inside that row's technical panel.
            val feature = requireNotNull(
                DiscordHermesFunctionCatalog.featureOf(patch.functionId.toInt())
            ) { "function ${patch.functionId} is audited but no feature covers it" }
            val key = DiscordHermesFunctionCatalog.itemKeyOf(feature.slug)
            val row = rows[key]
            assertNotNull("function ${patch.functionId} is audited but has no row", row)
            assertTrue(
                "function ${patch.functionId}'s audit note has to reach the row, got: " +
                    "${row!!.detail}",
                row.detail!!.contains(patch.title!!)
            )
            assertTrue(row.detail.contains(patch.explanation!!))
            // The functions an item covers are named in its detail, not its target: the target
            // summarises what is rewritten, and the detail is the list of functions behind it.
            assertTrue(
                "and the row has to name the function it rewrites, got: ${row.detail}",
                row.detail.contains(patch.functionId)
            )
        }
    }

    /**
     * Every permission row the section renders is the same shape as the blocklist's gates: a row
     * with no item, a fixed switch, and the model's own reason for it. None of them is a live
     * switch over a declaration the build has already decided about.
     *
     * The declarations Discord's own pass deletes are the case this exists for. They were rows
     * with an ordinary switch, switched on, under a build that takes them out regardless of the
     * switch position—a control whose position is the opposite of what the run does. The fix is
     * not a second kind of row: it is the same [InertKind.REQUIRED] gray-out the gates use, reached
     * through the model's [PermissionCoverage.ALWAYS_REMOVED_REASON], and this asserts both
     * halves—the switch is fixed, and the row states which declaration it is and what happens to
     * it.
     */
    @Test
    fun thePermissionRowsABuildRemovesItselfAreFixedAndSaySo() {
        val declared = requireNotNull(DeclaredPermissions.forPackage(DISCORD)) {
            "Discord's list is shipped"
        }
        val dead = DiscordPatches.DEAD_PERMISSIONS
        assertTrue(
            "the list should still name every declaration this build strips",
            dead.all { it in declared }
        )

        val selection = PatchSelection().with(PermissionCatalog.itemsOf(declared))
        val rows = PatchRows.permissionRows(declared, selection, DISCORD)
        assertEquals(
            "there is a row per declaration, in the manifest's order—a removed one is shown, " +
                "not hidden",
            declared.map { PermissionCatalog.itemKeyOf(it) },
            rows.map { it.key }
        )

        for (name in dead) {
            val deadRow = rows.first { it.key == PermissionCatalog.itemKeyOf(name) }
            assertFalse("$name is not kept: the run takes it out", deadRow.enabled)
            assertEquals(
                "$name's switch is fixed, so it is inert",
                InertKind.REQUIRED,
                deadRow.inertKind
            )
            assertNull("$name has no item: there is nothing a switch could select", deadRow.item)
            assertFalse("$name's switch cannot be moved", deadRow.switchable)
            assertEquals(
                "$name must say why its switch is fixed",
                PermissionCoverage.ALWAYS_REMOVED_REASON,
                deadRow.inertReason
            )
            assertTrue(
                "$name has no row text to read",
                deadRow.label.isNotBlank() && deadRow.description.isNotBlank()
            )
            val detail = requireNotNull(deadRow.detail) { "$name has no detail to open" }
            assertTrue(
                "$name's detail has to say the run removes it, got: $detail",
                detail.contains("Removed from the manifest of every build")
            )
            assertFalse("$name's detail must not read as kept", detail.contains("Left declared"))
        }

        // The control: a declaration this build does not decide about is an ordinary live switch,
        // switched on, so the graying above is about these names rather than about the section.
        val camera = rows.first {
            it.key == PermissionCatalog.itemKeyOf("android.permission.CAMERA")
        }
        assertTrue(camera.switchable)
        assertTrue("nothing switched it off", camera.enabled)
        assertNull(camera.inertKind)
        assertNotNull("a live row still has its item", camera.item)
        assertTrue(camera.detail!!.contains("Left declared"))

        // And the gate is the package, not the name: the very same list offered for another app
        // shows a live switch over every one of these, which is what the app has to keep doing for
        // a build that still uses them.
        val elsewhere = PatchRows.permissionRows(declared, selection, OCTOGRAM)
        for (name in dead) {
            val row = elsewhere.first { it.key == PermissionCatalog.itemKeyOf(name) }
            assertTrue(
                "$name is removable on a build sleepy does not strip it from",
                row.switchable
            )
            assertTrue(row.enabled)
            assertNull(row.inertKind)
        }
        assertNull(
            "and with no build named at all, nothing is fixed on the build's behalf",
            PatchRows.permissionRows(declared, selection)
                .first { it.key == PermissionCatalog.itemKeyOf(dead.first()) }
                .inertKind
        )
    }

    /** The row for [label] in a set's rows, as the screen finds it. */
    private fun row(rows: PatchSetRows, label: String): PatchRow =
        rows.groups.flatMap { it.rows }.first { it.label == label }

    private companion object {
        /** The catalog's label for the group the gift buttons are listed under. */
        const val MULTI_ITEM_GROUP = "Profile decorations"

        /** The package the dead declarations belong to, and one they do not. */
        const val DISCORD = "com.discord"
        const val OCTOGRAM = "it.octogram.android"
    }
}
