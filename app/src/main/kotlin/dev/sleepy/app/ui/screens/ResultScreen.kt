package dev.sleepy.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.sleepy.app.model.PatchProgress
import dev.sleepy.app.model.VerificationReport
import dev.sleepy.app.ui.theme.statusColors
import dev.sleepy.app.util.FileUtils
import dev.sleepy.app.viewmodel.PatchViewModel
import java.io.File

/**
 * The end of a patch run.
 *
 * Everything on this screen is the result of a check that actually ran against the finished
 * file, and nothing is rounded up to a success: a source that publishes no hash says so
 * instead of reporting a pass, and steps that were skipped are separated from steps that
 * failed. A reader should be able to tell what was verified, what was assumed, and what is
 * still unknown without opening a log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    viewModel: PatchViewModel,
    onStartOver: () -> Unit
) {
    val progress by viewModel.progress.collectAsState()
    val source by viewModel.selectedSource.collectAsState()
    val context = LocalContext.current
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (progress is PatchProgress.Done) "Build Successful" else "Build Failed",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
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

            when (val p = progress) {
                is PatchProgress.Done -> BuildSuccessContent(
                    report = p.report,
                    sha256 = p.sha256,
                    sourceName = source?.displayName ?: "The application",
                    savedMessage = savedMessage,
                    onSave = {
                        val apkFile = File(p.outputUri.path ?: "")
                        val targetName = "${source?.id ?: "sleepy"}_patched.apk"
                        val savedUri = FileUtils.saveApkToDownloads(context, apkFile, targetName)
                        if (savedUri != null) {
                            savedMessage = "Saved to Downloads/sleepy/$targetName"
                            Toast.makeText(context, "Saved to Downloads!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Failed to save file", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShare = {
                        val apkFile = File(p.outputUri.path ?: "")
                        try {
                            val contentUri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                apkFile
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/vnd.android.package-archive"
                                putExtra(Intent.EXTRA_STREAM, contentUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share patched APK"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Unable to share APK: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                is PatchProgress.Failed -> BuildFailureContent(message = p.message, detail = p.detail)

                else -> Unit
            }

            TextButton(
                onClick = onStartOver,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start over", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** The success path: what was built, what was checked, and what was changed. */
