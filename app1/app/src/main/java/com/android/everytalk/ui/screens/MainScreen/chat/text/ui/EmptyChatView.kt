package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun EmptyChatView(
    onNavigateToImageGen: () -> Unit,
    onNavigateToVoice: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onShowSystemPrompt: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
        val density = LocalDensity.current
        val imeHeightDp = with(density) { imeHeight.toDp() }

        val contentTranslationY by animateFloatAsState(
            targetValue = if (isImeVisible) with(density) { -(imeHeightDp / 2.2f).toPx() } else 0f,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "ContentTranslationY"
        )
        val spacerHeight by animateDpAsState(
            targetValue = if (isImeVisible) 16.dp else 36.dp,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "SpacerHeight"
        )

        Column(
            modifier = Modifier
                .graphicsLayer {
                    translationY = contentTranslationY
                }
                .width(IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                val style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                )
                Text("EveryTalk", style = style)

                val dotStyle = style.copy(fontSize = 18.sp)
                val animY = remember { List(3) { Animatable(0f) } }
                val coroutineScope = rememberCoroutineScope()
                val density = LocalDensity.current

                LaunchedEffect(Unit) {
                    animY.forEach { it.snapTo(0f) }
                    try {
                        repeat(Int.MAX_VALUE) {
                            if (!isActive) throw CancellationException("动画取消")
                            animY.forEachIndexed { index, anim ->
                                launch {
                                    delay((index * 150L) % 450)
                                    anim.animateTo(
                                        targetValue = with(density) { (-4).dp.toPx() },
                                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                                    )
                                    anim.animateTo(
                                        targetValue = 0f,
                                        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)
                                    )
                                    if (index == animY.lastIndex) delay(600)
                                }
                            }
                            delay(1200)
                        }
                    } catch (e: CancellationException) {
                        Log.d("Animation", "动画已取消")
                        coroutineScope.launch { animY.forEach { launch { it.snapTo(0f) } } }
                    }
                }

                Text(",一直都在", style = style)

                animY.forEach {
                    Text(
                        text = ".",
                        style = dotStyle,
                        modifier = Modifier.offset(y = with(density) { it.value.toDp() })
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacerHeight))

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PillCard(
                        title = "创建图片",
                        iconRes = R.drawable.gpt_images,
                        iconTint = Color(0xFF4CAF50),
                        onClick = { if (!isImeVisible) onNavigateToImageGen() }
                    )
                    PillCard(
                        title = "语音对话",
                        iconRes = R.drawable.gpt_voice,
                        iconTint = Color(0xFF2196F3),
                        onClick = { if (!isImeVisible) onNavigateToVoice() }
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PillCard(
                        title = "配置修改",
                        iconRes = R.drawable.gpt_settings,
                        iconTint = Color(0xFFFF9800),
                        onClick = { if (!isImeVisible) onNavigateToSettings() }
                    )
                    PillCard(
                        title = "会话风格",
                        iconRes = R.drawable.gpt_tuning,
                        iconTint = Color(0xFFAB47BC),
                        onClick = { if (!isImeVisible) onShowSystemPrompt() }
                    )
                }
            }
        }
    }
}

@Composable
private fun PillCard(
    modifier: Modifier = Modifier,
    title: String,
    iconRes: Int,
    iconTint: Color,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = Color.Transparent
    val borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(
                width = 0.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(50)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = cardColor,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
