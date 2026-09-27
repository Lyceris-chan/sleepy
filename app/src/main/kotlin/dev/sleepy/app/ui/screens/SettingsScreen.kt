package dev.sleepy.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sleepy.app.model.AppSource
import dev.sleepy.app.ui.SourcesManifest
import dev.sleepy.app.ui.components.DownloadHostRow
import dev.sleepy.app.ui.components.downloadHosts

/**
 * Settings and provenance.
 *
 * The screens above this one ask the reader to trust a build; this one is where that trust can
 * be checked — which hosts the app downloads from, which manifest drove the build, and where
 * the code that does the work lives.
 *
 * @param selectedSource the source currently chosen on the home screen, if any, so its
 *   download hosts can be shown before a build starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedSource: AppSource?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val manifest = remember(context) { SourcesManifest.load(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings & Transparency",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            AppIdentityCard()

            SettingsSectionTitle("Where builds come from")

            ProvenanceCard(
                selectedSource = selectedSource,
                manifestUrl = manifest.manifestUrl,
                onOpenUrl = { url -> openUrl(context, url) }
            )

            SettingsSectionTitle("Source code")

            SourceCodeCard(
                projectUrl = manifest.projectUrl,
                onOpenUrl = { url -> openUrl(context, url) }
            )

            SettingsSectionTitle("Security & runtime architecture")

            ArchitectureCard()

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** Product identity: what this app is and what it does. */
@Composable
private fun AppIdentityCard() {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "sleepy",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Version 1.0.0 • Target SDK 37 (Android 17 QPR2)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "100% native Kotlin on-device APK patcher with in-memory DEX surgery, " +
                    "Hermes HBC modification, and a Material 3 Expressive interface.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Provenance for the source chosen on the home screen: the hosts it downloads from, and the
 * manifest that declared those hosts.
 */
@Composable
private fun ProvenanceCard(
    selectedSource: AppSource?,
    manifestUrl: String?,
    onOpenUrl: (String) -> Unit
) {
    val hosts = remember(selectedSource) {
        selectedSource?.let { source -> downloadHosts(source) }.orEmpty()
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selectedSource != null) {
                Text(
                    text = selectedSource.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                DownloadHostRow(hosts = hosts)
                Text(
                    text = "Every file this build needs is fetched from the hosts above. " +
                        "Nothing else is contacted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "No application selected yet.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Pick a target on the home screen and its download hosts will be " +
                        "listed here before anything is fetched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "Download URLs, versions and hashes are declared in `sources.json`. " +
                    "The manifest used for this build is published at the address below, so the " +
                    "file this app ships with can be compared against it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (manifestUrl != null) {
                FilledTonalButton(
                    onClick = { onOpenUrl(manifestUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View build manifest", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    text = manifestUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Where the code that performs every step of a build can be read. */
@Composable
private fun SourceCodeCard(
    projectUrl: String?,
    onOpenUrl: (String) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "sleepy is open source. The downloader, the DEX and Hermes patchers, the " +
                    "signer and the checks on this screen are all in this project's repository — " +
                    "not the repository of any app it patches.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (projectUrl != null) {
                FilledTonalButton(
                    onClick = { onOpenUrl(projectUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("This project's source code", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    text = projectUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The guarantees the patching engine makes about where data goes and how it is signed. */
@Composable
private fun ArchitectureCard() {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ArchitectureRow(
                icon = Icons.Default.Memory,
                title = "RAM-first buffer processing",
                body = "DEX decompression and smali edits are held in memory"
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ArchitectureRow(
                icon = Icons.Default.Key,
                title = "On-device signing key (v1 + v2 + v3)",
                body = "An RSA-2048 key generated on this device and kept as a PKCS12 file, used with Google's apksig engine"
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ArchitectureRow(
                icon = Icons.Default.VpnLock,
                title = "OWASP Mobile M5 strict TLS",
                body = "Strict HTTPS only, cleartext traffic permanently blocked"
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ArchitectureRow(
                icon = Icons.Default.CheckCircleOutline,
                title = "WCAG 2.2 AA accessibility",
                body = "Screen reader semantics and predictive back support"
            )
        }
    }
}

/** One architecture claim: icon, title, and the detail that backs it up. */
@Composable
private fun ArchitectureRow(
    icon: ImageVector,
    title: String,
    body: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Group heading between cards. */
@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/** Opens a URL in whatever the device uses for links, reporting it rather than crashing. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open $url", Toast.LENGTH_SHORT).show()
    }
}
