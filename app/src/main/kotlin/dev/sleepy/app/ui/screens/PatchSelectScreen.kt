package dev.sleepy.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sleepy.app.patches.PatchRegistry
import dev.sleepy.app.ui.components.PatchCard
import dev.sleepy.app.viewmodel.PatchViewModel

/**
 * Chooses which changes to apply, and optionally gives the result its own package name so it
 * can be installed next to the app it was built from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchSelectScreen(
    sourceId: String,
    viewModel: PatchViewModel,
    onStartPatch: () -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(sourceId) {
        viewModel.selectSource(sourceId)
    }

    val source by viewModel.selectedSource.collectAsState()
    val selectedPatchIds by viewModel.selectedPatchIds.collectAsState()

    val availablePatches = remember(source) {
        source?.patchIds?.let { PatchRegistry.getAllForSource(it) } ?: emptyList()
    }

    val isCloneMode by viewModel.isCloneMode.collectAsState()
    val customPackageName by viewModel.customPackageName.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = source?.displayName ?: "Select patches",
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
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .navigationBarsPadding()
                ) {
                    Button(
                        onClick = {
                            viewModel.startPatch()
                            onStartPatch()
                        },
                        enabled = selectedPatchIds.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Patch APK (${selectedPatchIds.size} selected)",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))

                CloneModeCard(
                    isCloneMode = isCloneMode,
                    customPackageName = customPackageName,
                    originalPackageName = source?.packageName.orEmpty(),
                    onCloneModeChange = { viewModel.setCloneMode(it) },
                    onPackageNameChange = { viewModel.setCustomPackageName(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Configure modding pipeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select the modifications to apply surgically in memory during the " +
                        "build step.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            items(availablePatches, key = { it.id }) { patch ->
                PatchCard(
                    patch = patch,
                    selected = selectedPatchIds.contains(patch.id),
                    onToggle = { viewModel.togglePatch(patch.id) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Clone-app configuration.
 *
 * The whole row is the switch target, so it is reachable as one control with a label rather
 * than as a small thumb next to an unrelated sentence.
 */
@Composable
private fun CloneModeCard(
    isCloneMode: Boolean,
    customPackageName: String,
    originalPackageName: String,
    onCloneModeChange: (Boolean) -> Unit,
    onPackageNameChange: (String) -> Unit
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
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = isCloneMode,
                        role = Role.Switch,
                        onValueChange = onCloneModeChange
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Clone app (change package name)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Allows installing alongside the original app with no signature " +
                            "conflicts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isCloneMode,
                    onCheckedChange = null
                )
            }

            if (isCloneMode) {
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = customPackageName,
                    onValueChange = onPackageNameChange,
                    label = { Text("Cloned package name") },
                    placeholder = { Text("$originalPackageName.sleepy") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    supportingText = {
                        Text("Original: $originalPackageName")
                    }
                )
            }
        }
    }
}
