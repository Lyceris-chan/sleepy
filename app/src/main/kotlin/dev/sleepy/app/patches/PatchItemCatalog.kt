package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchItemSource
import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch

/**
 * Every patch set's items, by set id.
 *
 * This is the one place that records which sets are split into items and how. Three are: the Hermes
 * JavaScript set, whose items are the functions it patches ([DiscordHermesFunctionCatalog]); the
 * network blocklist, whose items are its rules ([DiscordBlocklistRules]); and every OctoGram set
 * ([OctoGramPatchItems]), whose items are the edits it would otherwise apply all at once.
 *
 * A set with no item split is not left out—it becomes a single item standing for the whole set,
 * whose label and description are the set's own. That is deliberate: it keeps one model for the
 * UI to render, and it keeps every existing set working as before, because selecting its one item
 * means selecting the set. It also answers what "all items of this set" means for a set that has
 * no items, which is what expanding a saved set id needs.
 *
 * A set is split where a per-item choice is useful: the Hermes set's features, each standing for
 * one or more of the 204 functions it patches; the blocklist's 81 rules; and every OctoGram set,
 * whose items are the named decisions behind its one switch. A Discord static set whose patches
 * are one edit has nothing to choose between, and keeps its whole-set item.
 */
object PatchItemCatalog : PatchItemSource {

    /** The identity of the item standing for a set that is not split into items. */
    private const val WHOLE_SET_IDENTITY = "set"

    /** The items of one OctoGram set, as a plain function so it can go in the map that follows. */
    private val OCTOGRAM_ITEMS: (String) -> List<PatchItem> =
        { setId -> OctoGramPatchItems.items(setId) }

    /**
     * The sets that are split into items, and how to build the items of each.
     *
     * Keyed by set id so a lookup uses the same id the sets are registered under; a lambda rather
     * than a list because the items carry the id of the set they belong to.
     *
     * The OctoGram entries are built from the sets themselves rather than written out, because
     * there are thirteen of them and all thirteen are split the same way: a set that is registered
     * but missing from here would lose its items without a report, and a test asserts the two
     * agree.
     */
    private val SPLIT_SETS: Map<String, (String) -> List<PatchItem>> =
        linkedMapOf<String, (String) -> List<PatchItem>>(
            DiscordPatches.HERMES.id to { setId -> DiscordHermesFunctionCatalog.items(setId) },
            DiscordBlocklistPatch.NETWORK_BLOCKLIST.id to
                { setId -> DiscordBlocklistRules.items(setId) }
        ) + OctoGramPatches.ALL.associate { set -> set.id to OCTOGRAM_ITEMS }

    override fun itemsOf(setId: String): List<PatchItem> =
        SPLIT_SETS[setId]?.invoke(setId)
            ?: PatchRegistry.get(setId)?.let { listOf(wholeSetItem(it)) }
            ?: DiscordHermesFunctionCatalog.itemsOfLegacyKey(setId)
            ?: emptyList()

    /**
     * The smali entries a set's items carry between them, in item order.
     *
     * An OctoGram set declares no patches of its own, because the engine takes them from its
     * generator rather than from the set—so the technical panel, which lists what a set touches,
     * has to read them from the item table instead. Every other set lists its own patches
     * ([PatchSet.smaliPatches]) or generated ones, so this function returns an empty list for
     * those sets.
     */
    fun itemPatches(setId: String): List<SmaliPatch> = OctoGramPatchItems.patches(setId)

    /** Every item of every registered set, in registration order. */
    fun all(): List<PatchItem> = PatchRegistry.all.flatMap { itemsOf(it.id) }

    /**
     * The single item standing for a set that is not split: the set is its own only item, so its
     * switch and its item are the same choice, and a saved set id expands to itself.
     */
    private fun wholeSetItem(set: PatchSet) = PatchItem(
        setId = set.id,
        identity = WHOLE_SET_IDENTITY,
        label = set.label,
        group = set.label,
        description = set.description
    )
}
