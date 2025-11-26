package com.android.everytalk.ui.screens.MainScreen.drawer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment

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
        val isDarkTheme = isSystemInDarkTheme()
        val cancelButtonColor = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
        val confirmButtonColor = if (isDarkTheme) Color.White else Color(0xFF212121)
        val confirmButtonTextColor = if (isDarkTheme) Color.Black else Color.White

        AlertDialog(
            shape = RoundedCornerShape(32.dp),
            onDismissRequest = onDismiss,
            title = {
                Text(
                    if (selectedItemCount > 1) "确定删除所有所选项？"
                    else if (selectedItemCount == 1) "确定删除所选项？"
                    else "确定删除此项？"
                )
            },
            // text = { Text("此操作无法撤销。") }, // 可选
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = cancelButtonColor
                        ),
                        border = BorderStroke(1.dp, cancelButtonColor)
                    ) {
                        Text(
                            text = "取消",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    Button(
                        onClick = {
                            onConfirm()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = confirmButtonColor,
                            contentColor = confirmButtonTextColor,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = "确定",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            },
            dismissButton = {},
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
        val isDarkTheme = isSystemInDarkTheme()
        val cancelButtonColor = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
        val confirmButtonColor = if (isDarkTheme) Color.White else Color(0xFF212121)
        val confirmButtonTextColor = if (isDarkTheme) Color.Black else Color.White

        AlertDialog(
            shape = RoundedCornerShape(32.dp),
            onDismissRequest = onDismiss,
            title = { Text("确定清空所有聊天记录？") },
            text = { Text("此操作无法撤销，所有聊天记录将被永久删除。") },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = cancelButtonColor
                        ),
                        border = BorderStroke(1.dp, cancelButtonColor)
                    ) {
                        Text(
                            text = "取消",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    Button(
                        onClick = {
                            onConfirm()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = confirmButtonColor,
                            contentColor = confirmButtonTextColor,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = "确定清空",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            },
            dismissButton = {},
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
       val isDarkTheme = isSystemInDarkTheme()
       val cancelButtonColor = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
       val confirmButtonColor = if (isDarkTheme) Color.White else Color(0xFF212121)
       val confirmButtonTextColor = if (isDarkTheme) Color.Black else Color.White

       AlertDialog(
           shape = RoundedCornerShape(32.dp),
           onDismissRequest = onDismiss,
           title = { Text("确定清空所有图像生成历史？") },
           text = { Text("此操作无法撤销，所有图像生成历史将被永久删除。") },
           confirmButton = {
               Row(
                   modifier = Modifier.fillMaxWidth(),
                   horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
               ) {
                   OutlinedButton(
                       onClick = onDismiss,
                       modifier = Modifier
                           .weight(1f)
                           .height(48.dp),
                       shape = RoundedCornerShape(24.dp),
                       colors = ButtonDefaults.outlinedButtonColors(
                           containerColor = MaterialTheme.colorScheme.surface,
                           contentColor = cancelButtonColor
                       ),
                       border = BorderStroke(1.dp, cancelButtonColor)
                   ) {
                       Text(
                           text = "取消",
                           style = MaterialTheme.typography.labelLarge.copy(
                               fontWeight = FontWeight.SemiBold
                           )
                       )
                   }

                   Button(
                       onClick = {
                           onConfirm()
                           onDismiss()
                       },
                       modifier = Modifier
                           .weight(1f)
                           .height(48.dp),
                       shape = RoundedCornerShape(24.dp),
                       colors = ButtonDefaults.buttonColors(
                           containerColor = confirmButtonColor,
                           contentColor = confirmButtonTextColor,
                           disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                           disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                       )
                   ) {
                       Text(
                           text = "确定清空",
                           style = MaterialTheme.typography.labelLarge.copy(
                               fontWeight = FontWeight.SemiBold
                           )
                       )
                   }
               }
           },
           dismissButton = {},
           containerColor = MaterialTheme.colorScheme.surface,
           titleContentColor = MaterialTheme.colorScheme.onSurface,
           textContentColor = MaterialTheme.colorScheme.onSurface
       )
   }
}

@Composable
internal fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text("创建新分组", style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("分组名称") },
                singleLine = true,
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                )
            )
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    if (groupName.isNotBlank()) {
                        onConfirm(groupName)
                    }
                    onDismiss()
                },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp),
                enabled = groupName.isNotBlank(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Text("创建", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
            ) {
                Text("取消", fontWeight = FontWeight.Medium)
            }
        }
    )
}

@Composable
internal fun MoveToGroupDialog(
    groups: List<String>,
    isCurrentlyGrouped: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text("移动到分组", style = MaterialTheme.typography.titleLarge) },
        text = {
            if (groups.isEmpty() && !isCurrentlyGrouped) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("目前暂无分组", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    if (isCurrentlyGrouped) {
                        item {
                            ListItem(
                                headlineContent = { Text("移出分组") },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.RemoveCircleOutline,
                                        contentDescription = "移出分组"
                                    )
                                },
                                modifier = Modifier.clickable {
                                    onConfirm(null) // null indicates moving to ungrouped
                                    onDismiss()
                                }
                            )
                        }
                    }
                    items(groups) { groupName ->
                        ListItem(
                            headlineContent = { Text(groupName) },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = "分组"
                                )
                            },
                            modifier = Modifier.clickable {
                                onConfirm(groupName)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
            ) {
                Text("取消", fontWeight = FontWeight.Medium)
            }
        },
        confirmButton = { }
    )
}