package com.android.everytalk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

data class ChatColors(
    val userBubble: Color,
    val aiBubble: Color,
    val errorContent: Color,
    val reasoningText: Color,
    val codeBlockBackground: Color,
    val loadingIndicator: Color
)

val lightChatColors = ChatColors(
    userBubble = Color(0xFFE8E8E8),               // 淡灰色背景
    aiBubble = Color(0xFFFFFFFF),                 // 修复：白天模式使用纯白色，与app背景一致
    errorContent = Color(0xFFD32F2F),             // Material Red 700
    reasoningText = Color(0xFF424242),            // 深灰色推理文字
    codeBlockBackground = Color(0xFFF5F5F5),      // 浅灰代码背景 - 响应主题
    loadingIndicator = Color(0xFF1976D2)          // 蓝色加载指示器
)

val darkChatColors = ChatColors(
    userBubble = Color(0xFF2C2C2E),               // 深色模式下的淡灰色背景
    aiBubble = Color(0xFF1A1A1A),                 // 修复：与夜间模式app背景一致
    errorContent = Color(0xFFE57373),             // 柔和的错误色
    reasoningText = Color(0xFFD0D0D0),            // 柔和的次要文字颜色
    codeBlockBackground = Color(0xFF1E1E1E),      // 统一的代码背景色
    loadingIndicator = Color(0xFF5B9BD5)          // 柔和的主色调指示器
)

@Composable
@ReadOnlyComposable
fun getChatColors(): ChatColors {
    val colorScheme = MaterialTheme.colorScheme
    return if (colorScheme.surface.luminance() > 0.5f) {
        // 亮色模式：使用为聊天定制的配色（适配白天）
        lightChatColors
    } else {
        // 深色模式：使用为聊天定制的配色（适配夜晚）
        darkChatColors
    }
}

val MaterialTheme.chatColors: ChatColors
    @Composable
    @ReadOnlyComposable
    get() = getChatColors()