package dev.sleepy.app.ui

import dev.sleepy.app.ui.state.PatchRow
import dev.sleepy.app.ui.state.PatchRowGroup
import dev.sleepy.app.ui.state.PatchSearch
import dev.sleepy.app.ui.state.PatchSectionRows
import dev.sleepy.app.ui.state.TriState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The patch-list search finds a row by what it changes, not only by its section.
 *
 * The case this exists for is the one that prompted it: "friends" and "calls" both have to reach
 * the row that hides the DM activity strip, even though its section is named Interface cleanup.
 */
class PatchSearchTest {

    private fun row(key: String, label: String, description: String, group: String) =
        PatchRow(
            key = key,
            label = label,
            description = description,
            enabled = false,
            group = group
        )

    private val activity = row(
        "dm_activity_cards",
        "Friends' activity in DMs",
        "Hides the happening-now strip above your DMs, where friends in calls, games and other " +
            "activities appear. Turn this switch off to see them again.",
        "Friends' activity"
    )
    private val games = row(
        "game_activity",
        "Games friends are playing",
        "Removes the game a friend is playing from their status line, profile activity list and " +
            "voice channel.",
        "Friends' activity"
    )
    private val separators = row(
        "row_separators",
        "Separator lines",
        "Removes the thin divider lines between rows and list items.",
        "Rows and dividers"
    )
    private val camera = row(
        "permission:camera",
        "Camera",
        "Lets the app take pictures and record video.",
        "Declared permissions"
    )

    private val clutter = PatchSectionRows(
        label = "Interface cleanup",
        description = "Hides cosmetic extras and visual clutter from the interface.",
        rows = listOf(activity, games, separators),
        groups = listOf(
            PatchRowGroup("Friends' activity", listOf(activity, games)),
            PatchRowGroup("Rows and dividers", listOf(separators))
        ),
        itemCount = 3,
        selectedItemCount = 0,
        triState = TriState.NONE
    )
    private val permissions = PatchSectionRows(
        label = "Permissions",
        description = "The permissions this build declares.",
        rows = listOf(camera),
        groups = listOf(PatchRowGroup("Declared permissions", listOf(camera))),
        itemCount = 1,
        selectedItemCount = 0,
        triState = TriState.NONE
    )

    @Test
    fun termsAreLowercasedAndWhitespaceIsDropped() {
        assertEquals(listOf("friends", "calls"), PatchSearch.terms("  FRIENDS   calls "))
        assertEquals(emptyList<String>(), PatchSearch.terms("   "))
    }

    @Test
    fun friendsAndCallsFindTheDmActivityRow() {
        val result = PatchSearch.search(listOf(clutter), emptyList(), "friends calls")

        assertEquals(1, result.total)
        assertEquals(listOf("Interface cleanup"), result.sections.map { it.label })
        assertEquals(listOf(activity.key), result.sections.single().rows.map { it.key })
    }

    @Test
    fun aSectionNameFindsEveryRowUnderIt() {
        val result = PatchSearch.search(listOf(clutter), emptyList(), "interface")

        assertEquals(3, result.total)
        assertEquals(
            listOf(activity.key, games.key, separators.key),
            result.sections.single().rows.map { it.key }
        )
    }

    @Test
    fun everyTermHasToMatch() {
        val result = PatchSearch.search(listOf(clutter), emptyList(), "friends camera")

        assertEquals(0, result.total)
        assertTrue(result.sections.isEmpty())
        assertTrue(result.permissions.isEmpty())
    }

    @Test
    fun aBlankQueryReturnsEverythingInListOrder() {
        val result = PatchSearch.search(listOf(clutter), listOf(camera), "")

        assertEquals(listOf("Interface cleanup"), result.sections.map { it.label })
        assertEquals(3 + 1, result.total)
        assertEquals(listOf(camera.key), result.permissions.map { it.key })
    }

    @Test
    fun permissionsAreSearchable() {
        val result = PatchSearch.search(listOf(clutter), listOf(camera), "camera")

        assertEquals(0, result.sections.size)
        assertEquals(listOf(camera.key), result.permissions.map { it.key })
        assertEquals(1, result.total)
    }

    @Test
    fun aHeadingAndItsRowsStayTogetherInTheResult() {
        val result = PatchSearch.search(listOf(clutter), emptyList(), "friends")

        val section = result.sections.single()
        assertEquals(1, section.groups.size)
        assertEquals("Friends' activity", section.groups.single().label)
        assertEquals(listOf(activity.key, games.key), section.groups.single().rows.map { it.key })
    }
}
