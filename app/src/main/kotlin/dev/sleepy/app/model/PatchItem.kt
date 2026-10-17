package dev.sleepy.app.model

/**
 * One switchable item inside a patch set.
 *
 * An item is the unit the user selects; a set is one switch, which cannot express "hide the gift
 * button but keep quests" when both live in one set.
 *
 * @property setId Patch set the item belongs to, for example `discord_hermes`.
 * @property identity Identity within the set, persisted in a saved selection: stable across
 *   releases, so not an index and not a label. A Hermes function is identified by its id (names
 *   are not unique) and a blocklist rule by its pattern.
 * @property label Name the UI shows; free to change.
 * @property group User-facing feature the item belongs to.
 * @property section Section of the patch list, empty for an item listed elsewhere (the declared
 *   permissions keep their own card).
 * @property description One line describing the effect of switching the item on.
 */
data class PatchItem(
    val setId: String,
    val identity: String,
    val label: String,
    val group: String,
    val section: String = "",
    val description: String
) {
    /**
     * The item's stable key: [setId], a colon, then [identity].
     *
     * The colon is a separator and not an escape—an identity can contain colons, and a
     * blocklist rule's does (`api:/typing`). Nothing parses a key back into its parts, so that
     * costs nothing; the catalog is what maps an item in either direction.
     */
    val key: String = keyOf(setId, identity)

    companion object {
        /** Builds the key of an item from the set it is in and its identity within that set. */
        fun keyOf(setId: String, identity: String): String {
            require(setId.isNotBlank()) { "a patch item key needs a patch set id" }
            require(identity.isNotBlank()) { "a patch item key needs an identity within its set" }
            return "$setId:$identity"
        }
    }
}

/**
 * Supplies a patch set's items.
 *
 * The selection model is not wired to the patch tables: it stores keys, and this interface is
 * where a stored set id is turned into the items of a real set.
 */
fun interface PatchItemSource {
    /**
     * Every item of the set with this id, in the order the UI should list them, or an empty list
     * when no patch set has that id.
     *
     * The empty list is not an error: the method returns it both for an item key, which names no
     * set, and for a set id that no longer exists after a release removes a set.
     */
    fun itemsOf(setId: String): List<PatchItem>
}
