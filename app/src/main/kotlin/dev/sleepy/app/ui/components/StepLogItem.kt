package dev.sleepy.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.ui.theme.statusColors

/**
 * One line of the patch log.
 *
 * Each entry leads with what changed in the user's terms, then why it was done, and only
 * reveals the exact class, method or function id when the row is expanded. The engineering
 * detail stays available — this is a tool that modifies someone's app, so the mechanism is
 * never hidden — but it no longer has to be read to understand the outcome.
 */
@Composable
fun StepLogItem(
    result: StepResult,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    val (icon, statusColor, statusLabel) = when (result.status) {
        StepStatus.OK -> StepBadge(Icons.Default.Check, MaterialTheme.statusColors.success, "Applied")
        StepStatus.SKIP -> StepBadge(Icons.Default.Remove, MaterialTheme.statusColors.warning, "Skipped")
        StepStatus.FAIL -> StepBadge(Icons.Default.Close, MaterialTheme.colorScheme.error, "Failed")
    }

    val technical = result.technicalTarget?.takeIf { it.isNotBlank() && it != result.title }
    val hasDetail = technical != null || !result.detail.isNullOrBlank()

    var expanded by remember(result) { mutableStateOf(initiallyExpanded) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .then(
                if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier
            ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(statusColor.copy(alpha = 0.18f))
                        .clearAndSetSemantics { contentDescription = statusLabel },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (!result.explanation.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = result.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (hasDetail) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Hide technical detail" else "Show technical detail",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded && hasDetail) {
                Column(modifier = Modifier.padding(top = 10.dp, start = 40.dp)) {
                    val detailText = result.detail
                    if (!detailText.isNullOrBlank()) {
                        Text(
                            text = detailText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (technical != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                text = technical,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Icon, accent colour and accessibility label for one status. */
private data class StepBadge(
    val icon: ImageVector,
    val color: Color,
    val label: String
)
