package com.example.everytalk.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.everytalk.ui.screens.MainScreen.chat.ChatScrollStateManager

@Composable
fun ScrollToBottomButton(
    scrollStateManager: ChatScrollStateManager,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 150.dp,
    endPadding: Dp = 0.dp
) {
    val showButton by scrollStateManager.showScrollToBottomButton
    AnimatedVisibility(
        visible = showButton,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = 150,
                easing = LinearOutSlowInEasing
            )
        ),
        exit = fadeOut(
            animationSpec = tween(
                durationMillis = 150,
                easing = FastOutLinearInEasing
            )
        )
    ) {
        // 检测是否为深色主题
        val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        
        Box(modifier = Modifier.fillMaxSize()) {
            FloatingActionButton(
                onClick = { scrollStateManager.jumpToBottom() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = endPadding, bottom = bottomPadding),
                shape = CircleShape,
                containerColor = if (isDarkTheme) {
                    // 夜间模式：使用与聊天页面背景相同的颜色
                    MaterialTheme.colorScheme.background
                } else {
                    // 白天模式：保持原来的浅灰色
                    Color(0xFFF2F2F2)
                },
                contentColor = if (isDarkTheme) {
                    // 夜间模式：使用浅色图标
                    Color.White
                } else {
                    // 白天模式：保持原来的黑色
                    Color.Black
                },
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp
                )
            ) {
                Icon(Icons.Filled.ArrowDownward, "滚动到底部")
            }
        }
    }
}