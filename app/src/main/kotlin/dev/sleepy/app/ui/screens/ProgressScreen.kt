package dev.sleepy.app.ui.screens

import android.animation.ValueAnimator
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sleepy.app.model.PatchProgress
import dev.sleepy.app.model.StepStatus
import dev.sleepy.app.ui.components.StepLogItem
import dev.sleepy.app.ui.state.ScrollMotion
import dev.sleepy.app.ui.state.newestStepIndex
import dev.sleepy.app.ui.state.phaseCopy
import dev.sleepy.app.ui.state.resultActionLabel
import dev.sleepy.app.ui.state.scrollMotionFor
import dev.sleepy.app.ui.state.STOPPED_COPY
import dev.sleepy.app.viewmodel.PatchViewModel

/**
 * The progress screen for a running patch: the current phase, the reasons for it, and the log of
 * changes.
 *
 * The phase card states what is happening and why, and the list underneath is a plain-language
 * account of every change. The exact class, method or function behind each line is one tap away
 * rather than shown in the row itself, but it remains available—this tool rewrites an app, so
 * the mechanism stays inspectable.
 *
 * @param viewModel The view model that supplies the progress, step log and cancellation.
 * @param onFinished Called when the user opens the result screen.
 * @param onExit Called when the user leaves for the app list after a stopped run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    viewModel: PatchViewModel,
    onFinished: () -> Unit,
    onExit: () -> Unit
) {
    val progress by viewModel.progress.collectAsState()
    val steps by viewModel.stepLog.collectAsState()
    val isPatching by viewModel.isPatching.collectAsState()
    val stopped by viewModel.stopped.collectAsState()

    var showCancelDialog by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // The newest row is scrolled to only while the platform reports that animations are enabled.
    // With them off, the list jumps rather than animates, so the same movement happens without the
    // motion a reader who turned animations off asked not to see.
    LaunchedEffect(steps.size) {
        val target = newestStepIndex(steps.size) ?: return@LaunchedEffect
        when (scrollMotionFor(ValueAnimator.areAnimatorsEnabled())) {
            ScrollMotion.ANIMATED -> listState.animateScrollToItem(target)
            ScrollMotion.IMMEDIATE -> listState.scrollToItem(target)
        }
    }

    BackHandler(enabled = isPatching) {
        showCancelDialog = true
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

            // A stopped run leaves the pipeline idle with its step log still on screen. Without a
            // branch for that state the card that follows falls through to "Getting ready" and its
            // spinner, which reports a run about to start rather than one that was stopped.
            if (stopped && progress is PatchProgress.Idle) {
                StoppedCard(onExit = onExit)
            } else {
                CurrentPhaseCard(progress = progress)

                // A finished run and a failed one both have a result screen, and the reader opens
                // it rather than being taken there. The label states which of the two is waiting.
                resultActionLabel(progress)?.let { label ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onFinished,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

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
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
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
                    text = "Starting up—nothing has been changed yet.",
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
                    text = "Nothing will be installed. The app you started with is untouched—" +
                        "only the work in progress is discarded.",
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

/** The headline card: the current phase and the reason for it. */
@Composable
private fun CurrentPhaseCard(progress: PatchProgress) {
    val copy = phaseCopy(progress)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // The title and the reason form one polite live region, so a screen reader announces
            // each new phase once. The detail line and the progress bar are siblings rather than
            // part of it: they change on every tick, and a live region around them announces
            // every one of those changes.
            Column(
                modifier = Modifier.clearAndSetSemantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = copy.announcement
                }
            ) {
                Text(
                    text = copy.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = copy.why,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = copy.detail != null) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = copy.detail.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            val ratio = when (val p = progress) {
                is PatchProgress.Downloading -> if (p.bytesTotal > 0) p.percent / 100f else null
                is PatchProgress.Patching ->
                    if (p.total > 0) p.current.toFloat() / p.total.toFloat() else null
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

/** The card shown after a run is stopped, which opens the way back to the app list. */
@Composable
private fun StoppedCard(onExit: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = STOPPED_COPY.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = STOPPED_COPY.why,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = onExit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Text(STOPPED_COPY.action, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
