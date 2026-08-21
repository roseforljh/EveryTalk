package com.android.everytalk.ui.screens.appinfo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.util.storage.AppStorageManager
import com.android.everytalk.util.storage.AppStorageSnapshot
import com.android.everytalk.util.storage.StorageCategoryContent
import com.android.everytalk.util.storage.StorageContentEntry
import com.android.everytalk.util.storage.StorageContentManager
import com.android.everytalk.util.storage.StorageDetail
import com.android.everytalk.util.storage.StorageDetailType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DataManagementScreen(
    onBack: () -> Unit,
    onDeleteConversations: suspend (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember(context) { AppStorageManager(context) }
    val contentManager = remember(context) { StorageContentManager(context) }
    val skillRepository = remember(context) { SkillRepository(context) }
    val scope = rememberCoroutineScope()
    val palette = storagePalette()
    var refreshKey by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<AppStorageSnapshot?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var isCleaning by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf<StorageDetailType?>(null) }
    var categoryContent by remember { mutableStateOf<StorageCategoryContent?>(null) }
    var selectedEntryIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteEntries by remember { mutableStateOf<List<StorageContentEntry>>(emptyList()) }
    var showJunkConfirmation by remember { mutableStateOf(false) }

    fun leaveCurrentPage() {
        if (selectedType != null) selectedType = null else onBack()
    }
    val safeBack = { if (!isCleaning) leaveCurrentPage() }

    BackHandler(enabled = !isCleaning, onBack = safeBack)

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

    val currentSnapshot = snapshot
    val selectedDetail = currentSnapshot?.details?.firstOrNull { it.type == selectedType }

    LaunchedEffect(selectedDetail) {
        selectedEntryIds = emptySet()
        categoryContent = selectedDetail?.let { detail -> contentManager.scan(detail) }
    }

    if (selectedType != null && selectedDetail != null) {
        StorageCategoryScreen(
            detail = selectedDetail,
            content = categoryContent,
            selectedIds = selectedEntryIds,
            palette = palette,
            isCleaning = isCleaning,
            onBack = safeBack,
            onSelectionChange = { selectedEntryIds = it },
            onDeleteSelected = {
                pendingDeleteEntries = categoryContent?.entries.orEmpty()
                    .filter { it.selectable && it.id in selectedEntryIds }
            },
            onOpenSystemSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
            modifier = modifier,
        )
    } else {
        DataManagementOverview(
            snapshot = currentSnapshot,
            loadFailed = loadFailed,
            isCleaning = isCleaning,
            palette = palette,
            onBack = safeBack,
            onRetry = { refreshKey++ },
            onClearJunk = { showJunkConfirmation = true },
            onSelectDetail = { selectedType = it },
            modifier = modifier,
        )
    }

    if (showJunkConfirmation && currentSnapshot != null) {
        AlertDialog(
            onDismissRequest = { showJunkConfirmation = false },
            icon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
            title = { Text(stringResource(R.string.storage_clear_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.storage_clear_dialog_message,
                        formatBytes(currentSnapshot.cleanableBytes),
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showJunkConfirmation = false
                        isCleaning = true
                        scope.launch {
                            val result = runCatching { manager.clearJunk() }
                            result.onSuccess { released ->
                                snapshot = manager.scan()
                                showResultToast(context, R.string.storage_clear_success, released)
                            }.onFailure {
                                Toast.makeText(context, R.string.storage_clear_failed, Toast.LENGTH_SHORT).show()
                            }
                            isCleaning = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.safe),
                ) {
                    Text(stringResource(R.string.storage_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showJunkConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val pendingEntries = pendingDeleteEntries
    if (pendingEntries.isNotEmpty()) {
        val type = pendingEntries.first().type
        val selectedBytes = pendingEntries.sumOf(StorageContentEntry::bytes)
        val visual = detailVisual(type, palette)
        AlertDialog(
            onDismissRequest = { pendingDeleteEntries = emptyList() },
            icon = { Icon(visual.icon, contentDescription = null) },
            title = {
                Text(stringResource(R.string.storage_selected_delete_title, pendingEntries.size))
            },
            text = {
                Text(
                    stringResource(
                        R.string.storage_selected_delete_message,
                        formatBytes(selectedBytes),
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val entriesToDelete = pendingEntries
                        pendingDeleteEntries = emptyList()
                        isCleaning = true
                        scope.launch {
                            val result = runCatching {
                                when (type) {
                                    StorageDetailType.CONVERSATIONS -> {
                                        onDeleteConversations(entriesToDelete.mapTo(linkedSetOf(), StorageContentEntry::id))
                                        // ViewModel 会同步内存和数据库，给落库与附件回收留出时间。
                                        delay(700)
                                    }
                                    StorageDetailType.SKILLS -> withContext(Dispatchers.IO) {
                                        entriesToDelete.forEach { entry -> skillRepository.delete(entry.id) }
                                    }
                                    StorageDetailType.ATTACHMENTS,
                                    StorageDetailType.TOOL_RESULTS,
                                    StorageDetailType.TEMPORARY_FILES -> contentManager.deleteFiles(type, entriesToDelete)
                                    StorageDetailType.OTHER_DATA -> Unit
                                }
                                manager.scan()
                            }
                            result.onSuccess { updated ->
                                snapshot = updated
                                updated.details.firstOrNull { it.type == type }?.let { updatedDetail ->
                                    categoryContent = contentManager.scan(updatedDetail)
                                }
                                selectedEntryIds = emptySet()
                                showSelectedResultToast(context, entriesToDelete.size, selectedBytes)
                            }.onFailure {
                                Toast.makeText(context, R.string.storage_category_clear_failed, Toast.LENGTH_SHORT).show()
                            }
                            isCleaning = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.storage_category_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteEntries = emptyList() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DataManagementOverview(
    snapshot: AppStorageSnapshot?,
    loadFailed: Boolean,
    isCleaning: Boolean,
    palette: StoragePalette,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onClearJunk: () -> Unit,
    onSelectDetail: (StorageDetailType) -> Unit,
    modifier: Modifier,
) {
    ImmersiveInfoPage(
        title = stringResource(R.string.data_management_title),
        onBack = onBack,
        modifier = modifier,
        opaqueTopBar = true,
    ) {
        when {
            snapshot == null && !loadFailed -> item(key = "loading") { StorageLoadingState() }
            loadFailed -> item(key = "error") { StorageErrorState(onRetry) }
            snapshot != null -> {
                item(key = "storage_hero") { StorageOverviewCard(snapshot, palette) }
                item(key = "cleaner") {
                    CleanerCard(
                        cleanableBytes = snapshot.cleanableBytes,
                        isCleaning = isCleaning,
                        palette = palette,
                        onClear = onClearJunk,
                    )
                }
                item(key = "detail_title") {
                    Column(
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.storage_detail_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(
                                R.string.storage_detail_reconciliation,
                                snapshot.details.size,
                                formatBytes(snapshot.dataBytes),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "details") {
                    StorageDetailsCard(
                        details = snapshot.details,
                        dataBytes = snapshot.dataBytes,
                        palette = palette,
                        onSelectDetail = onSelectDetail,
                    )
                }
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
}

@Composable
private fun StorageOverviewCard(snapshot: AppStorageSnapshot, palette: StoragePalette) {
    Surface(shape = RoundedCornerShape(28.dp), color = palette.surface) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.storage_total_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatBytes(snapshot.totalBytes),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            SegmentedStorageBar(snapshot, palette)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StorageMetric(
                    label = stringResource(R.string.storage_application),
                    bytes = snapshot.applicationBytes,
                    color = palette.application,
                    modifier = Modifier.weight(1f),
                )
                StorageMetric(
                    label = stringResource(R.string.storage_data),
                    bytes = snapshot.dataBytes,
                    color = palette.data,
                    modifier = Modifier.weight(1f),
                )
                StorageMetric(
                    label = stringResource(R.string.storage_cache),
                    bytes = snapshot.cacheBytes,
                    color = palette.cache,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SegmentedStorageBar(snapshot: AppStorageSnapshot, palette: StoragePalette) {
    val description = stringResource(R.string.storage_total_accessibility, formatBytes(snapshot.totalBytes))
    val segments = listOf(
        snapshot.applicationBytes to palette.application,
        snapshot.dataBytes to palette.data,
        snapshot.cacheBytes to palette.cache,
    ).filter { it.first > 0L }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(palette.track)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { (bytes, color) ->
            Box(
                modifier = Modifier
                    .weight(bytes.toFloat().coerceAtLeast(1f))
                    .height(14.dp)
                    .background(color),
            )
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatBytes(bytes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CleanerCard(
    cleanableBytes: Long,
    isCleaning: Boolean,
    palette: StoragePalette,
    onClear: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(22.dp), color = palette.safeSurface) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = palette.safe.copy(alpha = 0.14f),
                    contentColor = palette.safe,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.CleaningServices, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(13.dp))
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
                    color = palette.safe,
                )
            }
            Button(
                onClick = onClear,
                enabled = cleanableBytes > 0L && !isCleaning,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.safe),
            ) {
                if (isCleaning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(19.dp))
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
private fun StorageDetailsCard(
    details: List<StorageDetail>,
    dataBytes: Long,
    palette: StoragePalette,
    onSelectDetail: (StorageDetailType) -> Unit,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = palette.surface) {
        Column {
            details.forEachIndexed { index, detail ->
                StorageDetailRow(
                    detail = detail,
                    totalDataBytes = dataBytes,
                    palette = palette,
                    onClick = { onSelectDetail(detail.type) },
                )
                if (index < details.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 74.dp), color = palette.divider)
                }
            }
            HorizontalDivider(color = palette.divider)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 17.dp),
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
                    color = palette.data,
                )
            }
        }
    }
}

@Composable
private fun StorageDetailRow(
    detail: StorageDetail,
    totalDataBytes: Long,
    palette: StoragePalette,
    onClick: () -> Unit,
) {
    val visual = detailVisual(detail.type, palette)
    val progress = if (totalDataBytes > 0L) {
        (detail.bytes.toFloat() / totalDataBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = visual.accent.copy(alpha = 0.12f),
                contentColor = visual.accent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(visual.icon, contentDescription = null, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(visual.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatBytes(detail.bytes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = stringResource(R.string.storage_open_category),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth().height(5.dp).background(palette.track, CircleShape),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(progress).height(5.dp)
                            .background(visual.accent, CircleShape),
                    )
                }
                Text(
                    text = stringResource(visual.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StorageLoadingState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 96.dp),
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
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

internal data class StoragePalette(
    val primary: Color,
    val application: Color,
    val data: Color,
    val cache: Color,
    val safe: Color,
    val surface: Color,
    val safeSurface: Color,
    val track: Color,
    val divider: Color,
)

@Composable
private fun storagePalette(): StoragePalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        StoragePalette(
            primary = Color(0xFF7EA2FF),
            application = Color(0xFFAFC3FF),
            data = Color(0xFF668BFF),
            cache = Color(0xFF667287),
            safe = Color(0xFF48C7B1),
            surface = Color(0xFF151B25),
            safeSurface = Color(0xFF10241F),
            track = Color(0xFF293242),
            divider = Color(0xFF29313D),
        )
    } else {
        StoragePalette(
            primary = Color(0xFF2F6BFF),
            application = Color(0xFF8AA8FF),
            data = Color(0xFF2F6BFF),
            cache = Color(0xFFAAB4C4),
            safe = Color(0xFF168F7E),
            surface = Color(0xFFF5F7FB),
            safeSurface = Color(0xFFEEF8F5),
            track = Color(0xFFE1E6EF),
            divider = Color(0xFFE2E7EF),
        )
    }
}

internal data class DetailVisual(
    val icon: ImageVector,
    val titleRes: Int,
    val descriptionRes: Int,
    val accent: Color,
)

internal fun detailVisual(type: StorageDetailType, palette: StoragePalette): DetailVisual = when (type) {
    StorageDetailType.CONVERSATIONS -> DetailVisual(
        Icons.Outlined.ChatBubbleOutline,
        R.string.storage_conversations,
        R.string.storage_conversations_description,
        palette.primary,
    )
    StorageDetailType.ATTACHMENTS -> DetailVisual(
        Icons.Outlined.PhotoLibrary,
        R.string.storage_attachments,
        R.string.storage_attachments_description,
        palette.primary,
    )
    StorageDetailType.SKILLS -> DetailVisual(
        Icons.Outlined.Extension,
        R.string.storage_skills,
        R.string.storage_skills_description,
        palette.primary,
    )
    StorageDetailType.TOOL_RESULTS -> DetailVisual(
        Icons.Outlined.Terminal,
        R.string.storage_tool_results,
        R.string.storage_tool_results_description,
        palette.primary,
    )
    StorageDetailType.TEMPORARY_FILES -> DetailVisual(
        Icons.Outlined.DeleteSweep,
        R.string.storage_temporary,
        R.string.storage_temporary_description,
        palette.safe,
    )
    StorageDetailType.OTHER_DATA -> DetailVisual(
        Icons.Outlined.Folder,
        R.string.storage_other,
        R.string.storage_other_description,
        palette.cache,
    )
}

internal fun categoryNoticeRes(type: StorageDetailType): Int = when (type) {
    StorageDetailType.CONVERSATIONS -> R.string.storage_conversations_clear_notice
    StorageDetailType.ATTACHMENTS -> R.string.storage_attachments_clear_notice
    StorageDetailType.SKILLS -> R.string.storage_skills_clear_notice
    StorageDetailType.TOOL_RESULTS -> R.string.storage_tool_results_clear_notice
    StorageDetailType.TEMPORARY_FILES -> R.string.storage_temporary_clear_notice
    StorageDetailType.OTHER_DATA -> R.string.storage_other_protected_notice
}

@Composable
internal fun formatBytes(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0L))

private fun showResultToast(context: Context, messageRes: Int, bytes: Long) {
    Toast.makeText(
        context,
        context.getString(messageRes, Formatter.formatShortFileSize(context, bytes)),
        Toast.LENGTH_SHORT,
    ).show()
}

private fun showSelectedResultToast(context: Context, count: Int, selectedBytes: Long) {
    Toast.makeText(
        context,
        context.getString(
            R.string.storage_selected_delete_success,
            count,
            Formatter.formatShortFileSize(context, selectedBytes),
        ),
        Toast.LENGTH_SHORT,
    ).show()
}
