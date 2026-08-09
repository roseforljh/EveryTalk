package com.android.everytalk.ui.components
import com.android.everytalk.statecontroller.*

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.android.everytalk.ui.components.popup.AppFloatingCard
import com.android.everytalk.ui.screens.MainScreen.chat.models.sortModelConfigs
import kotlin.math.roundToInt

internal data class TopBarModelDisplayInfo(
    val label: String,
    val textColor: Color
)

private const val MODEL_SELECTION_SURROUNDING_ITEM_COUNT = 3
private const val MODEL_SELECTION_VISIBLE_ITEM_COUNT = MODEL_SELECTION_SURROUNDING_ITEM_COUNT * 2 + 1
private val ModelSelectionItemHeight = 40.dp
private val ModelSelectionVerticalPadding = 8.dp

internal fun modelSelectionInitialFirstVisibleIndex(
    modelCount: Int,
    selectedIndex: Int,
): Int {
    if (modelCount <= MODEL_SELECTION_VISIBLE_ITEM_COUNT || selectedIndex !in 0 until modelCount) {
        return 0
    }

    return (selectedIndex - MODEL_SELECTION_SURROUNDING_ITEM_COUNT)
        .coerceIn(0, modelCount - MODEL_SELECTION_VISIBLE_ITEM_COUNT)
}

internal fun resolveTopBarModelDisplayInfo(
    selectedConfigName: String,
    isDark: Boolean,
    otherLabel: String,
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
        else -> TopBarModelDisplayInfo(otherLabel, Color(0xFF9E9E9E))
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
    onModelLongClick: ((com.android.everytalk.data.DataClass.ApiConfig) -> Unit)? = null,
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
    val otherModelLabel = stringResource(R.string.top_bar_model_other)
    val buttonBg = if (isDark) Color(0xFF303030) else Color.White
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val topButtonSize = iconButtonSize + 2.dp

    // 模型名称提取与彩虹色映射
    val modelDisplayInfo = remember(selectedConfigName, isDark, otherModelLabel) {
        resolveTopBarModelDisplayInfo(selectedConfigName, isDark, otherModelLabel)
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
                        contentDescription = stringResource(R.string.navigation_menu),
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
                                onClickLabel = stringResource(R.string.model_select),
                                onLongClickLabel = stringResource(R.string.configuration_switch),
                                onClick = onTitleClick,
                                onLongClick = {
                                    if (allApiConfigs.isNotEmpty()) {
                                        showConfigSwitch = true
                                    } else {
                                        onTitleLongClick()
                                    }
                                },
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
                            onModelLongClick = onModelLongClick,
                            onDismiss = onDismissModelSelection
                        )
                    }

                    ConfigSwitchPopup(
                        visible = showConfigSwitch,
                        allConfigs = allApiConfigs,
                        selectedApiConfig = selectedApiConfig,
                        onModelSelected = onConfigModelSelected,
                        onDismiss = { showConfigSwitch = false },
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
                                contentDescription = stringResource(R.string.chat_new),
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
                                contentDescription = stringResource(R.string.action_more),
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
                        contentDescription = stringResource(R.string.settings_title),
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
                text = stringResource(R.string.chat_delete_title),
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Text(
                text = stringResource(R.string.chat_delete_description),
                color = subtextColor,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = textColor)
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
    if (!expanded) return

    Popup(
        alignment = Alignment.TopEnd,
        offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        AppFloatingCard(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(min = 200.dp),
        ) {
            val textColor = MaterialTheme.colorScheme.onSurface
            val deleteColor = Color(0xFFEF5350)

            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 12.dp)
            ) {
                TopBarMenuItem(iconRes = R.drawable.ic_share, text = stringResource(R.string.action_share), tint = textColor, onClick = onShare)
                TopBarMenuItem(iconRes = R.drawable.ic_pin, text = stringResource(R.string.action_pin), tint = textColor, onClick = onPin)
                TopBarMenuItem(iconRes = R.drawable.ic_settings, text = stringResource(R.string.settings_title), tint = textColor, onClick = onSettings)
                TopBarMenuItem(iconRes = R.drawable.ic_trash, text = stringResource(R.string.action_delete), tint = deleteColor, onClick = onDelete)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelSelectionDropdown(
    models: List<com.android.everytalk.data.DataClass.ApiConfig>,
    selectedApiConfig: com.android.everytalk.data.DataClass.ApiConfig?,
    onModelSelected: (com.android.everytalk.data.DataClass.ApiConfig) -> Unit,
    onModelLongClick: ((com.android.everytalk.data.DataClass.ApiConfig) -> Unit)?,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val sortedModels = remember(models) { sortModelConfigs(models) }
    val selectedIndex = remember(sortedModels, selectedApiConfig?.id) {
        sortedModels.indexOfFirst { it.id == selectedApiConfig?.id }
    }
    val initialFirstVisibleIndex = modelSelectionInitialFirstVisibleIndex(
        modelCount = sortedModels.size,
        selectedIndex = selectedIndex,
    )
    val initialScrollOffset = with(androidx.compose.ui.platform.LocalDensity.current) {
        (ModelSelectionItemHeight * initialFirstVisibleIndex).roundToPx()
    }
    val scrollState = key(sortedModels, selectedApiConfig?.id, initialScrollOffset) {
        rememberScrollState(initial = initialScrollOffset)
    }
    val popupMaxHeight = ModelSelectionVerticalPadding * 2 +
        ModelSelectionItemHeight * sortedModels.size.coerceAtMost(MODEL_SELECTION_VISIBLE_ITEM_COUNT)

    Popup(
        alignment = Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        AppFloatingCard(
            modifier = Modifier
                .testTag("model_selection_dropdown")
                .width(IntrinsicSize.Max)
                .widthIn(max = 280.dp)
                .heightIn(max = popupMaxHeight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(vertical = ModelSelectionVerticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                sortedModels.forEach { modelConfig ->
                    val isSelected = modelConfig.id == selectedApiConfig?.id
                    val modelInteractionModifier = if (onModelLongClick != null) {
                        Modifier.combinedClickable(
                            onClickLabel = stringResource(R.string.model_select),
                            onLongClickLabel = stringResource(R.string.model_parameters_open),
                            onClick = { onModelSelected(modelConfig) },
                            onLongClick = { onModelLongClick(modelConfig) },
                        )
                    } else {
                        Modifier.clickable { onModelSelected(modelConfig) }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(modelInteractionModifier)
                            .height(ModelSelectionItemHeight)
                            .padding(horizontal = 14.dp),
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
