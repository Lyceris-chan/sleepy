package dev.sleepy.app.ui.state

import dev.sleepy.app.model.BlocklistGateRow
import dev.sleepy.app.model.BlocklistRuleKind
import dev.sleepy.app.model.BlocklistRuleRow
import dev.sleepy.app.model.HermesPatch
import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.PermissionRow
import dev.sleepy.app.model.groupedByFeature
import dev.sleepy.app.patches.DiscordBlocklistPatch
import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.OctoGramPatchItems
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.patches.PermissionCatalog

/**
 * The state a patch set's own switch can be in.
 *
 * A set is not limited to on or off: it is on when every one of its items is on, off when none
 * are, and [PARTIAL] in between. A user reaches [PARTIAL] deliberately by switching one item on
 * inside a set, and the header reports it without rounding to either end. For the derivation, see
 * [PatchRows.triState].
 */
enum class TriState {
    /** Nothing in the set is on. */
    NONE,

    /** Some of the set is on, and some is not. */
    PARTIAL,

    /** Everything in the set is on. */
    ALL
}

/**
 * The two ways a row's switch can be inert, so the UI can indicate which one applies.
 *
 * They are different claims and must not be conflated: [COVERED] means the row still describes a
 * real choice that another enabled rule has made redundant, and it becomes a working switch again
 * when that rule is turned off, while [REQUIRED] means the row has no choice in it at all. The
 * reasons themselves come from [dev.sleepy.app.model.BlocklistCoverage] and
 * [dev.sleepy.app.model.BlocklistGate], and are carried here verbatim so nothing rewords them.
 */
enum class InertKind {
    /** An enabled rule already answers every request this row covers. */
    COVERED,

    /** A prefix gate: applied to every request, and not the user's to switch off. */
    REQUIRED
}

/**
 * One row of a patch set's expanded body: an item with its own switch, or one of the blocklist's
 * prefix gates, which has no item behind it because nothing can select it.
 *
 * @property key A key stable across recompositions and unique within the list, which is what the
 *   lazy list uses to identify the row. It is the item's own [PatchItem.key] for an item and
 *   [gateKey] for a gate.
 * @property label The row's name.
 * @property description What switching the row on does to the app, in the user's terms.
 * @property enabled Whether the row is on. A gate reports true, because it is applied to every
 *   request.
 * @property item The item this row switches, or null for a gate.
 * @property inertKind Which of the two ways the switch is fixed, or null when it can be moved.
 * @property inertReason Why the switch is fixed, in full. This value is not blank when
 *   [inertKind] is set; a grayed row with no reason is what the field prevents.
 * @property technicalTarget The exact thing the patch touches—the emitted smali literal, the
 *   function and its byte count, or the OctoGram class and the shape of each edit in it—shown
 *   one tap away rather than in the row itself.
 * @property detail The longer explanation behind [technicalTarget], or null when there is none.
 */
data class PatchRow(
    val key: String,
    val label: String,
    val description: String,
    val enabled: Boolean,
    val item: PatchItem? = null,
    val inertKind: InertKind? = null,
    val inertReason: String? = null,
    val technicalTarget: String? = null,
    val detail: String? = null
) {
    /** True when the user can move this row's switch. */
    val switchable: Boolean get() = inertKind == null && item != null
}

/** The rows of one feature group, under the label that group carries. */
data class PatchRowGroup(val label: String, val rows: List<PatchRow>)

/**
 * One patch set as the list renders it: its groups, and what its own switch reports.
 *
 * The counts are of items, not of rows: a gate is not an item, so the blocklist reports 81 items
 * whether or not its two gates are in the list, and a set switch covers exactly the items that
 * [PatchRows.of] counted.
 */
data class PatchSetRows(
    val groups: List<PatchRowGroup>,
    val itemCount: Int,
    val selectedItemCount: Int,
    val triState: TriState
) {
    /** True when expanding this set shows rows to choose between. */
    val expandable: Boolean get() = itemCount > 1
}

/**
 * Builds the rows the patch-selection list renders, and derives a set's tri-state from its items.
 *
 * Everything here is a pure function of the set, the current [PatchSelection] and the tables in
 * `patches/`, so the coverage graying is recomputed rather than stored: the blocklist's rows are
 * re-derived from the selection on every read ([DiscordBlocklistPatch.rows]), and turning a
 * covering rule off makes everything it covered selectable again with no state to keep in step.
 *
 * This is presentation only and adds no facts of its own. Item labels, descriptions, groups and
 * identities come from [PatchItemCatalog]; whether a rule is redundant comes from the coverage
 * table; the graying reasons are the model's own strings.
 */
