package com.example.everytalk.ui.screens.MainScreen.chat

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun EmptyChatView(
    density: Density,
    isKeyboardVisible: Boolean = false,
    imeHeightDp: androidx.compose.ui.unit.Dp = 0.dp
) {
    // 缩放动画，当输入法弹出时触发
    val scaleAnimation = remember { Animatable(1f) }
    
    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible) {
            // 输入法弹出时，缩放到 0.8
            scaleAnimation.animateTo(
                targetValue = 0.8f,
                animationSpec = tween(
                    durationMillis = 400,
                    easing = FastOutSlowInEasing
                )
            )
        } else {
            // 输入法隐藏时，恢复到原始大小
            scaleAnimation.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 400,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scaleAnimation.value
                    scaleY = scaleAnimation.value
                }
                // 当输入法弹出时，通过 offset 抵消界面上移，让"你好"保持在原位
                .offset(y = if (isKeyboardVisible) imeHeightDp else 0.dp)
        ) {
            val style = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.ExtraBold,
            )
            Text("你好", style = style)
            val animY = remember { List(3) { Animatable(0f) } }
            val coroutineScope = rememberCoroutineScope() // Needed for cancellation reset

            LaunchedEffect(Unit) {
                animY.forEach { it.snapTo(0f) } // Initialize
                try {
                    repeat(Int.MAX_VALUE) { // Loop indefinitely
                        if (!isActive) throw CancellationException("你好动画取消")
                        animY.forEachIndexed { index, anim ->
                            launch {
                                delay((index * 150L) % 450) // Staggered start
                                anim.animateTo(
                                    targetValue = with(density) { (-6).dp.toPx() },
                                    animationSpec = tween(
                                        durationMillis = 300,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                                anim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        durationMillis = 450,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                                if (index == animY.lastIndex) delay(600) // Pause at the end of a full cycle
                            }
                        }
                        delay(1200) // Wait for one full cycle of all dots to roughly complete + pause
                    }
                } catch (e: CancellationException) {
                    Log.d("Animation", "你好动画已取消")
                    // Ensure dots reset on cancellation
                    coroutineScope.launch { animY.forEach { launch { it.snapTo(0f) } } }
                }
            }
            animY.forEach {
                Text(
                    text = ".",
                    style = style,
                    modifier = Modifier.offset(y = with(density) { it.value.toDp() })
                )
            }
        }
    }
}