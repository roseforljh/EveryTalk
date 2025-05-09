package com.example.app1.ui.screens.BubbleMain.Main // 确保这是你正确的包名

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.app1.data.models.Message
import com.example.app1.ui.chat.views.MyCodeBlockComposable // 假设这个路径是正确的
import com.example.app1.ui.screens.BubbleMain.TextSegment // 假设这是你 MarkdownUtils 的正确路径
import com.example.app1.ui.screens.BubbleMain.ThreeDotsLoadingAnimation // 假设这是你 Animations 的正确路径
import com.example.app1.ui.screens.BubbleMain.extractStreamingCodeContent // 假设这是你 MarkdownUtils 的正确路径
import com.example.app1.ui.screens.BubbleMain.parseMarkdownSegments // 假设这是你 MarkdownUtils 的正确路径

import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle

// 从 BubbleMain.kt 移过来的常量
private const val CONTEXT_MENU_ANIMATION_DURATION_MS = 150
private val CONTEXT_MENU_CORNER_RADIUS = 16.dp
private val CONTEXT_MENU_ITEM_ICON_SIZE = 20.dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_X = (-120).dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_Y = (-8).dp
private val CONTEXT_MENU_FIXED_WIDTH = 120.dp

/**
 * Composable 用于渲染 AI 消息的内容（包括普通文本和代码块）。
 */