object PatchRows {

    /**
     * The heading the blocklist's two prefix gates are listed under.
     *
     * They are rows without a switch rather than items, because nothing can select or deselect
     * them, and they are listed first because that is where the interceptor tests them: before it
     * checks any rule.
     */
    const val GATE_GROUP_LABEL = "Applied before every rule (not switchable)"

    /** The row key of a gate, which is not an item and so has no item key. */
    private fun gateKey(setId: String, pattern: String): String = "gate:$setId:$pattern"

    /**
     * The key of a set's header in the lazy list.
     *
     * A lazy list uses keys as a row's identity across recompositions, so they are built here,
     * next to the row keys they have to stay distinct from, rather than spelled out at the call
     * site.
     */
    fun setKey(setId: String): String = "set:$setId"

    /** The key of a group heading inside a set's expanded body. */
    fun groupKey(setId: String, groupLabel: String): String = "group:$setId:$groupLabel"

    /** The key of the permission section's card in the lazy list. */
    fun permissionCardKey(): String = "permissions"

    /** The key of the group heading that precedes the permission rows. */
    fun permissionGroupKey(): String = "permissions:group"

    /**
     * The functions behind each feature, by item key, so a row can report what it rewrites.
     *
     * A list rather than one patch: an item stands for a thing a user recognises, and the thing
     * is usually several functions (guild tags are seven). The functions are what the row's
     * technical panel lists.
     */
    private val HERMES_PATCHES_BY_KEY: Map<String, List<DiscordHermesBundlePatch.FunctionPatch>> =
        DiscordHermesFunctionCatalog.FEATURES.associate { feature ->
            DiscordHermesFunctionCatalog.itemKeyOf(feature.slug) to
                DiscordHermesBundlePatch.PATCHES.filter { it.functionId in feature.functionIds }
        }

    /**
     * The reference's own audit note per function, by item key.
     *
     * Only the functions the reference suite documents have one; the rest are described by the
     * extracted bodies in [DiscordHermesBundlePatch]. Both kinds of description come from those
     * sources.
     */
    private val HERMES_AUDITS_BY_KEY: Map<String, Map<String, HermesPatch>> =
        DiscordHermesFunctionCatalog.FEATURES.associate { feature ->
            // A reference note names its function as a string, while the table uses an id, so the
            // two are matched by parsing rather than by comparing the two shapes directly.
            val ids: List<String> = feature.functionIds.map { it.toString() }
            val notes: Map<String, HermesPatch> = DiscordPatches.HERMES.hermesPatches
                .filter { patch -> patch.functionId in ids }
                .associateBy { patch -> patch.functionId }
            DiscordHermesFunctionCatalog.itemKeyOf(feature.slug) to notes
        }

    /**
     * A set's rows and its switch state, for the current [selection].
     *
     * A set that is not split into items is not left out: its one item stands for the whole set,
     * and this returns it as a single row, which is what makes a whole set's selection and its
     * item's selection the same choice.
     *
     * @param set The set to build rows for.
     * @param selection The selection the rows are derived from.
     * @return The set's groups, item counts, and switch state.
     */
    fun of(set: PatchSet, selection: PatchSelection): PatchSetRows {
        val items = PatchItemCatalog.itemsOf(set.id)
        val blocklistRows = if (set.id == DiscordBlocklistPatch.NETWORK_BLOCKLIST.id) {
            DiscordBlocklistPatch.rows(selection)
        } else {
            emptyList()
        }
        val rulesByIdentity = blocklistRows
            .filterIsInstance<BlocklistRuleRow>()
            .associateBy { it.rule.identity }

        val groups = buildList {
            val gates = blocklistRows.filterIsInstance<BlocklistGateRow>()
            if (gates.isNotEmpty()) {
                add(PatchRowGroup(GATE_GROUP_LABEL, gates.map { gateRow(set.id, it) }))
            }
            for (group in items.groupedByFeature()) {
                add(
                    PatchRowGroup(
                        label = group.label,
                        rows = group.items.map { item ->
                            itemRow(item, selection, rulesByIdentity[item.identity])
                        }
                    )
                )
            }
        }

        return PatchSetRows(
            groups = groups,
            itemCount = items.size,
            selectedItemCount = selection.selected(items).size,
            triState = triState(items, selection)
        )
    }

