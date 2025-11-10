package com.android.everytalk.ui.screens.settings.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 应用更新对话框（与语音模式设置对话卡样式对齐）
 *
 * - 外观：RoundedCornerShape(32.dp) + Material3 排版
 * - 按钮：FilledTonalButton + TextButton，统一 52.dp 高度与圆角
 */
@Composable
fun UpdateDialog(
    showDialog: Boolean,
    latestVersion: String,
    changelog: String?,
    force: Boolean = false,
    onDismiss: () -> Unit,
    onUpdateNow: () -> Unit,
    onRemindLater: () -> Unit = onDismiss
) {
    if (!showDialog) return

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = {
            if (!force) onDismiss()
        },
        shape = RoundedCornerShape(32.dp),
        title = {
            Column {
                Text(
                    text = "发现新版本",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "v$latestVersion",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp) // 固定内容高度，内部可滚动
                    .verticalScroll(scrollState)
            ) {
                if (!changelog.isNullOrBlank()) {
                    Text(
                        text = changelog,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = "包含体验优化与问题修复。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onUpdateNow()
                    onDismiss()
                },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Text("立即更新", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (!force) {
                TextButton(
                    onClick = {
                        onRemindLater()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .height(52.dp)
                        .padding(horizontal = 4.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text("稍后提醒", fontWeight = FontWeight.Medium)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}