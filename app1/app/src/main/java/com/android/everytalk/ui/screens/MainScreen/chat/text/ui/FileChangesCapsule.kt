package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.DiffLine
import com.android.everytalk.data.DataClass.DiffLineType
import com.android.everytalk.data.DataClass.FileChangeSummary
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.fileChangeSummaries
import com.android.everytalk.data.DataClass.toolSteps
import com.android.everytalk.ui.components.sheet.AppModalBottomSheet

private data class DiffPalette(val added: Color, val removed: Color, val edited: Color)

@Composable
private fun diffPalette(): DiffPalette =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        DiffPalette(added = Color(0xFF4ADE80), removed = Color(0xFFF87171), edited = Color(0xFFFACC15))
    } else {
        DiffPalette(added = Color(0xFF16A34A), removed = Color(0xFFDC2626), edited = Color(0xFFCA8A04))
    }

/** 输入框上方的改动胶囊：只读最近一条 AI 回复的工具步骤，流式正文变化不会触发重算 diff。 */
@Composable
internal fun FileChangesCapsule(messages: List<Message>, modifier: Modifier = Modifier) {
    val steps by remember(messages) {
        derivedStateOf { messages.lastOrNull { it.sender == Sender.AI }?.toolSteps().orEmpty() }
    }
    val changes = remember(steps) { fileChangeSummaries(steps) }
    var showSheet by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = changes.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            onClick = { showSheet = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = pluralStringResource(R.plurals.file_changes_count, changes.size, changes.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                ChangeCounts(
                    additions = changes.sumOf { it.additions },
                    deletions = changes.sumOf { it.deletions },
                )
            }
        }
    }

    if (showSheet && changes.isNotEmpty()) {
        FileChangesSheet(changes = changes, onDismissRequest = { showSheet = false })
    }
}

@Composable
private fun ChangeCounts(additions: Int, deletions: Int) {
    val palette = diffPalette()
    val style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("+$additions", style = style, color = palette.added)
        Text("−$deletions", style = style, color = palette.removed)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileChangesSheet(changes: List<FileChangeSummary>, onDismissRequest: () -> Unit) {
    // 只有一个文件时直接展开，多个文件默认收起，避免长 diff 把列表挤出屏幕。
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    AppModalBottomSheet(
        onDismissRequest = onDismissRequest,
        contentModifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 48.dp),
        topGradient = true,
        header = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.file_changes_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ChangeCounts(
                    additions = changes.sumOf { it.additions },
                    deletions = changes.sumOf { it.deletions },
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            changes.forEach { change ->
                val isExpanded = expanded[change.path] ?: (changes.size == 1)
                FileChangeCard(
                    change = change,
                    expanded = isExpanded,
                    onToggle = { expanded[change.path] = !isExpanded },
                )
            }
        }
    }
}

@Composable
private fun FileChangeCard(change: FileChangeSummary, expanded: Boolean, onToggle: () -> Unit) {
    val palette = diffPalette()
    val arrowRotation by animateFloatAsState(if (expanded) 0f else -90f, label = "fileChangeArrow")
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // 黄色=编辑已有文件，绿色=整段写入（新建或覆盖）。
                val kindColor = if (change.written) palette.added else palette.edited
                Text(
                    text = stringResource(if (change.written) R.string.file_change_written else R.string.file_change_edited),
                    style = MaterialTheme.typography.labelSmall,
                    color = kindColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(kindColor.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = change.path.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    change.path.substringBeforeLast('/', "").takeIf(String::isNotEmpty)?.let { dir ->
                        Text(
                            text = dir,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                ChangeCounts(additions = change.additions, deletions = change.deletions)
                Icon(
                    painter = painterResource(R.drawable.ic_gpt_chevron_down_md),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    change.hunks.forEachIndexed { index, hunk ->
                        if (index > 0) DiffLineRow(DiffLine(DiffLineType.Context, "⋯"), palette)
                        hunk.forEach { line -> DiffLineRow(line, palette) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, palette: DiffPalette) {
    val (marker, color) = when (line.type) {
        DiffLineType.Added -> "+" to palette.added
        DiffLineType.Removed -> "-" to palette.removed
        DiffLineType.Context -> " " to Color.Unspecified
    }
    val style = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (color == Color.Unspecified) Color.Transparent else color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = marker,
            style = style,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = line.text,
            style = style,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (color == Color.Unspecified) 0.7f else 1f),
        )
    }
}
