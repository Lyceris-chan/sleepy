package dev.sleepy.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sleepy.app.model.PatchSet
import dev.sleepy.app.model.SmaliPatch
import dev.sleepy.app.model.TargetWrittenGenerator
import dev.sleepy.app.patches.PatchItemCatalog
import dev.sleepy.app.ui.state.PatchRows
import dev.sleepy.app.ui.state.PatchSetRows
import dev.sleepy.app.ui.state.TriState

/**
 * One patch set in the selection list: its own switch, and the disclosure that opens its items.
 *
 * The switch is the set's, not an item's, so it is tri-state: on when every item is on, off when
 * none is, and showing a dash when the set is partly on — which is a state the user reaches on
 * purpose, by switching one item on inside the set. What a tap on it does is
 * [PatchRows.headerChecked]'s decision, so it is the same for everyone and is stated in the row's
 * own summary line: a set that is not fully on is completed by a tap, and one that is fully on is
 * cleared by it.
 *
 * The set's items are not rendered here. They are emitted as their own lazy rows by the screen,
 * so a set of a hundred and forty-two or eighty-one items scrolls without composing all of them,
 * and this card stays a header.
 */
@Composable
fun PatchSetCard(
    set: PatchSet,
    rows: PatchSetRows,
    expanded: Boolean,
    onSetToggled: (Boolean) -> Unit,
    onExpandedChange: () -> Unit,
    modifier: Modifier = Modifier
) {
    var technicalExpanded by remember(set.id) { mutableStateOf(false) }
    // A set whose edits are switched one at a time carries no patches of its own — the engine takes
    // them from its generator — so the targets it touches are read from the item table as well, or
    // the panel would report "0 hooks" for the set with the most of them.
    val itemPatches = remember(set.id) { PatchItemCatalog.itemPatches(set.id) }
    val hookCount = set.smaliPatches.size + set.hermesPatches.size + itemPatches.size
    val selected = rows.triState != TriState.NONE
    val summary = selectionSummary(rows)

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = PatchRows.headerChecked(rows.triState),
                        role = Role.Switch,
                        onValueChange = onSetToggled
                    )
                    .semantics { stateDescription = summary },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = set.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = set.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Three states, two of which read as "the set is contributing something": the dash
                // in the thumb is what tells them apart, so the state is never carried by the track
                // colour alone.
                Switch(
                    checked = selected,
                    onCheckedChange = null,
                    thumbContent = when (rows.triState) {
                        TriState.ALL -> {
                            { ThumbIcon(Icons.Default.Check) }
                        }

                        TriState.PARTIAL -> {
                            { ThumbIcon(Icons.Default.Remove) }
                        }

                        TriState.NONE -> null
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )

            if (rows.expandable) {
                Spacer(modifier = Modifier.height(4.dp))
                DisclosureRow(
                    icon = Icons.Default.Tune,
                    text = if (expanded) {
                        "Hide the ${rows.itemCount} items"
                    } else {
                        "Show the ${rows.itemCount} items"
                    },
                    expanded = expanded,
                    onClick = onExpandedChange,
                    onClickLabel = if (expanded) "Hide this set's items" else "Show this set's items"
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            DisclosureRow(
                icon = Icons.Default.Info,
                text = if (technicalExpanded) {
                    "Hide technical targets"
                } else {
                    // A set whose patch is generated from the target APK has no hooks to count, and
                    // "0 hooks" would read as "changes nothing" when it is the largest edit in the
                    // set.
                    when {
                        hookCount > 0 -> "View technical details ($hookCount hooks)"
                        set.generator != null -> "View technical details (generated for this build)"
                        else -> "View technical details (0 hooks)"
                    }
                },
                expanded = technicalExpanded,
                onClick = { technicalExpanded = !technicalExpanded },
                onClickLabel = if (technicalExpanded) {
                    "Hide technical targets"
                } else {
                    "Show technical targets"
                }
            )

            AnimatedVisibility(visible = technicalExpanded) {
                PatchTechnicalTargets(set = set, itemPatches = itemPatches)
            }
        }
    }
}

/**
 * The glyph inside a switch's thumb, sized the way Material sizes its own.
 *
 * A switch's thumb content is never labelled: the switch it sits in already announces its state,
 * and the dash it carries when a set is partly on is repeated in words by the summary beside it.
 */
