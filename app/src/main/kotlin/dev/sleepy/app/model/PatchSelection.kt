package dev.sleepy.app.model

/**
 * Which patch items the user has switched on, as a set of item keys.
 *
 * Keys rather than items: a saved selection outlives the release that wrote it, and storing
 * labels or descriptions would persist text a later release rewords. A set's switch is not a
 * separate flag — [setEnabled] selects or deselects every item of the set. [fromSavedIds] reads a
 * legacy value as a set id and expands it, which is what selecting that set used to mean.
 */
data class PatchSelection(val keys: Set<String> = emptySet()) {

    /** True when the item with this [key] is on. */
    operator fun contains(key: String): Boolean = key in keys

    /** True when [item] is on. */
    operator fun contains(item: PatchItem): Boolean = item.key in keys

    /** True when at least one of [items] is on—what "this set contributes something" means. */
    fun isEnabled(items: List<PatchItem>): Boolean = items.any { it.key in keys }

    /** The items of [items] that are on, in their own order. */
    fun selected(items: List<PatchItem>): List<PatchItem> = items.filter { it.key in keys }

    /** This selection plus [items]. */
    fun with(items: Iterable<PatchItem>): PatchSelection =
        PatchSelection(keys + items.map { it.key })

    /** This selection minus [items]. */
    fun without(items: Iterable<PatchItem>): PatchSelection =
        PatchSelection(keys - items.map { it.key }.toSet())

    /** This selection with [item] flipped. */
    fun toggle(item: PatchItem): PatchSelection =
        if (contains(item)) without(listOf(item)) else with(listOf(item))

    /**
     * This selection with every item of a set switched to [enabled] at once—the set's own
     * switch, which is why it takes the set's items rather than a set id.
     */
    fun setEnabled(items: List<PatchItem>, enabled: Boolean): PatchSelection =
        if (enabled) with(items) else without(items)

    /** This selection with every item of every given set switched to [enabled]. */
    fun setEnabled(sets: Map<String, List<PatchItem>>, enabled: Boolean): PatchSelection =
        if (enabled) with(sets.values.flatten()) else without(sets.values.flatten())

    companion object {
        /** A selection holding exactly [items]. */
        fun of(vararg items: PatchItem): PatchSelection =
            PatchSelection(emptySet()).with(items.toList())

        /** A selection holding exactly [keys], for the cases that already hold item keys. */
        fun ofKeys(vararg keys: String): PatchSelection = PatchSelection(keys.toSet())

        /**
         * Reads a saved selection, which can be set ids, item keys, or both.
         *
         * Each value is offered to [source] as a set id first, because that is what the previous
         * scheme wrote. A value that names a set expands to all of that set's items; anything
         * else is kept as it is, so an item key round-trips and an id whose set is gone is
         * harmless rather than a crash.
         */
        fun fromSavedIds(savedIds: Iterable<String>, source: PatchItemSource): PatchSelection {
            val keys = mutableSetOf<String>()
            for (saved in savedIds) {
                if (saved.isBlank()) continue
                val items = source.itemsOf(saved)
                if (items.isEmpty()) keys += saved else items.mapTo(keys) { it.key }
            }
            return PatchSelection(keys)
        }
    }
}
