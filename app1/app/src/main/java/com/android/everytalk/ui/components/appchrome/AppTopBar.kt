package com.android.everytalk.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppTopBar(
    selectedConfigName: String,
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTitleClick: () -> Unit,
    onSystemPromptClick: () -> Unit,
    systemPrompt: String,
    isSystemPromptExpanded: Boolean,
    isSystemPromptEngaged: Boolean = false,
    onToggleSystemPromptEngaged: () -> Unit = {},
    hasContent: Boolean = false,
    onNewChat: () -> Unit = {},
    onShareChat: () -> Unit = {},
    onPinChat: () -> Unit = {},
    onDeleteChat: () -> Unit = {},
    showModelSelection: Boolean = false,
    modelList: List<com.android.everytalk.data.DataClass.ApiConfig> = emptyList(),
    selectedApiConfig: com.android.everytalk.data.DataClass.ApiConfig? = null,
    onModelSelected: (com.android.everytalk.data.DataClass.ApiConfig) -> Unit = {},
    onDismissModelSelection: () -> Unit = {},
    onTitleLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    barHeight: Dp = 85.dp,
    contentPaddingHorizontal: Dp = 12.dp,
    bottomAlignPadding: Dp = 12.dp,
    titleFontSize: TextUnit = 16.sp,
    iconButtonSize: Dp = 44.dp,
    iconSize: Dp = 24.dp
) {
    val isDark = isSystemInDarkTheme()
    val buttonBg = if (isDark) Color(0xFF303030) else Color(0xFFEDEDED)
    val borderColor = if (isDark) Color(0xFF414141) else Color(0xFFF3F3F3)
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val modelTextColor = if (isDark) Color(0xFF6EB5FF) else Color(0xFF3B82F6)

    // 模型名称提取与彩虹色映射
    val modelDisplayInfo = remember(selectedConfigName) {
        val lower = selectedConfigName.lowercase()
        when {
            lower.contains("gemini") -> "Gemini" to Color(0xFFFF6B6B)       // 红
            lower.contains("gpt") -> "GPT" to Color(0xFFFFAB5E)             // 橙
            lower.contains("claude") -> "Claude" to Color(0xFFFFE66B)       // 黄
            lower.contains("deepseek") -> "DeepSeek" to Color(0xFF5EE6A0)   // 绿
            lower.contains("kimi") -> "Kimi" to Color(0xFF5ED8E6)           // 青
            lower.contains("minimax") -> "MiniMax" to Color(0xFF66B5FF)     // 蓝
            lower.contains("glm") -> "GLM" to Color(0xFFB388FF)            // 紫
            else -> "Other" to Color.White
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 48.dp)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 菜单按钮 - 圆形
                Box(
                    modifier = Modifier
                        .size(iconButtonSize)
                        .clip(CircleShape)
                        .background(buttonBg)
                        .border(1.dp, borderColor, CircleShape)
                        .clickable(onClick = onMenuClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu),
                        contentDescription = "菜单",
                        tint = contentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 模型选择器 - 胶囊，固定最大宽度
                Box {
                    @OptIn(ExperimentalFoundationApi::class)
                    Box(
                        modifier = Modifier
                            .height(iconButtonSize)
                            .widthIn(max = 130.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(buttonBg)
                            .border(1.dp, borderColor, RoundedCornerShape(percent = 50))
                            .combinedClickable(
                                onClick = onTitleClick,
                                onLongClick = onTitleLongClick
                            )
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = modelDisplayInfo.first,
                            color = modelDisplayInfo.second,
                            fontSize = titleFontSize,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (showModelSelection) {
                        ModelSelectionDropdown(
                            models = modelList,
                            selectedApiConfig = selectedApiConfig,
                            onModelSelected = onModelSelected,
                            onDismiss = onDismissModelSelection
                        )
                    }
                }
            }

            // 右侧
            if (hasContent) {
                var showMoreMenu by remember { mutableStateOf(false) }
                var showDeleteDialog by remember { mutableStateOf(false) }

                Box {
                    Row(
                        modifier = Modifier
                            .height(iconButtonSize)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(buttonBg)
                            .border(1.dp, borderColor, RoundedCornerShape(percent = 50)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(iconButtonSize)
                                .clickable(onClick = onNewChat),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_compose),
                                contentDescription = "新对话",
                                tint = contentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(iconButtonSize)
                                .clickable(onClick = { showMoreMenu = true }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_dots_vertical),
                                contentDescription = "更多",
                                tint = contentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    TopBarMoreMenu(
                        expanded = showMoreMenu,
                        onDismiss = { showMoreMenu = false },
                        onShare = { onShareChat(); showMoreMenu = false },
                        onPin = { onPinChat(); showMoreMenu = false },
                        onSettings = { onSettingsClick(); showMoreMenu = false },
                        onDelete = { showDeleteDialog = true; showMoreMenu = false }
                    )
                }

                if (showDeleteDialog) {
                    DeleteChatDialog(
                        onConfirm = { onDeleteChat(); showDeleteDialog = false },
                        onDismiss = { showDeleteDialog = false }
                    )
                }
            } else {
                // 空会话: 圆形设置按钮
                Box(
                    modifier = Modifier
                        .size(iconButtonSize)
                        .clip(CircleShape)
                        .background(buttonBg)
                        .border(1.dp, borderColor, CircleShape)
                        .clickable(onClick = onSettingsClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = "设置",
                        tint = contentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteChatDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF424242) else Color(0xFFFFFFFF)
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val subtextColor = if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF0D0D0D).copy(alpha = 0.7f)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = cardBg,
        title = {
            Text(
                text = "删除聊天",
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Text(
                text = "此操作无法撤销。请前往设置，删除在此次聊天中为你保存的任何记忆。",
                color = subtextColor,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = textColor)
            }
        }
    )
}

@Composable
private fun TopBarMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onPin: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit,
) {
    var showPopup by remember { mutableStateOf(false) }
    val scaleAnim = remember { Animatable(0.8f) }
    val alphaAnim = remember { Animatable(0f) }

    val emphasizedDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val decelerateEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    LaunchedEffect(expanded) {
        if (expanded) {
            showPopup = true
            scaleAnim.snapTo(0.8f)
            alphaAnim.snapTo(0f)
            coroutineScope {
                launch { scaleAnim.animateTo(1f, tween(120, easing = emphasizedDecelerate)) }
                launch { alphaAnim.animateTo(1f, tween(30, easing = decelerateEasing)) }
            }
        } else if (showPopup) {
            coroutineScope {
                launch { alphaAnim.animateTo(0f, tween(75, easing = decelerateEasing)) }
                launch { delay(74); scaleAnim.snapTo(0.8f) }
            }
            showPopup = false
        }
    }

    if (!showPopup) return

    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val popupBorderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)

    Popup(
        alignment = Alignment.TopEnd,
        offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(min = 200.dp)
                .graphicsLayer {
                    this.scaleX = scaleAnim.value
                    this.scaleY = scaleAnim.value
                    this.alpha = alphaAnim.value
                    this.transformOrigin = TransformOrigin(1f, 0f)
                }
                .shadow(8.dp, RoundedCornerShape(28.dp))
                .border(1.dp, popupBorderColor, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            color = cardBg
        ) {
            val textColor = MaterialTheme.colorScheme.onSurface
            val deleteColor = Color(0xFFEF5350)

            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 12.dp)
            ) {
                TopBarMenuItem(iconRes = R.drawable.ic_share, text = "分享", tint = textColor, onClick = onShare)
                TopBarMenuItem(iconRes = R.drawable.ic_pin, text = "置顶", tint = textColor, onClick = onPin)
                TopBarMenuItem(iconRes = R.drawable.ic_settings, text = "设置", tint = textColor, onClick = onSettings)
                TopBarMenuItem(iconRes = R.drawable.ic_trash, text = "删除", tint = deleteColor, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun TopBarMenuItem(
    iconRes: Int,
    text: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            color = tint,
            maxLines = 1
        )
    }
}

@Composable
private fun ModelSelectionDropdown(
    models: List<com.android.everytalk.data.DataClass.ApiConfig>,
    selectedApiConfig: com.android.everytalk.data.DataClass.ApiConfig?,
    onModelSelected: (com.android.everytalk.data.DataClass.ApiConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val popupBorderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)

    Popup(
        alignment = Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 200.dp, max = 280.dp)
                .heightIn(max = 320.dp)
                .shadow(8.dp, RoundedCornerShape(20.dp))
                .border(1.dp, popupBorderColor, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = cardBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                models.forEach { modelConfig ->
                    val isSelected = modelConfig.id == selectedApiConfig?.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModelSelected(modelConfig) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = modelConfig.name.ifEmpty { modelConfig.model },
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                color = if (isSelected) Color(0xFF66B5FF) else textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (modelConfig.name.isNotEmpty() && modelConfig.model.isNotEmpty() && modelConfig.name != modelConfig.model) {
                                Text(
                                    text = modelConfig.model,
                                    fontSize = 11.sp,
                                    color = if (isDark) Color(0xFF888888) else Color(0xFF999999),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (isSelected) {
                            androidx.compose.material3.Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = Color(0xFF66B5FF),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
