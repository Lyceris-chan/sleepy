package dev.sleepy.app.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sleepy.app.model.PatchItem
import dev.sleepy.app.ui.state.InertKind
import dev.sleepy.app.ui.state.PatchRow

/**
 * One row of an expanded patch set: an item with its own switch, or a row that has no choice in
 * it at all — a blocklist rule another enabled rule already answers for, or one of the
 * interceptor's prefix gates.
 *
 * The row is the switch's target, so it reads as one labelled control rather than as a small thumb
 * beside an unrelated sentence, and its description is always visible: a switch whose effect the
 * user cannot read is not a choice.
 *
 * A row whose switch cannot be moved says so in three ways at once — a disabled switch, an icon,
 * and the reason in full, which comes from the model rather than from here. Nothing about the
 * state is left to colour, and an inert row is never rendered without a reason to read: the two
 * kinds of inertness are different claims, and a rule that is merely redundant becomes a working
 * switch again the moment its coverer is turned off, while a gate never will.
 */
@Composable
fun PatchItemRow(
    row: PatchRow,
    onToggle: (PatchItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var detailExpanded by remember(row.key) { mutableStateOf(false) }
    val inertKind = row.inertKind
    val item = row.item
    val technicalTarget = row.technicalTarget
    val detail = row.detail
    val hasDetail = technicalTarget != null || detail != null

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (inertKind != null) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(
                        if (row.switchable && item != null) {
                            Modifier.toggleable(
                                value = row.enabled,
                                role = Role.Switch,
                                onValueChange = { onToggle(item) }
                            )
                        } else {
                            // Not interactive, so the row is announced as disabled and its texts
                            // are read as one label rather than as three unrelated fragments.
                            Modifier.semantics(mergeDescendants = true) { disabled() }
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = row.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val reason = row.inertReason
                    if (inertKind != null && !reason.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        InertNotice(kind = inertKind, reason = reason)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Switch(
                    checked = row.enabled,
                    onCheckedChange = null,
                    enabled = row.switchable
                )
            }

            if (hasDetail) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            onClickLabel = if (detailExpanded) {
                                "Hide the technical target"
                            } else {
                                "Show the technical target"
                            },
                            onClick = { detailExpanded = !detailExpanded }
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (detailExpanded) {
                            Icons.Default.ExpandLess
                        } else {
                            Icons.Default.ExpandMore
                        },
                        contentDescription = if (detailExpanded) {
                            "Hide the technical target"
                        } else {
                            "Show the technical target"
                        },
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (detailExpanded) "Hide technical target" else "View technical target",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                AnimatedVisibility(visible = detailExpanded) {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        if (technicalTarget != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text(
                                    text = technicalTarget,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                )
                            }
                        }
                        if (detail != null) {
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Why a row's switch cannot be moved, in the model's own words, beside a glyph that says which of
 * the two reasons it is.
 *
 * The glyph is decorative and unlabelled on purpose: the sentence next to it is the reason, and the
 * two kinds do not read alike — one names the pattern that covers the row, the other begins with
 * the word that says the row is required.
 */
@Composable
private fun InertNotice(kind: InertKind, reason: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = kind.glyph(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(14.dp)
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The glyph that tells the two kinds of inert row apart at a glance. */
private fun InertKind.glyph(): ImageVector = when (this) {
    InertKind.COVERED -> Icons.Default.Layers
    InertKind.REQUIRED -> Icons.Default.Lock
}
