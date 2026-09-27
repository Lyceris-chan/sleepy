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
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sleepy.app.patches.PatchRegistry
import dev.sleepy.app.ui.components.PatchItemRow
import dev.sleepy.app.ui.components.PatchSetCard
import dev.sleepy.app.ui.state.PatchRows
import dev.sleepy.app.viewmodel.PatchViewModel

/**
 * Chooses which changes to apply, alone or item by item, and optionally gives the result its own
 * package name so it can be installed next to the app it was built from.
 *
 * A patch set is a header — its own switch, and what it currently selects — over an expandable body
 * of its items. The header's switch is tri-state, so a set with the gift button on and everything
 * else off is shown as such rather than rounded to on or off, and the body is where that state is
 * reached: each item has its own switch and its own description of what turning it on does.
 *
 * The body is emitted as lazy list items rather than as one composable per set, with each row keyed
 * by the item's own stable key, so expanding the eighty-one-rule blocklist or the hundred-and-forty-
 * two-function JavaScript set composes only the rows on screen.
 *
 * A row can also be inert, and then it says why: a blocklist rule another enabled rule already
 * answers for is greyed with the pattern that covers it named in full, and the interceptor's two
 * prefix gates are locked with their own reason. Both come from
 * [dev.sleepy.app.model.BlocklistCoverage], recomputed from the selection on every change, so
 * switching a covering rule off makes what it covered live again immediately.
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
    val selection by viewModel.selection.collectAsState()

    // A set listed twice in a source's ids is one set: the rows below are keyed by set id, and two
    // entries for the same set would be two items with one key.
    val availablePatches = remember(source) {
        source?.patchIds?.let { PatchRegistry.getAllForSource(it) }?.distinctBy { it.id } ?: emptyList()
    }

    // Recomputed whenever the selection changes — which is what makes a covered row live again as
    // soon as its coverer is switched off, with no state to keep in step and nothing to invalidate.
    val rowsBySet = remember(source?.id, selection) {
        availablePatches.associate { it.id to PatchRows.of(it, selection) }
    }

    // Held here rather than inside the rows, because a lazy list discards and rebuilds the
    // composables of the items that scroll out of view.
    var expandedSetIds by remember { mutableStateOf(emptySet<String>()) }

    val isCloneMode by viewModel.isCloneMode.collectAsState()
    val customPackageName by viewModel.customPackageName.collectAsState()

    val selectedItemCount = rowsBySet.values.sumOf { it.selectedItemCount }
    val totalItemCount = rowsBySet.values.sumOf { it.itemCount }

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
                        enabled = selectedItemCount > 0,
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
                            text = "Patch APK (${selectedItemCount} " +
                                "${if (selectedItemCount == 1) "item" else "items"} selected)",
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "intro") {
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
                        "build step, by set or one item at a time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = selectionSummary(selectedItemCount, totalItemCount),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            availablePatches.forEach { patchSet ->
                val setRows = rowsBySet.getValue(patchSet.id)
                val expanded = patchSet.id in expandedSetIds

                item(key = PatchRows.setKey(patchSet.id), contentType = "set") {
                    PatchSetCard(
                        set = patchSet,
                        rows = setRows,
                        expanded = expanded,
                        onSetToggled = { enabled -> viewModel.setPatchSetEnabled(patchSet.id, enabled) },
                        onExpandedChange = {
                            expandedSetIds = if (expanded) {
                                expandedSetIds - patchSet.id
                            } else {
                                expandedSetIds + patchSet.id
                            }
                        }
                    )
                }

                if (expanded) {
                    setRows.groups.forEach { group ->
                        item(key = PatchRows.groupKey(patchSet.id, group.label), contentType = "group") {
                            GroupHeading(label = group.label, itemCount = group.rows.size)
                        }
                        items(
                            items = group.rows,
                            key = { it.key },
                            contentType = { "row" }
                        ) { row ->
                            PatchItemRow(
                                row = row,
                                onToggle = { viewModel.toggleItem(it) }
                            )
                        }
                    }
                }
            }

            item(key = "footer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * "251 of 253 items selected" — the line above the list, and what the button's count is drawn from.
 *
 * Items rather than sets: a set is a grouping the pipeline happens to apply in one pass, while an
 * item is what the user actually chose between, and "22 selected" would be the same number for a
 * run that patches everything and a run that patches one function in each set.
 */
private fun selectionSummary(selectedItems: Int, totalItems: Int): String {
    val noun = if (totalItems == 1) "item" else "items"
    return "$selectedItems of $totalItems $noun selected"
}

/**
 * The heading of one feature group inside an expanded set.
 *
 * It names the feature rather than the set, so a hundred and forty-two functions read as eighteen
 * things the app does — analytics, quests, gift buttons — instead of as one undifferentiated list.
 */
@Composable
private fun GroupHeading(label: String, itemCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Tune,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "$itemCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
