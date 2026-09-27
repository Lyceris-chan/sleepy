package dev.sleepy.app

import dev.sleepy.app.model.BlocklistCoverage
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.groupedByFeature
import dev.sleepy.app.patches.DiscordBlocklistPatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.patches.PatchRegistry
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
 * The state the selection screen is rendered from: the tri-state a set's own switch reads, which
 * switches are live and why the others are not, and the keys the lazy list identifies rows by.
 *
 * These are the two behaviours the per-item screen exists for. A set's switch has to be able to say
 * "some of this set is on" rather than rounding that to on or off — the user asked for exactly the
 * case where the gift button is on and the rest of the JavaScript set is not — and a greyed row has
 * to say which of the two reasons it is greyed for, because they are different claims and one of
 * them stops being true the moment the rule covering it is switched off.
 */
class PatchRowStateTest {

    private val hermes = requireNotNull(PatchRegistry.get(DiscordPatches.HERMES.id))
    private val blocklist = requireNotNull(PatchRegistry.get(DiscordBlocklistPatch.NETWORK_BLOCKLIST.id))
    private val hermesItems = PatchItemCatalog.itemsOf(DiscordPatches.HERMES.id)
    private val blocklistItems = PatchItemCatalog.itemsOf(DiscordBlocklistPatch.NETWORK_BLOCKLIST.id)

    @Test
    fun theSetSwitchIsTriStateAndCountsItemsRatherThanSets() {
        val off = PatchRows.of(hermes, PatchSelection())
        assertEquals("nothing selected is off", TriState.NONE, off.triState)
        assertEquals(142, off.itemCount)
        assertEquals(0, off.selectedItemCount)

        val everything = PatchSelection().setEnabled(hermesItems, true)
        assertEquals(TriState.ALL, PatchRows.of(hermes, everything).triState)
        assertEquals(142, PatchRows.of(hermes, everything).selectedItemCount)

        // The case the whole per-item split exists for: the gift button on and everything else off
        // reads as partly on, not as on and not as off.
        val giftOnly = PatchSelection().with(hermesItems.filter { it.group == GIFT_GROUP })
        val partial = PatchRows.of(hermes, giftOnly)
        assertTrue("the gift buttons have to be a real group of their own", giftOnly.keys.size >= 2)
        assertEquals(TriState.PARTIAL, partial.triState)
        assertEquals("the count is of items, not of sets", giftOnly.keys.size, partial.selectedItemCount)
        assertEquals(142, partial.itemCount)

        assertEquals(
            "an id that names nothing selects nothing rather than everything",
            TriState.NONE,
            PatchRows.triState(emptyList(), PatchSelection.ofKeys("discord_hermes:fn73760"))
        )
    }

    @Test
    fun aTapOnASetSwitchCompletesAPartialSetAndClearsAFullOne() {
        assertFalse("a set that is not fully on reports off", PatchRows.headerChecked(TriState.NONE))
        assertFalse("and so does a partly selected one", PatchRows.headerChecked(TriState.PARTIAL))
        assertTrue("only a fully selected set reports on", PatchRows.headerChecked(TriState.ALL))

        val partial = PatchSelection().with(listOf(hermesItems.first()))
        val completed = partial.setEnabled(
            hermesItems,
            !PatchRows.headerChecked(PatchRows.triState(hermesItems, partial))
        )
        assertEquals(
            "a tap on a partly selected set selects everything in it",
            142,
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
        assertEquals("and a tap on an empty set selects everything in it", 142, emptied.selected(hermesItems).size)
    }

    @Test
    fun everyItemIsItsOwnSwitchAndKeepsTheTextTheCatalogGivesIt() {
        val rows = PatchRows.of(hermes, PatchSelection().setEnabled(hermesItems, true))
        val flat = rows.groups.flatMap { it.rows }
        // Grouped, so the rows come back in the order the groups list them rather than in table
        // order — the catalog's own grouping, read through its own function.
        val expected = hermesItems.groupedByFeature().flatMap { it.items }

        assertEquals(142, flat.size)
        assertEquals("the rows are the items, and nothing else", expected.map { it.key }, flat.map { it.key })
        assertEquals(expected.map { it.label }, flat.map { it.label })
        assertEquals(expected.map { it.description }, flat.map { it.description })
        assertEquals("a group heading has to head a group", rows.groups.size, rows.groups.map { it.label }.distinct().size)
        assertTrue("every patched function is switchable and on", flat.all { it.switchable && it.enabled })
        assertTrue("and every row says which function it rewrites", flat.all { !it.technicalTarget.isNullOrBlank() })
    }

    @Test
    fun theJavaScriptSetIsListedUnderItsFeatureGroups() {
        val rows = PatchRows.of(hermes, PatchSelection())

        assertEquals(
            "a hundred and forty-two rows in one list is the same problem as one switch",
            DiscordHermesFunctionCatalog.GROUPS,
            rows.groups.map { it.label }
        )
        assertEquals(142, rows.groups.sumOf { it.rows.size })
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
            "every rule row is a rule the coverage table evaluated, so none lost its greying",
            81,
            groups.sumOf { it.rows.size }
        )
    }

