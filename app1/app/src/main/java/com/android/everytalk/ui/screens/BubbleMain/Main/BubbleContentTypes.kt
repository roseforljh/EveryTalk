package com.android.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.togetherWith
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import com.android.everytalk.statecontroller.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.ProportionalAsyncImage
import com.android.everytalk.ui.components.ImagePreviewDialog
import com.android.everytalk.ui.components.popup.AppFloatingCardPopup
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownRenderer
import com.android.everytalk.ui.components.streaming.contentVersionForRendering

private val CONTEXT_MENU_ITEM_ICON_SIZE = 22.dp

internal fun attachmentStripHorizontalAlignment(sender: Sender): Alignment.Horizontal =
    if (sender == Sender.User) Alignment.End else Alignment.Start

internal data class AttachmentThumbnailSize(
    val widthDp: Float,
    val heightDp: Float,
)

/**
 * 按原图比例计算缩略图尺寸，最长不超过 maxWidthDp × maxHeightDp。
 * 长截图或超宽全景图等比缩放后会变成细条，所以短边至少保留 minSideDp；
 * 超出比例的部分由调用方用 Crop + 顶部对齐裁掉，缩略图显示长图开头。
 */
internal fun fitAttachmentThumbnail(
    sourceWidth: Float,
    sourceHeight: Float,
    maxWidthDp: Float = 160f,
    maxHeightDp: Float = 100f,
    minSideDp: Float = 56f,
): AttachmentThumbnailSize {
    if (
        !sourceWidth.isFinite() ||
        !sourceHeight.isFinite() ||
        sourceWidth <= 0f ||
        sourceHeight <= 0f
    ) {
        return AttachmentThumbnailSize(maxHeightDp, maxHeightDp)
    }
    val scale = minOf(maxWidthDp / sourceWidth, maxHeightDp / sourceHeight)
    return AttachmentThumbnailSize(
        widthDp = (sourceWidth * scale).coerceIn(minSideDp, maxWidthDp),
        heightDp = (sourceHeight * scale).coerceIn(minSideDp, maxHeightDp),
    )
}

internal fun attachmentImageBorderColor(onSurface: Color): Color =
    onSurface.copy(alpha = 0.45f)

internal const val USER_BUBBLE_COLLAPSED_MAX_HEIGHT_RATIO = 0.32f
internal const val USER_BUBBLE_EXPANDED_MAX_HEIGHT_RATIO = 0.56f
internal val USER_BUBBLE_TEXT_OPTICAL_OFFSET = (-8).dp

internal fun resolveUserBubbleMaxHeightDp(
    screenHeightDp: Float,
    isExpanded: Boolean,
): Float = screenHeightDp * if (isExpanded) {
    USER_BUBBLE_EXPANDED_MAX_HEIGHT_RATIO
} else {
    USER_BUBBLE_COLLAPSED_MAX_HEIGHT_RATIO
}

internal fun shouldConstrainUserBubbleHeight(
    sender: Sender,
    hasOverflow: Boolean,
    isExpanded: Boolean,
): Boolean = sender == Sender.User && (!isExpanded || hasOverflow)

internal fun resolveUserBubbleContentBottomPaddingDp(
    sender: Sender,
    isExpanded: Boolean,
): Float = if (sender == Sender.User && isExpanded) USER_BUBBLE_TOGGLE_RESERVED_DP else 0f

/** 展开态底部给“收起”胶囊留的空间：胶囊 28dp + 底边距 8dp + 4dp 呼吸。 */
internal const val USER_BUBBLE_TOGGLE_RESERVED_DP = 40f
private val UserBubbleFadeHeight = 88.dp
private val UserBubbleToggleEasing = FastOutSlowInEasing

/**
 * 底部渐隐遮罩：直接降低内容自身透明度，露出气泡底色。
 * 旧做法是在文字上盖一块同色渐变，过渡只有 38dp 且 80% 以上不透明，看起来像一块白板。
 */
