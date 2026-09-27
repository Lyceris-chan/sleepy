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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import dev.sleepy.app.model.PermissionScan
import dev.sleepy.app.patches.PatchRegistry
import dev.sleepy.app.patches.PermissionCatalog
import dev.sleepy.app.ui.components.PatchItemRow
import dev.sleepy.app.ui.components.PatchSetCard
import dev.sleepy.app.ui.state.PatchRow
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
    val permissionScan by viewModel.permissions.collectAsState()

    // Recomputed from the selection like the patch rows, so the last permission standing starts
    // refusing as soon as it is the last and stops as soon as another is switched back on.
    val permissionRows = remember(permissionScan, selection) {
        (permissionScan as? PermissionScan.Read)
            ?.let { PatchRows.permissionRows(it.declared, selection) }
            .orEmpty()
    }
    val permissionRemovalCount = permissionRows.count { it.switchable && !it.enabled }

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
                        // A run that removes permissions and applies no patch is still a run: the
                        // declarations are edited either way, so the removals count towards being
                        // able to start one.
                        enabled = selectedItemCount > 0 || permissionRemovalCount > 0,
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
                            text = patchButtonLabel(selectedItemCount, permissionRemovalCount),
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

            item(key = PatchRows.permissionCardKey(), contentType = "permissions") {
                PermissionCard(
                    scan = permissionScan,
                    rows = permissionRows,
                    expanded = PermissionCatalog.SET_ID in expandedSetIds,
                    onRead = { viewModel.readPermissions() },
                    onExpandedChange = {
                        expandedSetIds = if (PermissionCatalog.SET_ID in expandedSetIds) {
                            expandedSetIds - PermissionCatalog.SET_ID
                        } else {
                            expandedSetIds + PermissionCatalog.SET_ID
                        }
                    }
                )
            }

            if (permissionRows.isNotEmpty() && PermissionCatalog.SET_ID in expandedSetIds) {
                item(key = PatchRows.permissionGroupKey(), contentType = "group") {
                    GroupHeading(label = PermissionCatalog.DECLARED_GROUP, itemCount = permissionRows.size)
                }
                items(
                    items = permissionRows,
                    key = { it.key },
                    contentType = { "row" }
                ) { row ->
                    PatchItemRow(
                        row = row,
                        onToggle = { viewModel.toggleItem(it) }
                    )
                }
            }

            item(key = "footer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * The patch button's label — "3 items selected", and what else the run will do when permission
 * declarations are being removed.
 *
 * The item count is the patch items and nothing else, so it keeps meaning what it always meant;
 * the permission removals are named separately rather than folded into it, because a run that
 * removes a permission and applies no patch is otherwise a run reporting "0 items selected".
 */
private fun patchButtonLabel(selectedItems: Int, permissionRemovals: Int): String {
    val items = "$selectedItems ${if (selectedItems == 1) "item" else "items"} selected"
    if (permissionRemovals == 0) return "Patch APK ($items)"
    val permissions = "$permissionRemovals ${if (permissionRemovals == 1) "permission" else "permissions"} removed"
    return "Patch APK ($items, $permissions)"
}

/**
 * The permission section: which of the declarations the build being patched makes it keeps.
 *
 * The list is read from the APK the source publishes — its own manifest — and never from a table
 * in the app, so it cannot offer a permission this build does not declare or hide one it does.
 * Reading it costs a download of that build, which is why it is a button rather than something
 * that happens when the screen opens, and why nothing can be switched off before it has run: the
 * engine reads the manifest again when it patches, so a build whose permissions were never read is
 * a build whose permissions are left exactly as they are.
 *
 * There is deliberately no switch for the whole section. Everywhere else a set's header carries
 * one, and a header switch here would put "remove every permission this build declares" behind one
 * tap — a state that cannot be undone on an installed app, and one the model refuses anyway once
 * the last permission stands. The rows are the only way in.
 *
 * The section appears for every source, because the only way to know whether a build declares
 * permissions is to read it; a build that declares none says so in one line rather than showing an
 * empty list of switches.
 */
@Composable
private fun PermissionCard(
    scan: PermissionScan,
    rows: List<PatchRow>,
    expanded: Boolean,
    onRead: () -> Unit,
    onExpandedChange: () -> Unit
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
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                if (rows.isNotEmpty()) {
                    IconButton(onClick = onExpandedChange) {
                        Icon(
                            imageVector = if (expanded) {
                                Icons.Default.ExpandLess
                            } else {
                                Icons.Default.ExpandMore
                            },
                            contentDescription = if (expanded) {
                                "Hide the permissions"
                            } else {
                                "Show the permissions"
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Read from this build's own manifest, so it is exactly what this release " +
                    "declares — not a list of names that could go stale.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))

            when (scan) {
                PermissionScan.NotRead -> {
                    Text(
                        text = "Nothing is known about this build's permissions yet. Reading them " +
                            "downloads the same APK the patch does and reads its manifest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onRead, shape = MaterialTheme.shapes.large) {
                        Text("Read this build's permissions")
                    }
                }

                PermissionScan.Reading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Downloading the build and reading its manifest…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is PermissionScan.Failed -> {
                    Text(
                        text = scan.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Nothing was read, so this run would leave every declaration alone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onRead, shape = MaterialTheme.shapes.large) {
                        Text("Try again")
                    }
                }

                is PermissionScan.Read -> PermissionSummary(rows)
            }
        }
    }
}

/**
 * What the read list currently means: how many declarations are kept, how many are locked, and what
 * removing the rest will do.
 *
 * The effect is stated rather than implied, because it is the one thing in this screen that cannot
 * be undone on the installed app: a declaration that is deleted cannot be re-declared by the app
 * later, so the permission is not "off" — it is gone.
 */
@Composable
private fun PermissionSummary(rows: List<PatchRow>) {
    if (rows.isEmpty()) {
        Text(
            text = "This build declares no permissions, so there is nothing here to remove.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val kept = rows.count { it.enabled }
    val locked = rows.count { !it.switchable }
    val removals = rows.count { it.switchable && !it.enabled }

    Text(
        text = "$kept of ${rows.size} kept" +
            if (removals > 0) " · $removals to remove" else " · none removed",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (removals > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = if (removals > 0) {
            "$removals declaration${if (removals == 1) "" else "s"} will be deleted from the " +
                "manifest of the APK this run produces. That is permanent: Android gives an app " +
                "only the permissions its manifest declares, and an installed app has no way to " +
                "declare more later, so whatever depends on ${if (removals == 1) "it" else "them"} " +
                "stops working for good."
        } else {
            "Nothing is switched off, so every declaration this build ships stays in the manifest."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (locked > 0) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "$locked of them cannot be removed and each says why on its own row.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
