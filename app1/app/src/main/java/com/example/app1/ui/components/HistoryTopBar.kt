package com.example.app1.ui.components // 确保包名正确

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack // 使用 AutoMirrored 图标以支持 RTL 布局
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class) // CenterAlignedTopAppBar 是 M3 的实验性 API
@Composable
fun HistoryTopBar(onBackClick: () -> Unit) { // 接收返回按钮的点击事件回调
    // 使用 CenterAlignedTopAppBar 实现居中标题的效果
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = "历史记录", // 标题文本
                fontSize = 20.sp, // 字体大小
                fontWeight = FontWeight.Bold // 字体加粗
            )
        },
        navigationIcon = { // 导航图标（通常是返回按钮）
            IconButton(onClick = onBackClick) { // 点击时调用传入的回调
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack, // 返回箭头图标
                    contentDescription = "返回" // 无障碍描述
                )
            }
        },
        // 可以添加 actions = { ... } 来放置右侧的操作按钮，如果需要的话
        // 例如：
        // actions = {
        //     IconButton(onClick = { /* 编辑操作 */ }) {
        //         Icon(Icons.Default.Edit, contentDescription = "编辑")
        //     }
        // },
        // 可以设置颜色等
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface, // 背景色
            titleContentColor = MaterialTheme.colorScheme.onSurface, // 标题颜色
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface // 导航图标颜色
        ),
        // 应用状态栏的边距
        modifier = Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets)
    )
}