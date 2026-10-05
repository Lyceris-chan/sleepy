package dev.sleepy.app.model

/**
 * One switchable item inside a patch set.
 *
 * A patch set was a single on/off switch, which is too coarse for some choices: "hide the gift
 * button but keep quests" cannot be expressed when the gift button and the quests belong to the
 * same set. An item is the unit the user selects, and this type holds what the UI needs to render
 * one: a name, the feature it belongs to, and a line that describes the effect of switching it
 * on.
 *
 * @property setId The patch set the item belongs to, for example `discord_hermes`.
 * @property identity The item's identity *within* its set. This is what a saved selection
 *   records, so it has to be stable across releases and across app restarts: not an index into a
 *   list, and not a display label. A Hermes function is identified by its function id (the names
 *   are not unique, and this bundle has three functions called `track`), and a blocklist rule by
 *   its own pattern, which is all there is of the rule.
 * @property label The item's name as the UI shows it. Free to change; nothing persists it.
 * @property group The user-facing feature the item belongs to, named so that it describes what it
 *   covers.
 * @property section The section of the patch list the item is sorted under, for example
 *   `Ads and promotions`. Empty for an item that is not part of that list, which today is the
 *   declared permissions: they keep their own card and are not filed under a section.
 * @property description One line that describes the effect of switching the item on.
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
