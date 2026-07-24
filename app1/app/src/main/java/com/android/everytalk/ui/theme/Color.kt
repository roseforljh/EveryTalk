package com.android.everytalk.ui.theme
import com.android.everytalk.statecontroller.*

import androidx.compose.ui.graphics.Color

// 亮色主题颜色
val Purple80 = Color(0xFFD0BCFF)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
val SeaBlue = Color(0xFF0091ff)

// 夜间模式专用颜色 - 统一和谐的设计
// 深色背景系列 - 使用更柔和的灰色调，避免纯黑
val DarkBackground = Color(0xFF000000)          // 主背景色 - 纯黑
val DarkSurface = Color(0xFF000000)            // 表面颜色 - 纯黑
val DarkSurfaceVariant = Color(0xFF000000)     // 表面变体颜色 - 纯黑
val DarkSurfaceContainer = Color(0xFF000000)    // 容器表面颜色 - 纯黑

// 卡片和容器颜色 - 关键改进，避免白色突兀
val DarkCardBackground = Color(0xFF2A2A2A)     // 卡片背景 - 取代白色
val DarkCardElevated = Color(0xFF000000)       // 悬浮卡片背景

// 深色前景系列 - 纯白主文字 + 灰阶次级文字
val DarkOnBackground = Color(0xFFFFFFFF)       // 背景上的文字 - 纯白
val DarkOnSurface = Color(0xFFFFFFFF)          // 表面上的文字 - 纯白
val DarkOnSurfaceVariant = Color(0xFFBBBBBB)   // 表面变体上的文字 - 中等灰
val DarkOnCard = Color(0xFFFFFFFF)             // 卡片上的文字 - 纯白

// 深色主色调 - 温和的蓝色系
val DarkPrimary = Color(0xFF6C9EFF)            // 主色调 - 更亮的蓝色
val DarkOnPrimary = Color(0xFFFFFFFF)          // 主色调上的文字
val DarkPrimaryContainer = Color(0xFF1E3A5F)   // 主色调容器 - 深蓝

// 深色次要色调 - 温和的绿色系
val DarkSecondary = Color(0xFF6FD68B)          // 次要色调 - 更亮的绿色
val DarkOnSecondary = Color(0xFFFFFFFF)        // 次要色调上的文字
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
val DarkOutline = Color(0xFF000000)            // 轮廓线 - 纯黑
val DarkOutlineVariant = Color(0xFF000000)     // 轮廓线变体

// 文字颜色
val DarkTextPrimary = Color(0xFFFFFFFF)        // 主要文字 - 纯白

// 弹出选项卡专用颜色 - 响应主题变化
val LightPopupBackground = Color(0xFFF5F5F5)    // 白天模式弹出选项卡背景 - 淡灰色
val DarkPopupBackground = Color(0xFF363636)     // 夜间模式弹出选项卡背景 - 深灰色
