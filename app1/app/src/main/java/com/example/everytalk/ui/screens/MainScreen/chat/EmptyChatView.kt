package com.example.everytalk.ui.screens.MainScreen.chat

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun EmptyChatView() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val scale by animateFloatAsState(
            targetValue = if (isImeVisible) 0.6f else 1.0f,
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            label = "ScaleAnimation"
        )

        Row(
            modifier = Modifier.graphicsLayer(
                scaleX = scale,
                scaleY = scale
            ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
        ) {
            val style = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.ExtraBold,
            )
            Text("你好", style = style)
            val animY = remember { List(3) { Animatable(0f) } }
            val coroutineScope = rememberCoroutineScope()
            val density = androidx.compose.ui.platform.LocalDensity.current

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