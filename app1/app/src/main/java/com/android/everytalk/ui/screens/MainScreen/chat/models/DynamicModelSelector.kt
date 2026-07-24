package com.android.everytalk.ui.screens.MainScreen.chat.models
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.border
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
import com.android.everytalk.ui.screens.settings.DialogTextFieldColors
import com.android.everytalk.ui.screens.settings.DialogShape
import com.android.everytalk.ui.screens.settings.SettingsFieldLabel
import java.util.Locale

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
    
    val isDark = isSystemInDarkTheme()
    val dialogBg = if (isDark) Color.Black else Color.White
    val borderColor = if (isDark) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val sortedModelList = remember(modelList) {
        modelList.sortedWith(
            compareBy<String> { it.trim().lowercase(Locale.ROOT) }
                .thenBy { it.trim() }
        )
    }

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
                    .wrapContentHeight()
                    .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = dialogBg
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "添加模型",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = contentColor
                    )
                    
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SettingsFieldLabel("模型名称")
                        OutlinedTextField(
                            value = newModelName,
                            onValueChange = { newModelName = it },
                            placeholder = { Text("请输入模型名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = DialogTextFieldColors,
                            shape = DialogShape
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAddDialog = false },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = contentColor
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
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
                                containerColor = contentColor,
                                contentColor = dialogBg
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
        modifier = Modifier.fillMaxWidth()
    ) {
        SettingsFieldLabel(label)
        
        ExposedDropdownMenuBox(
            expanded = expanded && sortedModelList.isNotEmpty(),
            onExpandedChange = { 
                if (sortedModelList.isEmpty()) {
                    // 如果列表为空，点击时直接弹出添加对话框
                    showAddDialog = true
                } else {
                    expanded = it 
                }
            }
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
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                        placeholder = { Text("选择模型") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        colors = DialogTextFieldColors,
                        shape = DialogShape,
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
                            tint = if (isDark) Color.White else Color.Black
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

            if (sortedModelList.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    sortedModelList.forEach { modelOption ->
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
