package dev.sleepy.app.ui.state

import dev.sleepy.app.model.BlocklistGateRow
import dev.sleepy.app.model.BlocklistRuleKind
import dev.sleepy.app.model.BlocklistRuleRow
import dev.sleepy.app.model.HermesPatch
import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.model.PatchSelection
import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.PermissionRow
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.patches.DiscordBlocklistPatch
import dev.sleepy.app.patches.DiscordHermesBundlePatch
import dev.sleepy.app.patches.DiscordHermesFunctionCatalog
import dev.sleepy.app.patches.DiscordPatches
import dev.sleepy.app.patches.OctoGramPatchItems
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.patches.PatchRegistry
import dev.sleepy.app.patches.PatchSections
import dev.sleepy.app.patches.PermissionCatalog

/**
 * The state a section's own switch can be in.
 *
 * A section is not limited to on or off: it is on when every one of its items is on, off when none
 * are, and [PARTIAL] in between. A user reaches [PARTIAL] deliberately by switching one item on
 * inside a section, and the header reports it without rounding to either end. For the derivation,
 * see [PatchRows.triState].
 */
enum class TriState {
    /** Nothing in the section is on. */
    NONE,

    /** Some of the section is on, and some is not. */
    PARTIAL,

    /** Everything in the section is on. */
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
 * One row of an expanded section: an item with its own switch, or one of the blocklist's prefix
 * gates, which has no item behind it because nothing can select it.
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
 *   function and its byte count, or the smali files a whole-set item's set rewrites—shown one tap
 *   away rather than in the row itself.
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

/**
 * One section as the list renders it: its rows, and what its own switch reports.
 *
 * The rows are flat and in the catalogue's own order—there is no heading inside a section, because
 * a heading under a heading is the third level of disclosure this screen exists to lose. The
 * counts are of items, not of rows: a gate is not an item, so Network blocking reports 81 items
 * whether or not its two gates are in the row list, and the section switch covers exactly the
 * items that [PatchRows.sectionsOf] counted.
 *
 * @property label The section's name, from [PatchSections].
 * @property description One line saying what the section is for, written for a user.
 * @property rows Every row under the section, in the order the list shows them.
 * @property itemCount How many items the section holds.
 * @property selectedItemCount How many of those are switched on.
 * @property triState What the section's own switch reports.
 */
data class PatchSectionRows(
    val label: String,
    val description: String,
    val rows: List<PatchRow>,
    val itemCount: Int,
    val selectedItemCount: Int,
    val triState: TriState
)

/**
 * Builds the rows the patch-selection list renders, and derives a section's tri-state from its
 * items.
 *
 * Everything here is a pure function of the sets, the current [PatchSelection] and the tables in
 * `patches/`, so the coverage graying is recomputed rather than stored: the blocklist's rows are
 * re-derived from the selection on every read ([DiscordBlocklistPatch.rows]), and turning a
 * covering rule off makes everything it covered selectable again with no state to keep in step.
 *
 * This is presentation only and adds no facts of its own. Item labels, descriptions, groups and
 * identities come from [PatchItemCatalog]; which section an item is filed under comes from the
 * item itself, which the catalog filled in from [PatchSections]; whether a rule is redundant comes
 * from the coverage table; the graying reasons are the model's own strings.
 */
object PatchRows {

    /** The row key of a gate, which is not an item and so has no item key. */
    private fun gateKey(setId: String, pattern: String): String = "gate:$setId:$pattern"

    /**
     * The key of a section's header in the lazy list.
     *
     * A lazy list uses keys as a row's identity across recompositions, so they are built here, next
     * to the row keys they have to stay distinct from, rather than spelled out at the call site.
     * The label is the key's body because the label is what a section is: there is no id behind it
     * to outlive it, and a renamed section is a different section on screen anyway.
     */
    fun sectionKey(label: String): String = "section:$label"

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
     * The recorded change set's own audit note per function, by item key.
     *
     * Only the functions the recorded change set documents have one; the rest are described by the
     * extracted bodies in [DiscordHermesBundlePatch]. Both kinds of description come from those
     * sources.
     */
    private val HERMES_AUDITS_BY_KEY: Map<String, Map<String, HermesPatch>> =
        DiscordHermesFunctionCatalog.FEATURES.associate { feature ->
            // A recorded note names its function as a string, while the table uses an id, so the
            // two are matched by parsing rather than by comparing the two shapes directly.
            val ids: List<String> = feature.functionIds.map { it.toString() }
            val notes: Map<String, HermesPatch> = DiscordPatches.HERMES.hermesPatches
                .filter { patch -> patch.functionId in ids }
                .associateBy { patch -> patch.functionId }
            DiscordHermesFunctionCatalog.itemKeyOf(feature.slug) to notes
        }