private fun Modifier.userBubbleBottomFade(strength: () -> Float, fadeHeight: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val k = strength()
        if (k <= 0f) return@drawWithContent
        val h = fadeHeight.toPx().coerceAtMost(size.height)
        val top = size.height - h
        // 缓入缓出的透明度曲线，避免线性渐变在中段出现明显分界。
        fun a(v: Float) = Color.Black.copy(alpha = 1f - k * (1f - v))
        drawRect(
            brush = Brush.verticalGradient(
                0f to a(1f),
                0.3f to a(0.86f),
                0.6f to a(0.45f),
                0.85f to a(0.1f),
                1f to a(0f),
                startY = top,
                endY = size.height,
            ),
            topLeft = Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, h),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }


@Composable
internal fun UserOrErrorMessageContent(
    message: Message,
    displayedText: String,
    showLoadingDots: Boolean,
    bubbleColor: Color,
    contentColor: Color,
    isError: Boolean,
    maxWidth: Dp,
    onLongPress: (Message, Offset) -> Unit,
    modifier: Modifier = Modifier,
    scrollStateManager: com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager
) {
    val haptic = LocalHapticFeedback.current
    val currentMessage by rememberUpdatedState(message)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    var globalPosition by remember { mutableStateOf(Offset.Zero) }
    val renderText = displayedText.ifBlank { message.text }
    val preparedMessage = remember(message.id, renderText) {
        StreamBlockParser.prepareMessage(
            content = renderText,
            messageId = message.id,
            contentVersion = contentVersionForRendering(renderText),
        )
    }

    // 基于发送者动态计算最大宽度：用户71%，AI80%
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val screenDp = with(density) { windowSize.width.toDp() }
    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 0.dp,
        bottomStart = 18.dp,
        bottomEnd = 18.dp
    )
    
    // 用户气泡最大约 71% 屏宽，AI 气泡放宽到约 98% 屏宽，尽量贴近屏幕两侧
    val roleMax = if (message.sender == Sender.User) screenDp * 0.71f else screenDp * 0.98f
    val appliedMax = roleMax.coerceAtMost(maxWidth)

    Box(
        modifier = modifier
            .wrapContentWidth()
            .padding(vertical = 2.dp)
    ) {
        Surface(
            color = bubbleColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 0.dp,     // 右上角不要圆角
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            tonalElevation = if (isError) 0.dp else 1.dp,
            modifier = Modifier
                .wrapContentWidth()                 // 以内容宽度为准
                .widthIn(max = appliedMax)          // 用户气泡最大71%，AI气泡最大80%
                .onGloballyPositioned { coordinates -> 
                    // 存储组件在屏幕中的全局位置
                    globalPosition = coordinates.localToRoot(Offset.Zero)
                }
                // 🔥 修复：将长按手势移至 Surface 层，移除覆盖在内容上的透明 Box
                // 这样内部组件（如表格）的触摸事件就不会被覆盖层拦截
                .pointerInput(message.id, isError) {
                    detectTapGestures(
                        onLongPress = { localOffset ->
                            haptic.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            // localOffset 是相对于 Surface 的，加上 globalPosition 即可
                            val globalOffset = globalPosition + localOffset
                            currentOnLongPress(currentMessage, globalOffset)
                        }
                    )
                }
        ) {
            // 展开/收起状态管理
            var isExpanded by remember(message.id) { mutableStateOf(false) }
            var hasOverflow by remember(message.id) { mutableStateOf(false) }
            val contentScrollState = rememberScrollState()
            val maxUserBubbleHeight = resolveUserBubbleMaxHeightDp(
                screenHeightDp = with(density) { windowSize.height.toDp().value },
                isExpanded = isExpanded,
            ).dp
            val constrainUserBubbleHeight = shouldConstrainUserBubbleHeight(
                sender = message.sender,
                hasOverflow = hasOverflow,
                isExpanded = isExpanded,
            )

            LaunchedEffect(isExpanded, message.id) {
                if (!isExpanded) {
                    contentScrollState.animateScrollTo(0, tween(300, easing = UserBubbleToggleEasing))
                }
            }
            val fadeStrength by animateFloatAsState(
                targetValue = if (!isExpanded && hasOverflow) 1f else 0f,
                animationSpec = tween(300, easing = UserBubbleToggleEasing),
                label = "user-bubble-fade",
            )
            
            // 使用 Box 作为主容器，让按钮浮动在底部
            Box(
                modifier = Modifier
                    // 用户保持原来略紧凑，AI 左右仅保留 1dp 安全边距
                    .padding(
                        horizontal = if (message.sender == Sender.User) 0.dp else 1.dp,
                        vertical = if (message.sender == Sender.User) 0.dp else 14.dp
                    )
                    .wrapContentWidth()
                    .then(
                        if (message.sender == Sender.User) Modifier
                        else Modifier.defaultMinSize(minHeight = 28.dp)
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .wrapContentWidth()
                        .animateContentSize(tween(300, easing = UserBubbleToggleEasing))
                        .then(
                            if (constrainUserBubbleHeight) {
                                Modifier
                                    .heightIn(max = maxUserBubbleHeight)
                                    // 遮罩放在滚动之前，作用在可视区域而不是整段内容上。
                                    .userBubbleBottomFade({ fadeStrength }, UserBubbleFadeHeight)
                                    .then(
                                        if (isExpanded && hasOverflow) {
                                            Modifier.verticalScroll(contentScrollState)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .drawWithContent {
                                        drawContent()
                                        // 简单检测：如果绘制高度达到了最大限制，认为有溢出
                                        if (!isExpanded) {
                                            hasOverflow = size.height >= maxUserBubbleHeight.toPx() - 1f
                                        }
                                    }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // 原有内容层
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .wrapContentWidth()
                            // 收起时按完整高度排版，文字一直延伸到气泡底部再由遮罩淡出；
                            // 否则文字被自身边界在底部 18dp 处硬切，下方露出一条空白。
                            .then(
                                if (constrainUserBubbleHeight && !isExpanded) {
                                    Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true)
                                } else {
                                    Modifier
                                }
                            )
                            .offset(y = if (message.sender == Sender.User) USER_BUBBLE_TEXT_OPTICAL_OFFSET else 0.dp)
                            .padding(
                                start = if (message.sender == Sender.User) 14.dp else 0.dp,
                                end = if (message.sender == Sender.User) 14.dp else 0.dp,
                                top = if (message.sender == Sender.User) 10.dp else 0.dp,
                                bottom = if (message.sender == Sender.User) 10.dp else 0.dp,
                            )
                            // 如果显示按钮，给底部留出空间，防止内容被按钮遮挡
                            .padding(
                                bottom = resolveUserBubbleContentBottomPaddingDp(
                                    sender = message.sender,
                                    isExpanded = isExpanded,
                                ).dp
                            )
                    ) {
                        if (showLoadingDots && !isError) {
                            ThreeDotsLoadingAnimation(
                                dotColor = contentColor,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(y = (-6).dp)
                            )
                        } else if (
                            message.sender == Sender.User &&
                            message.contentParts.any { it is MessageContentPart.SkillReference }
                        ) {
                            UserMessageWithSkillTags(message.contentParts, contentColor)
                        } else if (renderText.isNotBlank() || isError) {
                            UnifiedMarkdownRenderer(
                                preparedMessage = preparedMessage,
                                sender = message.sender,
                                modifier = Modifier.wrapContentWidth(),
                            )
                        }
                    }
                }
                
                // 展开/收起胶囊：浮在底部中央，不再铺整条渐变色块。
                if (message.sender == Sender.User && (hasOverflow || isExpanded)) {
                    UserBubbleToggle(
                        expanded = isExpanded,
                        bubbleColor = bubbleColor,
                        contentColor = contentColor,
                        onToggle = { isExpanded = !isExpanded },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                    )
                }
            }
        }

    }
}

@Composable
private fun UserBubbleToggle(
    expanded: Boolean,
    bubbleColor: Color,
    contentColor: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(120), label = "toggle-press")
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(300, easing = UserBubbleToggleEasing),
        label = "toggle-chevron",
    )
    val lightBubble = bubbleColor.luminance() > 0.5f
    // 与气泡同色系：浅色气泡略压暗、深色气泡略提亮，不再用偏白色块。
    val pillColor = androidx.compose.ui.graphics.lerp(
        bubbleColor,
        if (lightBubble) Color.Black else Color.White,
        if (lightBubble) 0.05f else 0.10f,
    )
    androidx.compose.material3.Surface(
        onClick = onToggle,
        interactionSource = interaction,
        shape = CircleShape,
        color = pillColor,
        contentColor = contentColor,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, contentColor.copy(alpha = if (lightBubble) 0.08f else 0.14f)),
        modifier = modifier
            .height(28.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = expanded,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "toggle-label",
            ) { isOpen ->
                Text(
                    text = stringResource(if (isOpen) R.string.action_collapse else R.string.action_expand),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.78f),
                )
            }
            Spacer(Modifier.width(2.dp))
            Icon(
                painter = painterResource(R.drawable.ic_gpt_chevron_down_md),
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.78f),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

