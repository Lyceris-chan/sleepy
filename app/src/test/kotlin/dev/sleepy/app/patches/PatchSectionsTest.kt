package dev.sleepy.app.patches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The section every patch set and every JavaScript feature is filed under.
 *
 * The section is derived from two tables rather than written at each call site, and an id or group
 * neither table names falls back to App fixes. The fallback keeps the screen working; these tests
 * are what stop a set added without a section from reaching it in silence, because a set that
 * lands under the wrong heading looks exactly like one that belongs there.
 */
class PatchSectionsTest {

    /**
     * The cover is exact in both directions.
     *
     * A set in the registry and not in the table falls back, which is the failure this file
     * exists for. An entry for a set that no longer exists is the other half: it would keep a
     * removed set's id in the table and make the next reader believe the set is still there.
     */
    @Test
    fun noRegisteredSetFallsBackToTheDefaultSection() {
        val registered = PatchRegistry.all.map { it.id }.toSet()
        // The JavaScript set is the one set not filed by its id: its items are placed by the
        // group each feature carries, so the id table must name every other set and not this one.
        val splitByGroup = setOf(DiscordPatches.HERMES.id)
        assertEquals(
            "these registered sets name no section and would fall back to ${PatchSections.FIXES}",
            emptySet<String>(),
            registered - PatchSections.SET_SECTIONS.keys - splitByGroup
        )
        assertEquals(
            "these entries name sets that are not registered, so the section table is stale",
            emptySet<String>(),
            PatchSections.SET_SECTIONS.keys - registered
        )
        assertEquals(
            "the Discord JavaScript set is split across the sections by its features' groups, " +
                "so it is the one set whose items the id table must not claim",
            emptySet<String>(),
            PatchSections.SET_SECTIONS.keys intersect splitByGroup
        )
    }

    /**
     * A group's own name is the key here rather than a second taxonomy, and the catalog's groups
     * are the names: a group renamed there and not here would move every item under it to App
     * fixes without another test noticing.
     */
    @Test
    fun noFeatureGroupFallsBackToTheDefaultSection() {
        val groups = DiscordHermesFunctionCatalog.GROUPS.toSet()
        assertEquals(
            "these groups name no section and would fall back to ${PatchSections.FIXES}",
            emptySet<String>(),
            groups - PatchSections.HERMES_GROUP_SECTIONS.keys
        )
        assertEquals(
            "these entries name groups the catalog does not declare, so the section table is stale",
            emptySet<String>(),
            PatchSections.HERMES_GROUP_SECTIONS.keys - groups
        )
    }

    @Test
    fun everyItemIsFiledUnderOneOfTheSections() {
        val items = PatchItemCatalog.all()
        assertTrue("there are no items to file", items.isNotEmpty())
        val unfiled = items.filter { it.section !in PatchSections.ALL }
        assertEquals(
            "every item of every registered set needs a section from ${PatchSections.ALL}",
            emptyList<String>(),
            unfiled.map { it.key }
        )
    }

    /**
     * What the mapping says, at the points where a wrong table entry would be felt: the sets that
     * carry a section each rather than a group, and the groups whose names the mapping hard-codes.
     */
    @Test
    fun itemsLandWhereTheMappingSaysTheyDo() {
        fun sectionsOf(setId: String): List<String> =
            PatchItemCatalog.itemsOf(setId).map { it.section }.distinct()

        assertEquals(listOf(PatchSections.NETWORK), sectionsOf(DiscordBlocklistPatch.NETWORK_BLOCKLIST.id))
        assertEquals(listOf(PatchSections.CRASHES), sectionsOf(DiscordPatches.SENTRY.id))
        assertEquals(listOf(PatchSections.TRACKING), sectionsOf(DiscordPatches.TELEMETRY.id))
        assertEquals(listOf(PatchSections.BACKGROUND), sectionsOf(DiscordNativePatches.WATCHDOG.id))
        assertEquals(listOf(PatchSections.TRACKING), sectionsOf(DiscordNativePatches.DEEP_LINKS.id))
        assertEquals(listOf(PatchSections.FIXES), sectionsOf(DiscordPatches.BUNDLE_LOCK.id))
        assertEquals(listOf(PatchSections.ADS), sectionsOf(OctoGramPatches.SPONSORED_MSGS.id))
        assertEquals(listOf(PatchSections.ADS), sectionsOf(OctoGramPatches.PREMIUM_SHEETS.id))
        assertEquals(listOf(PatchSections.TRACKING), sectionsOf(OctoGramPatches.FIREBASE_DATATRANSPORT.id))
        assertEquals(listOf(PatchSections.CRASHES), sectionsOf(OctoGramPatches.CRASH_REPORTER.id))
        assertEquals(listOf(PatchSections.FIXES), sectionsOf(OctoGramPatches.OTA_UPDATER.id))

        DiscordHermesFunctionCatalog.FEATURES.forEach { feature ->
            val item = PatchItemCatalog.itemsOf(DiscordPatches.HERMES.id)
                .first { it.identity == feature.slug }
            assertEquals(
                "${feature.slug} is filed by its group, ${feature.group}",
                PatchSections.HERMES_GROUP_SECTIONS.getValue(feature.group),
                item.section
            )
        }
        assertEquals(
            "the groups must spread the JavaScript features over the sections rather than " +
                "collapsing them into one",
            PatchSections.ALL.toSet() - PatchSections.NETWORK,
            PatchItemCatalog.itemsOf(DiscordPatches.HERMES.id).map { it.section }.toSet()
        )
    }

    @Test
    fun everySectionHasANameAndAOneLineDescription() {
        assertEquals(
            "two sections with the same name are one heading as far as the list is concerned",
            PatchSections.ALL.size,
            PatchSections.ALL.distinct().size
        )
        PatchSections.ALL.forEach { section ->
            assertTrue("$section is blank", section.isNotBlank())
            assertTrue(
                "$section has no description to tell the reader what it covers",
                PatchSections.descriptionOf(section).isNotBlank()
            )
        }
    }
}