    /**
     * Every section of [sets] that has a row, in [PatchSections.ALL] order, for [selection].
     *
     * A set that is not split into items is not left out: its one item stands for the whole set, so
     * it is a row inside its section, and switching that row is the same choice as selecting the
     * set. A section nothing lands in is left out entirely rather than shown empty: a source
     * carries different sets, so which of the seven sections exist is a property of the source, and
     * a section with nothing under it would be a heading with no switch behind it.
     *
     * @param sets The source's sets.
     * @param selection The selection the rows are derived from.
     * @return One entry per non-empty section, sections and rows in display order.
     */
    fun sectionsOf(sets: List<PatchSet>, selection: PatchSelection): List<PatchSectionRows> {
        val itemsBySection = LinkedHashMap<String, MutableList<PatchItem>>()
        sets.forEach { set ->
            PatchItemCatalog.itemsOf(set.id).forEach { item ->
                itemsBySection.getOrPut(item.section) { mutableListOf() } += item
            }
        }

        val blocklistRows = if (sets.any { it.id == DiscordBlocklistPatch.NETWORK_BLOCKLIST.id }) {
            DiscordBlocklistPatch.rows(selection)
        } else {
            emptyList()
        }
        val gates = blocklistRows
            .filterIsInstance<BlocklistGateRow>()
            .map { gateRow(DiscordBlocklistPatch.NETWORK_BLOCKLIST.id, it) }
        val gatesSection = PatchSections.forSet(DiscordBlocklistPatch.NETWORK_BLOCKLIST.id)
        val rulesByIdentity = blocklistRows
            .filterIsInstance<BlocklistRuleRow>()
            .associateBy { it.rule.identity }

        return PatchSections.ALL.mapNotNull { section ->
            val items = itemsBySection[section].orEmpty()
            val rows = (if (section == gatesSection) gates else emptyList()) +
                items.map { item -> itemRow(item, selection, rulesByIdentity[item.identity]) }
            if (rows.isEmpty()) return@mapNotNull null
            PatchSectionRows(
                label = section,
                description = PatchSections.descriptionOf(section),
                rows = rows,
                itemCount = items.size,
                selectedItemCount = selection.selected(items).size,
                triState = triState(items, selection)
            )
        }
    }

    /**
     * The permission section's rows: one per permission the build declares, in the order its
     * manifest declares them.
     *
     * These are the same rows an expanded section has, rendered by the same row and grayed by the
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
     * The permissions keep their own card rather than being filed under a section: the card states
     * what removing a declaration costs before it offers the switch, and the list is about the
     * build being patched rather than about a change sleepy makes.
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
     * The state of a section whose items are [items]: on only when every one of them is on.
     *
     * A section with no items is [TriState.NONE] rather than [TriState.ALL], so an id that names no
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
     * Returns the value a section's header switch reports for [state], and therefore what a tap on
     * it means.
     *
     * The switch reports "the whole section is on" and nothing else, which determines both
     * directions of a tap: a section that is fully on reports true, so a tap returns false and
     * clears it, and a section that is off—or partly on, which is the state the user asked for when
     * they switched one item on inside a section—reports false, so a tap returns true and selects
     * everything in the section. A partial section is therefore completed rather than cleared by a
     * tap on its header, and clearing one takes the same single tap that clearing a fully-on
     * section takes.
     *
     * @param state The section's switch state.
     * @return True if the switch reports on; false otherwise.
     */
    fun headerChecked(state: TriState): Boolean = state == TriState.ALL

