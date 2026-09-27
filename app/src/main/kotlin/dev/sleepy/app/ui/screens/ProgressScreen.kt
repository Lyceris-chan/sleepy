package dev.sleepy.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sleepy.app.model.PatchProgress
import dev.sleepy.app.ui.components.StepLogItem
import dev.sleepy.app.viewmodel.PatchViewModel
import kotlinx.coroutines.delay

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

    // Auto-scroll log as steps are added
    LaunchedEffect(steps.size) {
        if (steps.isNotEmpty()) {
            listState.animateScrollToItem(steps.size - 1)
        }
    }

    // Predictive back interceptor — protects user from losing running job
    BackHandler(enabled = isPatching) {
        showCancelDialog = true
    }

    // When done or failed, advance to Result screen
    LaunchedEffect(progress) {
        if (progress is PatchProgress.Done || progress is PatchProgress.Failed) {
            delay(600)
            onFinished()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Patching Pipeline",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (isPatching) {
                        IconButton(onClick = { showCancelDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Patch",
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
            Spacer(modifier = Modifier.height(12.dp))

            // Current Operation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    val statusTitle = when (val p = progress) {
                        is PatchProgress.Downloading -> "Downloading Source (${p.percent}%)"
                        is PatchProgress.Decoding -> "Decompiling Bytecode"
                        is PatchProgress.Patching -> "Patching Bytecode (${p.current}/${p.total})"
                        is PatchProgress.Assembling -> "Assembling DEX Containers"
                        is PatchProgress.Signing -> "Cryptographic Signing"
                        is PatchProgress.Done -> "Complete"
                        is PatchProgress.Failed -> "Failed"
                        PatchProgress.Idle -> "Preparing"
                    }

                    val statusSubtext = when (val p = progress) {
                        is PatchProgress.Downloading -> {
                            val mbRec = p.bytesReceived / (1024 * 1024.0)
                            val mbTot = p.bytesTotal / (1024 * 1024.0)
                            if (p.bytesTotal > 0) {
                                "%.1f MB / %.1f MB".format(mbRec, mbTot)
                            } else {
                                "%.1f MB downloaded".format(mbRec)
                            }
                        }
                        is PatchProgress.Decoding -> p.step
                        is PatchProgress.Patching -> p.step
                        is PatchProgress.Assembling -> p.step
                        is PatchProgress.Signing -> p.step
                        is PatchProgress.Done -> "Signed APK generated successfully"
                        is PatchProgress.Failed -> p.message
                        PatchProgress.Idle -> "Initializing worker coroutines..."
                    }

                    Text(
                        text = statusTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = statusSubtext,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    when (val p = progress) {
                        is PatchProgress.Downloading -> {
                            if (p.bytesTotal > 0) {
                                LinearProgressIndicator(
                                    progress = { p.percent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp),
                                    trackColor = MaterialTheme.colorScheme.surface,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp),
                                    trackColor = MaterialTheme.colorScheme.surface,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        is PatchProgress.Patching -> {
                            val ratio = if (p.total > 0) p.current.toFloat() / p.total.toFloat() else 0f
                            LinearProgressIndicator(
                                progress = { ratio },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                trackColor = MaterialTheme.colorScheme.surface,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        is PatchProgress.Done -> {
                            LinearProgressIndicator(
                                progress = { 1f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                trackColor = MaterialTheme.colorScheme.surface,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        else -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                trackColor = MaterialTheme.colorScheme.surface,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Live Execution Log",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(10.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
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
                    text = "Cancel Patching?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "The background patching operation will be terminated and all memory buffers discarded.",
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
                        text = "Cancel Patch",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Keep Going")
                }
            }
        )
    }
}
