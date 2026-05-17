package com.android.everytalk.ui.screens.MainScreen.chat.dialog

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.config.PerformanceConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 系统提示编辑弹窗：
 * - 优化后的 UI：更清晰的输入区域，更明确的操作按钮
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
    // 🎯 性能优化：使用本地状态管理输入文本，避免每次按键都触发 ViewModel 更新
    var localPrompt by remember { mutableStateOf(prompt) }
    val coroutineScope = rememberCoroutineScope()
    var syncJob by remember { mutableStateOf<Job?>(null) }
    var lastExternalPrompt by remember { mutableStateOf(prompt) }
    
    // 当外部 prompt 变化时（如清空），同步到本地状态
    LaunchedEffect(prompt) {
        if (prompt != lastExternalPrompt) {
            lastExternalPrompt = prompt
            localPrompt = prompt
        }
    }
    
    // 防抖同步到 ViewModel
    LaunchedEffect(localPrompt) {
        syncJob?.cancel()
        syncJob = coroutineScope.launch {
            delay(PerformanceConfig.STATE_DEBOUNCE_DELAY_MS)
            if (localPrompt != prompt) {
                onPromptChange(localPrompt)
                lastExternalPrompt = localPrompt
            }
        }
    }
    
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        val alphaAnim = remember { Animatable(0f) }
        val scaleAnim = remember { Animatable(0.92f) }
        LaunchedEffect(Unit) {
            alphaAnim.animateTo(1f, animationSpec = tween(durationMillis = 250))
            scaleAnim.animateTo(1f, animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing))
        }

        Card(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .padding(vertical = 24.dp)
                .heightIn(
                    min = 300.dp,
                    max = LocalConfiguration.current.screenHeightDp.dp * 0.70f
                )
                .graphicsLayer {
                    this.alpha = alphaAnim.value
                    this.scaleX = scaleAnim.value
                    this.scaleY = scaleAnim.value
                },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "系统提示",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "设定 AI 的行为准则和风格",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    if (localPrompt.isNotEmpty()) {
                        IconButton(onClick = {
                            // 清空时同时清空本地状态
                            localPrompt = ""
                            lastExternalPrompt = ""
                            syncJob?.cancel()
                            onClear()
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "清空",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 🎯 性能优化：使用本地状态驱动 TextField
                OutlinedTextField(
                    value = localPrompt,
                    onValueChange = { newPrompt ->
                        // 立即更新本地状态，无延迟
                        localPrompt = newPrompt
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    placeholder = {
                        Text(
                            "例如：你是一个乐于助人的编程专家，请用简洁的代码回答我的问题...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge
                )

                Spacer(Modifier.height(16.dp))

                // 底部按钮区
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 取消按钮
                    OutlinedButton(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF5252)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFFF5252))
                    ) {
                        Text(
                            text = "取消",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    val isDarkTheme = isSystemInDarkTheme()
                    val buttonColor = animateColorAsState(
                        targetValue = if (isEngaged) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            if (isDarkTheme) Color.White else Color.Black
                        },
                        label = "ButtonContainerColor"
                    )
                    
                    val contentColor = animateColorAsState(
                        targetValue = if (isEngaged) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            if (isDarkTheme) Color.Black else Color.White
                        },
                        label = "ButtonContentColor"
                    )

                    // 确定/暂停按钮
                    Button(
                        onClick = {
                            if (!isEngaged) {
                                // 确保在确认前同步本地文本到 ViewModel
                                if (localPrompt != prompt) {
                                    onPromptChange(localPrompt)
                                }
                                syncJob?.cancel()
                                onConfirm()
                                onToggleEngaged()
                                onDismissRequest()
                            } else {
                                onToggleEngaged()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonColor.value,
                            contentColor = contentColor.value
                        )
                    ) {
                        if (isEngaged) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = "暂停",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        } else {
                            Text(
                                text = "确定",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }
    }
}