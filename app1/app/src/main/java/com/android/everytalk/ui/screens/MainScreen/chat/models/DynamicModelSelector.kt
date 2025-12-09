package com.android.everytalk.ui.screens.MainScreen.chat.models

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicModelSelector(
    label: String,
    currentModel: String,
    onModelChange: (String) -> Unit,
    modelList: List<String>,
    onAddModel: (String) -> Unit,
    onRemoveModel: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newModelName by remember { mutableStateOf("") }
    
    // 添加模型对话框
    if (showAddDialog) {
        Dialog(
            onDismissRequest = { showAddDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        text = "添加模型",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    OutlinedTextField(
                        value = newModelName,
                        onValueChange = { newModelName = it },
                        placeholder = { Text("请输入模型名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAddDialog = false },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = if (isSystemInDarkTheme()) Color(0xFFFF5252) else Color(0xFFD32F2F)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSystemInDarkTheme()) Color(0xFFFF5252) else Color(0xFFD32F2F))
                        ) {
                            Text("取消", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                        }
                        
                        Button(
                            onClick = {
                                if (newModelName.isNotBlank()) {
                                    onAddModel(newModelName.trim())
                                    showAddDialog = false
                                    newModelName = ""
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSystemInDarkTheme()) Color.White else Color(0xFF212121),
                                contentColor = if (isSystemInDarkTheme()) Color.Black else Color.White
                            )
                        ) {
                            Text("确定", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = currentModel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .weight(1f)
                            .menuAnchor(),
                        placeholder = { Text("选择模型") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Add,
                            contentDescription = "添加模型",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                if (currentModel.isBlank()) {
                    Text(
                        text = "必填项：请输入模型名称",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
            }

            if (modelList.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    modelList.forEach { modelOption ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = modelOption, modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            onRemoveModel(modelOption)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.Close,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onModelChange(modelOption)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}