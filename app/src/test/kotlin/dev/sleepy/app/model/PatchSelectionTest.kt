package dev.sleepy.app.model

import dev.sleepy.app.patches.DiscordBlocklistPatch
import dev.sleepy.app.patches.DiscordBlocklistRules
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.patches.PatchRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-item selection: item keys, the set switch, and the blocklist's coverage rule.
 *
 * Blocklist coverage is recomputed from the enabled rules on every read rather than cached. A
 * rule is covered when another enabled rule matches every URL it would match; switching the
 * covering rule off makes the covered rules switchable again.
 */
class PatchSelectionTest {

    private val hermesItems = DiscordHermesFunctionCatalog.items()
    private val blocklistItems = DiscordBlocklistPatch.items()

    @Test
    fun anOldSelectionOfSetIdsStillMeansEveryItemOfThoseSets() {
        val selection = PatchSelection.fromSavedIds(
            savedIds = listOf(DiscordPatches.HERMES.id, DiscordBlocklistPatch.NETWORK_BLOCKLIST.id),
            source = PatchItemCatalog
        )

        assertEquals("both sets in full", 37 + 81, selection.keys.size)
        assertEquals(37, selection.selected(hermesItems).size)
        assertEquals(81, selection.selected(blocklistItems).size)

        val untouched = PatchRegistry.all
            .filter {
                it.id != DiscordPatches.HERMES.id &&
                    it.id != DiscordBlocklistPatch.NETWORK_BLOCKLIST.id
            }
        untouched.forEach { set ->
            assertFalse(
                "saving one set's id must not switch on another set: ${set.id}",
                selection.isEnabled(PatchItemCatalog.itemsOf(set.id))
            )
        }
    }

    @Test
    fun anItemKeyRoundTripsAndAnUnknownIdIsKeptRatherThanCrashing() {
        // An item key written by this version is its own identity, so it round-trips unchanged.
        val current = DiscordHermesFunctionCatalog.itemKeyOf("analytics_events")
        assertEquals(
            setOf(current),
            PatchSelection.fromSavedIds(listOf(current), PatchItemCatalog).keys
        )
        // A key written by 3.2.0 names one function; an item is a feature now, so reading it
        // resolves to the feature that covers that function instead of matching nothing.
        assertEquals(
            "a selection saved before items were features still means the same choice",
            setOf(current),
            PatchSelection.fromSavedIds(listOf("discord_hermes:fn83581"), PatchItemCatalog).keys
        )
        assertEquals(
            "an id whose set no longer exists has no items, so it is kept as it was written",
            setOf("some_set_from_an_older_release"),
            PatchSelection.fromSavedIds(listOf("some_set_from_an_older_release"), PatchItemCatalog)
                .keys
        )
        assertEquals(
            "blank saved values are not selections",
            emptySet<String>(),
            PatchSelection.fromSavedIds(listOf("", "   "), PatchItemCatalog).keys
        )
    }

    @Test
    fun aSetSwitchSelectsAndDeselectsEveryItemOfTheSet() {
        var selection = PatchSelection()
        selection = selection.setEnabled(hermesItems, true)
        assertEquals(37, selection.selected(hermesItems).size)

        selection = selection.toggle(hermesItems.first())
        assertEquals(
            "the set switch is not the last item state",
            36,
            selection.selected(hermesItems).size
        )

        selection = selection.setEnabled(hermesItems, false)
        assertEquals(emptySet<String>(), selection.keys)

        selection = selection.setEnabled(hermesItems, true)
        assertEquals(
            "switching the set back on restores the whole set",
            37,
            selection.selected(hermesItems).size
        )
    }

    @Test
    fun aSetWithNothingSelectedContributesNothing() {
        val selection = PatchSelection.of(blocklistItems.first())

        assertTrue(selection.isEnabled(blocklistItems))
        assertFalse(
            "a set with no item selected contributes nothing",
            selection.isEnabled(hermesItems)
        )
        assertEquals(
            "and nothing is applied from it",
            emptyList<Int>(),
            DiscordHermesFunctionCatalog.selectPatches(selection).map { it.functionId }
        )
        assertFalse(PatchSelection().isEnabled(hermesItems))
    }

