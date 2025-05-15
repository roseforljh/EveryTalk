package com.example.app1.ui.screens.BubbleMain.Main // 您的实际包名

import android.util.Log
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
import androidx.compose.foundation.shape.CircleShape // 需要为ThreeDotsLoadingAnimation导入
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentPaste // MyCodeBlockComposable 需要
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer // 需要为ThreeDotsLoadingAnimation导入
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight // MyCodeBlockComposable 需要
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.app1.data.DataClass.Message
// import com.example.app1.ui.screens.BubbleMain.MyCodeBlockComposable // 已在本文件中定义
// import com.example.app1.ui.screens.BubbleMain.TextSegment // 已在本文件中定义
// import com.example.app1.ui.screens.BubbleMain.ThreeDotsLoadingAnimation // 已在本文件中定义
// import com.example.app1.ui.screens.BubbleMain.extractStreamingCodeContent // 在本文件中定义但AiMessageContent不再直接调用
// import com.example.app1.ui.screens.BubbleMain.parseMarkdownSegments // 已在本文件中定义

import com.halilibo.richtext.markdown.Markdown // RichText 相关导入
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle

// 从 BubbleMain.kt 移过来的常量 (用于上下文菜单)
private const val CONTEXT_MENU_ANIMATION_DURATION_MS = 150 // 上下文菜单动画持续时间（毫秒）
private val CONTEXT_MENU_CORNER_RADIUS = 16.dp             // 上下文菜单圆角半径
private val CONTEXT_MENU_ITEM_ICON_SIZE = 20.dp            // 上下文菜单项图标大小
private val CONTEXT_MENU_FINE_TUNE_OFFSET_X = (-120).dp    // 上下文菜单X轴微调偏移量
private val CONTEXT_MENU_FINE_TUNE_OFFSET_Y = (-8).dp     // 上下文菜单Y轴微调偏移量
private val CONTEXT_MENU_FIXED_WIDTH = 120.dp              // 上下文菜单固定宽度

/**
 * Composable 用于渲染 AI 消息的内容（包括普通文本和代码块）。
 * 这是经过优化的版本，旨在减少流式输出结束时的“闪烁”。
 */
