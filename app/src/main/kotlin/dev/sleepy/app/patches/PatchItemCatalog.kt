package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchItemSource
import dev.sleepy.app.model.PatchSet

/**
 * Every patch set's items, by set id.
 *
 * This is the one place that knows which sets are split into items and how. Two are: the Hermes
 * JavaScript set, whose items are the functions it patches ([DiscordHermesFunctionCatalog]), and
 * the network blocklist, whose items are its rules ([DiscordBlocklistRules]).
 *
 * A set with no item split is not left out — it becomes a single item standing for the whole set,
 * whose label and description are the set's own. That is deliberate: it keeps one model for the
 * UI to render, and it keeps every existing set working exactly as it did, because selecting its
 * one item means selecting the set. It also answers what "all items of this set" means for a set
 * that has no items, which is what expanding a saved set id needs.
 *
 * The two split sets are the ones where a per-item choice was worth building. A set whose patches
 * are one edit (most of the OctoGram and Discord static sets) has nothing to choose between.
 */
object PatchItemCatalog : PatchItemSource {

    /** The identity of the item standing for a set that is not split into items. */
    private const val WHOLE_SET_IDENTITY = "set"

    /**
     * The sets that are split into items, and how to build the items of each.
     *
     * Keyed by set id so a lookup cannot drift from what the sets are registered as; a lambda
     * rather than a list because the items carry the id of the set they belong to.
     */
    private val SPLIT_SETS: Map<String, (String) -> List<PatchItem>> = linkedMapOf(
        DiscordPatches.HERMES.id to { setId -> DiscordHermesFunctionCatalog.items(setId) },
        DiscordBlocklistPatch.NETWORK_BLOCKLIST.id to { setId -> DiscordBlocklistRules.items(setId) }
    )

    override fun itemsOf(setId: String): List<PatchItem> =
        SPLIT_SETS[setId]?.invoke(setId)
            ?: PatchRegistry.get(setId)?.let { listOf(wholeSetItem(it)) }
            ?: emptyList()

    /** Every item of every registered set, in registration order. */
    fun all(): List<PatchItem> = PatchRegistry.all.flatMap { itemsOf(it.id) }

    /**
     * The single item standing for a set that is not split: the set is its own only item, so its
     * switch and its item are the same choice and a saved set id expands to exactly itself.
     */
    private fun wholeSetItem(set: PatchSet) = PatchItem(
        setId = set.id,
        identity = WHOLE_SET_IDENTITY,
        label = set.label,
        group = set.label,
        description = set.description
    )
}