    @Test
    fun anEnabledRuleCoversARuleItMakesRedundant() {
        val rules = DiscordBlocklistRules.ALL
        val quest = rules.first { it.pattern == "/quest" }
        val questHome = rules.first { it.pattern == "/quest-home" }
        assertTrue(
            "precondition: /quest is a substring of /quest-home",
            questHome.pattern.contains(quest.pattern)
        )

        val covered = BlocklistCoverage.evaluate(rules) { it.identity == quest.identity }
            .first { it.rule.identity == questHome.identity }

        assertEquals(
            "every URL matching /quest-home contains /quest, so /quest answers it first",
            quest,
            covered.coveredBy
        )
        assertFalse(covered.switchable)
        assertFalse("coverage does not switch anything on", covered.enabled)
        assertTrue(covered.lockedReason!!.startsWith(BlocklistCoverage.COVERED_REASON_PREFIX))
        assertTrue(
            "the reason has to name the rule that covers it: ${covered.lockedReason}",
            covered.lockedReason!!.contains("/quest")
        )

        val liveAgain = BlocklistCoverage.evaluate(rules) { false }
            .first { it.rule.identity == questHome.identity }
        assertNull("with its coverer off the switch is a real switch again", liveAgain.coveredBy)
        assertTrue(liveAgain.switchable)
    }

    @Test
    fun theRowListRecomputesOnEveryReadOfTheSelection() {
        val quest = DiscordBlocklistRules.ALL.first { it.pattern == "/quest" }

        var rows = DiscordBlocklistPatch.rows(
            PatchSelection.ofKeys(DiscordBlocklistPatch.itemKeyOf(quest))
        )
        assertTrue(ruleRow(rows, "/quest").enabled)
        assertFalse(
            "nothing selected it, and something else covers it",
            ruleRow(rows, "/quest-home").switchable
        )

        rows = DiscordBlocklistPatch.rows(PatchSelection())
        assertTrue(
            "the same rule is live again once the coverer is off",
            ruleRow(rows, "/quest-home").switchable
        )
        assertFalse("but it was never switched on", ruleRow(rows, "/quest-home").enabled)
    }

    @Test
    fun aHostRuleCanCoverAnApiRule() {
        // A host rule applies to every URL and an API rule only to /api/ ones, so an enabled host
        // rule can make an API rule redundant. The shipped tables hold no such pair, which the
        // check below asserts, so the pair is constructed here: the rule still has to hold for the
        // direction that the gates allow.
        assertTrue(
            "the shipped tables now contain a host pattern inside an API rule, so this is no " +
                "longer hypothetical",
            DiscordBlocklistRules.ALL.none { rule ->
                rule.kind == BlocklistRuleKind.API &&
                    DiscordBlocklistRules.HOST_RULES.any { it.pattern in rule.pattern }
            }
        )

        val host = BlocklistRule(
            BlocklistRuleKind.HOST,
            "spotify.com",
            "a host rule"
        )
        val api = BlocklistRule(
            BlocklistRuleKind.API,
            "/connections/spotify.com/token",
            "an API rule"
        )
        assertTrue(
            "every URL containing the API rule's path contains the host",
            BlocklistCoverage.covers(host, api)
        )

        val rows = BlocklistCoverage.evaluate(listOf(host, api)) { it.identity == host.identity }
        assertFalse(rows.first { it.rule.identity == api.identity }.switchable)
    }

