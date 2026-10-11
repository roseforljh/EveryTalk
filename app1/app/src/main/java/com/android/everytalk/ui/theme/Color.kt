package com.android.everytalk.ui.theme
import com.android.everytalk.statecontroller.*

import androidx.compose.ui.graphics.Color

val SeaBlue = Color(0xFF0091ff)

// 夜间模式专用颜色 - 统一和谐的设计
// 深色背景系列：主背景保持纯黑（OLED 省电），容器必须比背景亮一点，
// 否则卡片、输入框、边框在纯黑底上完全没有层次。
val DarkBackground = Color(0xFF000000)          // 主背景色 - 纯黑
val DarkSurface = Color(0xFF000000)            // 最低层容器 - 纯黑
val DarkSurfaceVariant = Color(0xFF1C1C1C)     // 表面变体 - 比背景亮一档
val DarkSurfaceContainer = Color(0xFF141414)    // 容器表面 - 卡片/分组容器

// 卡片和容器颜色 - 关键改进，避免白色突兀
val DarkCardBackground = Color(0xFF2A2A2A)     // 卡片背景 - 取代白色
val DarkCardElevated = Color(0xFF1F1F1F)       // 最高层容器 - 比容器再亮一档
val DarkSurfaceDim = Color(0xFF0A0A0A)         // 被压暗的表面 - 必须暗于 surfaceBright

// 深色前景系列 - 纯白主文字 + 灰阶次级文字
val DarkOnBackground = Color(0xFFFFFFFF)       // 背景上的文字 - 纯白
val DarkOnSurface = Color(0xFFFFFFFF)          // 表面上的文字 - 纯白
val DarkOnSurfaceVariant = Color(0xFFBBBBBB)   // 表面变体上的文字 - 中等灰
val DarkOnCard = Color(0xFFFFFFFF)             // 卡片上的文字 - 纯白

// 操作颜色统一为灰阶：暗色主题白底黑字，容器保留深灰层级。
val DarkPrimary = Color.White
val DarkOnPrimary = Color.Black
val DarkPrimaryContainer = Color(0xFF242424)

val DarkSecondary = Color(0xFFDDDDDD)
val DarkOnSecondary = Color.Black
val DarkSecondaryContainer = Color(0xFF242424)

val DarkTertiary = Color(0xFFBBBBBB)
val DarkOnTertiary = Color.Black
val DarkTertiaryContainer = Color(0xFF242424)

// 深色错误状态 - 柔和的红色
val DarkError = Color(0xFFE57373)              // 错误色 - 柔和红
val DarkOnError = Color(0xFF000000)            // 错误色上的文字
val DarkErrorContainer = Color(0xFF4A1A1A)     // 错误容器

// 深色轮廓线 - 纯黑描边在纯黑背景上等于没画，必须用中性灰
val DarkOutline = Color(0xFF5A5A5A)            // 轮廓线 - 按钮/输入框描边
val DarkOutlineVariant = Color(0xFF333333)     // 轮廓线变体 - 分割线

// 文字颜色
val DarkTextPrimary = Color(0xFFFFFFFF)        // 主要文字 - 纯白

// 弹出选项卡专用颜色 - 响应主题变化
val LightPopupBackground = Color(0xFFF5F5F5)    // 白天模式弹出选项卡背景 - 淡灰色
val DarkPopupBackground = Color(0xFF363636)     // 夜间模式弹出选项卡背景 - 深灰色
