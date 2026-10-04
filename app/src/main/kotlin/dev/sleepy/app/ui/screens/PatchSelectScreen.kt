package dev.sleepy.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sleepy.app.engine.PackageNameProblem
import dev.sleepy.app.engine.PackageNameRules
import dev.sleepy.app.model.DeclarationMismatch
import dev.sleepy.app.model.DeclarationSource
import dev.sleepy.app.model.PermissionCheck
import dev.sleepy.app.model.PermissionScan
import dev.sleepy.app.patches.PatchRegistry
import dev.sleepy.app.patches.PermissionCatalog
import dev.sleepy.app.ui.components.PatchItemRow
import dev.sleepy.app.ui.components.PatchSetCard
import dev.sleepy.app.ui.state.PatchRow
import dev.sleepy.app.ui.state.PatchRows
import dev.sleepy.app.ui.state.TriState
import dev.sleepy.app.viewmodel.PatchViewModel

/**
 * Chooses which changes to apply, alone or item by item, and optionally gives the result its own
 * package name so it can be installed next to the app it was built from.
 *
 * A patch set is a header—its own switch, and what it selects—over an expandable
 * body of its items. The header's switch is tri-state, so a set with the gift button on and
 * everything else off is shown as such rather than rounded to on or off, and the body is where
 * that state is reached: each item has its own switch and its own description of what turning it
 * on does.
 *
 * The body is emitted as lazy list items rather than as one composable per set, with each row
 * keyed by the item's own stable key, so expanding the eighty-one-rule blocklist or the
 * hundred-and-forty-two-function JavaScript set composes only the rows on screen.
 *
 * A row can also be inert, and the row then states why: a blocklist rule that another enabled
 * rule covers is grayed with the pattern that covers it named in full, and the interceptor's two
 * prefix gates are locked with their own reason. Both come from
 * [dev.sleepy.app.model.BlocklistCoverage], recomputed from the selection on every change, so
 * switching a covering rule off makes what it covered live again immediately.
 *
 * @param sourceId The id of the target whose patch sets are listed.
 * @param viewModel The view model that supplies and edits the selection.
 * @param onStartPatch Called when the user starts a run.
 * @param onBack Called when the user leaves the screen.
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

    // A set listed twice in a source's ids is one set: the rows that follow are keyed by set id, and two
    // entries for the same set are two items with one key.
    val availablePatches = remember(source) {
        source?.patchIds?.let { PatchRegistry.getAllForSource(it) }?.distinctBy { it.id }
            ?: emptyList()
    }

    // Recomputed whenever the selection changes—which is what makes a covered row live again as
    // soon as its coverer is switched off, with no state to keep in step and nothing to invalidate.
    val rowsBySet = remember(source?.id, selection) {
        availablePatches.associate { it.id to PatchRows.of(it, selection) }
    }

    // Held here rather than inside the rows, because a lazy list discards and rebuilds the
    // composables of the items that scroll out of view. Saved so a rotation keeps the sets the
    // reader opened rather than collapsing every one of them.
    var expandedSetIds by rememberSaveable(
        stateSaver = listSaver(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }

    val isCloneMode by viewModel.isCloneMode.collectAsState()
    val customPackageName by viewModel.customPackageName.collectAsState()
    val permissionScan by viewModel.permissions.collectAsState()

    // Recomputed from the selection like the patch rows, so the last remaining permission becomes
    // locked as soon as it is the last, and unlocked as soon as another is switched back on. The
    // source's package is part of the inputs because it is what determines which declarations the
    // build removes on its own, whatever the selection is—the same question the manifest pass
    // asks with the same name.
    val permissionRows = remember(permissionScan, selection, source?.packageName) {
        (permissionScan as? PermissionScan.Read)
            ?.let { PatchRows.permissionRows(it.declared, selection, source?.packageName) }
            .orEmpty()
    }
    val permissionRemovalCount = permissionRows.count { it.switchable && !it.enabled }

    val selectedItemCount = rowsBySet.values.sumOf { it.selectedItemCount }
    val totalItemCount = rowsBySet.values.sumOf { it.itemCount }

    // The name is checked against the source's own package because a clone that keeps it replaces
    // the original instead of installing beside it: the pipeline skips the rename for an equal
    // name, so a run finishes without the one change that was asked for.
    val packageNameProblem = if (isCloneMode) {
        PackageNameRules.validate(customPackageName, source?.packageName)
    } else {
        null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = source?.displayName ?: "Select patches",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
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
                        .imePadding()
                ) {
                    Button(
                        onClick = {
                            viewModel.startPatch()
                            onStartPatch()
                        },
                        // A run that removes permissions and applies no patch is still a run: the
                        // declarations are edited either way, so the removals count toward being
                        // able to start one. A clone name that breaks a rule blocks the start
                        // instead, because the rename is the reason the mode was switched on.
                        enabled = (selectedItemCount > 0 || permissionRemovalCount > 0) &&
                            packageNameProblem == null,
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
                    problem = packageNameProblem,
                    onCloneModeChange = { viewModel.setCloneMode(it) },
                    onPackageNameChange = { viewModel.setCustomPackageName(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            // The permission section comes before the patch sets, not after them: its list is
            // shipped with the app, so it is ready before anything is downloaded, and a section
            // a section after twenty-two set cards is one most people do not scroll to.
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
                    GroupHeading(
                        label = PermissionCatalog.DECLARED_GROUP,
                        rows = permissionRows,
                        onToggle = null
                    )
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

            item(key = "pipeline") {
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Configure modding pipeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
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
                        onSetToggled = { enabled ->
                            viewModel.setPatchSetEnabled(patchSet.id, enabled)
                        },
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
                        item(
                            key = PatchRows.groupKey(patchSet.id, group.label),
                            contentType = "group"
                        ) {
                            GroupHeading(
                                label = group.label,
                                rows = group.rows,
                                onToggle = { enabled ->
                                    viewModel.setItemsEnabled(
                                        group.rows.mapNotNull { it.item },
                                        enabled
                                    )
                                }
                            )
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
 * The patch button's label—"3 items selected", and what else the run does when permission
 * declarations are being removed.
 *
 * The item count is the patch items and nothing else, so it keeps its original meaning; the
 * permission removals are named separately rather than folded into it, because a run that removes
 * a permission and applies no patch is otherwise a run reporting "0 items selected".
 */