/** 历史数据可能在 Skill 标签后保存了多个空格；显示时统一压成一个标准间距。 */
internal fun normalizeSkillTagFollowingText(text: String): String {
    val trimmed = text.dropWhile { it == ' ' || it == '\t' }
    return when {
        trimmed.length == text.length -> text
        trimmed.isEmpty() -> ""
        else -> " $trimmed"
    }
}

/** 历史消息按原位置显示一次性 Skill 标签，引用本身仍由 contentParts 持久化。 */
@Composable
private fun UserMessageWithSkillTags(
    parts: List<MessageContentPart>,
    contentColor: Color,
) {
    val density = LocalDensity.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodyLarge
    val inlineContent = remember(parts, contentColor, density) {
        buildMap<String, InlineTextContent> {
            parts.filterIsInstance<MessageContentPart.SkillReference>().forEachIndexed { index, part ->
                val key = "skill-$index"
                val name = part.reference.displayName
                val measuredWidthPx = textMeasurer.measure(
                    text = AnnotatedString(name),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                ).size.width
                val tagWidthDp = with(density) { (measuredWidthPx + 16.dp.toPx()).toDp() }
                put(
                    key,
                    InlineTextContent(
                        placeholder = Placeholder(
                            width = tagWidthDp.value.sp,
                            height = 22.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                        ),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(percent = 50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = name,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = contentColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
    val text = remember(parts) {
        AnnotatedString.Builder().apply {
            var skillIndex = 0
            var previousWasSkill = false
            parts.forEach { part ->
                when (part) {
                    is MessageContentPart.Text -> {
                        append(part.text)
                        previousWasSkill = false
                    }
                    is MessageContentPart.SkillReference -> {
                        appendInlineContent("skill-${skillIndex++}", "/${part.reference.displayName}")
                        previousWasSkill = true
                    }
                }
            }
        }.toAnnotatedString()
    }
    Text(
        text = text,
        inlineContent = inlineContent,
        color = contentColor,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentsContent(
    attachments: List<SelectedMediaItem>,
    onAttachmentClick: (SelectedMediaItem) -> Unit,
    maxWidth: Dp,
    message: Message,
    onEditRequest: (Message) -> Unit,
    onRegenerateRequest: (Message) -> Unit,
    onLongPress: (Message, Offset) -> Unit,
    onImageLoaded: () -> Unit,
    scrollStateManager: com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager,
    bubbleColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    isAiGenerated: Boolean = false,  // 新增参数标识是否为AI生成
    onImageClick: ((String) -> Unit)? = null   // 新增：图片点击放大回调
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val currentMessage by rememberUpdatedState(message)
    val currentOnImageClick by rememberUpdatedState(onImageClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    var previewUrlInternal by remember(message.id) { mutableStateOf<String?>(null) }

    // 附件区域也跟随相同的最大宽度限制
    val screenDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val roleMax = if (message.sender == Sender.User) screenDp * 0.6f else screenDp * 0.8f
    val attachmentsAppliedMax = roleMax.coerceAtMost(maxWidth)

    val imageAttachments = remember(attachments) {
        attachments.filter { it is SelectedMediaItem.ImageFromUri || it is SelectedMediaItem.ImageFromBitmap }
    }
    val nonImageAttachments = remember(attachments) {
        attachments.filter { it is SelectedMediaItem.GenericFile || it is SelectedMediaItem.Audio }
    }

    val attachmentHorizontalAlignment = attachmentStripHorizontalAlignment(message.sender)

    Column(
        modifier = Modifier.padding(top = 8.dp),
        horizontalAlignment = attachmentHorizontalAlignment
    ) {
        if (imageAttachments.isNotEmpty() && isAiGenerated) {
            // AI 生成的图片：等比例缩放，填满可用宽度
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                imageAttachments.forEach { attachment ->
                    var imageGlobalPosition by remember(attachment.id) { mutableStateOf(Offset.Zero) }
                    val imageModel: Any = remember(attachment) {
                        when (attachment) {
                            is SelectedMediaItem.ImageFromUri -> attachment.filePath?.takeIf { it.isNotBlank() }
                                ?: if (attachment.uri.scheme == "data") attachment.uri.toString() else attachment.uri
                            is SelectedMediaItem.ImageFromBitmap -> attachment.model
                            else -> ""
                        }
                    }
                    val currentImageModel by rememberUpdatedState(imageModel)
                    coil3.compose.AsyncImage(
                        model = imageModel,
                        contentDescription = stringResource(R.string.attachment_generated_image),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                        onSuccess = { onImageLoaded() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .onGloballyPositioned { imageGlobalPosition = it.localToRoot(Offset.Zero) }
                            .pointerInput(message.id, attachment.id) {
                                detectTapGestures(
                                    onTap = {
                                        val url = currentImageModel.toString()
                                        if (url.isNotBlank()) {
                                            currentOnImageClick?.invoke(url) ?: run { previewUrlInternal = url }
                                        }
                                    },
                                    onLongPress = { localOffset ->
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        currentOnLongPress(currentMessage, imageGlobalPosition + localOffset)
                                    }
                                )
                            }
                    )
                }
            }
        } else if (imageAttachments.isNotEmpty()) {
            val imageStripHeight = 100.dp
            val imageShape = RoundedCornerShape(12.dp)
            val imageBorderColor = attachmentImageBorderColor(MaterialTheme.colorScheme.onSurface)
            val scrollState = rememberScrollState()
            val canScrollBackward by remember {
                derivedStateOf { scrollState.value > 0 }
            }
            val canScrollForward by remember {
                derivedStateOf { scrollState.value < scrollState.maxValue }
            }
            val isUser = message.sender == Sender.User

            LaunchedEffect(isUser, imageAttachments.size, scrollState.maxValue) {
                if (isUser) scrollState.scrollTo(scrollState.maxValue)
            }

            val fadeColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.Black else Color.White

            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = if (isUser) Arrangement.spacedBy(2.dp, Alignment.End) else Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(imageStripHeight)
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                ) {
                    imageAttachments.forEach { attachment ->
                        var imageGlobalPosition by remember(attachment.id) { mutableStateOf(Offset.Zero) }
                        var thumbnailSize by remember(attachment.id) {
                            mutableStateOf(AttachmentThumbnailSize(100f, 100f))
                        }
                        val imageModel: Any = remember(attachment) { when (attachment) {
                            is SelectedMediaItem.ImageFromUri -> attachment.filePath?.takeIf { it.isNotBlank() }
                                ?: if (attachment.uri.scheme == "data") attachment.uri.toString() else attachment.uri
                            is SelectedMediaItem.ImageFromBitmap -> attachment.model
                            else -> ""
                        } }
                        val currentImageModel by rememberUpdatedState(imageModel)
                        coil3.compose.AsyncImage(
                            model = imageModel,
                            contentDescription = stringResource(R.string.attachment_image),
                            // 尺寸已按原图比例算好，Crop 只会裁掉被最小边长撑出来的部分；顶部对齐让长截图显示开头。
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            onSuccess = { state ->
                                val intrinsicSize = state.painter.intrinsicSize
                                thumbnailSize = fitAttachmentThumbnail(
                                    sourceWidth = intrinsicSize.width,
                                    sourceHeight = intrinsicSize.height,
                                )
                                onImageLoaded()
                            },
                            modifier = Modifier
                                .width(thumbnailSize.widthDp.dp)
                                .height(thumbnailSize.heightDp.dp)
                                .clip(imageShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, imageBorderColor, imageShape)
                                .onGloballyPositioned { imageGlobalPosition = it.localToRoot(Offset.Zero) }
                                .pointerInput(message.id, attachment.id) {
                                    detectTapGestures(
                                        onTap = {
                                            val url = currentImageModel.toString()
                                            if (url.isNotBlank()) {
                                                currentOnImageClick?.invoke(url) ?: run { previewUrlInternal = url }
                                            }
                                        },
                                        onLongPress = { localOffset ->
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            currentOnLongPress(currentMessage, imageGlobalPosition + localOffset)
                                        }
                                    )
                                }
                        )
                    }
                }
                if (imageAttachments.size > 3) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.attachment_image_count,
                                imageAttachments.size,
                                imageAttachments.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (canScrollBackward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(16.dp)
                            .height(imageStripHeight)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(fadeColor, Color.Transparent)
                                )
                            )
                    )
                }
                if (canScrollForward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(16.dp)
                            .height(imageStripHeight)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, fadeColor)
                                )
                            )
                    )
                }
            }
        }

        nonImageAttachments.forEach { attachment ->
            when (attachment) {
                is SelectedMediaItem.GenericFile -> com.android.everytalk.ui.components.MessageFileLink(attachment, attachmentsAppliedMax)
                is SelectedMediaItem.Audio -> {
                    var itemGlobalPosition by remember(attachment.id) { mutableStateOf(Offset.Zero) }
                    Row(
                        modifier = Modifier
                            .widthIn(max = attachmentsAppliedMax)
                            .padding(vertical = 4.dp)
                            .background(bubbleColor, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .onGloballyPositioned { itemGlobalPosition = it.localToRoot(Offset.Zero) }
                            .pointerInput(message.id, attachment.id) {
                                detectTapGestures(
                                    onLongPress = { localOffset ->
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        currentOnLongPress(currentMessage, itemGlobalPosition + localOffset)
                                    }
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_gpt_file_audio),
                            contentDescription = stringResource(R.string.attachment_audio_description),
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.attachment_audio_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {}
            }
        }
    }

    if (previewUrlInternal != null) {
        com.android.everytalk.ui.components.ImagePreviewDialog(
            url = previewUrlInternal!!,
            onDismiss = { previewUrlInternal = null }
        )
    }
}






@Composable
private fun ThreeDotsLoadingAnimation(
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        (0..2).forEach { index ->
            val infiniteTransition =
                rememberInfiniteTransition(label = "dot_loading_transition_$index")
            val animatedAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis =
                            1200; 0.3f at 0 using LinearEasing; 1.0f at 200 using LinearEasing
                        0.3f at 400 using LinearEasing; 0.3f at 1200 using LinearEasing
                    },
                    repeatMode = RepeatMode.Restart, initialStartOffset = StartOffset(index * 150)
                ), label = "dot_alpha_$index"
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor.copy(alpha = animatedAlpha), RoundedCornerShape(50))
            )
        }
    }
}

