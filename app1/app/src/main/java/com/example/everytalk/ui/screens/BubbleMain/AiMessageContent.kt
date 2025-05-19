package com.example.everytalk.ui.screens.BubbleMain.Main // 您的实际包名

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState // 新增：用于可滚动文本
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer // 新增：用于文本选择
import androidx.compose.foundation.verticalScroll // 新增：用于可滚动文本
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
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
import androidx.compose.ui.platform.LocalConfiguration // 新增：用于获取屏幕尺寸
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog // 新增：用于弹出对话框
import androidx.compose.ui.window.DialogProperties // 新增：用于配置对话框属性
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
// Markdown 和 RichText 相关导入
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle

// 常量定义
private const val CONTEXT_MENU_ANIMATION_DURATION_MS = 150 // 上下文菜单动画持续时间（毫秒）
private val CONTEXT_MENU_CORNER_RADIUS = 16.dp             // 上下文菜单圆角半径
private val CONTEXT_MENU_ITEM_ICON_SIZE = 20.dp            // 上下文菜单项图标大小
private val CONTEXT_MENU_FINE_TUNE_OFFSET_X = (-120).dp
private val CONTEXT_MENU_FINE_TUNE_OFFSET_Y = (-8).dp
private val CONTEXT_MENU_FIXED_WIDTH = 160.dp              // 上下文菜单固定宽度

/**
 * 可动画的下拉菜单项。
 */
