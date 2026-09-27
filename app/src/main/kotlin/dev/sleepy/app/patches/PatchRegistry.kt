package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchSet

object PatchRegistry {
    private val allPatches: Map<String, PatchSet> =
        (OctoGramPatches.ALL + DiscordPatches.ALL + DiscordNativePatches.ALL + DiscordBlocklistPatch.ALL)
            .associateBy { it.id }

    /** Every registered set, in registration order. */
    val all: List<PatchSet> get() = allPatches.values.toList()

    fun get(id: String): PatchSet? = allPatches[id]

    fun getAllForSource(patchIds: List<String>): List<PatchSet> {
        return patchIds.mapNotNull { allPatches[it] }
    }
}