@Composable
internal fun AiMessageContent(
    // messageText: String, // 原始消息文本。在当前方案中，displayedText是主要数据源。保留它以备将来可能需要区分原始文本和显示文本的场景。
    displayedText: String,    // 当前用于显示的文本（流式传输时会逐步更新，结束后为最终文本）
    isStreaming: Boolean,     // 指示源数据是否仍在流式传输（可用于UI提示，但不再用于切换解析逻辑）
    showLoadingDots: Boolean, // 指示是否在气泡内容完全为空时显示初始加载点
    bubbleColor: Color,       // 气泡背景色
    contentColor: Color,      // 主要内容颜色（文本等）
    codeBlockBackgroundColor: Color, // 代码块背景色
    codeBlockContentColor: Color,    // 代码块内容颜色
    codeBlockCornerRadius: Dp,       // 代码块圆角
    codeBlockFixedWidth: Dp,         // 代码块固定宽度
    modifier: Modifier = Modifier    // 应用于此Composable根Column的修饰符
) {
    Column(modifier = modifier) { // AiMessageContent 的根是一个 Column
        if (showLoadingDots && displayedText.isBlank()) { // 如果需要显示初始加载点且当前无任何显示文本
            Surface( // 加载点的容器 Surface
                color = bubbleColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 0.dp, // 通常加载动画不需要额外的色调抬高
                modifier = Modifier
                    .align(Alignment.Start) // AI消息，加载点也靠左
                    .widthIn(min = 80.dp)   // 给加载气泡一个最小宽度，避免太窄
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Box( // 包裹加载动画的Box，用于内容对齐和最小尺寸
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .defaultMinSize(minHeight = 28.dp) // 确保加载气泡有一定高度
                ) {
                    ThreeDotsLoadingAnimation( // 三点加载动画
                        dotColor = contentColor,
                        modifier = Modifier.offset(y = (-6).dp) // 轻微向上偏移以优化视觉效果
                    )
                }
            }
        } else if (displayedText.isNotBlank()) { // 如果有要显示的文本（无论是流式还是最终）
            // --- 核心修改：始终基于 displayedText 解析片段 ---
            // `remember` 确保 `parseMarkdownSegments` 仅在 `displayedText` 变化时才重新执行
            val segments = remember(displayedText) {
                Log.d(
                    "AiMessageContent_Parse",
                    "解析 displayedText (长度 ${displayedText.length}): '${
                        displayedText.take(100).replace("\n", "\\n")
                    }'"
                )
                parseMarkdownSegments(displayedText.trim()) // 对当前显示的、裁剪过的文本进行解析
            }

            // 如果解析后没有片段，但原始显示文本（裁剪后）并不为空，
            // 这可能意味着解析器未能处理某些边缘情况，或者文本只有空白。
            // 作为回退，直接显示原始裁剪文本。
            if (segments.isEmpty() && displayedText.trim().isNotBlank()) {
                Surface(
                    color = bubbleColor,
                    contentColor = contentColor,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .widthIn(max = codeBlockFixedWidth + 30.dp) // 确保与代码块最大宽度行为一致
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        RichText( // 使用RichText渲染Markdown
                            style = RichTextStyle.Default.copy( // 自定义RichText样式
                                stringStyle = (RichTextStyle.Default.stringStyle
                                    ?: RichTextStringStyle.Default).copy(
                                    codeStyle = SpanStyle( //行内代码样式
                                        fontFamily = FontFamily.Monospace,
                                        background = Color.Transparent, // 或特定的行内代码背景色
                                        color = Color(0xFF5e5e5e),      // 行内代码文字颜色
                                        fontSize = 14.sp
                                    ),
                                    linkStyle = SpanStyle( //链接样式
                                        color = Color(0xFF4078f3),
                                        textDecoration = TextDecoration.None
                                    )
                                )
                            )
                        ) { Markdown(content = displayedText.trim()) } // 渲染裁剪过的原始显示文本
                    }
                }
            } else { // 如果成功解析出片段
                // 遍历并渲染每个片段
                segments.forEachIndexed { index, segment ->
                    when (segment) {
                        is TextSegment.Normal -> { // 普通文本片段
                            if (segment.text.isNotBlank()) { // 确保普通文本片段非空
                                Surface( // 普通文本的容器 Surface
                                    color = bubbleColor,
                                    contentColor = contentColor,
                                    shape = RoundedCornerShape(18.dp),
                                    tonalElevation = 0.dp,
                                    modifier = Modifier
                                        .align(Alignment.Start) // AI消息，文本靠左
                                        .widthIn(max = codeBlockFixedWidth + 30.dp) // 最大宽度
                                        .padding( // 根据相邻片段调整垂直间距
                                            start = 8.dp, end = 8.dp,
                                            top = if (index > 0 && segments.getOrNull(index - 1) is TextSegment.CodeBlock) 4.dp else 2.dp,
                                            bottom = if (index < segments.size - 1 && segments.getOrNull(
                                                    index + 1
                                                ) is TextSegment.CodeBlock
                                            ) 4.dp else 2.dp
                                        )
                                ) {
                                    Box( // 内边距容器
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 8.dp
                                        )
                                    ) {
                                        RichText(style = RichTextStyle.Default.copy( /* ... 您的RichText样式 ... */)) {
                                            Markdown(content = segment.text) // 渲染Markdown普通文本
                                        }
                                    }
                                }
                            }
                        }

                        is TextSegment.CodeBlock -> { // 代码块片段
                            MyCodeBlockComposable( // 调用自定义代码块组件
                                language = segment.language,
                                code = segment.code, // 代码内容（在流式传输时可能是部分的）
                                backgroundColor = codeBlockBackgroundColor,
                                contentColor = codeBlockContentColor,
                                cornerRadius = codeBlockCornerRadius,
                                fixedWidth = codeBlockFixedWidth,
                                showTopBar = true, // 是否显示代码块顶部栏（语言、复制按钮）
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally) // 代码块可以水平居中（相对于AI消息的起始位置）
                                    .padding(vertical = 4.dp, horizontal = 8.dp) // 外边距
                            )
                        }
                    }
                }
            }
        }
        // 如果 displayedText 为空且不显示初始加载点 (showLoadingDots == false)，
        // 则此处不渲染任何内容。这种情况可能发生在AI回复了一个合法的空消息，
        // 或者发生了错误但错误状态由 UserOrErrorMessageContent 处理。
    }
}


/**
 * Composable 用于渲染用户消息或 AI 错误消息的内容。
 */
