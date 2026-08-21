package com.android.everytalk.ui.screens.appinfo

import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import com.android.everytalk.R
import com.android.everytalk.util.storage.StorageCategoryContent
import com.android.everytalk.util.storage.StorageContentEntry
import com.android.everytalk.util.storage.StorageDetail
import com.android.everytalk.util.storage.StorageDetailType
import com.android.everytalk.util.storage.StorageEntryKind
import com.android.everytalk.util.storage.StorageEntrySection

/** 手机管家式的分类明细页，列表直接回答“空间被谁占用”。 */
@Composable
internal fun StorageCategoryScreen(
    detail: StorageDetail,
    content: StorageCategoryContent?,
    selectedIds: Set<String>,
    palette: StoragePalette,
    isCleaning: Boolean,
    onBack: () -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onDeleteSelected: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier,
) {
    val visual = detailVisual(detail.type, palette)
    val selectableEntries = content?.entries.orEmpty().filter(StorageContentEntry::selectable)
    val selectedEntries = selectableEntries.filter { it.id in selectedIds }
    val selectedBytes = selectedEntries.sumOf(StorageContentEntry::bytes)
    val protected = detail.type == StorageDetailType.OTHER_DATA

    ImmersiveInfoPage(
        title = stringResource(visual.titleRes),
        onBack = onBack,
        modifier = modifier,
        opaqueTopBar = true,
        bottomBar = {
            StorageSelectionBar(
                protected = protected,
                selectedCount = selectedEntries.size,
                selectedBytes = selectedBytes,
                enabled = !isCleaning,
                palette = palette,
                onClick = if (protected) onOpenSystemSettings else onDeleteSelected,
            )
        },
    ) {
        item(key = "category_summary") {
            StorageCategorySummary(
                detail = detail,
                itemCount = content?.entries?.count { it.kind != StorageEntryKind.SYSTEM_REMAINDER } ?: 0,
                visual = visual,
                palette = palette,
            )
        }

        if (content == null) {
            item(key = "category_loading") { StorageCategoryLoading() }
        } else {
            if (selectableEntries.isNotEmpty()) {
                item(key = "selection_control") {
                    StorageSelectionControl(
                        totalCount = selectableEntries.size,
                        selectedCount = selectedEntries.size,
                        onToggleAll = {
                            onSelectionChange(
                                if (selectedEntries.size == selectableEntries.size) emptySet()
                                else selectableEntries.mapTo(linkedSetOf(), StorageContentEntry::id),
                            )
                        },
                    )
                }
            }

            val sourceEntries = content.entries.filter { it.section == StorageEntrySection.CONTENT_SOURCES }
            if (sourceEntries.isNotEmpty()) {
                item(key = "source_header") {
                    StorageSectionHeader(
                        title = stringResource(R.string.storage_content_sources),
                        description = stringResource(R.string.storage_content_sources_description),
                    )
                }
                item(key = "source_entries") {
                    StorageEntryCard(
                        entries = sourceEntries,
                        selectedIds = selectedIds,
                        accent = visual.accent,
                        palette = palette,
                        onToggle = { entry -> onSelectionChange(selectedIds.toggle(entry.id)) },
                    )
                }
            }

            val diskEntries = content.entries.filter { it.section == StorageEntrySection.DISK_FILES }
            if (diskEntries.isNotEmpty()) {
                item(key = "disk_header") {
                    StorageSectionHeader(
                        title = stringResource(
                            if (sourceEntries.isEmpty()) R.string.storage_items_by_size
                            else R.string.storage_physical_files,
                        ),
                        description = if (sourceEntries.isEmpty()) {
                            stringResource(R.string.storage_items_by_size_description)
                        } else {
                            stringResource(R.string.storage_physical_files_description, formatBytes(detail.bytes))
                        },
                    )
                }
                item(key = "disk_entries") {
                    StorageEntryCard(
                        entries = diskEntries,
                        selectedIds = selectedIds,
                        accent = visual.accent,
                        palette = palette,
                        onToggle = { entry -> onSelectionChange(selectedIds.toggle(entry.id)) },
                    )
                }
            }

            if (content.entries.isEmpty()) {
                item(key = "category_empty") {
                    Text(
                        text = stringResource(R.string.storage_category_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 52.dp),
                    )
                }
            }

            item(key = "category_notice") {
                Text(
                    text = stringResource(categoryNoticeRes(detail.type)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StorageCategorySummary(
    detail: StorageDetail,
    itemCount: Int,
    visual: DetailVisual,
    palette: StoragePalette,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = palette.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(17.dp),
                color = visual.accent.copy(alpha = 0.12f),
                contentColor = visual.accent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(visual.icon, contentDescription = null, modifier = Modifier.size(25.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = formatBytes(detail.bytes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = stringResource(R.string.storage_category_item_count, itemCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StorageSelectionControl(
    totalCount: Int,
    selectedCount: Int,
    onToggleAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.storage_sorted_by_size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggleAll) {
            Text(
                stringResource(
                    if (selectedCount == totalCount) R.string.storage_unselect_all
                    else R.string.storage_select_all,
                ),
            )
        }
    }
}

@Composable
private fun StorageSectionHeader(title: String, description: String) {
    Column(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StorageEntryCard(
    entries: List<StorageContentEntry>,
    selectedIds: Set<String>,
    accent: Color,
    palette: StoragePalette,
    onToggle: (StorageContentEntry) -> Unit,
) {
    val largestBytes = entries.maxOfOrNull(StorageContentEntry::bytes)?.coerceAtLeast(1L) ?: 1L
    Surface(shape = RoundedCornerShape(22.dp), color = palette.surface) {
        Column {
            entries.forEachIndexed { index, entry ->
                StorageEntryRow(
                    entry = entry,
                    selected = entry.id in selectedIds,
                    largestBytes = largestBytes,
                    accent = accent,
                    palette = palette,
                    onToggle = { onToggle(entry) },
                )
                if (index < entries.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 64.dp), color = palette.divider)
                }
            }
        }
    }
}

@Composable
private fun StorageEntryRow(
    entry: StorageContentEntry,
    selected: Boolean,
    largestBytes: Long,
    accent: Color,
    palette: StoragePalette,
    onToggle: () -> Unit,
) {
    val icon = storageEntryIcon(entry.kind)
    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.selectable) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(checkedColor = accent),
                )
            } else {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (entry.kind == StorageEntryKind.SYSTEM_REMAINDER) Icons.Outlined.Lock else icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = entryTitle(entry),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatBytes(entry.bytes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = entrySubtitle(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier.fillMaxWidth().height(3.dp).background(palette.track, CircleShape),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((entry.bytes.toFloat() / largestBytes).coerceIn(0.02f, 1f))
                            .height(3.dp)
                            .background(accent.copy(alpha = 0.78f), CircleShape),
                    )
                }
            }
        }
    }
    if (entry.selectable) {
        Surface(onClick = onToggle, color = Color.Transparent, content = rowContent)
    } else {
        rowContent()
    }
}

@Composable
private fun entryTitle(entry: StorageContentEntry): String = when {
    entry.kind == StorageEntryKind.CONVERSATION && entry.title.isBlank() ->
        stringResource(R.string.storage_unnamed_conversation)
    entry.kind == StorageEntryKind.SYSTEM_REMAINDER -> stringResource(R.string.storage_system_remainder)
    entry.kind == StorageEntryKind.TOOL_ARCHIVE -> stringResource(R.string.storage_tool_archive_name, entry.title)
    else -> entry.title
}

@Composable
private fun entrySubtitle(entry: StorageContentEntry): String {
    val typeText = when (entry.kind) {
        StorageEntryKind.CONVERSATION -> stringResource(
            if (entry.isImageConversation) R.string.storage_image_conversation
            else R.string.storage_text_conversation,
        ) + " · " + stringResource(R.string.storage_message_count, entry.count)
        StorageEntryKind.DATABASE_FILE -> stringResource(R.string.storage_database_file)
        StorageEntryKind.ATTACHMENT_FILE -> stringResource(R.string.storage_attachment_file)
        StorageEntryKind.SKILL -> stringResource(R.string.storage_skill_usage_count, entry.count)
        StorageEntryKind.TOOL_ARCHIVE -> stringResource(R.string.storage_archive_file_count, entry.count)
        StorageEntryKind.TEMPORARY_FILE -> stringResource(R.string.storage_temporary_file)
        StorageEntryKind.OTHER_FILE -> stringResource(R.string.storage_runtime_data)
        StorageEntryKind.SYSTEM_REMAINDER -> stringResource(R.string.storage_system_remainder_description)
    }
    val time = entry.updatedAt?.let { timestamp ->
        DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }
    return if (time == null) typeText else "$typeText · $time"
}

private fun storageEntryIcon(kind: StorageEntryKind): ImageVector = when (kind) {
    StorageEntryKind.CONVERSATION -> Icons.Outlined.ChatBubbleOutline
    StorageEntryKind.DATABASE_FILE -> Icons.Outlined.DataObject
    StorageEntryKind.ATTACHMENT_FILE -> Icons.Outlined.Image
    StorageEntryKind.SKILL -> Icons.Outlined.Extension
    StorageEntryKind.TOOL_ARCHIVE -> Icons.Outlined.Terminal
    StorageEntryKind.TEMPORARY_FILE -> Icons.Outlined.DeleteOutline
    StorageEntryKind.OTHER_FILE, StorageEntryKind.SYSTEM_REMAINDER -> Icons.Outlined.Folder
}

@Composable
private fun StorageSelectionBar(
    protected: Boolean,
    selectedCount: Int,
    selectedBytes: Long,
    enabled: Boolean,
    palette: StoragePalette,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
        shadowElevation = 10.dp,
    ) {
        Button(
            onClick = onClick,
            enabled = enabled && (protected || selectedCount > 0),
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (protected) palette.primary else MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(
                if (protected) Icons.AutoMirrored.Outlined.OpenInNew else Icons.Outlined.DeleteOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (protected) {
                    stringResource(R.string.storage_open_system_settings)
                } else {
                    stringResource(R.string.storage_delete_selected, selectedCount, formatBytes(selectedBytes))
                },
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun StorageCategoryLoading() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
        Text(
            text = stringResource(R.string.storage_loading_details),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id
