package com.android.everytalk.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager
import kotlinx.coroutines.delay

private const val ScrollToBottomIdleVisibleMillis = 3000L
private const val ScrollToBottomAppearDelayMillis = 1000L
private const val ScrollToBottomFadeInMillis = 360

internal fun shouldShowScrollToBottomButtonForFrame(
    baseVisible: Boolean,
    isScrollInProgress: Boolean,
    idleHoldVisible: Boolean
): Boolean {
    return baseVisible && !isScrollInProgress && idleHoldVisible
}

internal fun scrollToBottomButtonAppearDelayMillis(): Long {
    return ScrollToBottomAppearDelayMillis
}

internal fun scrollToBottomButtonFadeInMillis(): Int {
    return ScrollToBottomFadeInMillis
}

internal fun scrollToBottomButtonDarkBorderColor(): Color {
    return Color.White.copy(alpha = 0.42f)
}

internal fun scrollToBottomButtonBorder(isDarkTheme: Boolean): BorderStroke? {
    return if (isDarkTheme) {
        BorderStroke(
            width = 1.dp,
            color = scrollToBottomButtonDarkBorderColor()
        )
    } else {
        null
    }
}

@Composable
fun ScrollToBottomButton(
    scrollStateManager: ChatScrollStateManager,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 150.dp,
    endPadding: Dp = 0.dp
) {
    val baseVisible by scrollStateManager.showScrollToBottomButton
    val isScrollInProgress by scrollStateManager.isScrollInProgress
    var idleHoldVisible by remember { mutableStateOf(false) }
    val showButton = shouldShowScrollToBottomButtonForFrame(
        baseVisible = baseVisible,
        isScrollInProgress = isScrollInProgress,
        idleHoldVisible = idleHoldVisible
    )

    LaunchedEffect(baseVisible, isScrollInProgress) {
        if (!baseVisible || isScrollInProgress) {
            idleHoldVisible = false
            return@LaunchedEffect
        }

        delay(scrollToBottomButtonAppearDelayMillis())
        if (!baseVisible || isScrollInProgress) {
            idleHoldVisible = false
            return@LaunchedEffect
        }

        idleHoldVisible = true
        delay(ScrollToBottomIdleVisibleMillis)
        idleHoldVisible = false
    }

    AnimatedVisibility(
        visible = showButton,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = scrollToBottomButtonFadeInMillis(),
                easing = LinearOutSlowInEasing
            )
        ),
        exit = fadeOut(
            animationSpec = tween(
                durationMillis = if (isScrollInProgress) 0 else 220,
                easing = FastOutLinearInEasing
            )
        )
    ) {
        val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val containerColor = if (isDarkTheme) {
            MaterialTheme.colorScheme.background
        } else {
            Color(0xFFF2F2F2)
        }
        val contentColor = if (isDarkTheme) Color.White else Color.Black
        val borderStroke = scrollToBottomButtonBorder(isDarkTheme)

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomPadding)
                    .size(40.dp)
                    .shadow(
                        elevation = if (isDarkTheme) 0.dp else 8.dp,
                        shape = CircleShape,
                        clip = false
                    )
                    .clip(CircleShape)
                    .background(containerColor)
                    .then(
                        if (borderStroke != null) {
                            Modifier.border(borderStroke, CircleShape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { scrollStateManager.jumpToBottom(true) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = "Scroll to bottom",
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