    @Test
    fun anApiRuleDoesNotCoverAHostRule() {
        // The reverse is not symmetric even though the patterns are: a URL matching the host rule
        // need not contain /api/, so it is not tested against the API rule, and switching the host
        // rule off leaves that URL unblocked.
        val api = BlocklistRule(BlocklistRuleKind.API, "spotify.com", "an API rule")
        val host = BlocklistRule(BlocklistRuleKind.HOST, "api.spotify.com", "a host rule")
        assertTrue(
            "precondition: the API rule's pattern is a substring of the host rule's",
            host.pattern.contains(api.pattern)
        )

        assertFalse(BlocklistCoverage.covers(api, host))
        val rows = BlocklistCoverage.evaluate(listOf(api, host)) { true }
        assertTrue(rows.first { it.rule.identity == host.identity }.switchable)
    }

    @Test
    fun theShippedTablesAreRedundantInExactlyTheReferenceImpliedPlaces() {
        val covered = BlocklistCoverage.evaluate(DiscordBlocklistRules.ALL) { true }
            .filter { it.coveredBy != null }

        assertEquals(
            "these are the only redundant pairs when everything is on, in table order; a change " +
                "here means the reference's tables moved",
            listOf(
                "/users/@me/activities/statistics",
                "/quest-home",
                "/users/@me/billing",
                "/guilds/premium"
            ),
            covered.map { it.rule.pattern }
        )
        assertEquals(
            listOf("/activities/statistics", "/quest", "/billing", "/premium"),
            covered.map { it.coveredBy!!.pattern }
        )
        assertTrue(
            "no host rule is answered for by another rule",
            covered.none { it.rule.kind == BlocklistRuleKind.HOST }
        )
    }

    @Test
    fun theGatesAreLockedWhileEveryRuleIsASwitch() {
        val rows = DiscordBlocklistPatch.rows(PatchSelection())
        assertEquals("the two gates, then every rule", 2 + 81, rows.size)

        val gates = rows.filterIsInstance<BlocklistGateRow>()
        assertEquals(listOf("/api/", "/external/"), gates.map { it.gate.pattern })
        gates.forEach { gate ->
            assertFalse(gate.switchable)
            assertTrue(gate.lockedReason.startsWith(BlocklistCoverage.REQUIRED_REASON_PREFIX))
        }

        val ruleRows = rows.filterIsInstance<BlocklistRuleRow>()
        assertEquals(81, ruleRows.size)
        assertTrue(
            "with nothing switched on there is nothing to be covered by",
            ruleRows.all { it.switchable }
        )
        assertTrue(ruleRows.none { it.enabled })
    }

    @Test
    fun aSelectionWithNoRuleOfTheBlocklistGeneratesNothing() {
        val generated = DiscordBlocklistPatch.generatedPatches(
            target = TargetApk(classToDexIndex = emptyMap(), dexEntries = emptyMap()),
            selection = PatchSelection.of(hermesItems.first())
        )
        assertEquals(emptyList<SmaliPatch>(), generated.patches)
        assertNotNull("skipping has to say why", generated.skipReason)
    }

    @Test
    fun selectedRulesAreTheRulesTheInterceptorWillScanFor() {
        val quest = DiscordBlocklistRules.ALL.first { it.pattern == "/quest" }
        val typing = DiscordBlocklistRules.ALL.first { it.pattern == "/typing" }

        val selection = PatchSelection.ofKeys(
            DiscordBlocklistPatch.itemKeyOf(typing),
            DiscordBlocklistPatch.itemKeyOf(quest)
        )
        assertEquals(
            "table order, not selection order",
            listOf("/quest", "/typing"),
            DiscordBlocklistPatch.selectedRules(selection).map { it.pattern }
        )
        assertEquals(
            emptyList<String>(),
            DiscordBlocklistPatch.selectedRules(PatchSelection()).map { it.pattern }
        )
    }

    /** The rule row for [pattern], as the UI finds it. */
    private fun ruleRow(rows: List<BlocklistRow>, pattern: String): BlocklistRuleRow =
        rows.filterIsInstance<BlocklistRuleRow>().first { it.rule.pattern == pattern }
}
