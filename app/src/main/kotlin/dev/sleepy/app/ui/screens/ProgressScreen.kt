package dev.sleepy.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sleepy.app.model.PatchProgress
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.ui.components.StepLogItem
import dev.sleepy.app.viewmodel.PatchViewModel
import kotlinx.coroutines.delay

/**
 * Live view of a patch run.
 *
 * The header answers "what is happening right now and why", and the list underneath is a
 * plain-language account of every change. The exact class, method or function behind each
 * line is one tap away rather than in the reader's face, but it is always there — this is a
 * tool that rewrites someone's app, so the mechanism stays inspectable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    viewModel: PatchViewModel,
    onFinished: () -> Unit
) {
    val progress by viewModel.progress.collectAsState()
    val steps by viewModel.stepLog.collectAsState()
    val isPatching by viewModel.isPatching.collectAsState()

    var showCancelDialog by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(steps.size) {
        if (steps.isNotEmpty()) {
            listState.animateScrollToItem(steps.size - 1)
        }
    }

    BackHandler(enabled = isPatching) {
        showCancelDialog = true
    }

    LaunchedEffect(progress) {
        if (progress is PatchProgress.Done || progress is PatchProgress.Failed) {
            delay(600)
            onFinished()
        }
    }

    val appliedCount = steps.count { it.status == StepStatus.OK }
    val skippedCount = steps.count { it.status == StepStatus.SKIP }
    val failedCount = steps.count { it.status == StepStatus.FAIL }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Patching",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    if (isPatching) {
                        IconButton(onClick = { showCancelDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Stop patching",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            CurrentPhaseCard(progress = progress)

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "What changed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (steps.isNotEmpty()) {
                    Text(
                        text = buildString {
                            append("$appliedCount applied")
                            if (skippedCount > 0) append(" · $skippedCount skipped")
                            if (failedCount > 0) append(" · $failedCount failed")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (steps.isEmpty()) {
                Text(
                    text = "Starting up — nothing has been changed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(steps) { step ->
                        StepLogItem(result = step)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = {
                Text(
                    text = "Stop patching?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Nothing will be installed. The app you started with is untouched — only the work in progress is discarded.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.cancel()
                        showCancelDialog = false
                    }
                ) {
                    Text(
                        text = "Stop",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Keep going")
                }
            }
        )
    }
}

/** The headline card: what phase we are in, and why that phase exists. */
@Composable
private fun CurrentPhaseCard(progress: PatchProgress) {
    val title = when (val p = progress) {
        is PatchProgress.Downloading -> "Downloading the app"
        is PatchProgress.Decoding -> "Opening the package"
        is PatchProgress.MergingSplits -> "Adding the missing native libraries"
        is PatchProgress.Patching -> p.step
        is PatchProgress.Assembling -> "Rebuilding the APK"
        is PatchProgress.Signing -> "Signing the result"
        is PatchProgress.Done -> "Finished"
        is PatchProgress.Failed -> "Something went wrong"
        PatchProgress.Idle -> "Getting ready"
    }

    val why = when (val p = progress) {
        is PatchProgress.Downloading -> "Fetching the untouched original so every change can be traced."
        is PatchProgress.Decoding -> "Reading the package in memory. The file on disk is never modified."
        is PatchProgress.MergingSplits ->
            if (p.librariesMerged > 0) {
                "This build ships its native code separately. Putting it back is what stops the app crashing on launch."
            } else {
                "Checking the extra pieces this build was split into."
            }
        is PatchProgress.Patching -> p.explanation ?: "Applying the changes you selected."
        is PatchProgress.Assembling -> "Putting the modified files back and re-aligning the archive."
        is PatchProgress.Signing -> "Android refuses to install an unsigned app, and the result is verified afterwards."
        is PatchProgress.Done -> "The patched app is ready to install."
        is PatchProgress.Failed -> p.message
        PatchProgress.Idle -> "Preparing the patching engine."
    }

    val detail: String? = when (val p = progress) {
        is PatchProgress.Downloading -> {
            val received = p.bytesReceived / (1024 * 1024.0)
            val total = p.bytesTotal / (1024 * 1024.0)
            if (p.bytesTotal > 0) "%.1f of %.1f MB".format(received, total) else "%.1f MB so far".format(received)
        }
        is PatchProgress.MergingSplits ->
            if (p.librariesMerged > 0) "${p.librariesMerged} libraries for ${p.abis.joinToString(", ")}" else null
        is PatchProgress.Patching ->
            if (p.total > 0) "Step ${(p.current + 1).coerceAtMost(p.total)} of ${p.total}" else null
        is PatchProgress.Failed -> p.detail?.lineSequence()?.take(3)?.joinToString("\n")
        else -> null
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = why,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(visible = detail != null) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = detail.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            val ratio = when (val p = progress) {
                is PatchProgress.Downloading -> if (p.bytesTotal > 0) p.percent / 100f else null
                is PatchProgress.Patching -> if (p.total > 0) p.current.toFloat() / p.total.toFloat() else null
                is PatchProgress.Done -> 1f
                else -> null
            }

            if (ratio != null) {
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
