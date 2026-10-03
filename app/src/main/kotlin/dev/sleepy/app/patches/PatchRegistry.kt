package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchSet

/**
 * Every patch set the app ships, looked up by set id.
 *
 * The sets are registered in the order the UI lists them: the OctoGram sets, the Discord static
 * sets, the Discord native sets, and the network blocklist.
 */
object PatchRegistry {

    private val allPatches: Map<String, PatchSet> =
        (OctoGramPatches.ALL +
            DiscordPatches.ALL +
            DiscordNativePatches.ALL +
            DiscordBlocklistPatch.ALL)
            .associateBy { it.id }

    /** Every registered set, in registration order. */
    val all: List<PatchSet> get() = allPatches.values.toList()

    /** The set with this id, or `null` when no registered set has it. */
    fun get(id: String): PatchSet? = allPatches[id]

    /** The registered sets named by [patchIds], in the order [patchIds] lists them. */
    fun getAllForSource(patchIds: List<String>): List<PatchSet> {
        return patchIds.mapNotNull { allPatches[it] }
    }
}