    /**
     * The permission section's rows: one per permission the build declares, in the order its
     * manifest declares them.
     *
     * These are the same rows an expanded set has, rendered by the same row and grayed by the
     * same mechanism, because a permission that cannot be switched off is the same kind of claim
     * as a blocklist gate: [InertKind.REQUIRED], the model's own reason, and no switch to move.
     * What differs is only where the list comes from—the declarations shipped for the release
     * being patched, or its own manifest when nothing is shipped for it—and that a fixed row is
     * not necessarily a kept one: [packageName]'s build removes some declarations itself,
     * regardless of the choice, and those rows report as removed and state why rather than showing
     * a switch whose position does not reflect the result.
     *
     * The switch is labeled "kept", so a permission is removed by switching it off, and the rows
     * are derived from the selection on every read like everything else here: the last remaining
     * permission is locked as soon as it is the last, and is unlocked as soon as another is
     * switched back on.
     *
     * @param declared The permission names the build declares.
     * @param selection The selection the rows are derived from.
     * @param packageName The package of the build being patched, which determines which declarations
     *   the build removes on its own.
     * @return One row per declared permission, in declaration order.
     */
    fun permissionRows(
        declared: List<String>,
        selection: PatchSelection,
        packageName: String? = null
    ): List<PatchRow> {
        val items = PermissionCatalog.itemsOf(declared).associateBy { it.identity }
        return PermissionCatalog.rows(declared, selection, packageName).map { row ->
            val item = items.getValue(row.permission.identity)
            PatchRow(
                key = item.key,
                label = row.permission.label,
                description = row.permission.description,
                enabled = row.kept,
                // A locked permission has no item behind it for the same reason a gate has none:
                // there is nothing the user could do with it.
                item = item.takeIf { row.switchable },
                inertKind = if (row.lockedReason != null) InertKind.REQUIRED else null,
                inertReason = row.lockedReason,
                technicalTarget = "android:name=\"${row.permission.name}\"",
                detail = permissionDetail(item, row)
            )
        }
    }

    /** Where a permission row's declaration is, and what the row's current state does to it. */
    private fun permissionDetail(item: PatchItem, row: PermissionRow): String {
        val effect = when {
            // The build's own edit rather than the user's: a switch over it is a control
            // that changes nothing, so the row states what the run does instead of offering one.
            !row.switchable && !row.kept ->
                "Removed from the manifest of every build this patch makes, whatever this " +
                    "switch is set to."
            row.switchable && !row.kept ->
                "Switched off, so this declaration is deleted from the manifest of the build " +
                    "this run produces."
            else -> "Left declared, so the app keeps this permission."
        }
        return "Declared in the manifest of this release. $effect Selection key ${item.key}."
    }

    /**
     * The state of a set whose items are [items]: on only when every one of them is on.
     *
     * A set with no items is [TriState.NONE] rather than [TriState.ALL], so an id that names no
     * set reads as nothing selected instead of everything.
     */
    fun triState(items: List<PatchItem>, selection: PatchSelection): TriState {
        if (items.isEmpty()) return TriState.NONE
        return when (selection.selected(items).size) {
            0 -> TriState.NONE
            items.size -> TriState.ALL
            else -> TriState.PARTIAL
        }
    }

    /**
     * Returns the value a set's header switch reports for [state], and therefore what a tap on it
     * means.
     *
     * The switch reports "the whole set is on" and nothing else, which determines both directions
     * of a tap: a set that is fully on reports true, so a tap returns false and clears it, and a
     * set that is off—or partly on, which is the state the user asked for when they switched
     * one item on inside a set—reports false, so a tap returns true and selects everything in
     * the set. A partial set is therefore completed rather than cleared by a tap on its header,
     * and clearing one takes the same single tap that clearing a fully-on set takes.
     *
     * @param state The set's switch state.
     * @return True if the switch reports on; false otherwise.
     */
    fun headerChecked(state: TriState): Boolean = state == TriState.ALL

    /**
     * The state a feature group's heading reports for its rows.
     *
     * Rows rather than items, because a group also holds the blocklist's two gates: they are rows
     * with no item and no switch, so counting them as items would make every group that holds one
     * read as permanently partial.
     */
    fun triStateOfRows(rows: List<PatchRow>): TriState {
        val switchable = rows.filter { it.switchable }
        if (switchable.isEmpty()) return TriState.NONE
        return when (switchable.count { it.enabled }) {
            0 -> TriState.NONE
            switchable.size -> TriState.ALL
            else -> TriState.PARTIAL
        }
    }

