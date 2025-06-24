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
import kotlinx.coroutines.delay

@Composable
fun ScrollToBottomButton(
    visible: Boolean,
    isAutoScrolling: Boolean,
    activityTrigger: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var fabVisibleDueToActivity by remember { mutableStateOf(true) }

    // Reset the timeout whenever the button should be visible or there's user activity.
    LaunchedEffect(visible, activityTrigger) {
        if (visible) {
            fabVisibleDueToActivity = true
        }
    }

    // Hide the button after a delay of inactivity.
    // The key includes activityTrigger to restart the timer on new activity.
    LaunchedEffect(fabVisibleDueToActivity, visible, activityTrigger) {
        if (fabVisibleDueToActivity && visible) {
            delay(3000) // Hide after 3 seconds of inactivity
            fabVisibleDueToActivity = false
        }
    }

    AnimatedVisibility(
        visible = visible && fabVisibleDueToActivity && !isAutoScrolling,
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
            onClick = onClick,
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