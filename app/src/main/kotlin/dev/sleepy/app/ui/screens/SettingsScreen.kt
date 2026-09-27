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
import androidx.compose.material3.Surface
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
import dev.sleepy.app.BuildConfig
import dev.sleepy.app.model.AppSource
import dev.sleepy.app.ui.SourcesManifest
import dev.sleepy.app.ui.components.DownloadHostRow
import dev.sleepy.app.ui.components.downloadHosts

/**
 * Settings and provenance.
 *
 * The screens above this one ask the reader to trust a build; this one is where that trust can
 * be checked — which hosts each target is downloaded from, which manifest drove the build, and
 * where the code that does the work lives.
 *
 * @param sources every target this build can patch, each with the hosts it downloads from, so a
 *   target other than the selected one is shown rather than left out.
 * @param selectedSourceId the target last opened on the home screen, which is marked in the list.
 *   It is null before anything has been opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sources: List<AppSource>,
    selectedSourceId: String?,
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

            AppIdentityCard(versionName = BuildConfig.VERSION_NAME)

            SettingsSectionTitle("Where builds come from")

            ProvenanceCard(
                sources = sources,
                selectedSourceId = selectedSourceId,
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

/**
 * Product identity: what this app is and what it does.
 *
 * The version is the one the build was made as — read from the generated `BuildConfig`, which the
 * build script derives from the changelog — so it cannot say something the release does not.
 */
@Composable
private fun AppIdentityCard(versionName: String) {
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
                text = "Version $versionName • Target SDK 37 (Android 17 QPR2)",
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
 * Where every build comes from: one entry per target, with the hosts its files are fetched from,
 * and the manifest that declared those hosts.
 *
 * Every target is listed rather than only the selected one, because provenance a reader has to
 * select a target to see is provenance they cannot check before selecting it. The one they last
 * opened is marked, so the list says both what this app can fetch and what it is about to.
 */
@Composable
private fun ProvenanceCard(
    sources: List<AppSource>,
    selectedSourceId: String?,
    manifestUrl: String?,
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
            if (sources.isEmpty()) {
                Text(
                    text = "No target is listed in the manifest this build ships.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Nothing can be downloaded until the manifest lists something to " +
                        "download. The manifest is published at the address below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Everything sleepy downloads, and where from.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                sources.forEachIndexed { index, source ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    ProvenanceSourceEntry(
                        source = source,
                        isSelected = source.id == selectedSourceId
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "Download URLs, versions and hashes are declared in sources.json. " +
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

/** One target's provenance: what it is, which hosts it fetches from, and whether it is selected. */
@Composable
private fun ProvenanceSourceEntry(source: AppSource, isSelected: Boolean) {
    val hosts = remember(source) { downloadHosts(source) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = "Last opened",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
        Text(
            text = "${source.versionName} • ${source.packageName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        DownloadHostRow(hosts = hosts)
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
