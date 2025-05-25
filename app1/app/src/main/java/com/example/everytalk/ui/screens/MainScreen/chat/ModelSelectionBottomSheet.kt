package com.example.everytalk.ui.screens.MainScreen.chat // 建议修改为 com.example.everytalk.ui.screens.mainscreen.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.everytalk.data.DataClass.ApiConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    availableModels: List<ApiConfig>,
    selectedApiConfig: ApiConfig?,
    onModelSelected: (ApiConfig) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        dragHandle = null
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            // 标题部分
            Row(
                modifier = Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 36.dp, // 加大与顶部的距离
                    bottom = 8.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "密钥图标",
                    tint = Color(0xff7bc047),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "当前密钥下的模型",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // 列表部分
            if (availableModels.isEmpty()) {
                Text(
                    "没有可用的模型配置。",
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 0.dp) // 列表本身的垂直内边距设为0，使列表项更紧凑
                ) {
                    items(items = availableModels, key = { it.id }) { modelConfig ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = modelConfig.name.ifEmpty { modelConfig.model },
                                    fontSize = 14.sp, // 较小的模型名称字体
                                    color = Color(0xff778899)  // 模型名称颜色为灰色
                                )
                            },
                            supportingContent = {
                                if (modelConfig.name.isNotEmpty() && modelConfig.model.isNotEmpty() && modelConfig.name != modelConfig.model) {
                                    Text(
                                        modelConfig.model,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp, // supporting text 字体
                                        color = Color.DarkGray
                                    )
                                }
                            },
                            trailingContent = {
                                if (modelConfig.id == selectedApiConfig?.id) {
                                    Icon(
                                        Icons.Filled.Done,
                                        contentDescription = "当前选中",
                                        tint = Color(0xff778899),
                                        modifier = Modifier.size(20.dp) // 较小的勾选图标
                                    )
                                } else {
                                    Spacer(Modifier.size(20.dp)) // 保持对齐的占位符
                                }
                            },
                            modifier = Modifier.clickable {
                                onModelSelected(modelConfig)
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent // 保持背景透明
                            )
                        )
                    }
                }
            }
        }
    }
}