    @Test
    fun aCoveredRuleIsGreyedWithThePatternThatCoversIt() {
        val rows = PatchRows.of(blocklist, PatchSelection().setEnabled(blocklistItems, true))
        val questHome = row(rows, "/quest-home")

        assertEquals(InertKind.COVERED, questHome.inertKind)
        assertFalse(questHome.switchable)
        assertTrue("coverage does not switch anything off, it only fixes the switch", questHome.enabled)
        assertTrue(questHome.inertReason!!.startsWith(BlocklistCoverage.COVERED_REASON_PREFIX))
        assertTrue(
            "the reason has to name the rule that covers it, got: ${questHome.inertReason}",
            questHome.inertReason.contains("\"/quest\"")
        )
        assertEquals("and it is still a row that exists", "/quest-home", questHome.label)
        assertTrue("a greyed row keeps the description it had", questHome.description.isNotBlank())
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
        assertTrue("switching the coverer off does not switch the covered rule off", liveAgain.enabled)
        assertEquals(
            "the other three redundancies are untouched",
            listOf("/users/@me/activities/statistics", "/users/@me/billing", "/guilds/premium"),
            rebuilt.groups.flatMap { it.rows }.filter { it.inertKind == InertKind.COVERED }.map { it.label }
        )

        val withoutQuest = withEverything.without(listOf(quest))
        assertEquals(
            "only the rule that was switched off left the selection; the greying moved and nothing else",
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
            assertTrue("a gate is applied whatever the user selected, so it reads as on", gate.enabled)
            assertTrue(gate.inertReason!!.startsWith(BlocklistCoverage.REQUIRED_REASON_PREFIX))
            assertTrue(gate.description.isNotBlank())
        }

        assertEquals("the gates are not items and are not counted as any", 81, rows.itemCount)
        assertEquals(83, rows.groups.sumOf { it.rows.size })
        assertEquals(0, rows.selectedItemCount)
    }

    @Test
    fun noRowIsGreyedWithoutSayingWhyAndNothingElseIsGreyed() {
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
                        assertTrue("${set.id} ${row.key} is neither switchable nor inert", row.switchable)
                        assertNotNull("${set.id} ${row.key} is not inert and has no item", row.item)
                        assertNull("${set.id} ${row.key} has a reason without a kind", row.inertReason)
                    }
                }
            }
        }
    }

    @Test
    fun everyRowKeyIsStableUniqueAndItsOwn() {
        val keys = mutableListOf<String>()
        PatchRegistry.all.forEach { set ->
            val rows = PatchRows.of(set, PatchSelection().setEnabled(PatchItemCatalog.itemsOf(set.id), true))
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
        assertTrue("most sets have nothing to choose between, and they must keep working", single.isNotEmpty())

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
        val gift = hermesItems.filter { it.group == GIFT_GROUP }
        val rows = PatchRows.of(hermes, PatchSelection().with(gift)).groups.flatMap { it.rows }

        assertEquals(gift.size, rows.count { it.enabled })
        assertEquals(gift.map { it.key }.sorted(), rows.filter { it.enabled }.map { it.key }.sorted())
        assertEquals(TriState.PARTIAL, PatchRows.of(hermes, PatchSelection().with(gift)).triState)
    }

    @Test
    fun theReferenceAuditNoteForAFunctionReachesItsRow() {
        val audited = DiscordPatches.HERMES.hermesPatches
        assertTrue("the audit table is what makes the extracted bodies reviewable", audited.isNotEmpty())

        val rows = PatchRows.of(hermes, PatchSelection().setEnabled(hermesItems, true))
            .groups.flatMap { it.rows }
            .associateBy { it.key }

        audited.forEach { patch ->
            val key = DiscordHermesFunctionCatalog.itemKeyOf(patch.functionId.toInt())
            val row = rows[key]
            assertNotNull("function ${patch.functionId} is audited but has no row", row)
            assertTrue(
                "function ${patch.functionId}'s audit note has to reach the row, got: ${row!!.detail}",
                row.detail!!.contains(patch.title!!)
            )
            assertTrue(row.detail.contains(patch.explanation!!))
            assertTrue(
                "and the row has to name the function it rewrites",
                row.technicalTarget!!.contains(patch.functionId)
            )
        }
    }

    /** The row for [label] in a set's rows, as the screen would find it. */
    private fun row(rows: PatchSetRows, label: String): PatchRow =
        rows.groups.flatMap { it.rows }.first { it.label == label }

    private companion object {
        /** The catalog's label for the group the gift buttons are listed under. */
        const val GIFT_GROUP = "Gift buttons"
    }
}