@Composable
private fun ThumbIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(SwitchDefaults.IconSize)
    )
}

/** "3 of 145 items selected", and the same sentence a screen reader hears for the set switch. */
private fun selectionSummary(rows: PatchSetRows): String {
    val noun = if (rows.itemCount == 1) "item" else "items"
    return "${rows.selectedItemCount} of ${rows.itemCount} $noun selected"
}

/**
 * One line that opens something, the same shape [StepLogItem] uses for its detail: a labelled
 * target at least a finger high, with the action spelled out for a screen reader.
 */
@Composable
private fun DisclosureRow(
    icon: ImageVector,
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
    onClickLabel: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = onClickLabel, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = onClickLabel,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * What one smali entry rewrites, as the one line under its class path.
 *
 * An entry replaces a method, slices a case out of a switch, or splices around an anchor, and only
 * the first has a method signature. A line reading "Method: null" would be noise exactly where the
 * precise target belongs, so each shape is named as itself and a shape with nothing to name gets no
 * line at all.
 */
private fun targetLine(patch: SmaliPatch): String? = when {
    !patch.methodSignature.isNullOrBlank() -> "Method: ${patch.methodSignature}"
    !patch.switchCaseLabel.isNullOrBlank() -> "Slices switch case ${patch.switchCaseLabel}"
    !patch.anchor.isNullOrBlank() ->
        "Splices around: " + patch.anchor.lines().joinToString(" ; ") { it.trim() }.trim(' ', ';')

    else -> null
}

/**
 * The exact bytecode and bytecode-stub targets behind a set.
 *
 * This is the audit trail the app has always shown — which classes, methods and function ids a set
 * rewrites — kept rather than replaced by the per-item list: the item list says what each switch
 * does, and this says what the set would touch on the build being patched.
 *
 * [itemPatches] are the entries a set's items carry, for the sets that hand the engine a generator
 * instead of declaring patches. They are listed under the same heading because to this reader they
 * are the same fact — the set's own patches first, then the ones behind its switches.
 */
@Composable
private fun PatchTechnicalTargets(set: PatchSet, itemPatches: List<SmaliPatch>) {
    val smaliPatches = set.smaliPatches + itemPatches
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (smaliPatches.isNotEmpty()) {
            Text(
                text = "SMALI METHOD SURGERY TARGETS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            smaliPatches.forEach { smaliPatch ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // The entry's own title and explanation are shown here rather than left to the
                    // progress log. A set with one item has no expanded rows to carry them, so for
                    // those sets this panel is the only place the text can be read before patching.
                    smaliPatch.title?.takeIf { it.isNotBlank() }?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    smaliPatch.explanation?.takeIf { it.isNotBlank() }?.let { explanation ->
                        Text(
                            text = explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // The dex is resolved at patch time, so an entry that names one says which, and
                    // an entry that leaves it to the pipeline does not read "[null]".
                    Text(
                        text = smaliPatch.dexName?.takeIf { it.isNotBlank() }
                            ?.let { "• [$it] ${smaliPatch.smaliPath}" }
                            ?: "• ${smaliPatch.smaliPath}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Not every entry rewrites a whole method: some slice one case out of a
                    // dispatcher and some splice around an anchor, and those have no signature.
                    targetLine(smaliPatch)?.let { target ->
                        Text(
                            text = "  $target",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (set.hermesPatches.isNotEmpty()) {
            if (set.smaliPatches.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Text(
                text = "HERMES BYTECODE STUBS (index.android.bundle)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            set.hermesPatches.forEach { hermesPatch ->
                Column {
                    Text(
                        text = "• Function ID ${hermesPatch.functionId}: ${hermesPatch.functionName}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "  HASM: ${hermesPatch.hasmStub.replace("\n", " ; ")}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Only a generator that writes its patch text from the target APK may claim to: a generator
        // that selects among patches written ahead of time solves a different problem, and the
        // sentence below would be false about it.
        if (set.generator is TargetWrittenGenerator) {
            if (smaliPatches.isNotEmpty() || set.hermesPatches.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Text(
                text = "GENERATED FROM THE TARGET APK",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "This method is written while the APK is patched rather than ahead of " +
                    "time: the names it has to spell out are renamed on every build, so they " +
                    "are read from the APK being patched.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
