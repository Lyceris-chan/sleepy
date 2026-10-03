package dev.sleepy.app.engine

import dev.sleepy.app.testing.BAG_ATTRIBUTE
import dev.sleepy.app.testing.DEFAULT_CONFIG
import dev.sleepy.app.testing.GERMAN_CONFIG
import dev.sleepy.app.testing.TYPE_STRING
import dev.sleepy.app.testing.TableType
import dev.sleepy.app.testing.bagEntry
import dev.sleepy.app.testing.readTableEntries
import dev.sleepy.app.testing.resourceTable
import dev.sleepy.app.testing.simpleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The string-pool rebase for a bag's map values, checked at the byte level.
 *
 * A merge moves two indexes in a copied entry: the entry's name index into the package key pool
 * and every `TYPE_STRING` value's index into the table's global pool. A bag's value sits inside a
 * `ResTable_map`, four bytes past the map's own name, and the test builds the smallest tables
 * that make a wrong index read back in range and resolve a different string.
 */
class ResourceTableBagValueTest {

    /**
     * A bag whose one map value is a string: the base points at index 1 of its own pool and the
     * split at index 0 of its own, so the split's value has to come out as 2.
     */
    @Test
    fun aBagsStringValueIsRebasedFromTheSplitsPoolToTheMergedOne() {
        val base = resourceTable(
            strings = listOf("base-first", "res/anim/base_thing.xml"),
            keys = listOf("base_bag", "base_plain"),
            types = listOf(
                TableType(DEFAULT_CONFIG, linkedMapOf(
                    0 to bagEntry(key = 0, poolIndex = 1),
                    1 to simpleEntry(key = 1, poolIndex = 0)
                ))
            )
        )
        val split = resourceTable(
            strings = listOf("res/anim/split_thing.xml", "split-other"),
            keys = listOf("split_bag", "split_plain"),
            types = listOf(
                TableType(GERMAN_CONFIG, linkedMapOf(
                    0 to bagEntry(key = 0, poolIndex = 0),
                    1 to simpleEntry(key = 1, poolIndex = 1)
                ))
            )
        )

        val result = ResourceTableMerger.merge(base, listOf(split))
        assertTrue(
            "the merge refused: ${(result as? ResourceTableMerger.Result.Refused)?.reason}",
            result is ResourceTableMerger.Result.Merged
        )
        val merged = (result as ResourceTableMerger.Result.Merged).table
        val entries = readTableEntries(merged)

        // The base's own slots: the merge adds to a table, it does not move what was in it.
        val baseBag = entries.single { it.config.contentEquals(DEFAULT_CONFIG) && it.index == 0 }
        assertEquals("the base's bag value must not move", 1, baseBag.values.single().data)
        assertEquals(
            "the map's name must not be touched",
            BAG_ATTRIBUTE,
            baseBag.values.single().name
        )

        // The split's bag, under the configuration the split carried it with. Its value is the
        // split-local 0 moved up by the base's two strings, and 0 left in place resolves to
        // "base-first"—a string from another table entirely.
        val bag = entries.single { it.config.contentEquals(GERMAN_CONFIG) && it.index == 0 }
        val value = bag.values.single()
        assertEquals("a bag's value is a string", TYPE_STRING, value.type)
        assertNotEquals("the split-local index must not survive the merge", 0, value.data)
        assertEquals(
            "the split's value must be its own index rebased by the base's pool",
            2,
            value.data
        )
        assertEquals("the map's name must not be touched", BAG_ATTRIBUTE, value.name)
        assertEquals("the split's key index must move to the merged key pool", 2, bag.key)

        // The simple entry beside it, whose value follows its entry header: the plain-value path
        // has to move by the same delta, and the assertions below report whether it did.
        val plain = entries.single { it.config.contentEquals(GERMAN_CONFIG) && it.index == 1 }
        assertEquals("a simple entry's value must be rebased too", 3, plain.values.single().data)
        assertEquals("a simple entry's value is a string", TYPE_STRING, plain.values.single().type)

        // And what the indexes mean, read back the way the rest of the app reads them: the merged
        // table names the split's file path, which it can only do through the bag's value.
        val named = ResourceTableMerger.namedPaths(merged)!!
        assertTrue("the base's path must still be named", "res/anim/base_thing.xml" in named)
        assertTrue(
            "the split's path is only reachable through the bag's rebased value: $named",
            "res/anim/split_thing.xml" in named
        )
    }
}
