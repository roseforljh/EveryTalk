package com.example.everytalk.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.everytalk.ui.screens.MainScreen.chat.ChatScrollStateManager

@Composable
fun ScrollToBottomButton(
    scrollStateManager: ChatScrollStateManager,
    modifier: Modifier = Modifier
) {
    val showButton by scrollStateManager.showScrollToBottomButton
    AnimatedVisibility(
        visible = showButton,
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
        FloatingActionButton(
            onClick = { scrollStateManager.jumpToBottom() },
            modifier = modifier.padding(bottom = 150.dp),
            shape = CircleShape,
            containerColor = Color(0xFFF2F2F2),
            contentColor = Color.Black,
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