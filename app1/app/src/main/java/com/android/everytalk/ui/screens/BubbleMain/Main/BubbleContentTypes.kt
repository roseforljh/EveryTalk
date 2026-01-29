package com.android.everytalk.ui.screens.BubbleMain.Main

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.ProportionalAsyncImage

import com.android.everytalk.ui.components.EnhancedMarkdownText
import com.android.everytalk.ui.components.ImagePreviewDialog
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

private const val CONTEXT_MENU_ANIMATION_DURATION_MS = 150
private val CONTEXT_MENU_CORNER_RADIUS = 16.dp
private val CONTEXT_MENU_ITEM_ICON_SIZE = 20.dp
// 基本对齐采用"角点贴手指"，再整体向下微移，避免"整体偏高"
private val CONTEXT_MENU_FINGER_VERTICAL_OFFSET = -90.dp
private val CONTEXT_MENU_FIXED_WIDTH = 120.dp


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
    var previewUrl by remember(message.id) { mutableStateOf<String?>(null) }

    // 基于发送者动态计算最大宽度：用户71%，AI80%
    val configuration = LocalConfiguration.current
    val screenDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp
    
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
            val maxCollapsedHeight = screenHeightDp * 0.4f // 默认最大高度为屏幕高度的 40%
            
            // 使用 Box 作为主容器，让按钮浮动在底部
            Box(
                modifier = Modifier
                    // 用户保持原来略紧凑，AI 左右仅保留 1dp 安全边距
                    .padding(
                        horizontal = if (message.sender == Sender.User) 10.dp else 1.dp,
                        vertical = if (message.sender == Sender.User) 6.dp else 14.dp
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
                            if (message.sender == Sender.User && !isExpanded) {
                                Modifier
                                    .heightIn(max = maxCollapsedHeight)
                                    .drawWithContent {
                                        drawContent()
                                        // 简单检测：如果绘制高度达到了最大限制，认为有溢出
                                        if (size.height >= maxCollapsedHeight.toPx() - 1f) {
                                            hasOverflow = true
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
                            // 如果显示按钮，给底部留出空间，防止内容被按钮遮挡
                            .padding(bottom = if (message.sender == Sender.User && (hasOverflow || isExpanded)) 28.dp else 0.dp)
                    ) {
                        if (showLoadingDots && !isError) {
                            ThreeDotsLoadingAnimation(
                                dotColor = contentColor,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(y = (-6).dp)
                            )
                        } else if (displayedText.isNotBlank() || isError) {
                            EnhancedMarkdownText(
                                message = message,
                                modifier = Modifier.wrapContentWidth(),
                                color = contentColor,
                                onImageClick = { url -> previewUrl = url },
                                onLongPress = { offset ->
                                    onLongPress(message, offset)
                                }
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
                            .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
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

        // 全屏图片预览对话框
        if (previewUrl != null) {
            ImagePreviewDialog(
                url = previewUrl!!,
                onDismiss = { previewUrl = null }
            )
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

    Box {
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            attachments.forEach { attachment ->
                when (attachment) {
                    is SelectedMediaItem.ImageFromUri -> {
                        var imageGlobalPosition by remember { mutableStateOf(Offset.Zero) }
                        // 🔥 修复：如果是 data URI，直接使用字符串而不是 Uri 对象，Coil 更好地支持字符串形式的 data URI
                        val imageModel = if (attachment.uri.scheme == "data") {
                            attachment.uri.toString()
                        } else {
                            attachment.uri
                        }
                        // 额外父级宽度约束，防止重组或内部状态重置导致图片短暂"放大占满并左对齐"
                        // 在 FlowRow 中，我们可能希望图片不要太大，以便多张并排
                        // 如果只有一张，可以大一点；多张时适当缩小
                        val isMultiple = attachments.size > 1
                        val itemMaxWidth = if (isMultiple) attachmentsAppliedMax * 0.48f else attachmentsAppliedMax * 0.8f
                        
                        Box(
                            modifier = Modifier
                                .widthIn(
                                    max = itemMaxWidth
                                )
                        ) {
                            ProportionalAsyncImage(
                                model = imageModel,
                                contentDescription = "Image attachment",
                                maxWidth = itemMaxWidth,
                                isAiGenerated = isAiGenerated,
                                onSuccess = { _ -> onImageLoaded() },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .onGloballyPositioned {
                                        imageGlobalPosition = it.localToRoot(Offset.Zero)
                                    }
                                    .pointerInput(message.id, attachment.uri) {
                                        detectTapGestures(
                                            onTap = {
                                                val url = attachment.uri.toString()
                                                if (onImageClick != null) {
                                                    onImageClick.invoke(url)
                                                } else {
                                                    // 默认内置预览
                                                    previewUrlInternal = url
                                                }
                                            },
                                            onLongPress = { localOffset ->
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                val globalOffset = imageGlobalPosition + localOffset
                                                onLongPress(message, globalOffset)
                                            }
                                        )
                                    }
                            )
                        }
                    }
                    is SelectedMediaItem.ImageFromBitmap -> {
                        var imageGlobalPosition by remember { mutableStateOf(Offset.Zero) }
                        // 额外父级宽度约束，防止重组或内部状态重置导致图片短暂"放大占满并左对齐"
                        val isMultiple = attachments.size > 1
                        val itemMaxWidth = if (isMultiple) attachmentsAppliedMax * 0.48f else attachmentsAppliedMax * 0.8f

                        Box(
                            modifier = Modifier
                                .widthIn(
                                    max = itemMaxWidth
                                )
                        ) {
                            ProportionalAsyncImage(
                                model = attachment.bitmap,
                                contentDescription = "Image attachment",
                                maxWidth = itemMaxWidth,
                                isAiGenerated = isAiGenerated,
                                onSuccess = { _ -> onImageLoaded() },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .onGloballyPositioned {
                                        imageGlobalPosition = it.localToRoot(Offset.Zero)
                                    }
                                    .pointerInput(message.id, attachment.bitmap) {
                                        detectTapGestures(
                                            onTap = {
                                                val bmp = attachment.bitmap
                                                val dataUri = bmp?.let { bitmapToDataUri(it) }
                                                if (dataUri != null) {
                                                    if (onImageClick != null) {
                                                        onImageClick.invoke(dataUri)
                                                    } else {
                                                        // 默认内置预览
                                                        previewUrlInternal = dataUri
                                                    }
                                                }
                                            },
                                            onLongPress = { localOffset ->
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                val globalOffset = imageGlobalPosition + localOffset
                                                onLongPress(message, globalOffset)
                                            }
                                        )
                                    }
                            )
                        }
                    }
                    is SelectedMediaItem.GenericFile -> {
                        var itemGlobalPosition by remember { mutableStateOf(Offset.Zero) }
                        Row(
                            modifier = Modifier
                                .widthIn(max = attachmentsAppliedMax)
                                .padding(vertical = 4.dp)
                                .background(bubbleColor, RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp))
                                .onGloballyPositioned {
                                    itemGlobalPosition = it.localToRoot(Offset.Zero)
                                }
                                .pointerInput(message.id, attachment.uri) {
                                    detectTapGestures(
                                        onTap = {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(
                                                    attachment.uri,
                                                    attachment.mimeType
                                                )
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(intent)
                                        },
                                        onLongPress = { localOffset ->
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            val globalOffset = itemGlobalPosition + localOffset
                                            onLongPress(message, globalOffset)
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
                                text = attachment.displayName
                                    ?: attachment.uri.path?.substringAfterLast('/')
                                    ?: "Attached File",
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
                                .onGloballyPositioned {
                                    itemGlobalPosition = it.localToRoot(Offset.Zero)
                                }
                                .pointerInput(message.id, attachment) {
                                    detectTapGestures(
                                        onTap = {
                                            // TODO: Implement audio playback
                                        },
                                        onLongPress = { localOffset ->
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            val globalOffset = itemGlobalPosition + localOffset
                                            onLongPress(message, globalOffset)
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
                }
            }
        }

        // 内置全屏图片预览对话框（当未外部接管 onImageClick 时）
        if (previewUrlInternal != null) {
            com.android.everytalk.ui.components.ImagePreviewDialog(
                url = previewUrlInternal!!,
                onDismiss = { previewUrlInternal = null }
            )
        }
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
                            1200; 0.3f at 0 with LinearEasing; 1.0f at 200 with LinearEasing
                        0.3f at 400 with LinearEasing; 0.3f at 1200 with LinearEasing
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
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        
        val menuWidthPx = with(density) { CONTEXT_MENU_FIXED_WIDTH.toPx() }
        val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
        val estimatedMenuHeightPx = with(density) { (48.dp * 3 + 16.dp).toPx() }

        // 用户消息：右上角对准手指位置
        val rawX = pressOffset.x - menuWidthPx
        val rawY = pressOffset.y

        // 边界夹紧
        val finalX = rawX.coerceIn(0f, screenWidthPx - menuWidthPx)
        val finalY = rawY.coerceIn(0f, screenHeightPx - estimatedMenuHeightPx)

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
                shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS),
                color = MaterialTheme.colorScheme.surfaceDim,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .width(CONTEXT_MENU_FIXED_WIDTH)
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS)
                    )
                    .padding(1.dp)
            ) {
                Column {
                    val menuVisibility =
                        remember { MutableTransitionState(false) }
                    LaunchedEffect(isVisible) {
                        menuVisibility.targetState = isVisible
                    }

                    @Composable
                    fun AnimatedDropdownMenuItem(
                        visibleState: MutableTransitionState<Boolean>,
                        delay: Int = 0,
                        text: @Composable () -> Unit,
                        onClick: () -> Unit,
                        leadingIcon: @Composable (() -> Unit)? = null
                    ) {
                        AnimatedVisibility(
                            visibleState = visibleState,
                            enter = fadeIn(
                                animationSpec = tween(
                                    CONTEXT_MENU_ANIMATION_DURATION_MS,
                                    delayMillis = delay,
                                    easing = LinearOutSlowInEasing
                                )
                            ) +
                                    scaleIn(
                                        animationSpec = tween(
                                            CONTEXT_MENU_ANIMATION_DURATION_MS,
                                            delayMillis = delay,
                                            easing = LinearOutSlowInEasing
                                        ), transformOrigin = TransformOrigin(0f, 0f)
                                    ),
                            exit = fadeOut(
                                animationSpec = tween(
                                    CONTEXT_MENU_ANIMATION_DURATION_MS,
                                    easing = FastOutLinearInEasing
                                )
                            ) +
                                    scaleOut(
                                        animationSpec = tween(
                                            CONTEXT_MENU_ANIMATION_DURATION_MS,
                                            easing = FastOutLinearInEasing
                                        ), transformOrigin = TransformOrigin(0f, 0f)
                                    )
                        ) {
                            DropdownMenuItem(
                                text = text, onClick = onClick, leadingIcon = leadingIcon,
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.onSurface,
                                    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    AnimatedDropdownMenuItem(
                        menuVisibility,
                        text = { Text("复制") },
                        onClick = {
                            onCopy(message)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.ContentCopy,
                                "复制",
                                Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                            )
                        })

                    AnimatedDropdownMenuItem(
                        menuVisibility,
                        delay = 30,
                        text = { Text("编辑") },
                        onClick = {
                            onEdit(message)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Edit,
                                "编辑",
                                Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                            )
                        })

                    AnimatedDropdownMenuItem(
                        menuVisibility,
                        delay = 60,
                        text = { Text("重新回答") },
                        onClick = {
                            onRegenerate(message)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Refresh,
                                "重新回答",
                                Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                            )
                        })
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
   pressOffset: Offset = Offset.Zero
) {
   if (isVisible) {
       val density = LocalDensity.current
       val configuration = LocalConfiguration.current

       val menuWidthPx = with(density) { CONTEXT_MENU_FIXED_WIDTH.toPx() }
       val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
       val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

       val estimatedMenuHeightPx = with(density) { (48.dp * 2 + 16.dp).toPx() }

       // 将菜单左上角"精确对齐"到手指按压位置（向上微移 16dp）；如越界再进行边界夹紧
       val rawX = pressOffset.x
       val rawY = pressOffset.y- with(density) { 71.dp.toPx() }
 

       val finalX = rawX.coerceIn(0f, screenWidthPx - menuWidthPx)
       val finalY = rawY.coerceIn(0f, screenHeightPx - estimatedMenuHeightPx)

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
               shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS),
               color = MaterialTheme.colorScheme.surfaceDim,
               tonalElevation = 0.dp,
               modifier = Modifier
                   .width(CONTEXT_MENU_FIXED_WIDTH)
                   .shadow(
                       elevation = 8.dp,
                       shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS)
                   )
                   .padding(1.dp)
           ) {
               Column {
                   val menuVisibility = remember { MutableTransitionState(false) }
                   LaunchedEffect(isVisible) { menuVisibility.targetState = isVisible }

                   @Composable
                   fun AnimatedDropdownMenuItem(
                       visibleState: MutableTransitionState<Boolean>,
                       delay: Int = 0,
                       text: @Composable () -> Unit,
                       onClick: () -> Unit,
                       leadingIcon: @Composable (() -> Unit)? = null
                   ) {
                       AnimatedVisibility(
                           visibleState = visibleState,
                           enter = fadeIn(
                               animationSpec = tween(
                                   CONTEXT_MENU_ANIMATION_DURATION_MS,
                                   delayMillis = delay,
                                   easing = LinearOutSlowInEasing
                               )
                           ) + scaleIn(
                               animationSpec = tween(
                                   CONTEXT_MENU_ANIMATION_DURATION_MS,
                                   delayMillis = delay,
                                   easing = LinearOutSlowInEasing
                               ),
                               transformOrigin = TransformOrigin(0f, 0f)
                           ),
                           exit = fadeOut(
                               animationSpec = tween(
                                   CONTEXT_MENU_ANIMATION_DURATION_MS,
                                   easing = FastOutLinearInEasing
                               )
                           ) + scaleOut(
                               animationSpec = tween(
                                   CONTEXT_MENU_ANIMATION_DURATION_MS,
                                   easing = FastOutLinearInEasing
                               ),
                               transformOrigin = TransformOrigin(0f, 0f)
                           )
                       ) {
                           DropdownMenuItem(
                               text = text,
                               onClick = onClick,
                               leadingIcon = leadingIcon,
                               colors = MenuDefaults.itemColors(
                                   textColor = MaterialTheme.colorScheme.onSurface,
                                   leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                               )
                           )
                       }
                   }

                   AnimatedDropdownMenuItem(
                       menuVisibility,
                       text = { Text("查看图片") },
                       onClick = { onView(message) },
                       leadingIcon = {
                           Icon(
                               Icons.Outlined.Image,
                               "查看图片",
                               Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                           )
                       }
                   )

                   AnimatedDropdownMenuItem(
                       menuVisibility,
                       delay = 30,
                       text = { Text("下载图片") },
                       onClick = { onDownload(message) },
                       leadingIcon = {
                           Icon(
                               Icons.Outlined.Download,
                               "下载图片",
                               Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                           )
                       }
                   )
               }
           }
       }
   }
}