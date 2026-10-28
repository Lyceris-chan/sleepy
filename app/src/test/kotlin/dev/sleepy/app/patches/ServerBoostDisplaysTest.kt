package dev.sleepy.app.patches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server-boost switch is one switch for every boost surface.
 *
 * Each target is a React component or a render method, so the replacement has to return a value
 * React renders as nothing. A component thrown in as a byte edit would keep the surface alive, and
 * a function that returns a value would make the caller dereference it; both look like a working
 * patch in the table.
 */
class ServerBoostDisplaysTest {

    private val feature = requireNotNull(
        DiscordHermesFunctionCatalog.feature("server_boost_displays")
    ) { "the server_boost_displays feature is missing" }

    private val patches = DiscordHermesBundlePatch.PATCHES
        .filter { it.functionId in feature.functionIds }

    @Test
    fun everyBoostSurfaceThereIsNamedByTheSwitch() {
        assertEquals(
            "the feature names functions the table does not patch",
            feature.functionIds.toSet(),
            patches.map { it.functionId }.toSet()
        )
        assertEquals(
            "the feature names the same function twice",
            feature.functionIds.size,
            feature.functionIds.distinct().size
        )
        assertEquals(
            "the switch is filed under the server-boost group and the ads section",
            "Server boosts",
            feature.group
        )
        assertEquals(PatchSections.ADS, PatchSections.forHermesGroup(feature.group))
    }

    @Test
    fun everyTargetHasARemovalStub() {
        val notRemoved = patches.filterNot { patch ->
            val body = patch.replacement
            val startsWithNull = body.isNotEmpty() &&
                (body[0].toInt() and 0xFF == 0x94 || body[0].toInt() and 0xFF == 0x93)
            val endsWithRet = body.size >= 2 &&
                body[body.size - 2].toInt() and 0xFF == 0x76 &&
                body[body.size - 1].toInt() and 0xFF == 0x00
            startsWithNull && endsWithRet
        }
        assertTrue(
            "these boost targets do not return null or undefined: " +
                notRemoved.joinToString { "${it.functionId} ${it.name}" },
            notRemoved.isEmpty()
        )
    }

    @Test
    fun theCoachmarksMovedOutOfTheGuildUpsellSwitch() {
        val coachmarks = setOf(77991, 77992, 77995, 77996)
        assertTrue(
            "the boost coachmarks belong to the boost display switch",
            feature.functionIds.containsAll(coachmarks)
        )
        val guildUpsells = requireNotNull(DiscordHermesFunctionCatalog.feature("guild_upsells"))
        assertTrue(
            "a coachmark cannot belong to two switches",
            guildUpsells.functionIds.none { it in coachmarks }
        )
    }
}
