package dev.sleepy.app

import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.patches.OctoGramPatches
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.ui.state.PatchRows
import dev.sleepy.app.ui.state.TriState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the OctoGram sets reach the per-item selection screen, and what a per-item selection means
 * for an OctoGram source.
 *
 * Nothing OctoGram-specific had to be built for this: [PatchItemCatalog] is generic over sets, and
 * a set it has no item split for becomes one item standing for the whole set. These tests pin that
 * shape for every OctoGram set — one item each, keyed `setId:set`, carrying the set's own label and
 * description — and pin that the selection a switch writes is the one the pipeline's narrowing
 * looks for.
 *
 * What is *not* pinned here is per-patch choosing inside a set: an OctoGram set is one switch, so
 * the only choice it offers is the whole set. Splitting one would take a third entry in
 * `PatchItemCatalog.SPLIT_SETS` and a catalog of its own, which is a change to how patching is
 * wired rather than to how it is presented.
 */
class OctoGramSelectionTest {

    @Test
    fun everyOctoGramSetIsOneItemStandingForTheWholeSet() {
        assertEquals(
            "the OctoGram sets are the ones sources.json declares",
            11,
            OctoGramPatches.ALL.size
        )

        OctoGramPatches.ALL.forEach { set ->
            val items = PatchItemCatalog.itemsOf(set.id)

            assertEquals("${set.id} must be its own only item", 1, items.size)
            val item = items.single()
            assertEquals(set.id, item.setId)
            assertEquals("a whole set's item is identified as the set's own", "set", item.identity)
            assertEquals(PatchItem.keyOf(set.id, "set"), item.key)
            assertEquals("the item says what the set says: ${set.id}", set.label, item.label)
            assertEquals(set.description, item.description)
        }
    }

    @Test
    fun anOctoGramSetIsItsOwnSwitchAndSaysWhatItSelects() {
        OctoGramPatches.ALL.forEach { set ->
            val item = PatchItemCatalog.itemsOf(set.id).single()
            val off = PatchRows.of(set, PatchSelection())

            assertEquals(1, off.itemCount)
            assertEquals(TriState.NONE, off.triState)
            assertFalse("a set with one item has nothing to expand into", off.expandable)
            assertEquals("the one group is the set", set.label, off.groups.single().label)

            val row = off.groups.single().rows.single()
            assertEquals(item.key, row.key)
            assertEquals(set.label, row.label)
            assertEquals(set.description, row.description)
            assertEquals(item, row.item)
            assertTrue("${set.id}'s switch is the user's to move", row.switchable)

            val on = PatchRows.of(set, PatchSelection().with(listOf(item)))
            assertEquals(TriState.ALL, on.triState)
            assertTrue(PatchRows.headerChecked(on.triState))
            assertEquals(1, on.selectedItemCount)
        }
    }

    /**
     * The narrowing, in the terms the pipeline applies it: an OctoGram set contributes exactly when
     * the selection names one of its items, so a selection naming one set leaves the other ten out.
     */
    @Test
    fun aSelectionNamingOneOctoGramSetLeavesTheOtherTenOut() {
        val itemsBySet = OctoGramPatches.ALL.associate { it.id to PatchItemCatalog.itemsOf(it.id) }
        val logger = PatchSelection().with(itemsBySet.getValue(OctoGramPatches.OCTO_LOGGER.id))

        assertEquals(
            "only the set whose item was selected may contribute",
            listOf(OctoGramPatches.OCTO_LOGGER.id),
            OctoGramPatches.ALL.filter { logger.isEnabled(itemsBySet.getValue(it.id)) }.map { it.id }
        )
        assertTrue(
            "and every other set is selected out, not merely unused",
            OctoGramPatches.ALL.filter { it.id != OctoGramPatches.OCTO_LOGGER.id }
                .none { logger.isEnabled(itemsBySet.getValue(it.id)) }
        )
    }

    /**
     * The same narrowing seen from a saved selection: switching one set off takes exactly its item
     * out, and nothing else.
     *
     * This is the whole of what a set switch is now — it writes its item's key — so a set that is
     * switched off is a set the pipeline does not apply, which is what it meant before the item
     * scheme as well.
     */
    @Test
    fun switchingOneOctoGramSetOffTakesExactlyItsItemOut() {
        val itemsBySet = OctoGramPatches.ALL.associate { it.id to PatchItemCatalog.itemsOf(it.id) }
        val everything = PatchSelection.fromSavedIds(OctoGramPatches.ALL.map { it.id }, PatchItemCatalog)
        assertEquals("every set's own item, and nothing else", 11, everything.keys.size)

        val loggerItems = itemsBySet.getValue(OctoGramPatches.OCTO_LOGGER.id)
        val withoutLogger = everything.without(loggerItems)

        assertEquals(10, withoutLogger.keys.size)
        assertEquals(
            "the set that was switched off is the only one that stops contributing",
            listOf(OctoGramPatches.OCTO_LOGGER.id),
            OctoGramPatches.ALL.filter { !withoutLogger.isEnabled(itemsBySet.getValue(it.id)) }.map { it.id }
        )
    }

    @Test
    fun aSetIdSavedBeforeItemsExistedStillSelectsTheWholeSet() {
        val savedIds = OctoGramPatches.ALL.map { it.id }
        val selection = PatchSelection.fromSavedIds(savedIds, PatchItemCatalog)

        OctoGramPatches.ALL.forEach { set ->
            val items = PatchItemCatalog.itemsOf(set.id)
            assertEquals(
                "an id saved before items existed stands for everything in the set",
                items.map { it.key },
                selection.selected(items).map { it.key }
            )
            assertTrue(selection.isEnabled(items))
        }
        assertEquals(
            "and nothing from outside those sets came with it",
            savedIds.size,
            selection.keys.size
        )
    }
}
