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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.R
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import com.android.everytalk.ui.components.popup.AppFloatingCardPopup
import com.android.everytalk.ui.screens.MainScreen.chat.models.sortModelConfigs
import kotlin.math.roundToInt

internal data class TopBarModelDisplayInfo(
    val label: String,
    val textColor: Color,
    val gradientColors: List<Color> = emptyList(),
)

private val TopBarDarkButtonColor = Color(0xFF303030)

/**
 * 品牌原色用于白色或深灰胶囊上的小号文字时，可能对比度不足。
 * 从原色逐步向黑/白调整亮度，达到所需对比度（至少 4.5:1）即停止；原本清楚的颜色直接保留。
 * 同时处理渐变的每个色点，避免黄色等浅色在白底上看不清。
 */
private fun readableTopBarBrandColor(
    brandColor: Color,
    isDark: Boolean,
    minimumContrast: Float = 4.5f,
): Color {
    val backgroundLuminance = if (isDark) TopBarDarkButtonColor.luminance() else 1f
    val target = if (isDark) Color.White else Color.Black
    for (step in 0..100) {
        val color = if (step == 0) brandColor else lerp(brandColor, target, step / 100f)
        val luminance = color.luminance()
        val contrast = (maxOf(luminance, backgroundLuminance) + 0.05f) /
            (minOf(luminance, backgroundLuminance) + 0.05f)
        if (contrast >= minimumContrast) return color
    }
    return target
}

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

/** 优先匹配具体模型家族，再匹配厂商品牌；兼容供应商前缀、中文名及图片/视频模型。 */
private val topBarModelPatterns = listOf(
    "Z-Image" to "z[-_ ]?image",
    "Kolors" to "kolors|kwai[-_/ ]?kolors|可图",
    "Seedream" to "seedream|即梦",
    "Seedance" to "seedance",
    "CogView" to "cogview",
    "CogVideo" to "cogvideo",
    "Gemma" to "gemma",
    "Imagen" to "imagen",
    "Veo" to "(?:^|[./\\s_-])veo(?:[\\d./\\s_-]|$)",
    "Sora" to "(?:^|[./\\s_-])sora(?:[\\d./\\s_-]|$)",
    "DALL-E" to "dall[-_ ]?e",
    "FLUX" to "flux",
    "Stable Diffusion" to "stable[-_ ]?diffusion|stable[-_ ]?image|sdxl|(?:^|[./\\s_-])sd[.-]?[35](?:[./\\s_-]|$)",
    "Recraft" to "recraft",
    "Ideogram" to "ideogram",
    "Wan" to "(?:^|[./\\s_-])wan(?:[\\d./\\s_-]|$)|万相",
    "Kling" to "kling|可灵",
    "Hailuo" to "hailuo|海螺",
    "Grok" to "grok|x[-_]?ai",
    "Gemini" to "gemini",
    "GPT" to "gpt|chatgpt|(?:^|[./\\s_-])o[134](?:[./\\s_-]|$)",
    "Claude" to "claude|anthropic|克劳德",
    "DeepSeek" to "deepseek|deep[-_ ]?seek|深度求索",
    "Kimi" to "kimi|moonshot|月之暗面",
    "MiniMax" to "minimax|mini[-_ ]?max",
    "GLM" to "glm|zhipu|智谱",
    "Qwen" to "qwen|qwq|qvq|tongyi|通义|千问",
    "Doubao" to "doubao|豆包|(?:^|[./\\s_-])seed(?:[\\d./\\s_-]|$)",
    "Mistral" to "mistral|mixtral|codestral|devstral|magistral|ministral|pixtral|voxtral",
    "Llama" to "llama",
    "Hunyuan" to "hunyuan|混元",
    "ERNIE" to "ernie|文心",
    "Step" to "stepfun|(?:^|[./\\s_-])step(?:[\\d./\\s_-]|$)|阶跃",
    "Yi" to "(?:^|[./\\s_-])yi(?:[\\d./\\s_-]|$)|零一万物",
    "InternLM" to "internlm|internvl|书生",
    "Baichuan" to "baichuan|百川",
    "Cohere" to "cohere|(?:^|[./\\s_-])command[-_ ]",
    "Jamba" to "jamba|ai21",
    "Nova" to "(?:^|[./\\s_-])nova(?:[\\d./\\s_-]|$)",
    "Titan" to "(?:^|[./\\s_-])titan(?:[\\d./\\s_-]|$)",
    "Phi" to "(?:^|[./\\s_-])phi(?:[\\d./\\s_-]|$)",
    "OLMo" to "olmo",
    "Falcon" to "falcon",
    "DBRX" to "dbrx",
    "MiMo" to "mimo|小米",
    "LongCat" to "longcat",
    "Trinity" to "trinity|arcee",
    "Hermes" to "hermes|nousresearch",
    "Cogito" to "cogito",
    "Sonar" to "sonar|perplexity",
).map { (label, pattern) -> label to Regex(pattern) }

/**
 * 使用真实模型 ID 判断家族，避免“我的模型”等昵称让已支持模型落到“其它”。
 * 未收录的模型仍显示自身名称，只有名称为空时才使用本地化兜底。
 */
