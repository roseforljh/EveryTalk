package com.example.app1.ui.screens.MainScreen.drawer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// --- 常量定义 ---
internal val SEARCH_BACKGROUND_COLOR = Color(0xFFEAEAEA) // 搜索框背景色
internal val DEFAULT_DRAWER_WIDTH = 280.dp // 抽屉默认宽度
internal const val EXPAND_ANIMATION_DURATION_MS = 300 // 展开/收起动画持续时间 (毫秒)
internal const val CONTENT_CHANGE_ANIMATION_DURATION_MS = 200 // 内容变化动画持续时间 (毫秒)

// 自定义涟漪效果常量
internal const val CUSTOM_RIPPLE_ANIMATION_DURATION_MS = 350 // 涟漪动画时长 (毫秒)
internal val CUSTOM_RIPPLE_COLOR = Color.Black             // 涟漪颜色
internal const val CUSTOM_RIPPLE_START_ALPHA = 0.12f       // 涟漪起始透明度
internal const val CUSTOM_RIPPLE_END_ALPHA = 0f            // 涟漪结束透明度