package com.android.everytalk.ui.components
import com.android.everytalk.statecontroller.*

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import com.android.everytalk.ui.screens.MainScreen.chat.models.sortModelConfigs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class TopBarModelDisplayInfo(
    val label: String,
    val textColor: Color
)

internal fun resolveTopBarModelDisplayInfo(
    selectedConfigName: String,
    isDark: Boolean
): TopBarModelDisplayInfo {
    val lower = selectedConfigName.lowercase()
    return when {
        lower.contains("grok") -> TopBarModelDisplayInfo(
            label = "Grok",
            textColor = if (isDark) Color.White else Color.Black
        )
        lower.contains("gemini") -> TopBarModelDisplayInfo("Gemini", Color(0xFF10B981))
        lower.contains("gpt") -> TopBarModelDisplayInfo(
            label = "GPT",
            textColor = if (isDark) Color.White else Color.Black
        )
        lower.contains("claude") -> TopBarModelDisplayInfo("Claude", Color(0xFFF97316))
        lower.contains("deepseek") -> TopBarModelDisplayInfo("DeepSeek", Color(0xFF3B82F6))
        lower.contains("kimi") -> TopBarModelDisplayInfo("Kimi", Color(0xFF06B6D4))
        lower.contains("minimax") -> TopBarModelDisplayInfo("MiniMax", Color(0xFFEF4444))
        lower.contains("glm") -> TopBarModelDisplayInfo("GLM", Color(0xFF8B5CF6))
        else -> TopBarModelDisplayInfo("Other", Color(0xFF9E9E9E))
    }
}

@Composable
fun AppTopBar(
    selectedConfigName: String,
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTitleClick: () -> Unit,
    onSystemPromptClick: () -> Unit,
    systemPrompt: String,
    isSystemPromptExpanded: Boolean,
    modifier: Modifier = Modifier,
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
    allApiConfigs: List<com.android.everytalk.data.DataClass.ApiConfig> = emptyList(),
    onConfigModelSelected: (com.android.everytalk.data.DataClass.ApiConfig) -> Unit = {},
    onControlsBottomChange: (Int) -> Unit = {},
    barHeight: Dp = 85.dp,
    contentPaddingHorizontal: Dp = 12.dp,
    bottomAlignPadding: Dp = 12.dp,
    titleFontSize: TextUnit = 16.sp,
    iconButtonSize: Dp = 44.dp,
    iconSize: Dp = 24.dp
) {
    val isDark = isSystemInDarkTheme()
    val buttonBg = if (isDark) Color(0xFF303030) else Color.White
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val topButtonSize = iconButtonSize + 2.dp

    // 模型名称提取与彩虹色映射
    val modelDisplayInfo = remember(selectedConfigName, isDark) {
        resolveTopBarModelDisplayInfo(selectedConfigName, isDark)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 48.dp)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    val bottom = coordinates.positionInWindow().y + coordinates.size.height
                    if (bottom.isFinite() && bottom >= 0f) {
                        onControlsBottomChange(bottom.roundToInt())
                    }
                },
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
                        .size(topButtonSize)
                        .shadow(3.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(buttonBg)
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
                    var showConfigSwitch by remember { mutableStateOf(false) }
                    @OptIn(ExperimentalFoundationApi::class)
                    Box(
                        modifier = Modifier
                            .height(topButtonSize)
                            .widthIn(max = 130.dp)
                            .shadow(3.dp, RoundedCornerShape(percent = 50), clip = false)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(buttonBg)
                            .combinedClickable(
                                onClick = onTitleClick,
                                onLongClick = {
                                    if (allApiConfigs.isNotEmpty()) {
                                        showConfigSwitch = true
                                    } else {
                                        onTitleLongClick()
                                    }
                                }
                            )
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (modelDisplayInfo.label == "Gemini") {
                            val geminiGradient = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFEA4335),
                                    Color(0xFFFBBC05),
                                    Color(0xFF34A853),
                                    Color(0xFF4285F4),
                                )
                            )
                            Text(
                                text = modelDisplayInfo.label,
                                style = TextStyle(
                                    brush = geminiGradient,
                                    fontSize = titleFontSize,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = modelDisplayInfo.label,
                                color = modelDisplayInfo.textColor,
                                fontSize = titleFontSize,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (showModelSelection) {
                        ModelSelectionDropdown(
                            models = modelList,
                            selectedApiConfig = selectedApiConfig,
                            onModelSelected = onModelSelected,
                            onDismiss = onDismissModelSelection
                        )
                    }

                    ConfigSwitchPopup(
                        visible = showConfigSwitch,
                        allConfigs = allApiConfigs,
                        selectedApiConfig = selectedApiConfig,
                        onModelSelected = { config ->
                            onConfigModelSelected(config)
                        },
                        onDismiss = { showConfigSwitch = false }
                    )
                }
            }

            // 右侧
            if (hasContent) {
                var showMoreMenu by remember { mutableStateOf(false) }
                var showDeleteDialog by remember { mutableStateOf(false) }

                Box {
                    Row(
                        modifier = Modifier
                            .height(topButtonSize)
                            .shadow(3.dp, RoundedCornerShape(percent = 50), clip = false)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(buttonBg),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(topButtonSize)
                                .clip(CircleShape)
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
                                .size(topButtonSize)
                                .clip(CircleShape)
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
                        .size(topButtonSize)
                        .shadow(3.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(buttonBg)
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
    val cardBg = appDialogContainerColor()
    val textColor = appDialogContentColor()
    val subtextColor = appDialogSubtextColor()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
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
    val sortedModels = remember(models) { sortModelConfigs(models) }

    val scaleAnim = remember { Animatable(0.8f) }
    val alphaAnim = remember { Animatable(0f) }
    val emphasizedDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val decelerateEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    LaunchedEffect(Unit) {
        launch { scaleAnim.animateTo(1f, tween(120, easing = emphasizedDecelerate)) }
        launch { alphaAnim.animateTo(1f, tween(30, easing = decelerateEasing)) }
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .widthIn(max = 280.dp)
                .heightIn(max = 320.dp)
                .graphicsLayer {
                    this.scaleX = scaleAnim.value
                    this.scaleY = scaleAnim.value
                    this.alpha = alphaAnim.value
                    this.transformOrigin = TransformOrigin(0.2f, 0f)
                }
                .shadow(8.dp, RoundedCornerShape(20.dp))
                .border(1.dp, popupBorderColor, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = cardBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                sortedModels.forEach { modelConfig ->
                    val isSelected = modelConfig.id == selectedApiConfig?.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModelSelected(modelConfig) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = modelConfig.name.ifEmpty { modelConfig.model },
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
