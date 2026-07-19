package com.android.everytalk.ui.screens.BubbleMain.Main

import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.ProportionalAsyncImage
import com.android.everytalk.ui.components.ImagePreviewDialog
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownRenderer
import com.android.everytalk.ui.components.streaming.contentVersionForRendering
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

private val CONTEXT_MENU_CORNER_RADIUS = 28.dp
private val CONTEXT_MENU_ITEM_ICON_SIZE = 22.dp

internal fun attachmentStripHorizontalAlignment(sender: Sender): Alignment.Horizontal =
    if (sender == Sender.User) Alignment.End else Alignment.Start

internal const val USER_BUBBLE_COLLAPSED_MAX_HEIGHT_RATIO = 0.32f
internal const val USER_BUBBLE_EXPANDED_MAX_HEIGHT_RATIO = 0.56f

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
): Float = if (sender == Sender.User && isExpanded) 28f else 0f


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
    val configuration = LocalConfiguration.current
    val screenDp = configuration.screenWidthDp.dp
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
                            onLongPress(message, globalOffset)
                        }
                    )
                }
        ) {
            // 展开/收起状态管理
            var isExpanded by remember(message.id) { mutableStateOf(false) }
            var hasOverflow by remember(message.id) { mutableStateOf(false) }
            val contentScrollState = rememberScrollState()
            val maxUserBubbleHeight = resolveUserBubbleMaxHeightDp(
                screenHeightDp = configuration.screenHeightDp.toFloat(),
                isExpanded = isExpanded,
            ).dp
            val constrainUserBubbleHeight = shouldConstrainUserBubbleHeight(
                sender = message.sender,
                hasOverflow = hasOverflow,
                isExpanded = isExpanded,
            )

            LaunchedEffect(isExpanded, message.id) {
                if (!isExpanded) {
                    contentScrollState.scrollTo(0)
                }
            }
            
            // 使用 Box 作为主容器，让按钮浮动在底部
            Box(
                modifier = Modifier
                    // 用户保持原来略紧凑，AI 左右仅保留 1dp 安全边距
                    .padding(
                        horizontal = if (message.sender == Sender.User) 0.dp else 1.dp,
                        vertical = if (message.sender == Sender.User) 0.dp else 14.dp
                    )
                    .wrapContentWidth()
                    .defaultMinSize(minHeight = 28.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .wrapContentWidth()
                        .animateContentSize() // 🔥 添加流畅的过渡动画
                        .then(
                            if (constrainUserBubbleHeight) {
                                Modifier
                                    .heightIn(max = maxUserBubbleHeight)
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
                        )
                ) {
                    // 原有内容层
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(
                                horizontal = if (message.sender == Sender.User) 10.dp else 0.dp,
                                vertical = if (message.sender == Sender.User) 6.dp else 0.dp,
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
                        } else if (displayedText.isNotBlank() || isError) {
                            UnifiedMarkdownRenderer(
                                preparedMessage = preparedMessage,
                                sender = message.sender,
                                modifier = Modifier.wrapContentWidth(),
                            )
                        }
                    }
                }
                
                // 展开/收起按钮 - 浮动在底部，带有渐变背景
                if (message.sender == Sender.User && (hasOverflow || isExpanded)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            // 显式裁剪为底部圆角，解决点击波纹直角问题，匹配气泡圆角
                            .clip(bubbleShape)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        bubbleColor.copy(alpha = 0f), // 顶部透明
                                        bubbleColor.copy(alpha = 0.8f), // 中间过渡
                                        bubbleColor                   // 底部实色
                                    )
                                )
                            )
                            .clickable { isExpanded = !isExpanded }
                            .padding(top = 12.dp, bottom = 6.dp), // 减小高度，调整视觉平衡
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (isExpanded) "收起" else "展开",
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

    }
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
    var previewUrlInternal by remember(message.id) { mutableStateOf<String?>(null) }

    // 附件区域也跟随相同的最大宽度限制
    val screenDp = LocalConfiguration.current.screenWidthDp.dp
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
                    var imageGlobalPosition by remember { mutableStateOf(Offset.Zero) }
                    val imageModel: Any = when (attachment) {
                        is SelectedMediaItem.ImageFromUri -> if (attachment.uri.scheme == "data") attachment.uri.toString() else attachment.uri
                        is SelectedMediaItem.ImageFromBitmap -> attachment.bitmap as Any
                        else -> ""
                    }
                    coil3.compose.AsyncImage(
                        model = imageModel,
                        contentDescription = "AI generated image",
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                        onSuccess = { onImageLoaded() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .onGloballyPositioned { imageGlobalPosition = it.localToRoot(Offset.Zero) }
                            .pointerInput(message.id) {
                                detectTapGestures(
                                    onTap = {
                                        val url = when (attachment) {
                                            is SelectedMediaItem.ImageFromUri -> attachment.uri.toString()
                                            is SelectedMediaItem.ImageFromBitmap -> attachment.bitmap?.let { bitmapToDataUri(it) } ?: ""
                                            else -> ""
                                        }
                                        if (url.isNotBlank()) {
                                            if (onImageClick != null) onImageClick.invoke(url) else { previewUrlInternal = url }
                                        }
                                    },
                                    onLongPress = { localOffset ->
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        onLongPress(message, imageGlobalPosition + localOffset)
                                    }
                                )
                            }
                    )
                }
            }
        } else if (imageAttachments.isNotEmpty()) {
            val imageStripHeight = 100.dp
            val imageShape = RoundedCornerShape(12.dp)
            val imageBorderColor = MaterialTheme.colorScheme.outlineVariant
            val scrollState = rememberScrollState()
            val isUser = message.sender == Sender.User

            LaunchedEffect(isUser, imageAttachments.size) {
                if (isUser) scrollState.scrollTo(scrollState.maxValue)
            }

            val fadeColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.Black else Color.White

            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = if (isUser) Arrangement.spacedBy(2.dp, Alignment.End) else Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .height(imageStripHeight)
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                ) {
                    imageAttachments.forEachIndexed { idx, attachment ->
                        var imageGlobalPosition by remember { mutableStateOf(Offset.Zero) }
                        val imageModel: Any = when (attachment) {
                            is SelectedMediaItem.ImageFromUri -> if (attachment.uri.scheme == "data") attachment.uri.toString() else attachment.uri
                            is SelectedMediaItem.ImageFromBitmap -> attachment.bitmap as Any
                            else -> ""
                        }
                        coil3.compose.AsyncImage(
                            model = imageModel,
                            contentDescription = "Image attachment",
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            onSuccess = { onImageLoaded() },
                            modifier = Modifier
                                .size(imageStripHeight)
                                .background(MaterialTheme.colorScheme.surfaceVariant, imageShape)
                                .border(1.dp, imageBorderColor, imageShape)
                                .clip(imageShape)
                                .onGloballyPositioned { imageGlobalPosition = it.localToRoot(Offset.Zero) }
                                .pointerInput(message.id, idx) {
                                    detectTapGestures(
                                        onTap = {
                                            val url = when (attachment) {
                                                is SelectedMediaItem.ImageFromUri -> attachment.uri.toString()
                                                is SelectedMediaItem.ImageFromBitmap -> attachment.bitmap?.let { bitmapToDataUri(it) } ?: ""
                                                else -> ""
                                            }
                                            if (url.isNotBlank()) {
                                                if (onImageClick != null) onImageClick.invoke(url) else { previewUrlInternal = url }
                                            }
                                        },
                                        onLongPress = { localOffset ->
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            onLongPress(message, imageGlobalPosition + localOffset)
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
                            text = "${imageAttachments.size} 张",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (scrollState.value > 0) {
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
                if (scrollState.value < scrollState.maxValue) {
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
                is SelectedMediaItem.GenericFile -> {
                    var itemGlobalPosition by remember { mutableStateOf(Offset.Zero) }
                    Row(
                        modifier = Modifier
                            .widthIn(max = attachmentsAppliedMax)
                            .padding(vertical = 4.dp)
                            .background(bubbleColor, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .onGloballyPositioned { itemGlobalPosition = it.localToRoot(Offset.Zero) }
                            .pointerInput(message.id, attachment.uri) {
                                detectTapGestures(
                                    onTap = {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(attachment.uri, attachment.mimeType)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(intent)
                                    },
                                    onLongPress = { localOffset ->
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        onLongPress(message, itemGlobalPosition + localOffset)
                                    }
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = getIconForMimeType(attachment.mimeType),
                            contentDescription = "Attachment",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = attachment.displayName ?: attachment.uri.path?.substringAfterLast('/') ?: "Attached File",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is SelectedMediaItem.Audio -> {
                    var itemGlobalPosition by remember { mutableStateOf(Offset.Zero) }
                    Row(
                        modifier = Modifier
                            .widthIn(max = attachmentsAppliedMax)
                            .padding(vertical = 4.dp)
                            .background(bubbleColor, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .onGloballyPositioned { itemGlobalPosition = it.localToRoot(Offset.Zero) }
                            .pointerInput(message.id, attachment) {
                                detectTapGestures(
                                    onTap = { },
                                    onLongPress = { localOffset ->
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        onLongPress(message, itemGlobalPosition + localOffset)
                                    }
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Audiotrack,
                            contentDescription = "Audio Attachment",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Audio attachment",
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
private fun getIconForMimeType(mimeType: String?): androidx.compose.ui.graphics.vector.ImageVector {
    return when (mimeType) {
        "application/pdf" -> Icons.Outlined.PictureAsPdf
        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Icons.Outlined.Description
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Icons.Outlined.TableChart
        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> Icons.Outlined.Slideshow
        "application/zip", "application/x-rar-compressed" -> Icons.Outlined.Archive
        else -> when {
            mimeType?.startsWith("video/") == true -> Icons.Outlined.Videocam
            mimeType?.startsWith("audio/") == true -> Icons.Outlined.Audiotrack
            mimeType?.startsWith("image/") == true -> Icons.Outlined.Image
            else -> Icons.Outlined.AttachFile
        }
    }
}

private fun bitmapToDataUri(bitmap: Bitmap): String {
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    return "data:image/png;base64,$base64"
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
    if (isVisible) {
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val isDark = isSystemInDarkTheme()

        val menuWidth = 160.dp
        val menuWidthPx = with(density) { menuWidth.toPx() }
        val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
        val estimatedMenuHeightPx = with(density) { (56.dp * 3 + 16.dp).toPx() }

        val rawX = pressOffset.x - menuWidthPx
        val rawY = pressOffset.y

        val finalX = rawX.coerceIn(0f, screenWidthPx - menuWidthPx)
        val finalY = rawY.coerceIn(0f, screenHeightPx - estimatedMenuHeightPx)

        val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
        val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)
        val iconBg = if (isDark) Color(0xFF3B3B3B) else Color(0xFFE8E8E8)
        val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
        val iconTint = if (isDark) Color.White else Color(0xFF0D0D0D)

        val scaleAnim = remember { Animatable(0.8f) }
        val alphaAnim = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            launch { scaleAnim.animateTo(1f, tween(120, easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f))) }
            launch { alphaAnim.animateTo(1f, tween(30, easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f))) }
        }

        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(finalX.toInt(), finalY.toInt()),
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                clippingEnabled = false
            )
        ) {
            Surface(
                modifier = Modifier
                    .width(menuWidth)
                    .graphicsLayer {
                        this.scaleX = scaleAnim.value
                        this.scaleY = scaleAnim.value
                        this.alpha = alphaAnim.value
                        this.transformOrigin = TransformOrigin(1f, 0f)
                    }
                    .shadow(8.dp, RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS))
                    .border(1.dp, borderColor, RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS)),
                shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS),
                color = cardBg
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    ContextMenuRow(
                        icon = Icons.Filled.ContentCopy,
                        label = "复制",
                        iconBg = iconBg,
                        iconTint = iconTint,
                        textColor = textColor,
                        onClick = { onCopy(message) }
                    )
                    ContextMenuRow(
                        icon = Icons.Filled.Edit,
                        label = "编辑",
                        iconBg = iconBg,
                        iconTint = iconTint,
                        textColor = textColor,
                        onClick = { onEdit(message) }
                    )
                    ContextMenuRow(
                        icon = Icons.Filled.Refresh,
                        label = "重新回答",
                        iconBg = iconBg,
                        iconTint = iconTint,
                        textColor = textColor,
                        onClick = { onRegenerate(message) }
                    )
                }
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
   if (isVisible) {
       val density = LocalDensity.current
       val configuration = LocalConfiguration.current
       val isDark = isSystemInDarkTheme()

       val menuWidth = 160.dp
       val menuWidthPx = with(density) { menuWidth.toPx() }
       val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
       val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
       val estimatedMenuHeightPx = with(density) { (56.dp * imageContextMenuItemCount(onEdit != null) + 16.dp).toPx() }

       val fingerVerticalOffsetPx = with(density) { 20.dp.toPx() }
       val rawX = pressOffset.x
       val rawY = pressOffset.y + fingerVerticalOffsetPx

       val finalX = rawX.coerceIn(0f, screenWidthPx - menuWidthPx)
       val finalY = rawY.coerceIn(0f, screenHeightPx - estimatedMenuHeightPx)

       val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
       val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)
       val iconBg = if (isDark) Color(0xFF3B3B3B) else Color(0xFFE8E8E8)
       val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
       val iconTint = if (isDark) Color.White else Color(0xFF0D0D0D)

       val scaleAnim = remember { Animatable(0.8f) }
       val alphaAnim = remember { Animatable(0f) }
       LaunchedEffect(Unit) {
           launch { scaleAnim.animateTo(1f, tween(120, easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f))) }
           launch { alphaAnim.animateTo(1f, tween(30, easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f))) }
       }

       Popup(
           alignment = Alignment.TopStart,
           offset = IntOffset(finalX.toInt(), finalY.toInt()),
           onDismissRequest = onDismiss,
           properties = PopupProperties(
               focusable = true,
               dismissOnBackPress = true,
               dismissOnClickOutside = true,
               clippingEnabled = false
           )
       ) {
           Surface(
               modifier = Modifier
                   .width(menuWidth)
                   .graphicsLayer {
                       this.scaleX = scaleAnim.value
                       this.scaleY = scaleAnim.value
                       this.alpha = alphaAnim.value
                       this.transformOrigin = TransformOrigin(0f, 0f)
                   }
                   .shadow(8.dp, RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS))
                   .border(1.dp, borderColor, RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS)),
               shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS),
               color = cardBg
           ) {
               Column(modifier = Modifier.padding(vertical = 8.dp)) {
                   ContextMenuRow(
                       icon = Icons.Outlined.Image,
                       label = "查看图片",
                       iconBg = iconBg,
                       iconTint = iconTint,
                       textColor = textColor,
                       onClick = { onView(message) }
                   )
                   if (onEdit != null) {
                       ContextMenuRow(
                           icon = Icons.Filled.Edit,
                           label = imageContextMenuEditLabel(),
                           iconBg = iconBg,
                           iconTint = iconTint,
                           textColor = textColor,
                           onClick = { onEdit(message) }
                       )
                   }
                   ContextMenuRow(
                       icon = Icons.Outlined.Download,
                       label = "下载图片",
                       iconBg = iconBg,
                       iconTint = iconTint,
                       textColor = textColor,
                       onClick = { onDownload(message) }
                   )
               }
           }
       }
   }
}

internal fun imageContextMenuItemCount(showEditAction: Boolean): Int =
    if (showEditAction) 3 else 2

internal fun imageContextMenuEditLabel(): String = "编辑图像"

@Composable
private fun ContextMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
                imageVector = icon,
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
