package dev.sleepy.app.model

/**
 * Which patch items the user has switched on, as a set of item keys.
 *
 * ## Why keys rather than items
 * A saved selection outlives the app version that wrote it, and it may be read by a version whose
 * patch tables have moved on. Storing whole [PatchItem]s would persist labels and descriptions
 * that a later release rewords; storing keys persists only the identity of the choice — see
 * [PatchItem.key]. Everything else about an item is looked up from the current tables, so a
 * reworded description or a renamed label cannot invalidate a saved selection.
 *
 * ## The set switch
 * A patch set's own switch is not a separate flag. Switching a set on selects every one of its
 * items and switching it off deselects all of them ([setEnabled]); a set all of whose items are
 * on is simply a set whose switch reads as on. That keeps one source of truth: "a set with
 * nothing selected contributes nothing" falls out of [isEnabled], and a user who turns a set on,
 * switches two of its items off, and turns the set off and on again gets the whole set back,
 * because a set switch means the set, not the last item selection.
 *
 * ## Backwards compatibility
 * Selections used to be patch set ids, so [fromSavedIds] reads a saved value as a set id and
 * expands it to every item of that set, which is exactly what selecting that set meant before.
 * A value that names no set is kept as it is, which covers both an item key written by this
 * scheme and an id for a set a release has since removed.
 */
data class PatchSelection(val keys: Set<String> = emptySet()) {

    /** True when the item with this [key] is on. */
    operator fun contains(key: String): Boolean = key in keys

    /** True when [item] is on. */
    operator fun contains(item: PatchItem): Boolean = item.key in keys

    /** True when at least one of [items] is on — what "this set contributes something" means. */
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
     * This selection with every item of a set switched to [enabled] at once — the set's own
     * switch, which is why it takes the set's items rather than a set id.
     */
    fun setEnabled(items: List<PatchItem>, enabled: Boolean): PatchSelection =
        if (enabled) with(items) else without(items)

    /** This selection with every item of every given set switched to [enabled]. */
    fun setEnabled(sets: Map<String, List<PatchItem>>, enabled: Boolean): PatchSelection =
        if (enabled) with(sets.values.flatten()) else without(sets.values.flatten())

    companion object {
        /** A selection holding exactly [items]. */
        fun of(vararg items: PatchItem): PatchSelection = PatchSelection(emptySet()).with(items.toList())

        /** A selection holding exactly [keys], for the cases that already hold item keys. */
        fun ofKeys(vararg keys: String): PatchSelection = PatchSelection(keys.toSet())

        /**
         * Reads a saved selection, which may be set ids, item keys, or both.
         *
         * Each value is offered to [source] as a set id first, because that is what the previous
         * scheme wrote. A value that names a set expands to all of that set's items; anything
         * else is kept verbatim, so an item key round-trips and an id whose set is gone is
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
