package com.android.everytalk.ui.screens.settings.dialogs
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.R
import com.android.everytalk.ui.screens.settings.DialogTextFieldColors
import com.android.everytalk.ui.screens.settings.DialogShape
import androidx.compose.foundation.background

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

    val isDark = isSystemInDarkTheme()
    val dialogBg = if (isDark) Color.Black else Color.White
    val borderColor = if (isDark) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF0D0D0D).copy(alpha = 0.6f)
    val selectedColor = if (isDark) Color(0xFF6EB5FF) else Color(0xFF3B82F6)

    var selectedModels by remember { mutableStateOf(setOf<String>()) }
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(showDialog, models) {
        if (showDialog) {
            selectedModels = emptySet()
            searchText = ""
        }
    }

    val filteredModels = remember(models, searchText) {
        if (searchText.isBlank()) models
        else models.filter { it.contains(searchText, ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.75f)
                .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            color = dialogBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "选择模型",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                        Text(
                            "${models.size} 个可用",
                            style = MaterialTheme.typography.labelMedium,
                            color = subtextColor
                        )
                    }
                    TextButton(
                        onClick = {
                            selectedModels = if (selectedModels.size == models.size) emptySet()
                            else models.toSet()
                        }
                    ) {
                        Text(
                            if (selectedModels.size == models.size) "取消全选" else "全选",
                            color = contentColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 搜索框
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("搜索...", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    singleLine = true,
                    shape = DialogShape,
                    colors = DialogTextFieldColors,
                    textStyle = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 模型列表
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (filteredModels.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (searchText.isBlank()) "没有可用的模型" else "没有匹配的模型",
                                style = MaterialTheme.typography.bodyMedium,
                                color = subtextColor
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
                        ) {
                            items(filteredModels) { model ->
                                val isSelected = model in selectedModels
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedModels = if (isSelected)
                                                selectedModels - model
                                            else
                                                selectedModels + model
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = null,
                                            tint = selectedColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = model,
                                        fontSize = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSelected) selectedColor else contentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // 顶部渐变
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(20.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(dialogBg, dialogBg.copy(alpha = 0f))
                                )
                            )
                    )

                    // 底部渐变
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(32.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(dialogBg.copy(alpha = 0f), dialogBg)
                                )
                            )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = onManualInput,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("手动输入模型", color = contentColor, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = contentColor
                        ),
                        border = BorderStroke(1.dp, borderColor)
                    ) {
                        Text("取消", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            if (selectedModels.isNotEmpty()) {
                                onSelectModels(selectedModels.toList())
                            } else {
                                onSelectAll()
                            }
                        },
                        enabled = selectedModels.isNotEmpty() || models.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = contentColor,
                            contentColor = dialogBg,
                            disabledContainerColor = borderColor,
                            disabledContentColor = subtextColor
                        )
                    ) {
                        Text(
                            if (selectedModels.isEmpty()) "全部添加" else "添加 (${selectedModels.size})",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
