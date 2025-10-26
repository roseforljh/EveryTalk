package com.android.everytalk.test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderDebugScreen() {
    var value1 by remember { mutableStateOf(0.5f) }
    var value2 by remember { mutableStateOf(0.5f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        Text(
            "Slider 渲染问题调试",
            style = MaterialTheme.typography.headlineMedium
        )

        // 问题复现：自定义 thumb
        Column {
            Text("问题复现 - 自定义 thumb (20dp)", fontSize = 14.sp)
            Text("Value: ${String.format("%.2f", value1)}", fontSize = 12.sp, color = Color.Gray)
            Slider(
                value = value1,
                onValueChange = { value1 = it },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            )
        }

        // 默认 Material 3 Slider
        Column {
            Text("默认 Material 3 Slider", fontSize = 14.sp)
            Text("Value: ${String.format("%.2f", value2)}", fontSize = 12.sp, color = Color.Gray)
            Slider(
                value = value2,
                onValueChange = { value2 = it },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                )
            )
        }

        // 分析说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "问题分析",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "1. 自定义 thumb 破坏了触摸目标大小",
                    fontSize = 12.sp
                )
                Text(
                    "2. Material 3 默认 thumb 包含涟漪效果和状态管理",
                    fontSize = 12.sp
                )
                Text(
                    "3. 自定义实现缺少 InteractionSource 处理",
                    fontSize = 12.sp
                )
                Text(
                    "4. 轨道分割是由于 thumb 渲染层级问题",
                    fontSize = 12.sp
                )
            }
        }
    }
}