@Composable
internal fun UserOrErrorMessageContent(
    message: Message,                // 完整消息对象，用于上下文菜单等
    displayedText: String,           // 当前要显示的文本
    showLoadingDots: Boolean,        // 是否显示加载点（通常仅用于用户消息发送前的短暂状态）
    bubbleColor: Color,              // 气泡背景色
    contentColor: Color,             // 内容颜色（普通用户文本色或AI错误文本色）
    isError: Boolean,                // 指示此消息是否为AI的错误消息
    maxWidth: Dp,                    // 气泡最大宽度
    onUserInteraction: () -> Unit,   // 用户交互回调
    onEditRequest: (Message) -> Unit, // 请求编辑消息的回调
    onRegenerateRequest: (Message) -> Unit, // 请求重新生成AI回答的回调
    modifier: Modifier = Modifier      // 应用于此Composable根Box的修饰符
) {
    // 控制上下文菜单的可见性
    var isContextMenuVisible by remember(message.id) { mutableStateOf(false) }
    // 记录长按位置，用于定位上下文菜单
    var pressOffset by remember(message.id) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current // 获取当前屏幕密度
    val context = LocalContext.current // 获取当前上下文
    val clipboardManager = LocalClipboardManager.current // 获取剪贴板管理器

    Box( // UserOrErrorMessageContent 的根是一个 Box
        modifier = modifier // 应用外部传入的修饰符 (通常不包含 align，对齐由 MessageBubble 控制)
            .widthIn(max = maxWidth) // 限制最大宽度
            .padding(horizontal = 8.dp, vertical = 2.dp) // 外边距
    ) {
        Surface( // 消息内容的 Surface
            color = bubbleColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(18.dp), // 圆角形状
            tonalElevation = if (isError) 0.dp else 1.dp, // 错误消息通常无抬高，用户消息略有抬高
            modifier = Modifier
                .pointerInput(message.id) { // 添加触摸输入处理器
                    detectTapGestures( // 检测手势
                        onLongPress = { offset -> // 长按手势
                            onUserInteraction() // 触发用户交互回调
                            if (!isError) { // 仅非AI错误的用户消息显示上下文菜单
                                pressOffset = offset // 记录按压位置
                                isContextMenuVisible = true // 显示上下文菜单
                            }
                        }
                    )
                }
        ) {
            Box( // 内容容器，用于对齐和最小尺寸
                contentAlignment = Alignment.CenterStart, // 内容向起始位置对齐
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp) // 内边距
                    .wrapContentWidth() // 包裹内容宽度
                    .defaultMinSize(minHeight = 28.dp) // 最小高度
            ) {
                if (showLoadingDots && !isError) { // 如果是用户消息且需要显示加载点（例如，发送中）
                    ThreeDotsLoadingAnimation( // 三点加载动画
                        dotColor = contentColor,
                        modifier = Modifier
                            .align(Alignment.Center) // 在Box内居中
                            .offset(y = (-6).dp)     // 视觉微调
                    )
                } else if (displayedText.isNotBlank() || isError) { // 如果有文本内容或这是一个错误消息
                    Text(
                        text = displayedText.trim(), // 显示裁剪后的文本
                        textAlign = TextAlign.Start, // 文本左对齐
                        color = contentColor         // 应用指定的文本颜色
                    )
                }
            }
        }

        // 上下文菜单 (仅用户消息且非错误时显示)
        if (isContextMenuVisible && !isError) {
            val dropdownMenuOffsetX =
                with(density) { pressOffset.x.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_X // 计算X轴偏移
            val dropdownMenuOffsetY =
                with(density) { pressOffset.y.toDp() } + CONTEXT_MENU_FINE_TUNE_OFFSET_Y // 计算Y轴偏移

            Popup( // 弹出层实现上下文菜单
                alignment = Alignment.TopStart, // 对齐方式
                offset = IntOffset( // 精确偏移
                    x = with(density) { dropdownMenuOffsetX.roundToPx() },
                    y = with(density) { dropdownMenuOffsetY.roundToPx() }),
                onDismissRequest = { isContextMenuVisible = false }, // 关闭请求
                properties = PopupProperties( // 弹出层属性
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false // 通常设为false以允许阴影等效果超出边界
                )
            ) {
                Surface( // 菜单背景
                    shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS), // 圆角
                    color = Color.White, // 背景色
                    tonalElevation = 0.dp, // 通常上下文菜单本身不需要色调抬高，由阴影提供层次
                    modifier = Modifier
                        .width(CONTEXT_MENU_FIXED_WIDTH) // 固定宽度
                        .shadow( // 添加阴影
                            elevation = 8.dp,
                            shape = RoundedCornerShape(CONTEXT_MENU_CORNER_RADIUS)
                        )
                        .padding(1.dp) // 轻微内边距，避免内容紧贴边缘
                ) {
                    Column { // 菜单项垂直排列
                        val menuVisibility =
                            remember { MutableTransitionState(false) } // 菜单项动画可见性状态
                        LaunchedEffect(isContextMenuVisible) { // 当isContextMenuVisible变化时更新动画目标状态
                            menuVisibility.targetState = isContextMenuVisible
                        }

                        // 封装的带动画的下拉菜单项
                        @Composable
                        fun AnimatedDropdownMenuItem(
                            visibleState: MutableTransitionState<Boolean>,
                            delay: Int = 0, // 动画延迟
                            text: @Composable () -> Unit, // 文本内容
                            onClick: () -> Unit, // 点击回调
                            leadingIcon: @Composable (() -> Unit)? = null // 前置图标
                        ) {
                            AnimatedVisibility( // 控制菜单项的动画显示/隐藏
                                visibleState = visibleState,
                                enter = fadeIn( // 进入动画：淡入 + 缩放
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
                                            ), transformOrigin = TransformOrigin(0f, 0f) // 缩放起始点
                                        ),
                                exit = fadeOut( // 退出动画：淡出 + 缩放
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
                                DropdownMenuItem( // Material 3 下拉菜单项
                                    text = text, onClick = onClick, leadingIcon = leadingIcon,
                                    colors = MenuDefaults.itemColors( // 自定义颜色
                                        textColor = MaterialTheme.colorScheme.onSurface,
                                        leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }

                        // 复制操作
                        AnimatedDropdownMenuItem(
                            menuVisibility,
                            text = { Text("复制") },
                            onClick = {
                                clipboardManager.setText(AnnotatedString(message.text)) // 复制消息文本
                                Toast.makeText(context, "内容已复制", Toast.LENGTH_SHORT).show()
                                isContextMenuVisible = false // 关闭菜单
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    "复制",
                                    Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                )
                            })

                        // 编辑操作
                        AnimatedDropdownMenuItem(
                            menuVisibility,
                            delay = 30, // 延迟出现，形成交错动画
                            text = { Text("编辑") },
                            onClick = {
                                onEditRequest(message) // 请求编辑
                                isContextMenuVisible = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Edit,
                                    "编辑",
                                    Modifier.size(CONTEXT_MENU_ITEM_ICON_SIZE)
                                )
                            })

                        // 重新回答操作
                        AnimatedDropdownMenuItem(
                            menuVisibility,
                            delay = 60,
                            text = { Text("重新回答") },
                            onClick = {
                                onRegenerateRequest(message) // 请求重新生成
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


// --- Animations.kt 内容 (ThreeDotsLoadingAnimation) ---
// package com.example.app1.ui.screens.BubbleMain // 替换为你的实际包名基础 + .ui.common (或Animations)

// (ThreeDotsLoadingAnimation 的 import 已经在文件顶部处理)

/**
 * 三点加载动画可组合项。此版本用于气泡内的文本加载指示。
 * @param modifier Modifier。
 * @param dotSize 点的大小。
 * @param dotColor 点的颜色。
 * @param spacing 点之间的间距。
 * @param bounceHeight 点跳动的高度。
 * @param scaleAmount 点缩放的幅度。
 * @param animationDuration 单个动画周期（跳起落下）的时长。
 */
@Composable
fun ThreeDotsLoadingAnimation( // 与推理部分的三点波浪动画(ThreeDotsWaveAnimation)略有不同或可统一
    modifier: Modifier = Modifier,
    dotSize: Dp = 10.dp,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    spacing: Dp = 10.dp,
    bounceHeight: Dp = 10.dp,     // 点向上跳动的高度
    scaleAmount: Float = 1.25f,   // 点在最高点时的缩放倍数
    animationDuration: Int = 450  // 单个点完成一次跳动动画（上、下）所需的时间
) {
    val infiniteTransition =
        rememberInfiniteTransition(label = "three_dots_loader_bubble_${dotColor.value.toULong()}") // 无限循环过渡

    // 为单个点创建Y轴偏移和缩放的动画值
    @Composable
    fun animateDot(delayMillis: Int): Pair<Float, Float> {
        val key = "dot_anim_bubble_${dotColor.value.toULong()}_$delayMillis" // 确保动画键唯一

        val yOffset by infiniteTransition.animateFloat( // Y轴偏移（跳动）
            initialValue = 0f,
            targetValue = 0f, // 实际动画在关键帧中定义
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = animationDuration * 3 // 总时长为单个周期的3倍，以错开动画
                    0f at (animationDuration * 0) + delayMillis with LinearEasing // 初始
                    -bounceHeight.value at (animationDuration * 1) + delayMillis with LinearEasing // 最高点
                    0f at (animationDuration * 2) + delayMillis with LinearEasing // 回到原位
                    0f at (animationDuration * 3) + delayMillis with LinearEasing // 等待下一轮
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "${key}_yOffset_bubble"
        )

        val scale by infiniteTransition.animateFloat( // 缩放
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = animationDuration * 3
                    1f at (animationDuration * 0) + delayMillis with LinearEasing
                    scaleAmount at (animationDuration * 1) + delayMillis with LinearEasing // 最大缩放
                    1f at (animationDuration * 2) + delayMillis with LinearEasing
                    1f at (animationDuration * 3) + delayMillis with LinearEasing
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "${key}_scale_bubble"
        )
        return Pair(yOffset, scale)
    }

    val dotsAnim = listOf( // 三个点的动画状态，通过延迟错开
        animateDot(0),
        animateDot(animationDuration / 3),
        animateDot(animationDuration * 2 / 3)
    )

    Row( // 横向排列点
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        dotsAnim.forEachIndexed { index, (yOffset, scale) ->
            Box(
                modifier = Modifier
                    .graphicsLayer { // 使用 graphicsLayer 进行高效变换
                        translationY = yOffset
                        scaleX = scale
                        scaleY = scale
                    }
                    .size(dotSize)
                    .background(dotColor.copy(alpha = 1f), shape = CircleShape)
            )
            if (index < dotsAnim.lastIndex) { // 点之间的间隔
                Spacer(Modifier.width(spacing))
            }
        }
    }
}

// --- MyCodeBlockComposable.kt 内容 ---
// package com.example.app1.ui.screens.BubbleMain // 替换为你的实际包名基础 + .ui.chat.views (或CodeBlock)

// (MyCodeBlockComposable 的 import 已经在文件顶部处理)

/**
 * 自定义代码块可组合项。
 * @param language 代码语言，可为null。
 * @param code 代码内容。
 * @param backgroundColor 背景颜色。
 * @param contentColor 内容颜色（文本、图标）。
 * @param cornerRadius 圆角大小。
 * @param fixedWidth 代码块的固定宽度。
 * @param showTopBar 是否显示顶部栏（包含语言名称和复制按钮）。
 * @param modifier Modifier。
 */
@Composable
fun MyCodeBlockComposable(
    language: String?,
    code: String,
    backgroundColor: Color,
    contentColor: Color,
    cornerRadius: Dp,
    fixedWidth: Dp,
    showTopBar: Boolean,
    modifier: Modifier = Modifier
) {
    Log.d(
        "MyCodeBlockComposable", // 日志标签
        "渲染中 - 语言: '$language', 代码预览: '${
            code.take(30).replace("\n", "\\n") // 预览代码前30字符，换行符转义
        }...', 显示顶部栏: $showTopBar, 背景色: $backgroundColor"
    )

    val clipboardManager = LocalClipboardManager.current // 获取剪贴板管理器
    val context = LocalContext.current // 获取当前上下文

    Column( // 代码块的根布局是一个垂直列
        modifier = modifier
            .width(fixedWidth) // 应用固定宽度
            .clip(RoundedCornerShape(cornerRadius)) // 圆角裁剪
            .background(backgroundColor) // 设置背景色
    ) {
        if (showTopBar) { // 如果需要显示顶部栏
            Row( // 顶部栏：包含语言名称和复制按钮
                modifier = Modifier
                    .fillMaxWidth() // 填充宽度
                    .padding(horizontal = 12.dp, vertical = 2.dp), // 内边距，调整以减少高度
                horizontalArrangement = Arrangement.SpaceBetween, // 子元素两端对齐
                verticalAlignment = Alignment.CenterVertically // 子元素垂直居中
            ) {
                Text( // 显示代码语言
                    text = language?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } // 语言名称首字母大写
                        ?: "Code", // 如果没有指定语言，默认为 "Code"
                    style = MaterialTheme.typography.labelSmall.copy( // 文本样式
                        color = contentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        lineHeight = 16.sp // 调整行高以匹配按钮
                    ),
                    modifier = Modifier.padding(start = 4.dp) // 左侧内边距
                )
                TextButton( // 复制按钮
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code)) // 将代码复制到剪贴板
                        Toast.makeText(context, "${language ?: "代码"}已复制", Toast.LENGTH_SHORT)
                            .show() // 显示提示
                    },
                    modifier = Modifier.heightIn(min = 28.dp), // 按钮最小高度
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp) // 按钮内边距
                ) {
                    Icon( // 复制图标
                        Icons.Outlined.ContentPaste, // 使用 Material Design Outlined 图标
                        contentDescription = "Copy code", // 无障碍描述
                        modifier = Modifier.size(16.dp), // 图标大小
                        tint = contentColor // 图标颜色
                    )
                    Spacer(Modifier.width(4.dp)) // 图标和文字之间的间距
                    Text( // "Copy" 文字
                        "Copy", style = MaterialTheme.typography.labelSmall.copy(
                            color = contentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            lineHeight = 16.sp, // 调整行高
                        )
                    )
                }
            }
        }
        // 代码文本本身
        Text(
            text = code, // 显示代码内容
            fontFamily = FontFamily.Monospace, // 使用等宽字体以保持代码对齐
            color = contentColor, // 文本颜色
            fontSize = 13.sp, // 字体大小
            lineHeight = 18.sp, // 行高
            modifier = Modifier
                .fillMaxWidth() // 填充宽度
                .padding( // 内边距
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 10.dp,
                    top = if (showTopBar) 6.dp else 10.dp // 如果有顶部栏，顶部内边距小一些
                )
        )
    }
}


// --- MarkdownUtils.kt 内容 ---
// package com.example.app1.ui.screens.BubbleMain // 替换为你的实际包名基础 + .util.markdown

// (MarkdownUtils 的 import 已经在文件顶部处理)

// --- 文本片段数据类定义 ---
sealed class TextSegment { // 密封类，表示文本可以被分割成的不同类型的片段
    data class Normal(val text: String) : TextSegment() // 普通文本片段
    data class CodeBlock(val language: String?, val code: String) : TextSegment() // 代码块片段，包含语言和代码内容
}

// --- 预编译的正则表达式 ---
// GFM (GitHub Flavored Markdown) 风格的闭合代码块正则表达式：
// 捕获语言提示（可选）和代码块内容。
// ```([a-zA-Z0-9_.-]*)      : 捕获组1，语言提示，允许字母、数字、下划线、点、中横线
// [ \t]*\n                 : 匹配可选的空格或制表符后跟一个换行符
// ([\s\S]*?)               : 捕获组2，代码内容，非贪婪匹配任何字符（包括换行符）
// \n```                    : 匹配一个换行符后跟三个反引号作为代码块结束
private val GFM_CLOSED_CODE_BLOCK_REGEX =
    Regex("```([a-zA-Z0-9_.-]*)[ \t]*\\n([\\s\\S]*?)\\n```")

// 匹配代码块开始标记 "```" 的正则表达式，用于处理剩余文本中可能的未闭合代码块
private val CODE_BLOCK_START_REGEX = Regex("```")

/**
 * 优化版的 Markdown 解析函数。
 * 将输入的Markdown字符串分割成 TextSegment 对象列表。
 * 它能识别GFM风格的闭合代码块 (例如 ```python\ncode\n```) 以及在文本末尾可能存在的未闭合代码块。
 */
fun parseMarkdownSegments(markdownInput: String): List<TextSegment> {
    if (markdownInput.isBlank()) { // 如果输入为空或仅包含空白字符，则返回空列表
        return emptyList()
    }

    val segments = mutableListOf<TextSegment>() // 用于存储解析出的片段
    var currentIndex = 0 // 当前在输入字符串中的处理位置

    Log.d(
        "ParseMarkdownOpt", // 日志标签
        "输入 (长度 ${markdownInput.length}):\nSTART_MD\n${ // 日志：记录输入文本（部分）
            markdownInput.take(200).replace("\n", "\\n") // 预览前200字符，换行符转义
        }...\nEND_MD"
    )

    // 查找第一个GFM风格的闭合代码块
    var matchResult = GFM_CLOSED_CODE_BLOCK_REGEX.find(markdownInput, currentIndex)

    while (matchResult != null) { // 当找到闭合代码块时循环
        val matchStart = matchResult.range.first // 匹配到的代码块的起始索引
        val matchEnd = matchResult.range.last   // 匹配到的代码块的结束索引

        Log.d(
            "ParseMarkdownOpt",
            "找到闭合代码块. 范围: ${matchResult.range}. 当前索引(之前): $currentIndex"
        )

        // 1. 处理当前查找到的闭合代码块之前的普通文本部分
        if (matchStart > currentIndex) { // 如果代码块不是从当前索引开始，则它们之间是普通文本
            val normalText = markdownInput.substring(currentIndex, matchStart)
            if (normalText.isNotBlank()) { // 仅当普通文本非纯空白时才添加
                segments.add(TextSegment.Normal(normalText.trim())) // 添加裁剪过的普通文本片段
                Log.d(
                    "ParseMarkdownOpt",
                    "添加普通文本 (闭合块之前): '${ // 日志：记录添加的普通文本
                        normalText.trim().take(50).replace("\n", "\\n")
                    }'"
                )
            }
        }

        // 2. 处理当前找到的闭合代码块
        val language = matchResult.groups[1]?.value?.trim()
            ?.takeIf { it.isNotEmpty() } // 提取语言提示 (捕获组1)，并去除首尾空白，如果结果非空则使用
        val code = matchResult.groups[2]?.value ?: "" // 提取代码内容 (捕获组2)，代码内容保持原始格式（不trim）
        segments.add(TextSegment.CodeBlock(language, code)) // 添加代码块片段
        Log.d(
            "ParseMarkdownOpt",
            "添加闭合代码块: 语言='$language', 代码='${
                code.take(50).replace("\n", "\\n")
            }'" // 日志：记录添加的代码块
        )

        currentIndex = matchEnd + 1 // 更新当前处理位置到此代码块之后
        matchResult = GFM_CLOSED_CODE_BLOCK_REGEX.find(markdownInput, currentIndex) // 继续查找下一个闭合代码块
    }

    Log.d(
        "ParseMarkdownOpt",
        "闭合代码块循环结束. 当前索引: $currentIndex, Markdown长度: ${markdownInput.length}"
    )

    // 3. 处理最后一个闭合代码块之后剩余的文本
    if (currentIndex < markdownInput.length) { // 如果在整个输入字符串的末尾还有剩余文本
        val remainingText = markdownInput.substring(currentIndex) // 获取剩余文本
        Log.d(
            "ParseMarkdownOpt",
            "剩余文本 (长度 ${remainingText.length}): '${ // 日志：记录剩余文本
                remainingText.take(100).replace("\n", "\\n")
            }'"
        )

        // 尝试在剩余文本中查找未闭合的代码块开始标记 "```"
        val openBlockMatch = CODE_BLOCK_START_REGEX.find(remainingText)
        if (openBlockMatch != null) { // 如果找到了 "```"
            val openBlockStartInRemaining = openBlockMatch.range.first // "```" 在剩余文本中的起始位置

            // a. "```" 之前的部分是普通文本
            if (openBlockStartInRemaining > 0) {
                val normalPrefix = remainingText.substring(0, openBlockStartInRemaining)
                if (normalPrefix.isNotBlank()) {
                    segments.add(TextSegment.Normal(normalPrefix.trim())) // 添加普通文本片段
                    Log.d(
                        "ParseMarkdownOpt",
                        "添加普通文本 (开放代码块前缀): '${ // 日志
                            normalPrefix.trim().take(50).replace("\n", "\\n")
                        }'"
                    )
                }
            }

            // b. "```" 之后的部分被视为未闭合代码块的内容
            val codeBlockCandidate =
                remainingText.substring(openBlockStartInRemaining + 3) // 跳过 "```" 本身
            // 尝试从此部分提取语言提示 (通常是 "```" 后的第一行)
            val firstNewlineIndex = codeBlockCandidate.indexOf('\n')
            var lang: String? = null
            var codeContent: String

            if (firstNewlineIndex != -1) { // 如果 "```lang" 这种形式后面有换行符
                val langLine = codeBlockCandidate.substring(0, firstNewlineIndex).trim() // 提取语言行并裁剪
                // 验证语言提示（允许字母、数字、下划线、点、中横线，以及常见的+号如c++）
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() } // 如果语言行有效且非空，则作为语言
                }
                codeContent = codeBlockCandidate.substring(firstNewlineIndex + 1) // 换行符之后的是代码内容
            } else { // "```lang" 后面没有换行，整行都可能是语言提示，代码内容为空或依赖后续流式输入
                val langLine = codeBlockCandidate.trim() // 整行作为语言候选并裁剪
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = "" // 对于没有换行符跟随的开放代码块，初始代码内容视为空
            }
            segments.add(TextSegment.CodeBlock(lang, codeContent)) // 添加开放代码块片段 (代码内容不trim)
            Log.d(
                "ParseMarkdownOpt",
                "从剩余文本添加开放代码块: 语言='$lang', 代码预览='${ // 日志
                    codeContent.take(50).replace("\n", "\\n")
                }'"
            )

        } else { // 剩余文本中没有 "```"，因此全部是普通文本
            if (remainingText.isNotBlank()) {
                segments.add(TextSegment.Normal(remainingText.trim()))
                Log.d(
                    "ParseMarkdownOpt",
                    "剩余文本是普通文本: '${ // 日志
                        remainingText.trim().take(50).replace("\n", "\\n")
                    }'"
                )
            }
        }
    }

    // 特殊处理：如果经过上述所有步骤后，segments 仍然为空，
    // 但原始 markdownInput 并非空白，这可能意味着整个输入是单一类型的文本。
    if (segments.isEmpty() && markdownInput.isNotBlank()) {
        Log.w(
            "ParseMarkdownOpt", // 警告日志
            "片段列表为空，但Markdown非空白. Markdown是否以 '```' 开头: ${ // 日志
                markdownInput.startsWith("```")
            }"
        )
        // 这种情况通常意味着整个输入是一个未闭合的代码块（且没有前导文本），
        // 或者整个输入就是一段普通文本。
        if (markdownInput.startsWith("```")) { // 如果整个输入以 "```" 开头
            val codeBlockCandidate = markdownInput.substring(3) // 获取 "```" 之后的内容
            val firstNewlineIndex = codeBlockCandidate.indexOf('\n')
            var lang: String? = null
            var codeContent: String
            if (firstNewlineIndex != -1) { // 同上，尝试解析语言和代码
                val langLine = codeBlockCandidate.substring(0, firstNewlineIndex).trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = codeBlockCandidate.substring(firstNewlineIndex + 1)
            } else {
                val langLine = codeBlockCandidate.trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = ""
            }
            segments.add(TextSegment.CodeBlock(lang, codeContent)) // 将整个输入视为一个开放代码块
            Log.d(
                "ParseMarkdownOpt",
                "将整个输入添加为开放代码块: 语言='$lang', 代码预览='${ // 日志
                    codeContent.take(50).replace("\n", "\\n")
                }'"
            )
        } else { // 否则，整个输入就是一段普通文本
            segments.add(TextSegment.Normal(markdownInput.trim()))
            Log.d(
                "ParseMarkdownOpt",
                "整个输入是普通文本 (片段为空且不以 ``` 开头)." // 日志
            )
        }
    }

    Log.i( // 信息日志
        "ParseMarkdownOpt",
        "最终片段数量: ${segments.size}, 类型: ${segments.map { it::class.simpleName }}" // 记录最终片段数量和类型
    )
    return segments // 返回解析出的片段列表
}

