package com.example.everytalk.ui.theme

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
    userBubble = Color(0xFFf4f4f4),
    aiBubble = Color.White,
    errorContent = Color(0xFFD32F2F), // Material Red 700
    reasoningText = Color(0xFF444444),
    codeBlockBackground = Color(0xFFF3F3F3),
    loadingIndicator = Color.Black
)

val darkChatColors = ChatColors(
    userBubble = Color(0xFF2D2D2D),
    aiBubble = Color.White,
    errorContent = Color(0xFFEF5350), // Material Red 400
    reasoningText = Color(0xFFBBBBBB),
    codeBlockBackground = Color(0xFF2D2D2D),
    loadingIndicator = Color.White
)

val MaterialTheme.chatColors: ChatColors
    @Composable
    @ReadOnlyComposable
    get() = if (colorScheme.surface.luminance() > 0.5f) lightChatColors else darkChatColors