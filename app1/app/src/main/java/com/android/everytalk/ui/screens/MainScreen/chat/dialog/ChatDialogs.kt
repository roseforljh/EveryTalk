package com.android.everytalk.ui.screens.MainScreen.chat.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.config.PerformanceConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMessageDialog(
    editDialogInputText: String,
    onDismissRequest: () -> Unit,
    onEditDialogTextChanged: (String) -> Unit,
    onConfirmMessageEdit: () -> Unit
) {
    // 🎯 性能优化：使用本地状态管理输入文本，避免每次按键都触发 ViewModel 更新
    // 这解决了"同一个用户气泡内的文本被编辑多次后无法输入"的问题
    var localText by remember { mutableStateOf(editDialogInputText) }
    val coroutineScope = rememberCoroutineScope()
    var syncJob by remember { mutableStateOf<Job?>(null) }
    var lastExternalText by remember { mutableStateOf(editDialogInputText) }
    
    // 当外部 editDialogInputText 变化时（如首次打开对话框），同步到本地状态
    LaunchedEffect(editDialogInputText) {
        if (editDialogInputText != lastExternalText) {
            lastExternalText = editDialogInputText
            localText = editDialogInputText
        }
    }
    
    // 防抖同步到 ViewModel
    LaunchedEffect(localText) {
        syncJob?.cancel()
        syncJob = coroutineScope.launch {
            delay(PerformanceConfig.STATE_DEBOUNCE_DELAY_MS)
            if (localText != editDialogInputText) {
                onEditDialogTextChanged(localText)
                lastExternalText = localText
            }
        }
    }
    
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.8f) }
    val isDarkTheme = isSystemInDarkTheme()
    val cancelButtonColor = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
    val confirmButtonColor = if (isDarkTheme) Color.White else Color(0xFF212121)
    val confirmButtonTextColor = if (isDarkTheme) Color.Black else Color.White

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
                Column {
                    Text(
                        text = "消息内容",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    // 🎯 性能优化：使用本地状态驱动 TextField
                    OutlinedTextField(
                        value = localText,
                        onValueChange = { newText ->
                            // 立即更新本地状态，无延迟
                            localText = newText
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("请输入消息内容") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            cursorColor = MaterialTheme.colorScheme.primary,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        singleLine = false,
                        maxLines = 5,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // 确认前确保同步本地文本到 ViewModel
                    if (localText != editDialogInputText) {
                        onEditDialogTextChanged(localText)
                    }
                    syncJob?.cancel()
                    onConfirmMessageEdit()
                },
                modifier = Modifier
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = confirmButtonColor,
                    contentColor = confirmButtonTextColor
                )
            ) {
                Text(
                    "确定",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = cancelButtonColor
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, cancelButtonColor)
            ) {
                Text(
                    "取消",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        },
        shape = RoundedCornerShape(28.dp)
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

    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val dialogHeight = screenHeight * 0.67f

    AlertDialog(
        modifier = Modifier.height(dialogHeight),
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                "会话参数",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                AnimatedVisibility(
                    visible = useCustomMaxTokens,
                    enter = expandVertically(
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    ) + fadeIn(
                        // 完全等待高度展开结束后再淡入内容，避免 Window Resize 时的抖动
                        animationSpec = tween(durationMillis = 200, delayMillis = 300)
                    ),
                    exit = fadeOut(
                        animationSpec = tween(durationMillis = 200)
                    ) + shrinkVertically(
                        // 等待淡出完全结束后再收缩高度
                        animationSpec = tween(durationMillis = 300, delayMillis = 200, easing = FastOutSlowInEasing)
                    )
                ) {
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