/**
 * 从已裁剪且以 "```" 开头的文本中提取流式代码内容和语言提示。
 * 注意：在“相对完美的解决方案”中，AiMessageContent 不再直接调用此函数，
 * 因为 parseMarkdownSegments 已能处理流式和最终的文本。
 * 此函数仍然保留，以防其他地方有特定用途，或者作为解析逻辑的参考。
 */
fun extractStreamingCodeContent(textAlreadyTrimmedAndStartsWithTripleQuote: String): Pair<String?, String> {
    Log.d(
        "ExtractStreamCode", // 日志标签
        "用于提取的输入: \"${ // 日志：记录输入文本（部分）
            textAlreadyTrimmedAndStartsWithTripleQuote.take(30).replace("\n", "\\n")
        }\""
    )
    val contentAfterTripleTicks =
        textAlreadyTrimmedAndStartsWithTripleQuote.substring(3) // 获取 "```" 之后的内容
    val firstNewlineIndex = contentAfterTripleTicks.indexOf('\n') // 查找第一个换行符

    if (firstNewlineIndex != -1) { // 如果找到了换行符 (即 ```lang\ncode 形式)
        val langHint = contentAfterTripleTicks.substring(0, firstNewlineIndex).trim() // 换行符之前的是语言提示
        val code = contentAfterTripleTicks.substring(firstNewlineIndex + 1) // 换行符之后的是代码内容
        // 验证语言提示
        val validatedLangHint =
            if (langHint.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                langHint.takeIf { it.isNotBlank() } // 有效且非空则采纳
            } else {
                null // 否则视为无语言提示
            }
        return Pair(validatedLangHint, code)
    } else { // 如果 "```" 之后没有换行符 (即 ```lang 或 ```)
        val langLine = contentAfterTripleTicks.trim() // 整行作为语言提示候选
        val validatedLangHint =
            if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                langLine.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return Pair(validatedLangHint, "") // 此时代码内容视为空字符串
    }
}