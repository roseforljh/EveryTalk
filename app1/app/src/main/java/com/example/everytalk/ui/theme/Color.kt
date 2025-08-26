package com.example.everytalk.ui.theme

import androidx.compose.ui.graphics.Color

// 亮色主题颜色
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
val SeaBlue = Color(0xFF0091ff)

// 夜间模式专用颜色 - 统一和谐的设计
// 深色背景系列 - 使用更柔和的灰色调，避免纯黑
val DarkBackground = Color(0xFF1A1A1A)          // 主背景色 - 柔和深灰
val DarkSurface = Color(0xFF242424)            // 表面颜色 - 稍亮的灰
val DarkSurfaceVariant = Color(0xFF2E2E2E)     // 表面变体颜色 - 中等灰
val DarkSurfaceContainer = Color(0xFF323232)    // 容器表面颜色 - 更亮一些

// 卡片和容器颜色 - 关键改进，避免白色突兀
val DarkCardBackground = Color(0xFF2A2A2A)     // 卡片背景 - 取代白色
val DarkCardElevated = Color(0xFF303030)       // 悬浮卡片背景
val DarkInputBackground = Color(0xFF363636)    // 输入框背景

// 深色前景系列 - 柔和的文字颜色
val DarkOnBackground = Color(0xFFE8E8E8)       // 背景上的文字 - 柔和白
val DarkOnSurface = Color(0xFFDEDEDE)          // 表面上的文字
val DarkOnSurfaceVariant = Color(0xFFBBBBBB)   // 表面变体上的文字 - 中等灰
val DarkOnCard = Color(0xFFE0E0E0)             // 卡片上的文字

// 深色主色调 - 温和的蓝色系
val DarkPrimary = Color(0xFF5B9BD5)            // 主色调 - 柔和蓝色
val DarkOnPrimary = Color(0xFFFFFFFF)          // 主色调上的文字
val DarkPrimaryContainer = Color(0xFF1E3A5F)   // 主色调容器 - 深蓝

// 深色次要色调 - 温和的绿色系
val DarkSecondary = Color(0xFF70A37F)          // 次要色调 - 柔和绿色
val DarkOnSecondary = Color(0xFF000000)        // 次要色调上的文字
val DarkSecondaryContainer = Color(0xFF1A2E1A) // 次要色调容器

// 深色强调色 - 温和的暖色
val DarkTertiary = Color(0xFFD2A84F)           // 强调色 - 柔和金黄
val DarkOnTertiary = Color(0xFF000000)         // 强调色上的文字
val DarkTertiaryContainer = Color(0xFF3D2F00)  // 强调色容器

// 深色错误状态 - 柔和的红色
val DarkError = Color(0xFFE57373)              // 错误色 - 柔和红
val DarkOnError = Color(0xFF000000)            // 错误色上的文字
val DarkErrorContainer = Color(0xFF4A1A1A)     // 错误容器

// 深色轮廓线 - 更微妙的分割线
val DarkOutline = Color(0xFF404040)            // 轮廓线 - 柔和灰
val DarkOutlineVariant = Color(0xFF353535)     // 轮廓线变体

// 聊天专用颜色 - 统一的灰色调
val DarkUserBubble = Color(0xFF3A4A5C)         // 用户消息气泡 - 深蓝灰
val DarkAIBubble = Color(0xFF2E2E2E)           // AI消息气泡 - 深灰，与表面色协调
val DarkCodeBackground = Color(0xFF1E1E1E)     // 代码块背景 - 稍深的灰
val DarkCodeSurface = Color(0xFF252525)        // 代码表面色

// 状态颜色 - 柔和版本
val DarkSuccess = Color(0xFF66BB6A)            // 成功色 - 柔和绿
val DarkWarning = Color(0xFFFFB74D)            // 警告色 - 柔和橙
val DarkInfo = Color(0xFF64B5F6)               // 信息色 - 柔和蓝

// 文字颜色 - 层次分明但不刺眼
val DarkTextPrimary = Color(0xFFF0F0F0)        // 主要文字 - 柔和白
val DarkTextSecondary = Color(0xFFD0D0D0)      // 次要文字 - 中等灰白
val DarkTextTertiary = Color(0xFFA8A8A8)       // 三级文字 - 浅灰
val DarkTextDisabled = Color(0xFF666666)       // 禁用文字 - 深灰

// 分割线和边框 - 微妙的分割
val DarkDivider = Color(0xFF383838)            // 分割线 - 微妙灰
val DarkBorder = Color(0xFF484848)             // 边框色 - 柔和边框
val DarkBorderLight = Color(0xFF404040)        // 浅边框色

// 弹出选项卡专用颜色 - 响应主题变化
val LightPopupBackground = Color(0xFFF5F5F5)    // 白天模式弹出选项卡背景 - 淡灰色
val DarkPopupBackground = Color(0xFF363636)     // 夜间模式弹出选项卡背景 - 深灰色
val DarkGreen = Color(0xFF2E7D32)