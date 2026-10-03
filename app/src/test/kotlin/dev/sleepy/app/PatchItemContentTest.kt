package dev.sleepy.app

import dev.sleepy.app.model.BlocklistCoverage
import dev.sleepy.app.patches.DiscordBlocklistPatch
import dev.sleepy.app.patches.DiscordBlocklistRules
import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.patches.PatchRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The human-readable content a per-item list is made of: what every patched JavaScript function
 * does, and what every blocklist rule prevents.
 *
 * A patch set that has been split into items is only usable if the items can be told apart. The
 * failure this guards against is a function or a rule that reaches the UI with nothing to say for
 * itself — a blank group, a description that just restates the function's name, or an entry that
 * exists in the patch table and nowhere in the content table.
 */
class PatchItemContentTest {

    @Test
    fun everyPatchedFunctionHasAFeatureGroupAndADescription() {
        val patched = DiscordHermesBundlePatch.PATCHES.map { it.functionId }
        assertEquals("the set is the 145 functions the reference build differs in", 145, patched.size)
        assertEquals(
            "the content table must describe exactly the patched functions, in the same order",
            patched,
            DiscordHermesFunctionCatalog.ENTRIES.map { it.functionId }
        )

        val incomplete = DiscordHermesFunctionCatalog.ENTRIES
            .filter { it.group.isBlank() || it.description.isBlank() }
        assertEquals("every function needs a group and a description", emptyList<Int>(), incomplete.map { it.functionId })

        assertEquals(
            "every group label must be non-blank",
            emptyList<String>(),
            DiscordHermesFunctionCatalog.GROUPS.filter { it.isBlank() }
        )

        // A hundred and forty-two rows in one list is the same problem as one switch, so the split
        // has to be a real one rather than a formality.
        assertTrue(
            "the functions must be split into feature groups, got ${DiscordHermesFunctionCatalog.GROUPS.size}",
            DiscordHermesFunctionCatalog.GROUPS.size >= 12
        )
        assertEquals(
            "a group label must name one feature, not be reused for a second",
            DiscordHermesFunctionCatalog.GROUPS.size,
            DiscordHermesFunctionCatalog.GROUPS.distinct().size
        )
    }

    @Test
    fun everyDescriptionSaysWhatTheChangeDoesRatherThanRestatingTheName() {
        val names = DiscordHermesBundlePatch.PATCHES.associate { it.functionId to it.name }
        val restated = DiscordHermesFunctionCatalog.ENTRIES.filter { entry ->
            val name = names[entry.functionId].orEmpty()
            name.isNotBlank() && entry.description.equals(name, ignoreCase = true)
        }
        assertEquals(
            "a description that is just the function's name tells the user nothing",
            emptyList<Int>(),
            restated.map { it.functionId }
        )
        val tooShort = DiscordHermesFunctionCatalog.ENTRIES.filter { it.description.length < 24 }
        assertEquals(
            "a one-line description still has to be a sentence",
            emptyList<Int>(),
            tooShort.map { it.functionId }
        )
    }

    @Test
    fun everyItemHasAKeyThatDoesNotDependOnItsPosition() {
        val items = DiscordHermesFunctionCatalog.items()
        assertEquals(145, items.size)
        assertEquals(
            "two items that share a key are one item as far as a saved selection is concerned",
            items.size,
            items.map { it.key }.distinct().size
        )
        assertEquals(
            "the label is what the user reads, so two rows must not read the same",
            items.size,
            items.map { it.label }.distinct().size
        )
        assertEquals(
            "every item must carry the set it belongs to",
            setOf(DiscordPatches.HERMES.id),
            items.map { it.setId }.toSet()
        )

        // The scheme, stated: the set id, then an identity that is the function id — because the
        // names are neither unique nor stable. Ten functions here have no name and three share one.
        assertEquals("discord_hermes:fn62294", DiscordHermesFunctionCatalog.itemKeyOf(62294))
        val typing = DiscordBlocklistRules.API_RULES.first { it.pattern == "/typing" }
        assertEquals("discord_native_blocklist:api:/typing", DiscordBlocklistPatch.itemKeyOf(typing))
    }

    @Test
    fun everyBlocklistEntryHasADescription() {
        val rules = DiscordBlocklistRules.ALL
        assertEquals("the two tables are the reference's 20 host and 61 API rules", 81, rules.size)
        assertEquals(20, DiscordBlocklistRules.HOST_RULES.size)
        assertEquals(61, DiscordBlocklistRules.API_RULES.size)
        assertEquals(
            "a rule with no description is a switch whose effect the user cannot know",
            emptyList<String>(),
            rules.filter { it.description.isBlank() }.map { it.identity }
        )
        assertEquals(
            "two rules with the same identity would be one switch",
            rules.size,
            rules.map { it.identity }.distinct().size
        )
        assertEquals(
            "the items must follow the order the interceptor is compiled in",
            rules.map { it.identity },
            DiscordBlocklistPatch.items().map { it.identity }
        )
        assertEquals(
            "an item's description is the rule's description",
            rules.map { it.description },
            DiscordBlocklistPatch.items().map { it.description }
        )
    }

    @Test
    fun theGatesAreDescribedAndCarryTheirOwnReason() {
        val gates = DiscordBlocklistRules.GATES
        assertEquals("the interceptor has two prefix gates", 2, gates.size)
        assertEquals(listOf("/api/", "/external/"), gates.map { it.pattern })
        gates.forEach { gate ->
            assertTrue("gate ${gate.pattern} needs a description", gate.description.isNotBlank())
            assertTrue("gate ${gate.pattern} needs a label", gate.label.isNotBlank())
            assertTrue(
                "a gate's reason is a different claim from a covered rule's, and says so: ${gate.reason}",
                gate.reason.startsWith(BlocklistCoverage.REQUIRED_REASON_PREFIX)
            )
            assertTrue(
                "gate ${gate.pattern} must not be phrased as redundancy",
                !gate.reason.startsWith(BlocklistCoverage.COVERED_REASON_PREFIX)
            )
        }
    }

    @Test
    fun everyRegisteredSetHasAtLeastOneItem() {
        PatchRegistry.all.forEach { set ->
            assertTrue(
                "set ${set.id} must have an item, or its switch would select nothing",
                PatchItemCatalog.itemsOf(set.id).isNotEmpty()
            )
        }
        assertEquals(
            "an id that names no set has no items, which is how an item key looks to the catalog",
            emptyList<String>(),
            PatchItemCatalog.itemsOf("no_such_set_id")
        )
    }
}
