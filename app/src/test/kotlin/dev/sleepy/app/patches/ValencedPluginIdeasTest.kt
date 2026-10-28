package dev.sleepy.app.patches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hide-only ideas taken from the Valenced plugin repositories.
 *
 * A Valenced plugin can replace a component at runtime; in the bundle we can only replace the
 * function. Each target here is a component or a height calculation, so a null or zero return is
 * the whole change. The test pins that shape: a target that started returning a value would keep
 * drawing, and a component left as a byte edit would keep its surface alive.
 */
class ValencedPluginIdeasTest {

    private data class Idea(val slug: String, val group: String, val section: String)

    private val ideas = listOf(
        Idea("row_separators", "Rows and dividers", PatchSections.DECLUTTER),
        Idea("dm_activity_cards", "Activity cards", PatchSections.DECLUTTER),
        Idea("call_screen_extras", "Call screen", PatchSections.CALLS),
        Idea("conjure_ai", "AI features", PatchSections.DECLUTTER),
        Idea("display_name_styles", "Profile decorations", PatchSections.DECLUTTER),
        Idea("server_themes", "Server themes", PatchSections.DECLUTTER),
        Idea("message_menu_reactions", "Chat display", PatchSections.DECLUTTER),
        Idea("icymi_feed", "Home feed", PatchSections.DECLUTTER),
        Idea("quest_bounties", "Quests", PatchSections.ADS),
        Idea("jump_to_present_button", "Chat display", PatchSections.DECLUTTER)
    )

    @Test
    fun eachIdeaIsOneFeatureInTheExpectedSection() {
        ideas.forEach { idea ->
            val feature = requireNotNull(DiscordHermesFunctionCatalog.feature(idea.slug)) {
                "the ${idea.slug} feature is missing"
            }
            assertEquals(idea.group, feature.group)
            assertEquals(idea.section, PatchSections.forHermesGroup(feature.group))
            assertTrue("${idea.slug} has no functions", feature.functionIds.isNotEmpty())
        }
    }

    @Test
    fun everyTargetReturnsNothing() {
        val targets = ideas.flatMap { idea ->
            requireNotNull(DiscordHermesFunctionCatalog.feature(idea.slug)).functionIds
        }.toSet()
        val patches = DiscordHermesBundlePatch.PATCHES.filter { it.functionId in targets }
        assertEquals(
            "an idea names a function the table does not patch",
            targets,
            patches.map { it.functionId }.toSet()
        )
        val notRemoved = patches.filterNot { patch ->
            val body = patch.replacement
            val first = body.firstOrNull()?.toInt()?.and(0xFF)
            val returns = body.size >= 2 &&
                body[body.size - 2].toInt() and 0xFF == 0x76 &&
                body[body.size - 1].toInt() and 0xFF == 0x00
            returns && (first == 0x94 || first == 0x93 || first == 0x96 || first == 0x8B)
        }
        assertTrue(
            "these targets do not return null, false or zero: " +
                notRemoved.joinToString { "${it.functionId} ${it.name}" },
            notRemoved.isEmpty()
        )
    }
}