private fun patchButtonLabel(selectedItems: Int, permissionRemovals: Int): String {
    val items = "$selectedItems ${if (selectedItems == 1) "item" else "items"} selected"
    if (permissionRemovals == 0) return "Patch APK ($items)"
    val permissions = "$permissionRemovals " +
        "${if (permissionRemovals == 1) "permission" else "permissions"}" +
        " removed"
    return "Patch APK ($items, $permissions)"
}

/**
 * The permission section: which of the declarations the release being patched makes it keeps.
 *
 * The list is the one shipped with the app for this exact release, so the rows are there the
 * moment a target is chosen and nothing has to be downloaded to switch one off. It used to be read
 * from the build, which meant the section held nothing at all until a whole APK had been fetched—
 * no rows, no switches, and nothing to indicate that any of it existed.
 *
 * The build's own manifest is still read, but as a cross-check rather than as the list: a source
 * pointed at another release is the case where a shipped list is wrong, and a permission the
 * build declares that the list does not name is a permission with no row—so it is stated rather
 * than omitted. What the read finds does not replace the list on its own.
 *
 * There is deliberately no switch for the whole section. Everywhere else a set's header carries
 * one, and a header switch here puts "remove every permission this build declares" behind one
 * tap—a state that cannot be undone on an installed app, and one the model does not allow once
 * the last permission remains. The rows are the only way in.
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
                // The header row carries a 48dp touch band of its own, so the side padding applied
                // to the top as well leaves a wider gap above the title than below it.
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)
        ) {
            // The whole row is the control rather than the glyph alone. A glyph in the corner is a
            // weak target and reads as decoration, and the row gives the 48dp minimum touch size
            // without an IconButton, whose own 48dp band is taller than the title and leaves a gap
            // above it. The label states the action, so the affordance does not rest on a chevron
            // being read as expandable.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(
                        if (rows.isEmpty()) {
                            Modifier
                        } else {
                            Modifier.clickable(
                                onClickLabel = if (expanded) {
                                    "Hide the permissions"
                                } else {
                                    "Edit the permissions"
                                },
                                onClick = onExpandedChange
                            )
                        }
                    ),
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
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(modifier = Modifier.weight(1f))
                if (rows.isNotEmpty()) {
                    Text(
                        text = if (expanded) "Done" else "Edit",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // The label names the action, so a description here repeats it in a second
                    // announcement.
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = permissionListSource(scan),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))

            when (scan) {
                PermissionScan.NotRead -> {
                    Text(
                        text = "Nothing is known about this build's permissions yet. " +
                            "Reading them downloads the same APK the patch does and reads its " +
                            "manifest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onRead, shape = MaterialTheme.shapes.large) {
                        Text("Read this build's permissions")
                    }
                }

                PermissionScan.Reading -> ReadProgress()

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

                is PermissionScan.Read -> {
                    PermissionSummary(rows)
                    Spacer(modifier = Modifier.height(12.dp))
                    PermissionCheckReport(check = scan.check, onRead = onRead)
                }
            }
        }
    }
}

/** Where the preceding list came from, stated in the section rather than only in the code. */
private fun permissionListSource(scan: PermissionScan): String = when {
    scan is PermissionScan.Read && scan.from == DeclarationSource.SHIPPED ->
        "The permissions this release declares, shipped with sleepy, so they are here before " +
            "anything is downloaded. Check them against the build at the source to be sure it is " +
            "still the build this list describes."
    scan is PermissionScan.Read ->
        "Read from this build's own manifest, because sleepy ships no list for this release. " +
            "This is exactly what it declares—not a list of names that could go stale."
    else ->
        "sleepy ships no list for this release, so what it declares has to be read from it. That " +
            "downloads the same APK the patch does, and reads its manifest."
}