@Composable
internal fun AnimatedDropdownMenuItem(
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


/**
 * 用于显示AI消息内容，并处理长按弹出自定义上下文菜单（包含“复制”和“选择文本”）。
 */
@Composable
internal fun AiMessageContent(
    fullMessageTextToCopy: String, // 用于复制和在选择文本对话框中显示的完整AI消息文本
    displayedText: String,         // 当前用于流式显示的文本
    isStreaming: Boolean,          // 指示源数据是否仍在流式传输
    showLoadingDots: Boolean,      // 是否在气泡内容完全为空时显示初始加载点
    bubbleColor: Color,            // 气泡背景色
    contentColor: Color,           // 主要内容颜色
    codeBlockBackgroundColor: Color, // 代码块背景色
    codeBlockContentColor: Color,    // 代码块内容颜色
    codeBlockCornerRadius: Dp,       // 代码块圆角
    codeBlockFixedWidth: Dp,         // 代码块固定宽度
    onUserInteraction: () -> Unit,   // 用户交互回调
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val density = LocalDensity.current

    // AI气泡上下文菜单的状态
    var isAiContextMenuVisible by remember(fullMessageTextToCopy) { mutableStateOf(false) }
    var pressOffset by remember(fullMessageTextToCopy) { mutableStateOf(Offset.Zero) }

    // 新增：控制“选择文本”对话框的可见性状态
    var showSelectableTextDialog by remember(fullMessageTextToCopy) { mutableStateOf(false) }

    Column(
        modifier = modifier.pointerInput(fullMessageTextToCopy) {
            detectTapGestures(
                onLongPress = { offsetValue ->
                    onUserInteraction()
                    pressOffset = offsetValue
                    isAiContextMenuVisible = true
                    Log.d("AiMessageContextMenu", "AI消息长按. Offset: $offsetValue")
                }
            )
        }
    ) {
        // ... [原有的 showLoadingDots 和 displayedText.isNotBlank() 的渲染逻辑保持不变] ...
        if (showLoadingDots && displayedText.isBlank()) {
            Surface( /* ... 加载点 ... */
                color = bubbleColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 0.dp,
                modifier = Modifier
                    .align(Alignment.Start)
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
        } else if (displayedText.isNotBlank()) {
            val segments = remember(displayedText) {
                Log.d(
                    "AiMessageContent_Parse",
                    "解析 displayedText (长度 ${displayedText.length}): '${
                        displayedText.take(100).replace("\n", "\\n")
                    }'"
                )
                parseMarkdownSegments(displayedText.trim())
            }

            if (segments.isEmpty() && displayedText.trim().isNotBlank()) {
                Surface( /* ... 回退逻辑 ... */
                    color = bubbleColor,
                    contentColor = contentColor,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .widthIn(max = codeBlockFixedWidth + 30.dp)
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
                        ) { Markdown(content = displayedText.trim()) }
                    }
                }
            } else {
                segments.forEachIndexed { index, segment ->
                    when (segment) {
                        is TextSegment.Normal -> { /* ... 普通文本渲染 ... */
                            if (segment.text.isNotBlank()) {
                                Surface(
                                    color = bubbleColor,
                                    contentColor = contentColor,
                                    shape = RoundedCornerShape(18.dp),
                                    tonalElevation = 0.dp,
                                    modifier = Modifier
                                        .align(Alignment.Start)
                                        .widthIn(max = codeBlockFixedWidth + 30.dp)
                                        .padding(
                                            start = 8.dp, end = 8.dp,
                                            top = if (index > 0 && segments.getOrNull(index - 1) is TextSegment.CodeBlock) 4.dp else 2.dp,
                                            bottom = if (index < segments.size - 1 && segments.getOrNull(
                                                    index + 1
                                                ) is TextSegment.CodeBlock
                                            ) 4.dp else 2.dp
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

                        is TextSegment.CodeBlock -> { /* ... 代码块渲染 ... */
                            MyCodeBlockComposable(
                                language = segment.language,
                                code = segment.code,
                                backgroundColor = codeBlockBackgroundColor,
                                contentColor = codeBlockContentColor,
                                cornerRadius = codeBlockCornerRadius,
                                fixedWidth = codeBlockFixedWidth,
                                showTopBar = true,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }


        // AI 消息的上下文菜单
        if (isAiContextMenuVisible) {
            val aiMenuVisibility = remember { MutableTransitionState(false) }
            LaunchedEffect(isAiContextMenuVisible) {
                aiMenuVisibility.targetState = isAiContextMenuVisible
            }

            val dropdownMenuOffsetX =
                with(density) { pressOffset.x.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_X
            val dropdownMenuOffsetY =
                with(density) { pressOffset.y.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_Y

            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    x = with(density) { dropdownMenuOffsetX.roundToPx() },
                    y = with(density) { dropdownMenuOffsetY.roundToPx() }
                ),
                onDismissRequest = { isAiContextMenuVisible = false },
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
                        AnimatedDropdownMenuItem(
                            visibleState = aiMenuVisibility,
                            delay = 0,
                            text = { Text("复制") }, // 菜单项：复制
                            onClick = {
                                clipboardManager.setText(AnnotatedString(fullMessageTextToCopy))
                                Toast.makeText(context, "AI回复已复制", Toast.LENGTH_SHORT).show()
                                Log.d("AiMenu", "AI消息 - 复制操作已触发")
                                isAiContextMenuVisible = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "复制AI回复",
                                    Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                )
                            }
                        )
                        AnimatedDropdownMenuItem(
                            visibleState = aiMenuVisibility,
                            delay = 30,
                            text = { Text("选择文本") }, // 菜单项：选择文本
                            onClick = {
                                Log.d("AiMenu", "AI消息 - '选择文本' 操作被点击")
                                showSelectableTextDialog = true // <-- 修改：显示选择文本对话框
                                isAiContextMenuVisible = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.SelectAll, // 使用全选图标作为示例
                                    contentDescription = "选择文本",
                                    Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                )
                            }
                        )
                    }
                }
            }
        }

        // --- 新增：用于显示和选择文本的对话框 ---
        if (showSelectableTextDialog) {
            SelectableTextDialog(
                textToDisplay = fullMessageTextToCopy, // 将完整的AI消息文本传入
                onDismissRequest = { showSelectableTextDialog = false } // 关闭对话框的回调
            )
        }
    }
}

/**
 * 新增 Composable：用于显示可选择文本的对话框。
 * @param textToDisplay 需要在对话框中显示的完整文本。
 * @param onDismissRequest 当用户请求关闭对话框时的回调。
 */
@Composable
internal fun SelectableTextDialog(
    textToDisplay: String,
    onDismissRequest: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest, // 用户点击对话框外部或按返回键时调用
        properties = DialogProperties(
            dismissOnClickOutside = true,    // 允许点击外部关闭
            dismissOnBackPress = true,       // 允许按返回键关闭
            usePlatformDefaultWidth = false  // 不使用平台默认宽度，以便自定义对话框大小
        )
    ) {
        Card( // 使用Card作为对话框的背景和容器
            shape = RoundedCornerShape(28.dp), // 大圆角
            modifier = Modifier
                .fillMaxWidth(0.92f) // 对话框宽度为屏幕宽度的92%
                .padding(vertical = 24.dp) // 上下留出一些边距，避免过于贴近屏幕边缘
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.75f), // 最大高度为屏幕的75%
            colors = CardDefaults.cardColors(containerColor = Color.White) // 纯白背景
        ) {
            // SelectionContainer 使得其内部的Text组件支持文本选择功能
            SelectionContainer(
                modifier = Modifier.padding(20.dp) // 内容区域的内边距
            ) {
                Text(
                    text = textToDisplay, // 显示完整的AI消息文本（包含Markdown原文）
                    modifier = Modifier.verticalScroll(rememberScrollState()), // 如果文本过长，则允许垂直滚动
                    style = MaterialTheme.typography.bodyLarge // 使用合适、易读的文本样式
                )
            }
        }
    }
}





