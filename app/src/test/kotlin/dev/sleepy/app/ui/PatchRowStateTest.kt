package dev.sleepy.app.ui

import dev.sleepy.app.model.BlocklistCoverage
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.PermissionCoverage
import dev.sleepy.app.patches.DeclaredPermissions
import dev.sleepy.app.patches.DiscordBlocklistPatch
import dev.sleepy.app.patches.DiscordBlocklistRules
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.patches.PatchRegistry
import dev.sleepy.app.patches.PatchSections
import dev.sleepy.app.patches.PermissionCatalog
import dev.sleepy.app.ui.state.InertKind
import dev.sleepy.app.ui.state.PatchRow
import dev.sleepy.app.ui.state.PatchRows
import dev.sleepy.app.ui.state.PatchSectionRows
import dev.sleepy.app.ui.state.TriState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows the selection screen renders: tri-state section switches, locked rows, and stable keys.
 *
 * A section switch reports partial selection rather than rounding to on or off, a row that cannot
 * be switched says which of the reasons applies, and every row carries a key that is stable and
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
    fun theSectionSwitchIsTriStateAndCountsItemsRatherThanSets() {
        val off = sectionsOf(hermes, PatchSelection())
        assertEquals("nothing selected is off", TriState.NONE, off.single { it.label == ADS }.triState)
        assertEquals(
            "every item of the set is under some section",
            38,
            off.sumOf { it.itemCount }
        )
        assertTrue("nothing is on", off.all { it.selectedItemCount == 0 })

        val everything = PatchSelection().setEnabled(hermesItems, true)
        assertEquals(38, sectionsOf(hermes, everything).sumOf { it.selectedItemCount })
        assertTrue(
            "everything on is on in every section",
            sectionsOf(hermes, everything).all { it.triState == TriState.ALL }
        )

        // The case the whole per-item split exists for: one feature on and everything else off
        // reads as partly on, not as on and not as off. The group is one with several items in
        // it, so the selection is a real part of the section rather than a single row.
        val giftOnly = PatchSelection().with(hermesItems.filter { it.group == MULTI_ITEM_GROUP })
        val partial = sectionsOf(hermes, giftOnly)
        assertTrue(
            "the group has to hold more than one item for the case to mean anything",
            giftOnly.keys.size >= 2
        )
        assertEquals(TriState.PARTIAL, partial.single { it.label == DECLUTTER }.triState)
        assertEquals(
            "the count is of items, not of sets",
            giftOnly.keys.size,
            partial.single { it.label == DECLUTTER }.selectedItemCount
        )
        assertEquals(
            "and the section the selection is not in stays off",
            TriState.NONE,
            partial.single { it.label == ADS }.triState
        )
        assertEquals(38, partial.sumOf { it.itemCount })

        assertEquals(
            "an id that names nothing selects nothing rather than everything",
            TriState.NONE,
            PatchRows.triState(emptyList(), PatchSelection.ofKeys("discord_hermes:fn83581"))
        )
    }

    @Test
    fun aTapOnASectionSwitchCompletesAPartialSectionAndClearsAFullOne() {
        assertFalse("a section that is not fully on reports off", PatchRows.headerChecked(TriState.NONE))
        assertFalse("and so does a partly selected one", PatchRows.headerChecked(TriState.PARTIAL))
        assertTrue("only a fully selected section reports on", PatchRows.headerChecked(TriState.ALL))

        val items = hermesItems.filter { it.section == DECLUTTER }
        val partial = PatchSelection().with(listOf(items.first()))
        assertTrue("the section has to hold more than one item", items.size > 1)

        val completed = partial.setEnabled(
            items,
            !PatchRows.headerChecked(PatchRows.triState(items, partial))
        )
        assertEquals(
            "a tap on a partly selected section selects everything in it",
            items.size,
            completed.selected(items).size
        )
        assertEquals(TriState.ALL, PatchRows.triState(items, completed))
        assertEquals(
            "and only the section it belongs to moved",
            TriState.NONE,
            PatchRows.triState(hermesItems.filter { it.section == ADS }, completed)
        )

        val cleared = completed.setEnabled(
            items,
            !PatchRows.headerChecked(PatchRows.triState(items, completed))
        )
        assertEquals("a tap on a fully selected section clears it", emptySet<String>(), cleared.keys)
        assertEquals(TriState.NONE, PatchRows.triState(items, cleared))

        val emptied = PatchSelection().setEnabled(
            items,
            !PatchRows.headerChecked(PatchRows.triState(items, PatchSelection()))
        )
        assertEquals(
            "and a tap on an empty section selects everything in it",
            items.size,
            emptied.selected(items).size
        )
    }

    @Test
    fun everyRowIsItsItemAndKeepsTheTextTheCatalogGivesIt() {
        val sections = sectionsOf(hermes, PatchSelection().setEnabled(hermesItems, true))

        sections.forEach { section ->
            // The rows of a section are its items, in the catalogue's own order: the list has no
            // second level, so the section is the only grouping and the order inside it is the
            // order the items were declared in.
            val expected = hermesItems.filter { it.section == section.label }
            assertEquals(expected.map { it.key }, section.rows.map { it.key })
            assertEquals(expected.map { it.label }, section.rows.map { it.label })
            assertEquals(expected.map { it.description }, section.rows.map { it.description })
            assertTrue(
                "${section.label} has a row that is not its item",
                section.rows.all { it.item != null }
            )
        }
        assertTrue(
            "every patched function is switchable and on",
            sections.flatMap { it.rows }.all { it.switchable && it.enabled }
        )
        assertTrue(
            "and every row says which function it rewrites",
            sections.flatMap { it.rows }.all { !it.technicalTarget.isNullOrBlank() }
        )
    }

    @Test
    fun theJavaScriptSetIsListedUnderTheSectionsItsGroupsName() {
        val sections = sectionsOf(hermes, PatchSelection())

        assertEquals(
            "the sections are [PatchSections.ALL] order and no other",
            PatchSections.ALL.filter { label -> sections.any { it.label == label } },
            sections.map { it.label }
        )
        assertTrue(
            "a section has to head rows: an empty one is a heading with no switch behind it",
            sections.all { it.rows.isNotEmpty() }
        )
        assertEquals(38, sections.sumOf { it.rows.size })
        assertEquals(
            "the rows of a section are exactly the items filed under it",
            hermesItems.map { it.section to it.key }.toSet(),
            sections.flatMap { section -> section.rows.map { section.label to it.key } }.toSet()
        )
    }

    @Test
    fun theBlocklistKeepsItsRuleOrderAndItsTwoMatchingRegimes() {
        val network = networkSection(PatchSelection())

        assertEquals(
            "the gates are applied before the rules, so they are the first two rows",
            listOf("const-string v2, \"/api/\"", "const-string v2, \"/external/\""),
            network.rows.take(2).map { it.technicalTarget }
        )
        assertEquals(
            "every rule follows, in the order the interceptor is compiled in",
            blocklistItems.map { it.key },
            network.rows.drop(2).map { it.key }
        )
        assertEquals(
            "every rule row is a rule the coverage table evaluated, so none lost its graying",
            81,
            network.rows.drop(2).size
        )

        // The two matching regimes survive as text now that the headings that named them are
        // gone: a rule still says whether it is tested against every URL or only Discord API calls,
        // which is what makes one rule able to cover another.
        val hostPatterns = DiscordBlocklistRules.HOST_RULES.map { it.pattern }.toSet()
        network.rows.drop(2).forEach { row ->
            val expected = if (row.label in hostPatterns) {
                "Tested against every request URL."
            } else {
                "Tested only against Discord API URLs"
            }
            assertTrue(
                "${row.label} does not say which kind of request it is tested against, got: " +
                    row.detail,
                row.detail.orEmpty().startsWith(expected)
            )
        }
    }

    @Test
    fun aCoveredRuleIsGrayedWithThePatternThatCoversIt() {
        val questHome = row(rowsOf(blocklist, PatchSelection().setEnabled(blocklistItems, true)), "/quest-home")

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
        assertFalse(row(rowsOf(blocklist, withEverything), "/quest-home").switchable)

        val rebuilt = rowsOf(blocklist, withEverything.without(listOf(quest)))
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
            rebuilt.filter { it.inertKind == InertKind.COVERED }.map { it.label }
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
        val network = networkSection(PatchSelection())
        val gates = network.rows.take(2)

        assertEquals("the gates are not items and are not counted as any", 81, network.itemCount)
        assertEquals(83, network.rows.size)
        assertEquals(0, network.selectedItemCount)
        gates.forEach { gate ->
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
    }

    @Test
    fun noRowIsGrayedWithoutSayingWhyAndNothingElseIsGrayed() {
        val states = listOf(PatchSelection(), PatchSelection().setEnabled(blocklistItems, true))

        PatchRegistry.all.forEach { set ->
            states.forEach { selection ->
                sectionsOf(set, selection).flatMap { it.rows }.forEach { row ->
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
        // The screen hands the whole source to the builder, so the keys are checked the way the
        // list receives them: one key per section, one per row, and no two alike anywhere.
        val selection = PatchSelection().setEnabled(blocklistItems, true)
        val sections = PatchRows.sectionsOf(PatchRegistry.all, selection)
        val keys = sections.map { PatchRows.sectionKey(it.label) } +
            sections.flatMap { it.rows.map { row -> row.key } }
        assertEquals(
            "a lazy list keys rows by these, and two equal keys is a crash: " +
                "${keys.size - keys.distinct().size} duplicate(s)",
            keys.size,
            keys.distinct().size
        )
        assertEquals(
            "the same selection renders the same keys, so nothing is rebuilt that did not change",
            keys,
            PatchRows.sectionsOf(PatchRegistry.all, selection).let { rebuilt ->
                rebuilt.map { PatchRows.sectionKey(it.label) } +
                    rebuilt.flatMap { it.rows.map { row -> row.key } }
            }
        )

        val rowKeys = networkSection(selection).rows.map { it.key }
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
            val sections = sectionsOf(set, PatchSelection())
            val rows = sections.flatMap { it.rows }

            assertEquals(
                "the set contributes one row, under the section its item was filed in",
                listOf(item.key),
                rows.map { it.key }
            )
            assertEquals(listOf(item.section), sections.map { it.label })
            assertTrue(
                "a saved set id has to select the item that now stands for the set",
                PatchSelection.fromSavedIds(listOf(set.id), PatchItemCatalog).contains(item)
            )
        }
    }

    /**
     * A whole-set item has no entry of its own to name what it rewrites, so its row carries the
     * set's smali targets: the set's technical panel used to be where they were read, and the
     * panel went away with the set cards.
     */
    @Test
    fun aWholeSetItemRowNamesTheSmaliItsSetRewrites() {
        val wholeSet = PatchRegistry.all.filter {
            it.smaliPatches.isNotEmpty() && PatchItemCatalog.itemsOf(it.id).size == 1
        }
        assertTrue("the Discord sets are the whole-set ones", wholeSet.isNotEmpty())

        wholeSet.forEach { set ->
            val row = rowsOf(set, PatchSelection()).single()
            val target = row.technicalTarget.orEmpty()
            val detail = row.detail.orEmpty()
            set.smaliPatches.forEach { patch ->
                assertTrue(
                    "${row.key} does not name ${patch.smaliPath}, its row reads: $target",
                    target.contains(patch.smaliPath)
                )
                assertTrue(
                    "${row.key} has no text behind its target",
                    detail.contains(patch.smaliPath)
                )
                patch.title?.takeIf { it.isNotBlank() }?.let { title ->
                    assertTrue(
                        "${row.key} does not carry ${patch.smaliPath}'s title, got: $detail",
                        detail.contains(title)
                    )
                }
            }
        }
    }

    @Test
    fun aPartialSelectionSwitchesOnExactlyTheItemsItNames() {
        val group = hermesItems.filter { it.group == MULTI_ITEM_GROUP }
        val rows = rowsOf(hermes, PatchSelection().with(group))
        val sections = sectionsOf(hermes, PatchSelection().with(group))

        assertEquals(group.size, rows.count { it.enabled })
        assertEquals(
            group.map { it.key }.sorted(),
            rows.filter { it.enabled }.map { it.key }.sorted()
        )
        assertEquals(
            TriState.PARTIAL,
            sections.single { it.label == DECLUTTER }.triState
        )
    }

    /**
     * A heading's own count reports the rows beneath it that can be switched, so the two never
     * disagree.
     *
     * The case that makes this its own function is the blocklist's gates: they are rows with no
     * item and no switch, so a heading that counted every row it drew would report a group of
     * gates as partly selected forever. The permission heading is the one that reads it now.
     */
    @Test
    fun aHeadingCountsOnlyTheRowsUnderItThatCanBeSwitched() {
        val rows = rowsOf(blocklist, PatchSelection())
        val gates = rows.filter { !it.switchable }
        val rules = rows.filter { it.switchable }
        assertTrue("the gates have to be rows without a switch", gates.isNotEmpty())

        assertEquals("nothing selected is off", TriState.NONE, PatchRows.triStateOfRows(rules))

        val allOn = rules.map { it.copy(enabled = true) }
        assertEquals("every one of them on is on", TriState.ALL, PatchRows.triStateOfRows(allOn))

        val oneOn = allOn.mapIndexed { index, row -> row.copy(enabled = index == 0) }
        assertEquals(
            "and one of them on is neither",
            TriState.PARTIAL,
            PatchRows.triStateOfRows(oneOn)
        )

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

        val rows = rowsOf(hermes, PatchSelection().setEnabled(hermesItems, true))
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

    /** The sections [set]'s items land in, for [selection], as the screen builds them. */
    private fun sectionsOf(set: PatchSet, selection: PatchSelection): List<PatchSectionRows> =
        PatchRows.sectionsOf(listOf(set), selection)

    /** Every row [set] contributes, across the sections its items were filed in. */
    private fun rowsOf(set: PatchSet, selection: PatchSelection): List<PatchRow> =
        sectionsOf(set, selection).flatMap { it.rows }

    /** The one section the blocklist's rules are under, gates and all. */
    private fun networkSection(selection: PatchSelection): PatchSectionRows =
        sectionsOf(blocklist, selection).single { it.label == PatchSections.NETWORK }

    /** The row for [label] in a set's rows, as the screen finds it. */
    private fun row(rows: List<PatchRow>, label: String): PatchRow =
        rows.first { it.label == label }

    private companion object {
        /** The catalog's label for the group the gift buttons are listed under. */
        const val MULTI_ITEM_GROUP = "Profile decorations"

        /** The section the gifts are filed under, from the group above. */
        const val DECLUTTER = PatchSections.DECLUTTER

        /** The section the shop and its offers are filed under. */
        const val ADS = PatchSections.ADS

        /** The package the dead declarations belong to, and one they do not. */
        const val DISCORD = "com.discord"
        const val OCTOGRAM = "it.octogram.android"
    }
}
