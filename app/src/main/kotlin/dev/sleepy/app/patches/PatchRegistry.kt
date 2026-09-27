package dev.sleepy.app.patches

import dev.sleepy.app.model.PatchSet

object PatchRegistry {
    private val allPatches: Map<String, PatchSet> = (OctoGramPatches.ALL + DiscordPatches.ALL)
        .associateBy { it.id }

    fun get(id: String): PatchSet? = allPatches[id]

    fun getAllForSource(patchIds: List<String>): List<PatchSet> {
        return patchIds.mapNotNull { allPatches[it] }
    }
}
