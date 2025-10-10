package com.example.everytalk.ui.screens.MainScreen.drawer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
            shape = RoundedCornerShape(32.dp),
            onDismissRequest = onDismiss,
            title = { Text(if (selectedItemCount > 1) "确定删除所有所选项？" else if (selectedItemCount == 1) "确定删除所选项？" else "确定删除此项？") },
            // text = { Text("此操作无法撤销。") }, // 可选
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(32.dp),
                    onClick = {
                        onConfirm()
                        onDismiss() // 确认后也关闭对话框
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("确定") }
            },
            dismissButton = {
                Button(
                    shape = RoundedCornerShape(32.dp),
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface)
                ) { Text("取消") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
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
            shape = RoundedCornerShape(32.dp),
            onDismissRequest = onDismiss,
            title = { Text("确定清空所有聊天记录？") },
            text = { Text("此操作无法撤销，所有聊天记录将被永久删除。") },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(32.dp),
                    onClick = {
                        onConfirm()
                        onDismiss() // 确认后也关闭对话框
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("确定清空") }
            },
            dismissButton = {
                Button(
                    shape = RoundedCornerShape(32.dp),
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface)
                ) { Text("取消") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
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
           shape = RoundedCornerShape(32.dp),
           onDismissRequest = onDismiss,
           title = { Text("确定清空所有图像生成历史？") },
           text = { Text("此操作无法撤销，所有图像生成历史将被永久删除。") },
           confirmButton = {
               Button(
                   shape = RoundedCornerShape(32.dp),
                   onClick = {
                       onConfirm()
                       onDismiss()
                   },
                   colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
               ) { Text("确定清空") }
           },
           dismissButton = {
               Button(
                   shape = RoundedCornerShape(32.dp),
                   onClick = onDismiss,
                   colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface)
               ) { Text("取消") }
           },
           containerColor = MaterialTheme.colorScheme.surface,
           titleContentColor = MaterialTheme.colorScheme.onSurface,
           textContentColor = MaterialTheme.colorScheme.onSurface
       )
   }
}