package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.SelectivePatchGenerator
import dev.sleepy.app.model.TargetApk
import dev.sleepy.app.ui.state.PatchRows
import dev.sleepy.app.ui.state.TriState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the OctoGram sets reach the per-item selection and what a partial selection generates.
 *
 * Every set is split into items, every edit belongs to exactly one item, and a saved set id still
 * selects the whole set. The generator a set passes to the engine emits the selected items' edits
 * and no others.
 */
class OctoGramSelectionTest {

    private val sets = OctoGramPatches.ALL
    private val itemsBySet = sets.associate { it.id to PatchItemCatalog.itemsOf(it.id) }

    @Test
    fun everyOctoGramSetIsSplitIntoItemsOfItsOwn() {
        assertEquals("the OctoGram sets are the ones sources.json declares", 16, sets.size)
        assertEquals(
            "the item table has to cover exactly the registered sets",
            sets.map { it.id },
            OctoGramPatchItems.SET_IDS
        )
        assertEquals(
            "nineteen items over the sixteen sets",
            19,
            itemsBySet.values.sumOf { it.size }
        )

        sets.forEach { set ->
            val items = itemsBySet.getValue(set.id)
            assertTrue("${set.id} must offer at least one item", items.isNotEmpty())
            items.forEach { item ->
                assertEquals(set.id, item.setId)
                assertTrue("${item.key} has no name", item.label.isNotBlank())
                assertTrue("${item.key} does not say what it does", item.description.isNotBlank())
                assertTrue("${item.key} is under no feature heading", item.group.isNotBlank())
                assertNotEquals(
                    "${item.key} is a whole-set item: every OctoGram set is split now",
                    "set",
                    item.identity
                )
            }
            assertEquals(
                "${set.id}'s items must be told apart by identity: it is what a saved selection " +
                    "keeps",
                items.size,
                items.map { it.identity }.distinct().size
            )
            assertEquals(items.size, items.map { it.key }.distinct().size)
            assertEquals(
                "${set.id} has two rows reading the same, which is one choice as far as the " +
                    "user is concerned",
                items.size,
                items.map { it.label }.distinct().size
            )
        }
    }

    @Test
    fun aSetIsItsItemsAndSaysWhichOfThemAreOn() {
        sets.forEach { set ->
            val items = itemsBySet.getValue(set.id)
            val off = PatchRows.of(set, PatchSelection())
            assertEquals(items.size, off.itemCount)
            assertEquals(0, off.selectedItemCount)
            assertEquals(TriState.NONE, off.triState)
            assertFalse(PatchRows.headerChecked(off.triState))
            assertEquals(
                "a set has something to expand into only when it holds more than one item",
                items.size > 1,
                off.expandable
            )

            if (items.size > 1) {
                // The state a user reaches on purpose, and the one the header must not round to
                // either end: some of the set is on and some is not.
                val partial = PatchRows.of(set, PatchSelection().with(items.take(1)))
                assertEquals(TriState.PARTIAL, partial.triState)
                assertEquals(1, partial.selectedItemCount)
                assertFalse(PatchRows.headerChecked(partial.triState))
            }

            val on = PatchRows.of(set, PatchSelection().with(items))
            assertEquals(TriState.ALL, on.triState)
            assertTrue(PatchRows.headerChecked(on.triState))
            assertEquals(items.size, on.selectedItemCount)
            assertEquals(
                "every item is one row, and every row is one item",
                items.map { it.key }.toSet(),
                on.groups.flatMap { it.rows }.map { it.key }.toSet()
            )

            on.groups.flatMap { it.rows }.forEach { row ->
                assertTrue("${row.key} is the user's to move", row.switchable)
                assertEquals(set.id, row.item?.setId)
                assertTrue("${row.key} names no target", row.technicalTarget.orEmpty().isNotBlank())
                assertTrue(
                    "${row.key} has nothing behind its target",
                    row.detail.orEmpty().isNotBlank()
                )
            }
        }
    }

    @Test
    fun everyEditBelongsToExactlyOneItem() {
        sets.forEach { set ->
            val entries = OctoGramPatchItems.entries(set.id)
            assertEquals(
                "${set.id} has an item that applies nothing",
                emptyList<String>(),
                entries.filter { it.patches.isEmpty() }.map { it.identity }
            )
            val edits = OctoGramPatchItems.patches(set.id)
            assertEquals(
                "${set.id} lists one edit twice, so switching either item off would leave it " +
                    "applied",
                edits.size,
                edits.distinct().size
            )
            assertEquals(
                "a set's edits and its items' edits are the same list, in item order",
                edits,
                entries.flatMap { it.patches }
            )
        }
        assertEquals(
            "the crash reporter is one edit: the call that installs the handler",
            1,
            OctoGramPatchItems.patches(OctoGramPatches.CRASH_REPORTER.id).size
        )
        assertEquals(
            "the Telegram Premium row is one item over four edits, the reference's three and the " +
                "row on the app's own Settings screen",
            4,
            OctoGramPatchItems.patches(OctoGramPatches.PREMIUM_SETTINGS.id).size
        )
    }