/** The spinner both reads share: one for the list, one for the check. */
@Composable
private fun ReadProgress() {
    Row(
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
}

/**
 * What the cross-check against the build found—the one place a difference between what sleepy
 * lists and what a build declares is stated.
 *
 * Both directions are named in full rather than counted, because they are different problems with
 * different answers. A declaration the list does not name has no row, so it stays whatever the user
 * does; a listed permission the build does not declare means the earlier switch governs nothing. The
 * list is not rewritten in either case: it describes the release sleepy supports, and a run goes by
 * the choice the user made against it.
 */
@Composable
private fun PermissionCheckReport(check: PermissionCheck, onRead: () -> Unit) {
    when (check) {
        PermissionCheck.NotChecked -> Column {
            Text(
                text = "Not checked against the build yet. Checking downloads it and compares " +
                    "what it declares with the list above—nothing you switch is affected " +
                    "either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = onRead, shape = MaterialTheme.shapes.large) {
                Text("Check against the build")
            }
        }

        PermissionCheck.Checking -> ReadProgress()

        PermissionCheck.Agrees -> Text(
            text = "Checked against this build's own manifest: it declares exactly these " +
                "permissions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        is PermissionCheck.Disagrees -> Column {
            val unlisted = check.mismatches
                .filterIsInstance<DeclarationMismatch.Unlisted>()
                .map { it.name }
            val absent = check.mismatches
                .filterIsInstance<DeclarationMismatch.Absent>()
                .map { it.name }

            Text(
                text = "This build is not the one sleepy lists permissions for.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            if (unlisted.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "It also declares ${unlisted.joinToString(", ")}. sleepy has no entry " +
                        "for ${if (unlisted.size == 1) "it" else "them"}, so " +
                        "${if (unlisted.size == 1) "it has" else "they have"} no row above and " +
                        "will not be removed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (absent.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "It no longer declares ${absent.joinToString(", ")}, so " +
                        "${if (absent.size == 1) "that row" else "those rows"} above would " +
                        "remove nothing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "The list above is the one for the release sleepy supports, so a run goes " +
                    "by your choices in it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is PermissionCheck.Failed -> Column {
            Text(
                text = check.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "The list above is the one sleepy ships for this release and is unaffected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = onRead, shape = MaterialTheme.shapes.large) {
                Text("Try again")
            }
        }
    }
}

/**
 * What the read list means: how many declarations are kept, how many are not the user's
 * to move, and what removing the rest does.
 *
 * The effect is stated rather than implied, because it is the one thing in this screen that cannot
 * be undone on the installed app: a declaration that is deleted cannot be re-declared by the app
 * later, so the permission is not "off"—it is gone.
 *
 * A row the build removes on its own is counted and described as itself rather than folded into
 * either end. It is not a removal the user is making, so it must not appear as one; it is not kept
 * either, so a summary that counts it as kept contradicts what its row states.
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
    val alwaysRemoved = rows.count { !it.switchable && !it.enabled }
    val locked = rows.count { !it.switchable && it.enabled }
    val removals = rows.count { it.switchable && !it.enabled }
    val removedNoun = if (alwaysRemoved == 1) "declaration" else "declarations"

    Text(
        text = "$kept of ${rows.size} kept" +
            if (removals > 0) " · $removals to remove" else " · none removed",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (removals > 0) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = when {
            removals > 0 ->
                "$removals declaration${if (removals == 1) "" else "s"} will be deleted from the " +
                    "manifest of the APK this run produces. That is permanent: Android gives " +
                    "an app only the permissions its manifest declares, and an installed app " +
                    "has no way to declare more later, so whatever depends on " +
                    "${if (removals == 1) "it" else "them"} stops working for good."
            alwaysRemoved > 0 ->
                "Nothing is switched off. $alwaysRemoved $removedNoun will still not be declared " +
                    "in the APK this run produces: sleepy deletes " +
                    "${if (alwaysRemoved == 1) "this one" else "these"} from every build of this " +
                    "app, which is why ${if (alwaysRemoved == 1) "its" else "their"} row has no " +
                    "switch to move."
            else -> "Nothing is switched off, so every declaration this build ships " +
                "stays in the manifest."
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
    if (alwaysRemoved > 0) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "$alwaysRemoved of them are removed by every build this patch makes, and each " +
                "says why on its own row.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * "251 of 253 items selected"—the line shown with the list, and what the button's count is drawn from.
 *
 * Items rather than sets: a set is a grouping the pipeline happens to apply in one pass, while an
 * item is what the user chose between, and "22 selected" is the same number for a run that
 * patches everything and a run that patches one function in each set.
 */
private fun selectionSummary(selectedItems: Int, totalItems: Int): String {
    val noun = if (totalItems == 1) "item" else "items"
    return "$selectedItems of $totalItems $noun selected"
}

/**
 * The heading of one feature group inside an expanded set, and the switch for the whole group.
 *
 * It names the feature rather than the set, so the patched functions appear as things the app
 * does—analytics, quests, guild tags—instead of as one undifferentiated list. Making the heading
 * a switch is what turns "all of the decorations" or "none of them" into one tap.
 *
 * [onToggle] is null for a section that is not a choice. The permission list is one: its rows
 * include declarations the build removes whatever you do, and a switch over those would offer a
 * decision that does not exist.
 */
@Composable
private fun GroupHeading(
    label: String,
    rows: List<PatchRow>,
    onToggle: ((Boolean) -> Unit)?
) {
    val state = PatchRows.triStateOfRows(rows)
    val switchable = rows.count { it.switchable }
    val selected = rows.count { it.switchable && it.enabled }
    val headings = Modifier
        .fillMaxWidth()
        .padding(start = 8.dp, end = 8.dp)

    Row(
        modifier = if (onToggle == null) {
            headings
        } else {
            headings
                .heightIn(min = 48.dp)
                .toggleable(
                    value = PatchRows.headerChecked(state),
                    role = Role.Switch,
                    onValueChange = onToggle
                )
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (state == TriState.NONE) Icons.Default.Tune else Icons.Default.Check,
            contentDescription = null,
            tint = if (state == TriState.NONE) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        // The count is of what can be switched, and it says how much of it is on, so a heading
        // reads the same way as the set switch above it.
        Text(
            text = if (state == TriState.NONE) "$switchable" else "$selected of $switchable",
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
 *
 * A name the field cannot carry is reported on the field itself, before a run starts: the field
 * shows the rule that was broken, and the screen blocks the start of a run until it is met.
 */
@Composable
private fun CloneModeCard(
    isCloneMode: Boolean,
    customPackageName: String,
    originalPackageName: String,
    problem: PackageNameProblem?,
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
                    isError = problem != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    supportingText = {
                        // The message states the rule rather than relying on the field's color, so
                        // the state does not depend on color vision.
                        Text(problem?.message ?: "Original: $originalPackageName")
                    }
                )
            }
        }
    }
}
