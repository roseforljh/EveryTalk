package com.android.everytalk.ui.screens.MainScreen.chat.dialog

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.ui.components.dialog.AppDialogButtonShape
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogCancelColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SystemPromptDialog(
    prompt: String,
    isEngaged: Boolean = false,
    onToggleEngaged: () -> Unit = {},
    onDismissRequest: () -> Unit,
    onPromptChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onClear: () -> Unit,
) {
    var localPrompt by remember { mutableStateOf(prompt) }
    val coroutineScope = rememberCoroutineScope()
    var syncJob by remember { mutableStateOf<Job?>(null) }
    var lastExternalPrompt by remember { mutableStateOf(prompt) }

    LaunchedEffect(prompt) {
        if (prompt != lastExternalPrompt) {
            lastExternalPrompt = prompt
            localPrompt = prompt
        }
    }

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
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val alphaAnim = remember { Animatable(0f) }
        val scaleAnim = remember { Animatable(0.92f) }
        val screenHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        val dialogBg = appDialogContainerColor()
        val contentColor = appDialogContentColor()
        val subtextColor = appDialogSubtextColor()
        val cancelButtonColor = appDialogCancelColor()
        val dialogHeight = screenHeight * 0.76f

        LaunchedEffect(Unit) {
            alphaAnim.animateTo(1f, animationSpec = tween(durationMillis = 250))
            scaleAnim.animateTo(1f, animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = AppDialogShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .heightIn(max = dialogHeight)
                    .graphicsLayer {
                        alpha = alphaAnim.value
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                    },
                colors = CardDefaults.cardColors(containerColor = dialogBg),
                border = BorderStroke(1.dp, appDialogBorderColor()),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "系统提示",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = contentColor,
                        )
                        Text(
                            text = "设定 AI 的行为准则和回复风格",
                            style = MaterialTheme.typography.bodySmall,
                            color = subtextColor,
                        )
                    }

                    if (localPrompt.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                localPrompt = ""
                                lastExternalPrompt = ""
                                syncJob?.cancel()
                                onClear()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "清空",
                                tint = subtextColor,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = localPrompt,
                    onValueChange = { newPrompt ->
                        localPrompt = newPrompt
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    placeholder = {
                        Text(
                            text = "例如：你是一个乐于助人的编程专家，请用简洁的代码回答我的问题...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = subtextColor.copy(alpha = 0.6f),
                        )
                    },
                    shape = AppDialogTextFieldShape,
                    colors = appDialogTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    minLines = 10,
                    singleLine = false,
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = AppDialogButtonShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = dialogBg,
                            contentColor = cancelButtonColor,
                        ),
                        border = BorderStroke(1.dp, cancelButtonColor),
                    ) {
                        Text(
                            text = "取消",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }

                    val buttonColor by animateColorAsState(
                        targetValue = if (isEngaged) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            contentColor
                        },
                        label = "SystemPromptButtonContainerColor",
                    )
                    val buttonContentColor by animateColorAsState(
                        targetValue = if (isEngaged) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            dialogBg
                        },
                        label = "SystemPromptButtonContentColor",
                    )

                    Button(
                        onClick = {
                            if (!isEngaged) {
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
                        shape = AppDialogButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonColor,
                            contentColor = buttonContentColor,
                        ),
                        border = if (isEngaged) {
                            BorderStroke(1.dp, appDialogBorderColor())
                        } else {
                            null
                        },
                    ) {
                        if (isEngaged) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = "暂停",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        } else {
                            Text(
                                text = "确定",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
        }
    }
}
