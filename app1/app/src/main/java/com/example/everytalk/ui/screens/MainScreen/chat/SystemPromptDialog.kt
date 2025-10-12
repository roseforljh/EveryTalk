package com.example.everytalk.ui.screens.MainScreen.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.everytalk.ui.theme.DarkGreen

/**
 * 系统提示编辑弹窗：
 * - 将右下角“两个按钮”改为“一个开始/暂停按钮（播放/暂停图标）”
 * - 开始：本会话接入当前 Prompt；顶栏胶囊右侧小点播放动画
 * - 暂停：本会话不接入 Prompt；正常对话
 */
@Composable
fun SystemPromptDialog(
    prompt: String,
    isEngaged: Boolean = false,
    onToggleEngaged: () -> Unit = {},
    onDismissRequest: () -> Unit,
    onPromptChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onClear: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        // 进入弹窗的淡入与缩放
        val alphaAnim = remember { Animatable(0f) }
        val scaleAnim = remember { Animatable(0.92f) }
        LaunchedEffect(Unit) {
            alphaAnim.animateTo(1f, animationSpec = tween(durationMillis = 250))
            scaleAnim.animateTo(1f, animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing))
        }

        Card(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 12.dp)
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.78f)
                .graphicsLayer {
                    this.alpha = alphaAnim.value
                    this.scaleX = scaleAnim.value
                    this.scaleY = scaleAnim.value
                },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
            ) {
                // 顶部标题（移除右上角 清空/保存 按钮，精简为仅标题）
                Row(
                    modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "系统提示",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.size(6.dp))

                // 提示编辑区域
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val scrimColor = MaterialTheme.colorScheme.surface
                    val scrimHeight = 24.dp

                    Column(modifier = Modifier.fillMaxSize()) {
                        // 顶部渐隐
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(scrimHeight)
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            scrimColor,
                                            scrimColor.copy(alpha = 0.6f),
                                            scrimColor.copy(alpha = 0.3f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                        // 输入框
                        TextField(
                            value = prompt,
                            onValueChange = onPromptChange,
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            placeholder = { Text("请输入系统提示（Prompt），用于影响本会话回答的风格与约束…") }
                        )
                        // 底部渐隐
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(scrimHeight)
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            scrimColor.copy(alpha = 0.3f),
                                            scrimColor.copy(alpha = 0.6f),
                                            scrimColor
                                        )
                                    )
                                )
                        )
                    }
                }

                Spacer(Modifier.size(12.dp))

                // 右下角“单一开始/暂停按钮”
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    // 颜色与轻微脉冲效果（接入时脉冲）
                    val targetColor = if (isEngaged) DarkGreen else MaterialTheme.colorScheme.primary
                    val iconColor = animateColorAsState(
                        targetValue = targetColor,
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        label = "EngageColor"
                    )
                    val pulseScale = remember { Animatable(1f) }
                    LaunchedEffect(isEngaged) {
                        pulseScale.stop()
                        if (isEngaged) {
                            pulseScale.snapTo(1.0f)
                            pulseScale.animateTo(
                                1.06f,
                                infiniteRepeatable(
                                    tween(900, easing = FastOutSlowInEasing),
                                    RepeatMode.Reverse
                                )
                            )
                        } else {
                            pulseScale.animateTo(1.0f, tween(200, easing = FastOutSlowInEasing))
                        }
                    }

                    FilledIconButton(
                        onClick = {
                            if (!isEngaged) {
                                // 单击“开始”：先保存当前 Prompt，再开启接入，并关闭弹窗
                                onConfirm()
                                onToggleEngaged()
                                onDismissRequest()
                            } else {
                                // 单击“暂停”：仅关闭接入，保持当前 Prompt，不必另行保存
                                onToggleEngaged()
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .scale(pulseScale.value),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = iconColor.value.copy(alpha = 0.18f),
                            contentColor = iconColor.value
                        )
                    ) {
                        Icon(
                            imageVector = if (isEngaged) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isEngaged) "暂停接入系统提示" else "开始接入系统提示"
                        )
                    }
                }
            }
        }
    }
}