@Composable
private fun BuildSuccessContent(
    report: VerificationReport,
    sha256: String,
    sourceName: String,
    savedMessage: String?,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    StatusHero(
        icon = Icons.Default.CheckCircle,
        tint = MaterialTheme.statusColors.success,
        title = "Patched APK Ready",
        subtitle = "$sourceName was modified and re-signed on this device."
    )

    OutputCard(report)
    DownloadIntegrityCard(report)
    PatchOutcomeCard(report)
    ChecksumCard(sha256)

    if (savedMessage != null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.statusColors.success,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = savedMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    Button(
        onClick = onSave,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Icon(
            imageVector = Icons.Default.SaveAlt,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Save to Downloads", style = MaterialTheme.typography.labelLarge)
    }

    FilledTonalButton(
        onClick = onShare,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Share APK", style = MaterialTheme.typography.labelLarge)
    }
}

/** The failure path: what went wrong, and the raw detail when there is any. */
@Composable
private fun BuildFailureContent(message: String, detail: String?) {
    StatusHero(
        icon = Icons.Default.Error,
        tint = MaterialTheme.colorScheme.error,
        title = "Patch Operation Failed",
        subtitle = message
    )

    if (detail != null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "TECHNICAL DETAIL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Icon, headline and one line of context, shared by both outcomes. */
@Composable
private fun StatusHero(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Size of the produced APK, and the native code that had to be added back to it. */
@Composable
private fun OutputCard(report: VerificationReport) {
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
            SectionLabel("WHAT WAS BUILT")

            AuditRow(
                icon = Icons.Default.SaveAlt,
                tint = MaterialTheme.colorScheme.primary,
                label = "Output size",
                status = formatByteSize(report.outputBytes)
            )

            if (report.mergedNativeLibraries > 0) {
                AuditRow(
                    icon = Icons.Default.Layers,
                    tint = MaterialTheme.colorScheme.primary,
                    label = "Native libraries merged",
                    status = "${report.mergedNativeLibraries} added",
                    detail = "These are the native libraries this build was missing. " +
                        "The app was published as a split bundle, so its native code had to be " +
                        "put back before it could run."
                )
            }

            if (report.mergedResourceFiles > 0) {
                AuditRow(
                    icon = Icons.Default.Image,
                    tint = MaterialTheme.colorScheme.primary,
                    label = "Resources merged",
                    status = "${report.mergedResourceFiles} added",
                    detail = "Images and other resource files the app's density split holds " +
                        "and the base split does not, put back at the paths the desktop build " +
                        "uses. The resource table that points at them ships in pieces across " +
                        "the splits and is not rebuilt here, so the files are in the archive " +
                        "without anything in it referring to them."
                )
            }
        }
    }
}

/**
 * What is known about the file that was patched, kept separate from what is known about the
 * result: an unverified download is reported as unverified rather than as a pass.
 */
@Composable
private fun DownloadIntegrityCard(report: VerificationReport) {
    val statusColors = MaterialTheme.statusColors

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
            SectionLabel("DOWNLOAD & SIGNATURE AUDIT")

            when (report.sourceIntegrityVerified) {
                true -> AuditRow(
                    icon = Icons.Default.CheckCircle,
                    tint = statusColors.success,
                    label = "Source download",
                    status = "Verified",
                    detail = "The download was verified against the published SHA-256."
                )

                false -> AuditRow(
                    icon = Icons.Default.Warning,
                    tint = MaterialTheme.colorScheme.error,
                    label = "Source download",
                    status = "Mismatch",
                    detail = "The download did not match the published SHA-256. The result " +
                        "was built from a file that is not the one the manifest describes."
                )

                null -> AuditRow(
                    icon = Icons.Default.Info,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Source download",
                    status = "Not checked",
                    detail = "This source publishes no SHA-256, so the download could not be " +
                        "checked against anything."
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Three outcomes, not two: a scheme that cannot apply to this build was not
            // checked, so it is named as not applicable rather than as a failure. JAR signing
            // is only honoured below API 24, which is why this row reads that way on most
            // builds — a v1 signature is written, and nothing that installs the APK reads it.
            AuditRow(
                icon = signatureIcon(report.v1SignatureValid),
                tint = signatureTint(report.v1SignatureValid),
                label = "Signature v1 (JAR)",
                status = signatureStatusText(report.v1SignatureValid),
                detail = if (report.v1SignatureValid == null) {
                    "Android stopped reading JAR signatures at API 24 and this build declares " +
                        "a minSdkVersion at or above that, so no platform that can install it " +
                        "consults this signature. It is present in the file, and that is all it " +
                        "needs to be. This is not a failure."
                } else {
                    null
                }
            )

            AuditRow(
                icon = signatureIcon(report.v2SignatureValid),
                tint = signatureTint(report.v2SignatureValid),
                label = "Signature v2 (APK Signing Block)",
                status = if (report.v2SignatureValid) "Valid" else "Not valid"
            )

            AuditRow(
                icon = signatureIcon(report.v3SignatureValid),
                tint = signatureTint(report.v3SignatureValid),
                label = "Signature v3 (Android 9+)",
                status = if (report.v3SignatureValid) "Valid" else "Not valid"
            )

            // Three outcomes, not two: an archive whose directory could not be read was not
            // measured, so it is reported as unchecked rather than as an alignment pass.
            when (report.zipalignPassed) {
                true -> AuditRow(
                    icon = Icons.Default.CheckCircle,
                    tint = MaterialTheme.statusColors.success,
                    label = "ZIP alignment",
                    status = "4-byte aligned"
                )

                false -> AuditRow(
                    icon = Icons.Default.Warning,
                    tint = MaterialTheme.colorScheme.error,
                    label = "ZIP alignment",
                    status = "Misaligned"
                )

                null -> AuditRow(
                    icon = Icons.Default.Info,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "ZIP alignment",
                    status = "Not checked",
                    detail = "The finished archive's central directory could not be read, so no " +
                        "entry was measured. Nothing here says the result is aligned."
                )
            }
        }
    }
}

/**
 * What the run changed.
 *
 * Applied, skipped and failed are three different outcomes: a skipped step was not needed for
 * this build and is not a failure, while a failed one means the build is not what was asked
 * for, so it is called out separately.
 */
@Composable
private fun PatchOutcomeCard(report: VerificationReport) {
    val statusColors = MaterialTheme.statusColors
    val failedCount = report.patchesFailed.size

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (failedCount > 0) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionLabel("PATCH OUTCOME")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutcomeTile(
                    icon = Icons.Default.Check,
                    tint = statusColors.success,
                    count = report.patchesApplied.size,
                    label = "Applied",
                    modifier = Modifier.weight(1f)
                )
                OutcomeTile(
                    icon = Icons.Default.Remove,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    count = report.patchesSkipped.size,
                    label = "Skipped",
                    modifier = Modifier.weight(1f)
                )
                OutcomeTile(
                    icon = Icons.Default.Close,
                    tint = if (failedCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    count = failedCount,
                    label = "Failed",
                    modifier = Modifier.weight(1f)
                )
            }

            if (report.patchesSkipped.isNotEmpty()) {
                Text(
                    text = "Skipped steps were not needed for this build, which is not a failure. " +
                        "They are listed below so the difference is visible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BulletList(items = report.patchesSkipped)
            }

            if (failedCount > 0) {
                Text(
                    text = "Some steps could not be applied, so this build is not what was " +
                        "asked for. Review the list before installing.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                BulletList(items = report.patchesFailed)
            }
        }
    }
}

/** The SHA-256 of the finished file, selectable so it can be compared elsewhere. */
@Composable
private fun ChecksumCard(sha256: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            SectionLabel("SHA-256 OF THE PATCHED APK")
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = sha256,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** One counted outcome. The tint is paired with an icon and a word, never used alone. */
@Composable
private fun OutcomeTile(
    icon: ImageVector,
    tint: Color,
    count: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One checked property.
 *
 * The icon repeats the status word rather than replacing it, so the row still reads correctly
 * without colour vision.
 */
@Composable
private fun AuditRow(
    icon: ImageVector,
    tint: Color,
    label: String,
    status: String,
    detail: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = tint
                )
            }
            if (detail != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A short list of step names, for the reader who wants to know exactly which ones. */
@Composable
private fun BulletList(items: List<String>, limit: Int = 8) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.take(limit).forEach { item ->
            Text(
                text = "• $item",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (items.size > limit) {
            Text(
                text = "and ${items.size - limit} more",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The small capitalised heading that opens each card. */
@Composable
private fun SectionLabel(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * How a signature scheme's verdict reads.
 *
 * Three states rather than two, because a scheme that cannot apply to a build has not failed:
 * `null` is "there was nothing here to check", and showing that as "Not valid" is a claim the
 * patcher has no evidence for.
 */
internal fun signatureStatusText(verified: Boolean?): String = when (verified) {
    true -> "Valid"
    false -> "Not valid"
    null -> "Not applicable"
}

/** Pass, fail and not-applicable differ by glyph, so the three are never colour-only. */
private fun signatureIcon(passed: Boolean?): ImageVector = when (passed) {
    true -> Icons.Default.CheckCircle
    false -> Icons.Default.Warning
    null -> Icons.Default.Info
}

@Composable
private fun signatureTint(passed: Boolean?): Color = when (passed) {
    true -> MaterialTheme.statusColors.success
    false -> MaterialTheme.colorScheme.error
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Human-readable size, in the unit a reader would use for an APK. */
private fun formatByteSize(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1.0) {
        "%.1f MB".format(megabytes)
    } else {
        "%.0f KB".format(bytes / 1024.0)
    }
}