    /**
     * The state a group of rows reports, for a heading that is not a section.
     *
     * Rows rather than items, because the permission heading holds rows that cannot be switched:
     * counting them as items would make the heading read as partly selected forever while no
     * control on it could change that.
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
            // Each kind of item has its own target, and a kind with nothing to name keeps the row
            // without a target rather than showing a blank where it goes.
            else -> hermesTarget(item) ?: octoGramTarget(item) ?: wholeSetTarget(item)
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
                ?: wholeSetDetail(item)
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
     * The files a whole-set item's set rewrites.
     *
     * A set that is not split into items has no entry of its own to name them, so the row takes
     * them from the set: the set's technical panel used to be where they were read, and the panel
     * went away with the set cards.
     */
    private fun wholeSetTarget(item: PatchItem): String? {
        val set = PatchRegistry.get(item.setId) ?: return null
        val paths = set.smaliPatches.map { it.smaliPath }.distinct()
        if (paths.isEmpty()) return null
        return if (set.smaliPatches.size == 1) {
            paths.single()
        } else {
            "${set.smaliPatches.size} smali entries in ${paths.size} " +
                (if (paths.size == 1) "file" else "files") + ": " + paths.joinToString(", ")
        }
    }

    /**
     * The text behind a whole-set item's target: each entry's own title and explanation, above the
     * file and method it rewrites.
     *
     * The same facts the set's technical panel listed, moved to the row rather than dropped with
     * the panel. A set of one edit has no rows inside it to carry them otherwise.
     */
    private fun wholeSetDetail(item: PatchItem): String? {
        val set = PatchRegistry.get(item.setId) ?: return null
        val entries = set.smaliPatches.joinToString("\n\n") { patch ->
            val location = listOfNotNull(
                patch.dexName?.takeIf { it.isNotBlank() }?.let { "[$it] ${patch.smaliPath}" }
                    ?: patch.smaliPath.takeIf { it.isNotBlank() },
                targetLine(patch)
            ).joinToString(" · ")
            listOfNotNull(
                patch.title?.takeIf { it.isNotBlank() },
                patch.explanation?.takeIf { it.isNotBlank() },
                location.takeIf { it.isNotBlank() }
            ).joinToString("\n")
        }
        if (entries.isBlank()) return null
        return "$entries\nSelection key ${item.key}."
    }

    /**
     * What one smali entry does to the file it names: replaces a method, slices a case out of a
     * switch, or splices around an anchor.
     *
     * Only the first has a method signature, and a line reading "Method: null" reports nothing
     * where the precise target belongs, so a shape with nothing to name gets no line.
     */
    private fun targetLine(patch: SmaliPatch): String? = when {
        !patch.methodSignature.isNullOrBlank() -> "replaces ${patch.methodSignature}"
        !patch.switchCaseLabel.isNullOrBlank() -> "slices switch case ${patch.switchCaseLabel}"
        !patch.anchor.isNullOrBlank() ->
            "splices around: " +
                patch.anchor.lines().joinToString(" ; ") { it.trim() }.trim(' ', ';')

        else -> null
    }

    /**
     * Which functions an item patches, one line each, with the recorded note where it has one.
     *
     * Read-only: the switch above it is the choice. A feature is several functions often enough
     * that naming them is the only way to see what an item covers, and the note is the recorded
     * change set's own words for the functions it documents.
     */
    private fun hermesDetail(item: PatchItem): String? {
        val patches = HERMES_PATCHES_BY_KEY[item.key]?.takeIf { it.isNotEmpty() } ?: return null
        val audits = HERMES_AUDITS_BY_KEY[item.key].orEmpty()
        val lines = patches.joinToString("\n") { patch ->
            val name = patch.name.ifBlank { "unnamed" }
            val bytes = patch.replacementHex.length / 2
            val note = audits[patch.functionId.toString()]?.let { audit ->
                // The note's own words, and only the parts it has: a recorded entry is free to
                // carry a title without an explanation, and "null" is not something to show.
                listOfNotNull(audit.title, audit.explanation).joinToString(" ")
            }
            val suffix = if (note.isNullOrBlank()) "" else "\n      $note"
            "  ${patch.functionId}  $name  ${bytes}B$suffix"
        }
        return "Functions replaced:\n$lines\nSelection key ${item.key}."
    }
}
