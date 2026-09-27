package dev.sleepy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sleepy.app.model.StepResult
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.ui.theme.SleepySuccess
import dev.sleepy.app.ui.theme.SleepyWarning

@Composable
fun StepLogItem(
    result: StepResult,
    modifier: Modifier = Modifier
) {
    val (icon, badgeColor, iconColor) = when (result.status) {
        StepStatus.OK -> Triple(Icons.Default.Check, SleepySuccess.copy(alpha = 0.2f), SleepySuccess)
        StepStatus.SKIP -> Triple(Icons.Default.Remove, SleepyWarning.copy(alpha = 0.2f), SleepyWarning)
        StepStatus.FAIL -> Triple(Icons.Default.Close, MaterialTheme.colorScheme.error.copy(alpha = 0.2f), MaterialTheme.colorScheme.error)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(badgeColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = result.status.name,
                tint = iconColor,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (result.detail != null) {
                Text(
                    text = result.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