@Composable
internal fun AiMessageContent(
    messageText: String, // 完整的原始消息文本
    displayedText: String, // 当前用于显示的文本（可能是流式部分）
    isStreaming: Boolean,
    showLoadingDots: Boolean, // 指示是否显示初始加载点（不是连接中的那种）
    bubbleColor: Color,
    contentColor: Color,
    codeBlockBackgroundColor: Color,
    codeBlockContentColor: Color,
    codeBlockCornerRadius: Dp,
    codeBlockFixedWidth: Dp,
    modifier: Modifier = Modifier // 这个 modifier 会应用到下面的 Column
) {
    Column(modifier = modifier) { // AiMessageContent 的根是一个 Column
        if (showLoadingDots && displayedText.isBlank()) {
            Surface(
                color = bubbleColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 0.dp,
                modifier = Modifier // 这个 Surface 是 Column 的子元素
                    .align(Alignment.Start) // 在 ColumnScope 内对齐
                    .widthIn(min = 80.dp)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .defaultMinSize(minHeight = 28.dp)
                ) {
                    ThreeDotsLoadingAnimation(
                        dotColor = contentColor,
                        modifier = Modifier.offset(y = (-6).dp)
                    )
                }
            }
        } else if (isStreaming) {
            val trimmedTextForStreamCheck = displayedText.trimStart()
            val indexOfTripleQuote = trimmedTextForStreamCheck.indexOf("```")

            if (indexOfTripleQuote != -1) {
                val normalTextPrefix = trimmedTextForStreamCheck.substring(0, indexOfTripleQuote)
                val codeBlockCandidate = trimmedTextForStreamCheck.substring(indexOfTripleQuote)

                if (normalTextPrefix.isNotBlank()) {
                    Surface(
                        color = bubbleColor,
                        contentColor = contentColor,
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .align(Alignment.Start) // 在 ColumnScope 内对齐
                            .width(codeBlockFixedWidth + 30.dp)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            RichText(
                                style = RichTextStyle.Default.copy(
                                    stringStyle = (RichTextStyle.Default.stringStyle
                                        ?: RichTextStringStyle.Default).copy(
                                        codeStyle = SpanStyle(
                                            fontFamily = FontFamily.Monospace,
                                            background = Color.Transparent,
                                            color = Color(0xFF5e5e5e),
                                            fontSize = 14.sp
                                        ),
                                        linkStyle = SpanStyle(
                                            color = Color(0xFF4078f3),
                                            textDecoration = TextDecoration.None
                                        )
                                    )
                                )
                            ) { Markdown(content = normalTextPrefix) }
                        }
                    }
                }
                val (langHint, codeContent) = extractStreamingCodeContent(codeBlockCandidate)
                MyCodeBlockComposable(
                    language = langHint,
                    code = codeContent,
                    backgroundColor = codeBlockBackgroundColor,
                    contentColor = codeBlockContentColor,
                    cornerRadius = codeBlockCornerRadius,
                    fixedWidth = codeBlockFixedWidth,
                    showTopBar = true,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally) // 在 ColumnScope 内对齐
                        .padding(
                            vertical = if (normalTextPrefix.isNotBlank()) 4.dp else 0.dp,
                            horizontal = 8.dp
                        )
                )
            } else if (displayedText.isNotBlank()) {
                Surface(
                    color = bubbleColor,
                    contentColor = contentColor,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .align(Alignment.Start) // 在 ColumnScope 内对齐
                        .width(codeBlockFixedWidth + 30.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        RichText(
                            style = RichTextStyle.Default.copy(
                                stringStyle = (RichTextStyle.Default.stringStyle
                                    ?: RichTextStringStyle.Default).copy(
                                    codeStyle = SpanStyle(
                                        fontFamily = FontFamily.Monospace,
                                        background = Color.Transparent,
                                        color = Color(0xFF5e5e5e),
                                        fontSize = 14.sp
                                    ),
                                    linkStyle = SpanStyle(
                                        color = Color(0xFF4078f3),
                                        textDecoration = TextDecoration.None
                                    )
                                )
                            )
                        ) { Markdown(content = displayedText) }
                    }
                }
            }
        } else { // Not streaming (final content)
            val segments = remember(messageText) { parseMarkdownSegments(messageText) }

            if (segments.isEmpty() && messageText.isNotBlank()) {
                Surface(
                    color = bubbleColor,
                    contentColor = contentColor,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .align(Alignment.Start) // 在 ColumnScope 内对齐
                        .width(codeBlockFixedWidth + 30.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        RichText(
                            style = RichTextStyle.Default.copy(
                                stringStyle = (RichTextStyle.Default.stringStyle
                                    ?: RichTextStringStyle.Default).copy(
                                    codeStyle = SpanStyle(
                                        fontFamily = FontFamily.Monospace,
                                        background = Color.Transparent,
                                        color = Color(0xFF5e5e5e),
                                        fontSize = 14.sp
                                    ),
                                    linkStyle = SpanStyle(
                                        color = Color(0xFF4078f3),
                                        textDecoration = TextDecoration.None
                                    )
                                )
                            )
                        ) { Markdown(content = messageText.trim()) }
                    }
                }
            } else if (segments.isNotEmpty()) {
                segments.forEachIndexed { index, segment ->
                    when (segment) {
                        is TextSegment.Normal -> {
                            if (segment.text.isNotBlank()) {
                                Surface(
                                    color = bubbleColor,
                                    contentColor = contentColor,
                                    shape = RoundedCornerShape(18.dp),
                                    tonalElevation = 0.dp,
                                    modifier = Modifier
                                        .align(Alignment.Start) // 在 ColumnScope 内对齐
                                        .width(codeBlockFixedWidth + 30.dp)
                                        .padding(
                                            start = 8.dp, end = 8.dp,
                                            top = if (index > 0 && segments[index - 1] is TextSegment.CodeBlock) 4.dp else 2.dp,
                                            bottom = if (index < segments.size - 1 && segments[index + 1] is TextSegment.CodeBlock) 4.dp else 2.dp
                                        )
                                ) {
                                    Box(
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 8.dp
                                        )
                                    ) {
                                        RichText(
                                            style = RichTextStyle.Default.copy(
                                                stringStyle = (RichTextStyle.Default.stringStyle
                                                    ?: RichTextStringStyle.Default).copy(
                                                    codeStyle = SpanStyle(
                                                        fontFamily = FontFamily.Monospace,
                                                        background = Color.Transparent,
                                                        color = Color(0xFF5e5e5e),
                                                        fontSize = 14.sp
                                                    ),
                                                    linkStyle = SpanStyle(
                                                        color = Color(0xFF4078f3),
                                                        textDecoration = TextDecoration.None
                                                    )
                                                )
                                            )
                                        ) { Markdown(content = segment.text) }
                                    }
                                }
                            }
                        }

                        is TextSegment.CodeBlock -> {
                            MyCodeBlockComposable(
                                language = segment.language,
                                code = segment.code,
                                backgroundColor = codeBlockBackgroundColor,
                                contentColor = codeBlockContentColor,
                                cornerRadius = codeBlockCornerRadius,
                                fixedWidth = codeBlockFixedWidth,
                                showTopBar = true,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally) // 在 ColumnScope 内对齐
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Composable 用于渲染用户消息或 AI 错误消息的内容。
 */
@Composable
internal fun UserOrErrorMessageContent(
    message: Message, // 完整消息对象，用于上下文菜单
    displayedText: String,
    showLoadingDots: Boolean, // 仅用于用户消息的初始加载
    bubbleColor: Color,
    contentColor: Color, // 可能是普通用户文本色，也可能是错误色
    isError: Boolean, // AI消息是否为错误
    maxWidth: Dp,
    onUserInteraction: () -> Unit,
    onEditRequest: (Message) -> Unit,
    onRegenerateRequest: (Message) -> Unit,
    modifier: Modifier = Modifier // 这个 modifier 会应用到下面的 Box
) {
    var isContextMenuVisible by remember(message.id) { mutableStateOf(false) }
    var pressOffset by remember(message.id) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Box( // UserOrErrorMessageContent 的根是 Box
        modifier = modifier // 应用外部传入的 modifier (不含 align)
            .widthIn(max = maxWidth)
            .padding(horizontal = 8.dp, vertical = 2.dp)
        // 对齐由 BubbleMain.kt 中的父 Box 控制
    ) {
        Surface(
            color = bubbleColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = if (isError) 0.dp else 1.dp,
            modifier = Modifier // Surface 是 Box 的直接子元素，它会根据 Box 的 contentAlignment 对齐
                // 如果需要覆盖 Box 的 contentAlignment，可以在这里用 .align()
                // 例如: .align(Alignment.CenterStart)
                .pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            onUserInteraction()
                            if (!isError) { // 仅用户消息（非AI错误）显示上下文菜单
                                pressOffset = offset
                                isContextMenuVisible = true
                            }
                        }
                    )
                }
        ) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .wrapContentWidth()
                    .defaultMinSize(minHeight = 28.dp)
            ) {
                if (showLoadingDots && !isError) { // 用户消息的加载点
                    ThreeDotsLoadingAnimation(
                        dotColor = contentColor,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = (-6).dp) // ThreeDots 在 BoxScope 内对齐
                    )
                } else if (displayedText.isNotBlank() || isError) {
                    Text(
                        text = displayedText.trim(),
                        textAlign = TextAlign.Start,
                        color = contentColor
                    )
                }
            }
        }

        if (isContextMenuVisible && !isError) { // 仅用户消息（非AI错误）显示上下文菜单
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
                        val menuVisibility = remember { MutableTransitionState(false) }
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
                                onRegenerateRequest(message)
                                isContextMenuVisible = false
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