internal fun resolveTopBarModelDisplayInfo(
    selectedConfigName: String,
    isDark: Boolean,
    otherLabel: String,
    selectedModelId: String = "",
): TopBarModelDisplayInfo {
    val modelName = selectedModelId.trim().ifBlank { selectedConfigName.trim() }
    val lower = modelName.lowercase()
    val label = topBarModelPatterns.firstOrNull { (_, pattern) -> pattern.containsMatchIn(lower) }?.first
        ?: modelName.substringAfterLast('/').ifBlank { otherLabel }
    // 色值来源：现有图标资源及 LobeHub icons 5.19.3 的品牌 style.js / Color.js。
    // 无单独主色的家族沿用厂商色（如 CogView/CogVideo → 智谱、Seed → 字节），
    // 黑白品牌保留黑白，不为了区分模型而虚构品牌色。
    val brandColor = when (label) {
        "GPT", "Grok", "DALL-E", "FLUX", "Recraft", "Ideogram", "Step", "MiMo", "Hermes" ->
            if (isDark) Color.White else Color.Black
        "Sora" -> Color(0xFF0968DA)
        "Gemini" -> Color(0xFF3186FF)
        "Gemma" -> Color(0xFF2E96FF)
        "Imagen", "Veo" -> Color(0xFF4285F4)
        "Claude" -> Color(0xFFD97757)
        "DeepSeek" -> Color(0xFF4D6BFE)
        "Kimi" -> Color(0xFF1783FF)
        "MiniMax" -> Color(0xFFF23F5D)
        "Hailuo" -> Color(0xFFFF5538)
        "GLM", "CogView", "CogVideo" -> Color(0xFF3859FF)
        "Qwen" -> Color(0xFF615CED)
        "Z-Image", "Wan" -> Color(0xFFFF6003) // 通义系列沿用阿里品牌色。
        "Doubao" -> Color(0xFF1E37FC)
        "Seedream", "Seedance" -> Color(0xFF325AB4)
        "Mistral" -> Color(0xFFFA520F)
        "Llama" -> Color(0xFF1D65C1)
        "Hunyuan" -> Color(0xFF0053E0)
        "ERNIE" -> Color(0xFF2932E1)
        "Yi" -> Color(0xFF003425)
        "InternLM" -> Color(0xFF1B3882)
        "Baichuan" -> Color(0xFFFF6933)
        "Cohere" -> Color(0xFF39594D)
        "Jamba" -> Color(0xFFE91E63)
        "Nova", "Titan" -> Color(0xFF222F3E)
        "Phi" -> Color(0xFF00A4EF)
        "OLMo" -> Color(0xFFF0529C)
        "Falcon" -> Color(0xFF6400FF)
        "DBRX" -> Color(0xFFEE3D2C)
        "LongCat" -> Color(0xFF29E154)
        "Trinity" -> Color(0xFF008C8C)
        "Cogito" -> Color(0xFF4E81EE)
        "Sonar" -> Color(0xFF22B8CD)
        "Stable Diffusion" -> Color(0xFF330066)
        "Kolors" -> Color(0xFF83FF63)
        "Kling" -> Color(0xFF0DF35E)
        else -> Color(0xFF9E9E9E)
    }
    // Gemini 使用本地 Gemini 图标的四色，不再套用 Google 标志的另一组色值。
    val gradientColors = if (label == "Gemini") {
        listOf(Color(0xFF3186FF), Color(0xFFF94543), Color(0xFFFABC12), Color(0xFF08B962))
            // 深色背景上，渐变中间色可能比端点更暗，预留亮度以保证整段文字清楚。
            .map { readableTopBarBrandColor(it, isDark, minimumContrast = if (isDark) 6f else 4.5f) }
    } else emptyList()
    return TopBarModelDisplayInfo(label, readableTopBarBrandColor(brandColor, isDark), gradientColors)
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
    val buttonBg = if (isDark) TopBarDarkButtonColor else Color.White
    val contentColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val topButtonSize = iconButtonSize + 2.dp

    // 模型名称与品牌配色一起缓存，只有模型或主题改变时重新计算。
    val modelDisplayInfo = remember(selectedConfigName, selectedApiConfig?.model, isDark, otherModelLabel) {
        resolveTopBarModelDisplayInfo(selectedConfigName, isDark, otherModelLabel, selectedApiConfig?.model.orEmpty())
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
                        if (modelDisplayInfo.gradientColors.isNotEmpty()) {
                            val geminiGradient = Brush.horizontalGradient(modelDisplayInfo.gradientColors)
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

                    ModelSelectionDropdown(
                        expanded = showModelSelection,
                        models = remember(modelList, selectedApiConfig) { modelList.modelsInSelectedConfig(selectedApiConfig) },
                        selectedApiConfig = selectedApiConfig,
                        onModelSelected = onModelSelected,
                        onModelLongClick = onModelLongClick,
                        onDismiss = onDismissModelSelection
                    )

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
    AppFloatingCardPopup(
        visible = expanded,
        alignment = Alignment.TopEnd,
        offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
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
    expanded: Boolean,
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

    AppFloatingCardPopup(
        visible = expanded,
        alignment = Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
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
