package com.android.everytalk.ui.screens.settings.dialogs

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

/**
 * 自动获取模型列表确认对话框
 *
 * 在用户添加新配置后,询问是否自动从API获取模型列表
 *
 * @param showDialog 是否显示对话框
 * @param onDismiss 当请求关闭对话框时调用
 * @param onConfirmAutoFetch 当用户选择自动获取时调用
 * @param onManualInput 当用户选择手动输入时调用
 */
@Composable
fun AutoFetchModelsConfirmDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    onConfirmAutoFetch: () -> Unit,
    onManualInput: () -> Unit
) {
    if (showDialog) {
        AlertDialog(
            shape = RoundedCornerShape(32.dp),
            onDismissRequest = onDismiss,
            title = {
                Text(
                    "获取模型列表",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "是否自动从API获取可用的模型列表?\n\n" +
                           "• 选择\"是\":将自动获取并显示所有可用模型供您选择\n" +
                           "• 选择\"否\":手动输入模型名称",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        onConfirmAutoFetch()
                        onDismiss()
                    },
                    enabled = true,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.height(52.dp).padding(horizontal = 4.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Text("是,自动获取", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onManualInput()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
                ) {
                    Text("否,手动输入", fontWeight = FontWeight.Medium)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}