    /**
     * A selection that names one item of a set applies that item's edits and none of its siblings',
     * which is what the engine's own narrowing asks the generator for.
     *
     * The target is empty on purpose: an OctoGram generator reads the selection rather than the
     * build, so an empty target shows that no patch comes from the build itself.
     */
    @Test
    fun theGeneratorAppliesTheSelectedItemsAndOnlyThose() {
        val emptyTarget = TargetApk(emptyMap(), emptyMap())
        val logger = OctoGramPatches.OCTO_LOGGER
        val generator = logger.generator as SelectivePatchGenerator

        val emittersOnly = PatchSelection.ofKeys(PatchItem.keyOf(logger.id, "emitters"))
        val generated = generator.generate(emptyTarget, emittersOnly)
        assertEquals(
            "the twelve emitters are what the item names, in the order the set applies them",
            OctoGramPatches.LOG_EMITTERS,
            generated.patches
        )
        assertTrue(
            "an edit of the item that was not selected is in the run",
            generated.patches.none { it in OctoGramPatches.LOG_UPLOADERS }
        )

        val uploadersOnly = PatchSelection.ofKeys(PatchItem.keyOf(logger.id, "uploaders"))
        assertEquals(
            OctoGramPatches.LOG_UPLOADERS,
            generator.generate(emptyTarget, uploadersOnly).patches
        )

        val nothingOfThisSet =
            generator.generate(emptyTarget, PatchSelection.ofKeys("octogram_logger:nobody"))
        assertTrue(
            "a selection naming no item of the set applies nothing",
            nothingOfThisSet.patches.isEmpty()
        )
        assertTrue(
            "and says so rather than reporting an empty success",
            !nothingOfThisSet.skipReason.isNullOrBlank()
        )
    }

    /**
     * The narrowing as the pipeline applies it: a set contributes exactly when the selection names
     * one of its items, so a selection naming one set leaves the other fifteen out.
     */
    @Test
    fun aSelectionNamingOneOctoGramSetLeavesTheOtherFifteenOut() {
        val logger = PatchSelection().with(itemsBySet.getValue(OctoGramPatches.OCTO_LOGGER.id))

        assertEquals(
            "only the set whose item was selected may contribute",
            listOf(OctoGramPatches.OCTO_LOGGER.id),
            sets.filter { logger.isEnabled(itemsBySet.getValue(it.id)) }.map { it.id }
        )
        assertTrue(
            "and every other set is selected out, not merely unused",
            sets.filter { it.id != OctoGramPatches.OCTO_LOGGER.id }
                .none { logger.isEnabled(itemsBySet.getValue(it.id)) }
        )
    }

    /**
     * A saved selection from before items existed: a set id still means everything in that set.
     *
     * This is what keeps an upgrade from dropping edits without a report—the stored value is the
     * set, and the set is its items.
     */
    @Test
    fun aSetIdSavedBeforeItemsExistedStillSelectsTheWholeSet() {
        val selection = PatchSelection.fromSavedIds(sets.map { it.id }, PatchItemCatalog)

        sets.forEach { set ->
            val items = itemsBySet.getValue(set.id)
            assertEquals(
                "an id saved before items existed stands for everything in the set",
                items.map { it.key },
                selection.selected(items).map { it.key }
            )
            assertTrue(selection.isEnabled(items))
        }
        assertEquals(
            "and nothing from outside those sets came with it",
            itemsBySet.values.sumOf { it.size },
            selection.keys.size
        )
    }

    @Test
    fun switchingOneOctoGramSetOffTakesExactlyItsItemsOut() {
        val everything = PatchSelection.fromSavedIds(sets.map { it.id }, PatchItemCatalog)
        val loggerItems = itemsBySet.getValue(OctoGramPatches.OCTO_LOGGER.id)
        val withoutLogger = everything.without(loggerItems)

        assertEquals(everything.keys.size - loggerItems.size, withoutLogger.keys.size)
        assertEquals(
            "the set that was switched off is the only one that stops contributing",
            listOf(OctoGramPatches.OCTO_LOGGER.id),
            sets.filter { !withoutLogger.isEnabled(itemsBySet.getValue(it.id)) }.map { it.id }
        )
    }
}