    /** One item's row: its switch, its text, and the model's reason when another rule covers it. */
    private fun itemRow(
        item: PatchItem,
        selection: PatchSelection,
        ruleRow: BlocklistRuleRow?
    ): PatchRow {
        val technical = when {
            ruleRow != null -> constString(ruleRow.rule.pattern)
            // Each of the three kinds of item has its own target, and a kind with nothing to name
            // keeps the row without a target rather than showing a blank where it goes.
            else -> hermesTarget(item) ?: octoGramTarget(item)
        }
        return PatchRow(
            key = item.key,
            label = item.label,
            description = item.description,
            enabled = selection.contains(item),
            item = item,
            // "Covered" is the model's own verdict, and the reason is its own sentence: this only
            // classifies which of the two claims the model made.
            inertKind = if (ruleRow?.coveredBy != null) InertKind.COVERED else null,
            inertReason = ruleRow?.lockedReason,
            technicalTarget = technical,
            detail = ruleRow?.let { ruleDetail(item, it) }
                ?: hermesDetail(item)
                ?: octoGramDetail(item)
        )
    }

    /** One gate's row: no item, no switch to move, and the model's reason it has neither. */
    private fun gateRow(setId: String, row: BlocklistGateRow): PatchRow = PatchRow(
        key = gateKey(setId, row.gate.pattern),
        label = row.label,
        description = row.description,
        // A gate is applied to every request. The row shows that state and shows it is fixed.
        enabled = true,
        inertKind = InertKind.REQUIRED,
        inertReason = row.lockedReason,
        technicalTarget = constString(row.gate.pattern)
    )

    /** The literal the generated interceptor emits for a pattern, as it emits it. */
    private fun constString(pattern: String): String = "const-string v2, \"$pattern\""

    /** Which requests a rule is tested against—what makes one rule able to cover another. */
    private fun ruleDetail(item: PatchItem, row: BlocklistRuleRow): String {
        val regime = when (row.rule.kind) {
            BlocklistRuleKind.HOST -> "Tested against every request URL."
            BlocklistRuleKind.API -> "Tested only against Discord API URLs: " +
                "those containing /api/ and not /external/."
        }
        return "$regime Selection key $item.key."
    }

    /** The exact thing this item rewrites in the JavaScript bundle, before it is opened. */
    private fun hermesTarget(item: PatchItem): String? {
        val patches = HERMES_PATCHES_BY_KEY[item.key]?.takeIf { it.isNotEmpty() } ?: return null
        // One function is named by its id, which is what identifies it across releases; several
        // are summarised by count, since the ids are listed behind the disclosure.
        return if (patches.size == 1) {
            val only = patches.single()
            "index.android.bundle · function ${only.functionId} · ${only.originalSize} bytes"
        } else {
            "index.android.bundle · ${patches.size} functions · " +
                "${patches.sumOf { it.originalSize }} bytes"
        }
    }

    /** The exact thing an OctoGram item rewrites: its class, and the shape of each edit in it. */
    private fun octoGramTarget(item: PatchItem): String? = OctoGramPatchItems.technicalTarget(item)

    /** The longer text behind an OctoGram item's target, from the item table's own entry. */
    private fun octoGramDetail(item: PatchItem): String? = OctoGramPatchItems.detail(item)

    /**
     * Which functions an item patches, one line each, with the reference's note where it has one.
     *
     * Read-only: the switch above it is the choice. A feature is several functions often enough
     * that naming them is the only way to see what an item covers, and the note is the reference's
     * own words for the functions it documents.
     */
    private fun hermesDetail(item: PatchItem): String? {
        val patches = HERMES_PATCHES_BY_KEY[item.key]?.takeIf { it.isNotEmpty() } ?: return null
        val audits = HERMES_AUDITS_BY_KEY[item.key].orEmpty()
        val lines = patches.joinToString("\n") { patch ->
            val name = patch.name.ifBlank { "unnamed" }
            val bytes = patch.replacementHex.length / 2
            val note = audits[patch.functionId.toString()]?.let { audit ->
                // The note's own words, and only the parts it has: a reference entry is free to
                // carry a title without an explanation, and "null" is not something to show.
                listOfNotNull(audit.title, audit.explanation).joinToString(" ")
            }
            val suffix = if (note.isNullOrBlank()) "" else "\n      $note"
            "  ${patch.functionId}  $name  ${bytes}B$suffix"
        }
        return "Functions replaced:\n$lines\nSelection key ${item.key}."
    }
}
