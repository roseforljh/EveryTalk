package com.example.everytalk.ui.screens.BubbleMain.Main

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.model.SelectedMediaItem
import kotlinx.coroutines.launch

private const val CONTEXT_MENU_ANIMATION_DURATION_MS = 150
private val CONTEXT_MENU_CORNER_RADIUS = 16.dp
private val CONTEXT_MENU_ITEM_ICON_SIZE = 20.dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_X = (-120).dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_Y = (-8).dp
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
    onEditRequest: (Message) -> Unit,
    onRegenerateRequest: (Message) -> Unit,
    modifier: Modifier = Modifier,
    scrollStateManager: com.example.everytalk.ui.screens.MainScreen.chat.ChatScrollStateManager
) {
    var isContextMenuVisible by remember(message.id) { mutableStateOf(false) }
    var pressOffset by remember(message.id) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .widthIn(max = maxWidth)
            .padding(vertical = 2.dp)
    ) {
        Surface(
            color = bubbleColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = if (isError) 0.dp else 1.dp,
            modifier = Modifier
                .pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            if (!isError) {
                                pressOffset = offset
                                isContextMenuVisible = true
                            }
                        }
                    )
                }
        ) {
            Column {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .wrapContentWidth()
                        .defaultMinSize(minHeight = 28.dp)
                ) {
                    if (showLoadingDots && !isError) {
                        ThreeDotsLoadingAnimation(
                            dotColor = contentColor,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(y = (-6).dp)
                        )
                    } else if (displayedText.isNotBlank() || isError) {
                        // Use message.text as the single source of truth to avoid issues with
                        // recomposition and unstable displayedText state from the caller.
                        // By parsing displayedText, we enable real-time markdown rendering during streaming.
                        val segments = remember(message.text) {
                            parseMarkdownSegments(message.text)
                        }

                        Column {
                            segments.forEach { segment ->
                                when (segment) {
                                    is TextSegment.Normal -> if (segment.text.isNotBlank()) {
                                        val annotatedText = remember(segment.text) {
                                            com.example.everytalk.util.parseInlineMarkdownToAnnotatedString(segment.text)
                                        }
                                        Text(
                                            text = annotatedText,
                                            textAlign = TextAlign.Start,
                                            color = contentColor
                                        )
                                    }
                                    is TextSegment.CodeBlock -> {
                                        Surface(
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = segment.code,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                color = contentColor,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                            )
                                        }
                                    }
                                    is TextSegment.Header -> {
                                        val annotatedText = remember(segment.text) {
                                            com.example.everytalk.util.parseInlineMarkdownToAnnotatedString(segment.text)
                                        }
                                        Text(
                                            text = annotatedText,
                                            style = when (segment.level) {
                                                1 -> MaterialTheme.typography.headlineLarge
                                                2 -> MaterialTheme.typography.headlineMedium
                                                3 -> MaterialTheme.typography.headlineSmall
                                                else -> MaterialTheme.typography.bodyLarge
                                            },
                                            color = contentColor,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                    is TextSegment.ListItem -> {
                                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                            Text(
                                                text = "• ",
                                                color = contentColor,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            val annotatedText = remember(segment.text) {
                                                com.example.everytalk.util.parseInlineMarkdownToAnnotatedString(segment.text)
                                            }
                                            Text(
                                                text = annotatedText,
                                                color = contentColor,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isContextMenuVisible && !isError) {
            val dropdownMenuOffsetX =
                with(density) { pressOffset.x.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_X
            val dropdownMenuOffsetY =
                with(density) { pressOffset.y.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_Y

            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    x = with(density) { dropdownMenuOffsetX.roundToPx() },
                    y = with(density) { dropdownMenuOffsetY.roundToPx() }),
                onDismissRequest = { isContextMenuVisible = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS),
                    color = Color.White,
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
                        LaunchedEffect(isContextMenuVisible) {
                            menuVisibility.targetState = isContextMenuVisible
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
                                clipboardManager.setText(AnnotatedString(message.text))
                                Toast.makeText(context, "内容已复制", Toast.LENGTH_SHORT).show()
                                isContextMenuVisible = false
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
                                onEditRequest(message)
                                isContextMenuVisible = false
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
                                // 重置滚动状态
                                scrollStateManager.resetScrollState()
                                
                                // 执行重新回答请求
                                onRegenerateRequest(message)
                                isContextMenuVisible = false
                                
                                // 使用强制滚动方法，确保滚动到用户气泡的最底部
                                // 多次尝试滚动，确保在不同时间点都能滚动到底部
                                coroutineScope.launch {
                                    scrollStateManager.jumpToBottom()
                                }
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
}

@Composable
fun AttachmentsContent(
    attachments: List<SelectedMediaItem>,
    onAttachmentClick: (SelectedMediaItem) -> Unit,
    maxWidth: Dp,
    message: Message,
    onEditRequest: (Message) -> Unit,
    onRegenerateRequest: (Message) -> Unit,
    onImageLoaded: () -> Unit,
    scrollStateManager: com.example.everytalk.ui.screens.MainScreen.chat.ChatScrollStateManager
) {
    val context = LocalContext.current
    var isContextMenuVisible by remember(message.id) { mutableStateOf(false) }
    var pressOffset by remember(message.id) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val onLongPressHandler = { offset: Offset ->
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        pressOffset = offset
        isContextMenuVisible = true
    }

    Box {
        Column(
            modifier = Modifier.padding(top = 8.dp),
            horizontalAlignment = Alignment.End
        ) {
            attachments.forEach { attachment ->
                when (attachment) {
                    is SelectedMediaItem.ImageFromUri -> {
                        AsyncImage(
                            model = attachment.uri,
                            imageLoader = com.example.everytalk.util.AppImageLoader.get(context),
                            contentDescription = "Image attachment",
                            onSuccess = { _ -> onImageLoaded() },
                            modifier = Modifier
                                .widthIn(max = maxWidth * 0.8f)
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .pointerInput(message.id) {
                                    detectTapGestures(
                                        onTap = {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(attachment.uri, "image/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(intent)
                                        },
                                        onLongPress = onLongPressHandler
                                    )
                                }
                        )
                    }
                    is SelectedMediaItem.ImageFromBitmap -> {
                        AsyncImage(
                            model = attachment.bitmap,
                            imageLoader = com.example.everytalk.util.AppImageLoader.get(context),
                            contentDescription = "Image attachment",
                            onSuccess = { _ -> onImageLoaded() },
                            modifier = Modifier
                                .widthIn(max = maxWidth * 0.8f)
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .pointerInput(message.id) {
                                    detectTapGestures(
                                        onTap = { onAttachmentClick(attachment) },
                                        onLongPress = onLongPressHandler
                                    )
                                }
                        )
                    }
                    is SelectedMediaItem.GenericFile -> {
                        Row(
                            modifier = Modifier
                                .widthIn(max = maxWidth)
                                .padding(vertical = 4.dp)
                                .background(Color(0xFFF0F0F0), RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp))
                                .pointerInput(message.id) {
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
                                        onLongPress = onLongPressHandler
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = getIconForMimeType(attachment.mimeType),
                                contentDescription = "Attachment",
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = attachment.displayName ?: attachment.uri.path?.substringAfterLast('/') ?: "Attached File",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        if (isContextMenuVisible) {
            val dropdownMenuOffsetX =
                with(density) { pressOffset.x.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_X
            val dropdownMenuOffsetY =
                with(density) { pressOffset.y.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_Y

            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    x = with(density) { dropdownMenuOffsetX.roundToPx() },
                    y = with(density) { dropdownMenuOffsetY.roundToPx() }),
                onDismissRequest = { isContextMenuVisible = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS),
                    color = Color.White,
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
                        LaunchedEffect(isContextMenuVisible) {
                            menuVisibility.targetState = isContextMenuVisible
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

                        if (message.text.isNotBlank()) {
                            AnimatedDropdownMenuItem(
                                menuVisibility,
                                text = { Text("复制") },
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(message.text))
                                    Toast.makeText(context, "内容已复制", Toast.LENGTH_SHORT).show()
                                    isContextMenuVisible = false
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        "复制",
                                        Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                    )
                                })
                        }

                        AnimatedDropdownMenuItem(
                            menuVisibility,
                            delay = 30,
                            text = { Text("编辑") },
                            onClick = {
                                onEditRequest(message)
                                isContextMenuVisible = false
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
                                // 重置滚动状态
                                scrollStateManager.resetScrollState()
                                
                                // 执行重新回答请求
                                onRegenerateRequest(message)
                                isContextMenuVisible = false
                                
                                // 使用强制滚动方法，确保滚动到用户气泡的最底部
                                // 多次尝试滚动，确保在不同时间点都能滚动到底部
                                coroutineScope.launch {
                                    scrollStateManager.jumpToBottom()
                                }
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
}

sealed class TextSegment {
    data class Normal(val text: String) : TextSegment()
    data class CodeBlock(val language: String?, val code: String) : TextSegment()
    data class Header(val level: Int, val text: String) : TextSegment()
    data class ListItem(val text: String) : TextSegment()
}

fun parseMarkdownSegments(markdownInput: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    val lines = markdownInput.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmedLine = line.trim()

        when {
            // Code Blocks
            trimmedLine.startsWith("```") -> {
                val lang = trimmedLine.substring(3).trim()
                val codeLines = mutableListOf<String>()
                i++ // Move to next line
                while (i < lines.size && !lines[i].trim().equals("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                segments.add(TextSegment.CodeBlock(lang.ifEmpty { null }, codeLines.joinToString("\n")))
                if (i < lines.size) {
                    i++ // Skip the closing ```
                }
            }
            // Headers
            trimmedLine.startsWith("#") -> {
                val level = trimmedLine.takeWhile { it == '#' }.count()
                if (level <= 6) {
                    val text = trimmedLine.removePrefix("#".repeat(level)).trim()
                    segments.add(TextSegment.Header(level, text))
                    i++
                } else {
                    // Not a valid header, fall through to paragraph handling
                    val paragraphLines = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().isNotBlank() && !lines[i].trim().startsWith("```")) {
                        paragraphLines.add(lines[i])
                        i++
                    }
                    if (paragraphLines.isNotEmpty()) {
                        segments.add(TextSegment.Normal(paragraphLines.joinToString("\n")))
                    }
                }
            }
            // Unordered & Ordered List Items (handled one by one)
            trimmedLine.startsWith("* ") || trimmedLine.startsWith("- ") -> {
                segments.add(TextSegment.ListItem(trimmedLine.substring(2)))
                i++
            }
            trimmedLine.matches(Regex("^\\d+\\. .*")) -> {
                segments.add(TextSegment.ListItem(trimmedLine.substringAfter(". ")))
                i++
            }
            // Paragraphs
            trimmedLine.isNotBlank() -> {
                val paragraphLines = mutableListOf<String>()
                while (
                    i < lines.size &&
                    lines[i].trim().isNotBlank() &&
                    !lines[i].trim().startsWith("```") &&
                    !lines[i].trim().startsWith("#") &&
                    !lines[i].trim().startsWith("* ") &&
                    !lines[i].trim().startsWith("- ") &&
                    !lines[i].trim().matches(Regex("^\\d+\\. .*"))
                ) {
                    paragraphLines.add(lines[i])
                    i++
                }
                if (paragraphLines.isNotEmpty()) {
                    segments.add(TextSegment.Normal(paragraphLines.joinToString("\n")))
                }
            }
            // Blank lines
            else -> {
                i++
            }
        }
    }
    return segments
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
    return when (mimeType?.substringBefore('/')) {
        "application" -> Icons.Outlined.Article
        "text" -> Icons.Outlined.Article
        else -> Icons.Default.ContentCopy
    }
}