@Composable
fun MessageContextMenu(
    isVisible: Boolean,
    message: Message,
    onDismiss: () -> Unit,
    onCopy: (Message) -> Unit,
    onEdit: (Message) -> Unit,
    onRegenerate: (Message) -> Unit,
    pressOffset: Offset = Offset.Zero
) {
    key(message.id) {
        val density = LocalDensity.current
        val windowSize = LocalWindowInfo.current.containerSize
        val isDark = isSystemInDarkTheme()

        val menuWidth = 160.dp
        val menuWidthPx = with(density) { menuWidth.toPx() }
        val screenWidthPx = windowSize.width.toFloat()
        val screenHeightPx = windowSize.height.toFloat()
        val estimatedMenuHeightPx = with(density) { (56.dp * 3 + 16.dp).toPx() }

        val rawX = pressOffset.x - menuWidthPx
        val rawY = pressOffset.y

        val finalX = rawX.coerceIn(0f, (screenWidthPx - menuWidthPx).coerceAtLeast(0f))
        val finalY = rawY.coerceIn(0f, (screenHeightPx - estimatedMenuHeightPx).coerceAtLeast(0f))

        val iconBg = if (isDark) Color(0xFF3B3B3B) else Color(0xFFE8E8E8)
        val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
        val iconTint = if (isDark) Color.White else Color(0xFF0D0D0D)

        AppFloatingCardPopup(
            visible = isVisible,
            alignment = Alignment.TopStart,
            offset = IntOffset(finalX.toInt(), finalY.toInt()),
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                clippingEnabled = false
            ),
            modifier = Modifier.width(menuWidth),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                ContextMenuRow(
                    icon = R.drawable.ic_copy,
                    label = stringResource(R.string.action_copy),
                    iconBg = iconBg,
                    iconTint = iconTint,
                    textColor = textColor,
                    onClick = { onCopy(message) }
                )
                ContextMenuRow(
                    icon = R.drawable.ic_gpt_edit,
                    label = stringResource(R.string.chat_action_edit),
                    iconBg = iconBg,
                    iconTint = iconTint,
                    textColor = textColor,
                    onClick = { onEdit(message) }
                )
                ContextMenuRow(
                    icon = R.drawable.ic_regenerate,
                    label = stringResource(R.string.chat_action_regenerate),
                    iconBg = iconBg,
                    iconTint = iconTint,
                    textColor = textColor,
                    onClick = { onRegenerate(message) }
                )
            }
        }
    }
}

