package com.android.everytalk.ui.screens.settings
import com.android.everytalk.statecontroller.*

import android.util.Log
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.android.everytalk.R
import com.android.everytalk.ui.components.dialog.appDialogTextFieldDefaultBorderColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldBorderColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.network.ExternalWebSearchProvider

@Composable
internal fun ConfirmDeleteDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    text: String
) {
    val isDarkTheme = isSystemInDarkTheme()
    val dialogBg = if (isDarkTheme) Color.Black else Color.White
    val borderColor = if (isDarkTheme) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)
    AlertDialog(
        modifier = Modifier
            .wrapContentHeight()
            .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(28.dp),
        containerColor = dialogBg,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = {
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.8f)
            )
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = contentColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                ) {
                    Text("取消", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        onConfirm()
                        onDismissRequest()
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350),
                        contentColor = Color.White
                    )
                ) {
                    Text("删除", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
internal fun ImportExportDialog(
    onDismissRequest: () -> Unit,
    onExport: (includeHistory: Boolean) -> Unit,
    onImport: () -> Unit,
    isExportEnabled: Boolean,
    chatHistoryCount: Int,
    imageHistoryCount: Int
) {
    val isDarkTheme = isSystemInDarkTheme()
    val dialogBg = if (isDarkTheme) Color.Black else Color.White
    val borderColor = if (isDarkTheme) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDarkTheme) Color.White.copy(alpha = 0.6f) else Color(0xFF0D0D0D).copy(alpha = 0.6f)
    var includeHistory by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = Modifier
            .wrapContentHeight()
            .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(28.dp),
        containerColor = dialogBg,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = {
            Text(
                "导入 / 导出",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (chatHistoryCount > 0 || imageHistoryCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                            .clickable { includeHistory = !includeHistory }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (includeHistory) contentColor else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (includeHistory) contentColor else borderColor,
                                    RoundedCornerShape(7.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (includeHistory) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = dialogBg,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "包含聊天历史",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = contentColor
                            )
                            Text(
                                "文本: $chatHistoryCount 个会话, 图像: $imageHistoryCount 个会话",
                                style = MaterialTheme.typography.bodySmall,
                                color = subtextColor
                            )
                        }
                    }
                }

                Text(
                    "导出文件包含API密钥等敏感信息，请妥善保管",
                    style = MaterialTheme.typography.bodySmall,
                    color = subtextColor
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = onImport,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = contentColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                ) {
                    Text("导入", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { onExport(includeHistory) },
                    enabled = isExportEnabled,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = contentColor,
                        contentColor = dialogBg,
                        disabledContainerColor = borderColor,
                        disabledContentColor = subtextColor
                    )
                ) {
                    Text("导出", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddModelDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var modelName by remember { mutableStateOf("") }

    val isDarkTheme = isSystemInDarkTheme()
    val dialogBg = if (isDarkTheme) Color.Black else Color.White; val borderColor = if (isDarkTheme) Color(0xFF414141) else Color(0xFFF3F3F3); val contentColor = if (isDarkTheme) Color.White else Color(0xFF0D0D0D)

    AlertDialog(
        modifier = Modifier
            .wrapContentHeight()
            .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(28.dp),
        containerColor = dialogBg,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = {
            Text(
                "添加新模型",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsFieldLabel("模型名称")
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    placeholder = { Text("例如: gpt-4-turbo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (modelName.isNotBlank()) onConfirm(modelName) }),
                    shape = DialogShape,
                    colors = DialogTextFieldColors
                )
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = contentColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
                ) {
                    Text("取消", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { onConfirm(modelName) },
                    enabled = modelName.isNotBlank(),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = contentColor,
                        contentColor = dialogBg,
                        disabledContainerColor = borderColor,
                        disabledContentColor = contentColor.copy(alpha = 0.4f)
                    )
                ) {
                    Text("添加", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {}
    )
}
