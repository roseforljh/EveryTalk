package com.android.everytalk.ui.screens.settings.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 模型选择对话框
 * 
 * 显示从API获取的模型列表,支持全选或手动选择多个模型
 * 
 * @param showDialog 是否显示对话框
 * @param models 可用的模型列表
 * @param onDismiss 当请求关闭对话框时调用
 * @param onSelectAll 当用户选择添加全部模型时调用
 * @param onSelectModels 当用户选择添加部分模型时调用,参数为选中的模型列表
 * @param onManualInput 当用户选择手动输入时调用
 */
@Composable
fun ModelSelectionDialog(
    showDialog: Boolean,
    models: List<String>,
    onDismiss: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectModels: (List<String>) -> Unit,
    onManualInput: () -> Unit
) {
    if (!showDialog) return
    
    var selectedModels by remember { mutableStateOf(setOf<String>()) }
    var searchText by remember { mutableStateOf("") }
    
    // 重置选中状态当对话框显示时
    LaunchedEffect(showDialog) {
        if (showDialog) {
            selectedModels = emptySet()
            searchText = ""
        }
    }
    
    val filteredModels = remember(models, searchText) {
        if (searchText.isBlank()) {
            models
        } else {
            models.filter { it.contains(searchText, ignoreCase = true) }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(32.dp),
        title = { 
            Column {
                Text(
                    "选择模型 (${models.size}个可用)",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                // 搜索框
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("搜索模型...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                // 全选按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            selectedModels = if (selectedModels.size == models.size) {
                                emptySet()
                            } else {
                                models.toSet()
                            }
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedModels.size == models.size && models.isNotEmpty(),
                        onCheckedChange = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "全选 (${selectedModels.size}/${models.size})",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                
                HorizontalDivider()
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 模型列表
                if (filteredModels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (searchText.isBlank()) "没有可用的模型" else "没有匹配的模型",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(filteredModels) { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedModels = if (model in selectedModels) {
                                            selectedModels - model
                                        } else {
                                            selectedModels + model
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = model in selectedModels,
                                    onCheckedChange = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    model,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            onSelectAll()
                            onDismiss()
                        },
                        enabled = models.isNotEmpty(),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(52.dp).padding(horizontal = 4.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Text("添加全部", fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = {
                            onSelectModels(selectedModels.toList())
                            onDismiss()
                        },
                        enabled = selectedModels.isNotEmpty(),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(52.dp).padding(horizontal = 4.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Text("添加选中 (${selectedModels.size})", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = {
                        onManualInput()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
                ) {
                    Text("手动输入", fontWeight = FontWeight.Medium)
                }
                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
                ) {
                    Text("取消", fontWeight = FontWeight.Medium)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface
    )
}