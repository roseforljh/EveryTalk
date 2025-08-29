package com.example.everytalk.ui.screens.MainScreen.drawer

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 删除确认对话框。
 * @param showDialog 是否显示对话框。
 * @param selectedItemCount 要删除的项的数量。
 * @param onDismiss 当请求关闭对话框时调用。
 * @param onConfirm 当确认删除时调用。
 */
@Composable
internal fun DeleteConfirmationDialog(
    showDialog: Boolean,
    selectedItemCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (selectedItemCount > 1) "确定删除所有所选项？" else if (selectedItemCount == 1) "确定删除所选项？" else "确定删除此项？") },
            // text = { Text("此操作无法撤销。") }, // 可选
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm()
                        onDismiss() // 确认后也关闭对话框
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                ) { Text("取消") }
            },
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 清空所有记录确认对话框。
 * @param showDialog 是否显示对话框。
 * @param onDismiss 当请求关闭对话框时调用。
 * @param onConfirm 当确认清空所有记录时调用。
 */
@Composable
internal fun ClearAllConfirmationDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("确定清空所有聊天记录？") },
            text = { Text("此操作无法撤销，所有聊天记录将被永久删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm()
                        onDismiss() // 确认后也关闭对话框
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("确定清空") }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                ) { Text("取消") }
            },
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}
@Composable
internal fun ClearImageHistoryConfirmationDialog(
   showDialog: Boolean,
   onDismiss: () -> Unit,
   onConfirm: () -> Unit
) {
   if (showDialog) {
       AlertDialog(
           onDismissRequest = onDismiss,
           title = { Text("确定清空所有图像生成历史？") },
           text = { Text("此操作无法撤销，所有图像生成历史将被永久删除。") },
           confirmButton = {
               TextButton(
                   onClick = {
                       onConfirm()
                       onDismiss()
                   },
                   colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
               ) { Text("确定清空") }
           },
           dismissButton = {
               TextButton(
                   onClick = onDismiss,
                   colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
               ) { Text("取消") }
           },
           containerColor = MaterialTheme.colorScheme.background,
           titleContentColor = MaterialTheme.colorScheme.onSurface,
           textContentColor = MaterialTheme.colorScheme.onSurface
       )
   }
}