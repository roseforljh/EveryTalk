package com.android.everytalk.ui.screens.MainScreen.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMessageDialog(
    editDialogInputText: String,
    onDismissRequest: () -> Unit,
    onEditDialogTextChanged: (String) -> Unit,
    onConfirmMessageEdit: () -> Unit
) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.8f) }

    LaunchedEffect(Unit) {
        launch {
            alpha.animateTo(1f, animationSpec = tween(durationMillis = 300))
        }
        launch {
            scale.animateTo(1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha.value
            this.scaleX = scale.value
            this.scaleY = scale.value
        },
        title = { Text("编辑消息", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            SelectionContainer {
                OutlinedTextField(
                    value = editDialogInputText,
                    onValueChange = onEditDialogTextChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = "消息内容",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = false, maxLines = 5,
                    shape = RoundedCornerShape(32.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmMessageEdit,
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = if (isSystemInDarkTheme()) Color.White else Color.Black
                )
            ) { Text("确定", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Button(
                onClick = onDismissRequest,
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = if (isSystemInDarkTheme()) Color.White else Color.Black
                )
            ) { Text("取消", fontWeight = FontWeight.Bold) }
        },
        shape = RoundedCornerShape(32.dp)
    )
}
@Composable
fun SystemPromptDialog(
    prompt: String,
    onDismissRequest: () -> Unit,
    onPromptChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onClear: (() -> Unit)? = null  // 添加一个可选的onClear参数
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("自定义提示") },
        text = {
            SelectionContainer {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(225.dp),
                    label = { Text("设置系统提示") },
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (onClear != null) {
                    // 如果提供了onClear回调，则调用它
                    onClear()
                } else {
                    // 否则使用默认行为
                    onPromptChange("")
                    onConfirm()
                }
            }) {
                Text("清空")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationParametersDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (temperature: Float, topP: Float, maxTokens: Int?) -> Unit,
    initialTemperature: Float? = null,
    initialTopP: Float? = null,
    initialMaxTokens: Int? = null
) {
    var temperature by remember(initialTemperature) { mutableStateOf(initialTemperature ?: 0.7f) }
    var topP by remember(initialTopP) { mutableStateOf(initialTopP ?: 1.0f) }
    var useCustomMaxTokens by remember(initialMaxTokens) { mutableStateOf(initialMaxTokens != null) }
    var maxTokens by remember(initialMaxTokens) { mutableStateOf(initialMaxTokens?.toString() ?: "64000") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { 
            Text(
                "会话参数", 
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            Column {
                // Temperature Slider
                Text(
                    "Temperature: ${String.format("%.2f", temperature)}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "控制回复的创造性（0=保守，2=创造性）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    modifier = Modifier.padding(vertical = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = Color.Transparent, // 由自定义 track 绘制
                        inactiveTrackColor = Color.Transparent
                    ),
                    track = { _ ->
                        val trackHeight = 8.dp
                        val radius = trackHeight / 2
                        val activeColor = MaterialTheme.colorScheme.primary
                        val inactiveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                        val dir = LocalLayoutDirection.current
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(trackHeight)
                                .clip(RoundedCornerShape(radius))
                                .background(inactiveColor)
                        ) {
                            // valueRange 0f..2f
                            val fraction = ((temperature - 0f) / 2f).coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction)
                                    .align(if (dir == LayoutDirection.Ltr) Alignment.CenterStart else Alignment.CenterEnd)
                                    .clip(RoundedCornerShape(radius))
                                    .background(activeColor)
                            )
                        }
                    },
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Top-p Slider
                Text(
                    "Top-p: ${String.format("%.2f", topP)}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "控制采样概率（0.1=严格，1=多样）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Slider(
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0.1f..1f,
                    modifier = Modifier.padding(vertical = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = Color.Transparent, // 由自定义 track 绘制
                        inactiveTrackColor = Color.Transparent
                    ),
                    track = { _ ->
                        val trackHeight = 8.dp
                        val radius = trackHeight / 2
                        val activeColor = MaterialTheme.colorScheme.primary
                        val inactiveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                        val dir = LocalLayoutDirection.current
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(trackHeight)
                                .clip(RoundedCornerShape(radius))
                                .background(inactiveColor)
                        ) {
                            // valueRange 0.1f..1f
                            val fraction = ((topP - 0.1f) / (1f - 0.1f)).coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction)
                                    .align(if (dir == LayoutDirection.Ltr) Alignment.CenterStart else Alignment.CenterEnd)
                                    .clip(RoundedCornerShape(radius))
                                    .background(activeColor)
                            )
                        }
                    },
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Max Tokens Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "Max Tokens",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (useCustomMaxTokens) "自定义最大输出长度" else "使用模型默认值",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = useCustomMaxTokens,
                        onCheckedChange = { useCustomMaxTokens = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
                
                // Show input field only when switch is enabled
                if (useCustomMaxTokens) {
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { newValue ->
                            // Only allow numeric input
                            if (newValue.all { it.isDigit() } || newValue.isEmpty()) {
                                maxTokens = newValue
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        label = { Text("最大令牌数") },
                        placeholder = { Text("例如: 64000") },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val maxTokensValue = if (useCustomMaxTokens) {
                        maxTokens.toIntOrNull() ?: 64000
                    } else {
                        null
                    }
                    onConfirm(temperature, topP, maxTokensValue)
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) { 
                Text("应用") 
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) { 
                Text("取消") 
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp)
    )
}