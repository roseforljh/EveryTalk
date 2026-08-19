package com.android.everytalk.ui.screens.appinfo

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.util.storage.AppStorageManager
import com.android.everytalk.util.storage.AppStorageSnapshot
import com.android.everytalk.util.storage.StorageDetail
import com.android.everytalk.util.storage.StorageDetailType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DataManagementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember(context) { AppStorageManager(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var refreshKey by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<AppStorageSnapshot?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var isCleaning by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    BackHandler(enabled = !isCleaning, onBack = onBack)

    LaunchedEffect(refreshKey) {
        loadFailed = false
        try {
            snapshot = manager.scan()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    Box(modifier = modifier) {
        ImmersiveInfoPage(
            title = stringResource(R.string.data_management_title),
            onBack = { if (!isCleaning) onBack() },
        ) {
            when {
                snapshot == null && !loadFailed -> item(key = "loading") { StorageLoadingState() }
                loadFailed -> item(key = "error") {
                    StorageErrorState(onRetry = { refreshKey++ })
                }
                else -> {
                    val current = checkNotNull(snapshot)
                    item(key = "storage_hero") { StorageHero(current) }
                    item(key = "cleaner") {
                        CleanerCard(
                            cleanableBytes = current.cleanableBytes,
                            isCleaning = isCleaning,
                            onClear = { showClearConfirmation = true },
                        )
                    }
                    item(key = "detail_title") {
                        Column(
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.storage_detail_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(
                                    R.string.storage_detail_reconciliation,
                                    current.details.size,
                                    formatBytes(current.dataBytes),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "details") { StorageDetailsCard(current.details, current.dataBytes) }
                    item(key = "storage_note") {
                        Text(
                            text = stringResource(R.string.storage_estimate_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 28.dp),
        )
    }

    if (showClearConfirmation && snapshot != null) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.storage_clear_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.storage_clear_dialog_message,
                        formatBytes(snapshot!!.cleanableBytes),
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmation = false
                        isCleaning = true
                        scope.launch {
                            val freed = runCatching { manager.clearJunk() }
                            isCleaning = false
                            freed.onSuccess { bytes ->
                                snapshot = manager.scan()
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.storage_clear_success,
                                        Formatter.formatShortFileSize(context, bytes),
                                    ),
                                )
                            }.onFailure {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.storage_clear_failed),
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.storage_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun StorageLoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
        Text(
            text = stringResource(R.string.storage_scanning),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StorageErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.storage_scan_failed),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun StorageHero(snapshot: AppStorageSnapshot) {
    val applicationColor = MaterialTheme.colorScheme.primary
    val dataColor = MaterialTheme.colorScheme.tertiary
    val cacheColor = MaterialTheme.colorScheme.secondary
    val totalDescription = stringResource(R.string.storage_total_accessibility, formatBytes(snapshot.totalBytes))

    Surface(
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(184.dp)
                    .semantics { contentDescription = totalDescription },
                contentAlignment = Alignment.Center,
            ) {
                StorageRing(
                    values = listOf(snapshot.applicationBytes, snapshot.dataBytes, snapshot.cacheBytes),
                    colors = listOf(applicationColor, dataColor, cacheColor),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.storage_total_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatBytes(snapshot.totalBytes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StorageMetric(
                    label = stringResource(R.string.storage_application),
                    bytes = snapshot.applicationBytes,
                    color = applicationColor,
                    modifier = Modifier.weight(1f),
                )
                StorageMetric(
                    label = stringResource(R.string.storage_data),
                    bytes = snapshot.dataBytes,
                    color = dataColor,
                    modifier = Modifier.weight(1f),
                )
                StorageMetric(
                    label = stringResource(R.string.storage_cache),
                    bytes = snapshot.cacheBytes,
                    color = cacheColor,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StorageRing(values: List<Long>, colors: List<Color>) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier = Modifier.size(184.dp)) {
        val stroke = 15.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        val total = values.sum().coerceAtLeast(1L).toFloat()
        var start = -90f
        values.zip(colors).forEach { (bytes, color) ->
            val sweep = bytes / total * 360f
            if (sweep > 0.5f) {
                drawArc(
                    color = color,
                    startAngle = start + 2f,
                    sweepAngle = (sweep - 4f).coerceAtLeast(1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            start += sweep
        }
    }
}

@Composable
private fun StorageMetric(
    label: String,
    bytes: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatBytes(bytes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CleanerCard(
    cleanableBytes: Long,
    isCleaning: Boolean,
    onClear: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.secondary
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.62f),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = accent.copy(alpha = 0.14f),
                    contentColor = accent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.CleaningServices, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.storage_cleanable_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.storage_cleanable_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatBytes(cleanableBytes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = accent,
                )
            }
            Button(
                onClick = onClear,
                enabled = cleanableBytes > 0L && !isCleaning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                if (isCleaning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                } else {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (cleanableBytes > 0L) {
                            stringResource(R.string.storage_clear_button, formatBytes(cleanableBytes))
                        } else {
                            stringResource(R.string.storage_already_clean)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageDetailsCard(details: List<StorageDetail>, dataBytes: Long) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            details.forEachIndexed { index, detail ->
                StorageDetailRow(detail = detail, totalDataBytes = dataBytes)
                if (index < details.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.storage_data_total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatBytes(dataBytes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun StorageDetailRow(detail: StorageDetail, totalDataBytes: Long) {
    val presentation = detailPresentation(detail.type)
    val progress = if (totalDataBytes > 0L) {
        (detail.bytes.toFloat() / totalDataBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = presentation.color.copy(alpha = 0.13f),
            contentColor = presentation.color,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(presentation.icon, contentDescription = null, modifier = Modifier.size(21.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(presentation.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (detail.cleanable) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = stringResource(R.string.storage_cleanable_badge),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = formatBytes(detail.bytes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(5.dp)
                        .background(presentation.color, CircleShape),
                )
            }
            Text(
                text = stringResource(presentation.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class DetailPresentation(
    val icon: ImageVector,
    val titleRes: Int,
    val descriptionRes: Int,
    val color: Color,
)

@Composable
private fun detailPresentation(type: StorageDetailType): DetailPresentation = when (type) {
    StorageDetailType.CONVERSATIONS -> DetailPresentation(
        Icons.Outlined.ChatBubbleOutline,
        R.string.storage_conversations,
        R.string.storage_conversations_description,
        MaterialTheme.colorScheme.primary,
    )
    StorageDetailType.ATTACHMENTS -> DetailPresentation(
        Icons.Outlined.PhotoLibrary,
        R.string.storage_attachments,
        R.string.storage_attachments_description,
        MaterialTheme.colorScheme.tertiary,
    )
    StorageDetailType.SKILLS -> DetailPresentation(
        Icons.Outlined.Extension,
        R.string.storage_skills,
        R.string.storage_skills_description,
        MaterialTheme.colorScheme.secondary,
    )
    StorageDetailType.TOOL_RESULTS -> DetailPresentation(
        Icons.Outlined.Terminal,
        R.string.storage_tool_results,
        R.string.storage_tool_results_description,
        MaterialTheme.colorScheme.error,
    )
    StorageDetailType.TEMPORARY_FILES -> DetailPresentation(
        Icons.Outlined.DeleteSweep,
        R.string.storage_temporary,
        R.string.storage_temporary_description,
        MaterialTheme.colorScheme.secondary,
    )
    StorageDetailType.OTHER_DATA -> DetailPresentation(
        Icons.Outlined.Folder,
        R.string.storage_other,
        R.string.storage_other_description,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun formatBytes(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0L))
