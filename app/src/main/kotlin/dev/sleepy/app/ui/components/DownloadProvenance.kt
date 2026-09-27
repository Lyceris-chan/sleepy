package dev.sleepy.app.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sleepy.app.model.AppSource

/**
 * The distinct hosts this source's files are fetched from.
 *
 * A source can be a base APK plus split configuration files, and those can live on different
 * hosts; every one of them is listed so a reader can see the full set of servers this app
 * talks to for a build.
 */
fun downloadHosts(source: AppSource): List<String> =
    (listOf(source.url) + source.splitUrls)
        .mapNotNull { url -> Uri.parse(url).host?.takeIf { host -> host.isNotBlank() } }
        .distinct()

/**
 * Where the selected APK comes from, stated plainly.
 *
 * This is text rather than a link on purpose: it sits inside a card that is itself clickable,
 * and a second tap target in the same row would make the card ambiguous to anyone using
 * switch access or a screen reader.
 */
@Composable
fun DownloadHostRow(
    hosts: List<String>,
    modifier: Modifier = Modifier
) {
    if (hosts.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "Downloaded from ${hosts.joinToString(" and ")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