@Composable
fun ImageContextMenu(
   isVisible: Boolean,
   message: Message,
   onDismiss: () -> Unit,
   onView: (Message) -> Unit,
   onDownload: (Message) -> Unit,
   onEdit: ((Message) -> Unit)? = null,
   pressOffset: Offset = Offset.Zero
) {
   key(message.id) {
       val density = LocalDensity.current
       val windowSize = LocalWindowInfo.current.containerSize
       val isDark = isSystemInDarkTheme()

       val menuWidth = 160.dp
       val menuWidthPx = with(density) { menuWidth.toPx() }
       val screenWidthPx = windowSize.width.toFloat()
       val screenHeightPx = windowSize.height.toFloat()
       val estimatedMenuHeightPx = with(density) { (56.dp * imageContextMenuItemCount(onEdit != null) + 16.dp).toPx() }

       val fingerVerticalOffsetPx = with(density) { 20.dp.toPx() }
       val rawX = pressOffset.x
       val rawY = pressOffset.y + fingerVerticalOffsetPx

       val finalX = rawX.coerceIn(0f, (screenWidthPx - menuWidthPx).coerceAtLeast(0f))
       val finalY = rawY.coerceIn(0f, (screenHeightPx - estimatedMenuHeightPx).coerceAtLeast(0f))

       val iconBg = if (isDark) Color(0xFF3B3B3B) else Color(0xFFE8E8E8)
       val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
       val iconTint = if (isDark) Color.White else Color(0xFF0D0D0D)

       AppFloatingCardPopup(
           visible = isVisible,
           alignment = Alignment.TopStart,
           offset = IntOffset(finalX.toInt(), finalY.toInt()),
           onDismissRequest = onDismiss,
           properties = PopupProperties(
               focusable = true,
               dismissOnBackPress = true,
               dismissOnClickOutside = true,
               clippingEnabled = false
           ),
           modifier = Modifier.width(menuWidth),
       ) {
           Column(modifier = Modifier.padding(vertical = 8.dp)) {
               ContextMenuRow(
                   icon = R.drawable.ic_gpt_file_image,
                   label = stringResource(R.string.image_view),
                   iconBg = iconBg,
                   iconTint = iconTint,
                   textColor = textColor,
                   onClick = { onView(message) }
               )
               if (onEdit != null) {
                   ContextMenuRow(
                       icon = R.drawable.ic_gpt_edit,
                       label = stringResource(imageContextMenuEditLabelRes()),
                       iconBg = iconBg,
                       iconTint = iconTint,
                       textColor = textColor,
                       onClick = { onEdit(message) }
                   )
               }
               ContextMenuRow(
                   icon = R.drawable.ic_download,
                   label = stringResource(R.string.image_download),
                   iconBg = iconBg,
                   iconTint = iconTint,
                   textColor = textColor,
                   onClick = { onDownload(message) }
               )
           }
       }
   }
}

internal fun imageContextMenuItemCount(showEditAction: Boolean): Int =
    if (showEditAction) 3 else 2

internal fun imageContextMenuEditLabelRes(): Int = R.string.image_edit

@Composable
private fun ContextMenuRow(
    @DrawableRes icon: Int,
    label: String,
    iconBg: Color,
    iconTint: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 16.sp,
            color = textColor
        )
    }
}
