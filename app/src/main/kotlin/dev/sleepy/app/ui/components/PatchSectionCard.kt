package dev.sleepy.app.ui.components

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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sleepy.app.ui.state.PatchRows
import dev.sleepy.app.ui.state.PatchSectionRows
import dev.sleepy.app.ui.state.TriState

/**
 * One section of the selection list: its own switch, and the disclosure that opens its rows.
 *
 * The sections are the list's only grouping level—the rows under one are flat, with no heading
 * between them—because a heading under a heading is the third level of disclosure the screen used
 * to have and the reason it was hard to read.
 *
 * The switch is the section's, not a row's, so it is tri-state: on when every item is on, off when
 * none is, and showing a dash when the section is partly on—a state the user reaches on purpose by
 * switching one row on inside it. What a tap on it does is defined by [PatchRows.headerChecked],
 * so the behavior is the same for every section, and the count beside it states which way the next
 * tap will go: a section that is not fully on is completed by a tap, and one that is fully on is
 * cleared by it.
 *
 * The section's rows are not rendered here. They are emitted as their own lazy rows by the screen,
 * so the eighty-one-rule blocklist scrolls without composing all of them, and this card stays a
 * header.
 *
 * @param section The section to render.
 * @param expanded Whether the section's rows are shown.
 * @param onSectionToggled Called with the new value when the section's switch is moved.
 * @param onExpandedChange Called when the disclosure row is tapped.
 * @param modifier The modifier applied to the card.
 */
@Composable
fun PatchSectionCard(
    section: PatchSectionRows,
    expanded: Boolean,
    onSectionToggled: (Boolean) -> Unit,
    onExpandedChange: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = section.triState != TriState.NONE
    val summary = selectionSummary(section)

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
                        value = PatchRows.headerChecked(section.triState),
                        role = Role.Switch,
                        onValueChange = onSectionToggled
                    )
                    .semantics { stateDescription = summary },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = section.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "${section.selectedItemCount} of ${section.itemCount}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Three states, two of which mean "the section is contributing something": the
                // dash in the thumb is what distinguishes them, so the state is not carried by the
                // track color alone.
                Switch(
                    checked = selected,
                    onCheckedChange = null,
                    thumbContent = when (section.triState) {
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

            Spacer(modifier = Modifier.height(4.dp))

            // Counted rather than pluralised as "items": a source with few sets lands a section
            // with one item in it, and "Show the 1 items" is what that reads as otherwise.
            val noun = if (section.itemCount == 1) "item" else "items"
            DisclosureRow(
                icon = Icons.Default.Tune,
                text = if (expanded) {
                    "Hide the ${section.itemCount} $noun"
                } else {
                    "Show the ${section.itemCount} $noun"
                },
                expanded = expanded,
                onClick = onExpandedChange,
                onClickLabel = if (expanded) {
                    "Hide this section's items"
                } else {
                    "Show this section's items"
                }
            )
        }
    }
}

/**
 * The glyph inside a switch's thumb, sized the way Material sizes its own.
 *
 * A switch's thumb content is not labeled: the switch it sits in already announces its state, and
 * the dash it carries when a section is partly on is repeated in words by the count beside it.
 */
@Composable
private fun ThumbIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(SwitchDefaults.IconSize)
    )
}

/**
 * The summary line for a section, for example "3 of 12 items selected", which is also the
 * description the section switch reports to a screen reader.
 */
private fun selectionSummary(section: PatchSectionRows): String {
    val noun = if (section.itemCount == 1) "item" else "items"
    return "${section.selectedItemCount} of ${section.itemCount} $noun selected"
}

/**
 * One line that opens something, the same shape [StepLogItem] uses for its detail: a labeled
 * target at least 48 dp high, with the action spelled out for a screen reader.
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
        // The clickable row names the action in its onClickLabel, so a description on this glyph
        // repeats the action in a second announcement.
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}
