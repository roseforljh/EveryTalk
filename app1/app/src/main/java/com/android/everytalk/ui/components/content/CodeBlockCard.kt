package com.android.everytalk.ui.components.content
import com.android.everytalk.statecontroller.*

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import java.lang.Float.max
import java.lang.Float.min
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectTapGestures
import com.android.everytalk.R
import com.android.everytalk.ui.theme.chatColors
import com.android.everytalk.ui.components.syntax.SyntaxHighlightTheme
import com.android.everytalk.ui.components.FullScreenCodeViewerDialog
import com.android.everytalk.ui.components.WebPreviewLoadState
import com.android.everytalk.ui.components.WebPreviewContent
import com.android.everytalk.ui.components.syntax.HighlightCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 提供列表顶部位置的 CompositionLocal，用于代码块吸顶计算
 * 单位：px
 */
val LocalStickyHeaderTop = compositionLocalOf { Float.NaN }

internal const val GPT_CODE_BLOCK_CONTENT_PADDING_DP = 16f
internal const val CODE_BLOCK_FADE_HEIGHT_DP = 64f
internal const val CODE_BLOCK_TOP_FADE_HEADER_OVERLAP_DP = 10f
internal const val CODE_BLOCK_TOP_FADE_RAMP_DP = 24f
internal const val INLINE_WEB_PREVIEW_LOADING_HEIGHT_DP = 160f
internal const val INLINE_WEB_PREVIEW_ERROR_HEIGHT_DP = 220f
internal const val INLINE_WEB_PREVIEW_READY_HEIGHT_DP = 450f

/**
 * 计算代码块吸顶头部相对卡片顶部的下移距离（px）。
 */
internal fun resolveCodeBlockStickyOffsetPx(
    stickyTopPx: Float,
    cardTopPx: Float,
    cardHeightPx: Int,
    headerHeightPx: Int,
): Float {
    if (stickyTopPx.isNaN()) return 0f
    val diff = max(0f, stickyTopPx - cardTopPx)
    val maxOffset = max(0f, (cardHeightPx - headerHeightPx).toFloat())
    return min(diff, maxOffset)
}

/**
 * 计算代码块顶部渐隐强度（0f..1f）。
 * 当吸顶头部在代码块内下移或代码区内部发生纵向滚动时，平滑增强顶部透明渐变。
 */
internal fun resolveCodeBlockTopFadeStrength(
    stickyOffsetPx: Float,
    scrollOffsetPx: Int,
    rampPx: Float,
): Float {
    val effectiveScrollPx = max(
        stickyOffsetPx.coerceAtLeast(0f),
        scrollOffsetPx.coerceAtLeast(0).toFloat(),
    )
    if (effectiveScrollPx <= 0f) return 0f
    if (!rampPx.isFinite() || rampPx <= 0f) return 1f
    return (effectiveScrollPx / rampPx).coerceIn(0f, 1f)
}

/**
 * 代码块顶部/底部渐隐遮罩：通过离屏合成与 DstIn 混合模式直接降低代码内容自身透明度，
 * 露出代码块底色，过渡曲线与用户气泡收起时的底部渐隐保持一致。
 */
internal fun Modifier.codeBlockScrollFade(
    topStrength: () -> Float,
    bottomStrength: () -> Float = { 0f },
    fadeHeightDp: Float = CODE_BLOCK_FADE_HEIGHT_DP,
    clearAbovePx: () -> Float = { 0f },
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val topK = topStrength().coerceIn(0f, 1f)
        if (topK > 0f) {
            val startY = clearAbovePx().coerceIn(0f, size.height)
            fun a(v: Float) = Color.Black.copy(alpha = 1f - topK * (1f - v))
            if (startY > 0f) {
                drawRect(
                    color = a(0f),
                    topLeft = Offset.Zero,
                    size = Size(size.width, startY),
                    blendMode = BlendMode.DstIn,
                )
            }
            val remainingHeight = (size.height - startY).coerceAtLeast(0f)
            val h = fadeHeightDp.dp.toPx().coerceAtMost(remainingHeight)
            if (h > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to a(0f),
                        0.15f to a(0.1f),
                        0.4f to a(0.45f),
                        0.7f to a(0.86f),
                        1f to a(1f),
                        startY = startY,
                        endY = startY + h,
                    ),
                    topLeft = Offset(0f, startY),
                    size = Size(size.width, h),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

        val bottomK = bottomStrength().coerceIn(0f, 1f)
        if (bottomK > 0f) {
            val h = fadeHeightDp.dp.toPx().coerceAtMost(size.height)
            if (h > 0f) {
                val top = size.height - h
                fun b(v: Float) = Color.Black.copy(alpha = 1f - bottomK * (1f - v))
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to b(1f),
                        0.3f to b(0.86f),
                        0.6f to b(0.45f),
                        0.85f to b(0.1f),
                        1f to b(0f),
                        startY = top,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, h),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }

/** 实测高度优先；超长网页保留原来的内联上限，完整交互仍在全屏预览中进行。 */
internal fun resolveInlineWebPreviewHeightDp(
    state: WebPreviewLoadState,
    contentHeightDp: Float? = null,
): Float = when (state) {
    WebPreviewLoadState.LOADING -> INLINE_WEB_PREVIEW_LOADING_HEIGHT_DP
    WebPreviewLoadState.ERROR -> INLINE_WEB_PREVIEW_ERROR_HEIGHT_DP
    WebPreviewLoadState.READY -> contentHeightDp
        ?.takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(INLINE_WEB_PREVIEW_LOADING_HEIGHT_DP, INLINE_WEB_PREVIEW_READY_HEIGHT_DP)
        ?: INLINE_WEB_PREVIEW_READY_HEIGHT_DP
}

internal fun resolveCodeBlockScrollTarget(isStreaming: Boolean, maxValue: Int): Int {
    return if (isStreaming) maxValue.coerceAtLeast(0) else 0
}

internal fun shouldAnimateCodeBlockContentSize(
    isStreaming: Boolean,
    suppressInitialAsyncResizeAnimation: Boolean,
    initialAsyncResizeSettled: Boolean,
): Boolean = isStreaming ||
    !suppressInitialAsyncResizeAnimation ||
    initialAsyncResizeSettled

internal const val CODE_BLOCK_VISIBLE_LINE_LIMIT = 60
internal const val CODE_BLOCK_VISIBLE_CHAR_LIMIT = 4_000

/**
 * 卡片内代码区限高 400dp 且不能内部滚动：完成态只露出开头，流式只露出末尾。
 * 只排版、高亮这一段可见窗口；几千行代码整段排版会把列表加载和代码/预览切换卡住数百毫秒。
 * 60 行在任何字号下都高于 400dp，可见效果与整段排版一致；复制、全屏、预览仍使用完整代码。
 */
internal fun codeBlockVisibleWindow(code: String, isStreaming: Boolean): String {
    if (isStreaming) {
        var cut = code.length
        repeat(CODE_BLOCK_VISIBLE_LINE_LIMIT) { if (cut >= 0) cut = code.lastIndexOf('\n', cut - 1) }
        val tail = if (cut < 0) code else code.substring(cut + 1)
        return if (tail.length > CODE_BLOCK_VISIBLE_CHAR_LIMIT) tail.takeLast(CODE_BLOCK_VISIBLE_CHAR_LIMIT) else tail
    }
    var end = -1
    repeat(CODE_BLOCK_VISIBLE_LINE_LIMIT) {
        end = code.indexOf('\n', end + 1)
        if (end < 0) return code.take(CODE_BLOCK_VISIBLE_CHAR_LIMIT)
    }
    return code.substring(0, end).take(CODE_BLOCK_VISIBLE_CHAR_LIMIT)
}

private data class HighlightedCodeState(
    val text: AnnotatedString,
    val asyncHighlightReady: Boolean,
)

/**
 * 代码块卡片组件
 *
 * 特性：
 * - 圆角边框
 * - 顶部操作栏（语言标签 + 复制/预览按钮）
 * - 代码内容区域（等宽字体）
 * - 语法高亮（支持 HTML/CSS/JavaScript/Python/Kotlin/JSON）
 * - 吸顶效果（Header 在列表滚动时保持在顶部）
 */
@Composable
fun CodeBlockCard(
    language: String?,
    code: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    suppressInitialAsyncResizeAnimation: Boolean = false,
    onCopy: (() -> Unit)? = null,
    onPreviewRequested: (() -> Unit)? = null,
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    /** AI 深色气泡中的代码块不单独画卡片。 */
    flatDarkStyle: Boolean = false,
) {
    val shape = RoundedCornerShape(if (flatDarkStyle) 16.dp else 24.dp)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val isDarkTheme = isSystemInDarkTheme()
    val codeLabel = stringResource(R.string.code_label)
    val copyDescription = stringResource(R.string.action_copy)
    val previewDescription = stringResource(R.string.code_preview)
    val openFullScreenPreviewDescription = stringResource(R.string.code_open_fullscreen_preview)
    val bg = MaterialTheme.chatColors.codeBlockBackground
    // 深色气泡里也要能和纯黑背景分开：底色用比背景亮一档的 #1F1F1F，再画一圈细边框。
    val borderStroke = if (isDarkTheme) {
        BorderStroke(1.dp, Color.White.copy(alpha = if (flatDarkStyle) 0.14f else 0.22f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    }
    
    // 根据主题设置头部内容颜色：亮色系为黑色，暗色系为白色
    val headerContentColor = if (isDarkTheme) {
        Color.White
    } else {
        Color.Black
    }
    
    // 获取语法高亮主题
    val syntaxTheme = remember(isDarkTheme) {
        if (isDarkTheme) SyntaxHighlightTheme.Dark else SyntaxHighlightTheme.Light
    }
    
    val windowCode = remember(code, isStreaming) { codeBlockVisibleWindow(code, isStreaming) }
    val shouldHighlightCode = remember(windowCode, isStreaming) {
        !isStreaming && HighlightCache.shouldHighlight(windowCode, isStreaming = false)
    }
    val highlightedCodeState by produceState(
        HighlightedCodeState(
            text = AnnotatedString(windowCode),
            asyncHighlightReady = !shouldHighlightCode,
        ),
        windowCode,
        language,
        isDarkTheme,
        isStreaming,
    ) {
        // produceState 会保留上一轮的值；每次输入变化都先更新原文，避免超过高亮上限后停在旧内容。
        value = HighlightedCodeState(AnnotatedString(windowCode), !shouldHighlightCode)
        if (shouldHighlightCode) {
            value = HighlightedCodeState(
                text = withContext(Dispatchers.Default) {
                    HighlightCache.highlight(windowCode, language, isDarkTheme, syntaxTheme)
                },
                asyncHighlightReady = true,
            )
        }
    }
    // 流式原文同步显示，不等待异步高亮；完成后才切换到当前窗口的高亮结果。
    val visibleCode = remember(windowCode, isStreaming, highlightedCodeState.text) {
        if (isStreaming || highlightedCodeState.text.text != windowCode) AnnotatedString(windowCode)
        else highlightedCodeState.text
    }
    var initialAsyncResizeSettled by remember(
        code,
        language,
        isDarkTheme,
        isStreaming,
        suppressInitialAsyncResizeAnimation,
    ) {
        mutableStateOf(
            !suppressInitialAsyncResizeAnimation || isStreaming || !shouldHighlightCode
        )
    }
    LaunchedEffect(
        highlightedCodeState.asyncHighlightReady,
        suppressInitialAsyncResizeAnimation,
        isStreaming,
    ) {
        if (
            suppressInitialAsyncResizeAnimation &&
            !isStreaming &&
            highlightedCodeState.asyncHighlightReady &&
            !initialAsyncResizeSettled
        ) {
            withFrameNanos { }
            initialAsyncResizeSettled = true
        }
    }
    val animateContentResize = shouldAnimateCodeBlockContentSize(
        isStreaming = isStreaming,
        suppressInitialAsyncResizeAnimation = suppressInitialAsyncResizeAnimation,
        initialAsyncResizeSettled = initialAsyncResizeSettled,
    )
    
    // 规范化语言标签
    val displayLanguage = language?.trim()?.ifBlank { codeLabel }?.uppercase() ?: codeLabel.uppercase()
    
    // 判断是否支持预览
    val canPreview = onPreviewRequested != null && isPreviewSupported(language)

    // 胶囊组件底色与选中颜色
    // 由于代码块背景现在是深黑(0xFF2A2A2A)，胶囊背景需要稍微亮一点以产生对比，选中的按钮则更亮
    val capsuleBgColor = if (isDarkTheme) Color(0xFF383838) else Color(0xFFE2E2E2)
    val capsuleSelectedBgColor = if (isDarkTheme) Color(0xFF505050) else Color.White
    val pageBgColor = MaterialTheme.colorScheme.background
    val previewBgColor = pageBgColor
    val previewTextColor = if (isDarkTheme) Color(0xFFEAEAEA) else Color.Black
    
    // 预览模式状态
    var isPreviewMode by remember { mutableStateOf(false) }
    var previewLoadState by remember(code, language) {
        mutableStateOf(WebPreviewLoadState.LOADING)
    }
    var previewContentHeightDp by remember(code, language) { mutableStateOf<Float?>(null) }
    var showFullScreenPreview by remember { mutableStateOf(false) }
    var cardBoundsInWindow by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // 吸顶逻辑状态
    val stickyTop = LocalStickyHeaderTop.current
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var cardTopPx by remember { mutableFloatStateOf(0f) }
    var cardHeightPx by remember { mutableIntStateOf(0) }

    val previewRevealAlpha by animateFloatAsState(
        targetValue = if (isPreviewMode) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "previewRevealAlpha"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(onLongPress) {
                if (onLongPress != null) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            onLongPress(offset)
                        },
                        onTap = { /* Allow click pass-through */ }
                    )
                }
            }
            .onGloballyPositioned { coordinates ->
                cardBoundsInWindow = coordinates.boundsInWindow()
                val newTop = coordinates.positionInWindow().y
                val newHeight = coordinates.size.height
                // 仅当位置或高度发生显著变化时更新状态，减少不必要的重组
                if (Math.abs(cardTopPx - newTop) > 1f || cardHeightPx != newHeight) {
                    cardTopPx = newTop
                    cardHeightPx = newHeight
                }
            },
        shape = shape,
        color = bg,
        border = borderStroke,
        // 注意这里：即使内部也有拦截，表面也需要允许点击以防止穿透到底层列表
        onClick = { showFullScreenPreview = true } 
    ) {
        Box {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(previewBgColor.copy(alpha = previewRevealAlpha))
            )

            // 使用 Column 布局：Header 在上，Content 在下，自然堆叠避免遮挡。
            // 通过 translationY 实现 Header 的视觉吸顶，同时利用 zIndex 确保其在滚动时覆盖 Content。
            Column {
            // 顶部操作栏 (Header)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f) // 确保在内容之上绘制
                    // 动态吸顶偏移：使用 graphicsLayer 在绘制阶段计算偏移
                    .graphicsLayer {
                        // 直接在绘制阶段读取状态并计算偏移
                        translationY = resolveCodeBlockStickyOffsetPx(
                            stickyTopPx = stickyTop,
                            cardTopPx = cardTopPx,
                            cardHeightPx = cardHeightPx,
                            headerHeightPx = headerHeightPx,
                        )
                    }
                    // 代码模式下由下方内容层的离屏透明渐隐露出卡片底色，不再用实心色块硬切代码
                    .background(previewBgColor.copy(alpha = previewRevealAlpha))
                    .onSizeChanged { headerHeightPx = it.height },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically // 垂直居中对齐，与右侧按钮对齐
            ) {
                // 左侧：语言标签
                // 背景直接透明，只显示文字
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp) // 增加左边距和垂直间距，使其与右侧对称并且显得不那么小
                ) {
                    Text(
                        text = displayLanguage,
                        style = MaterialTheme.typography.bodyMedium.copy( // 字体进一步增大
                            fontFamily = FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, // 加粗
                            letterSpacing = 1.sp,
                            fontSize = 14.sp
                        ),
                        color = headerContentColor
                    )
                }
                
                // 右侧：按钮组
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 8.dp), // 增加垂直间距，让头部整体更高
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp) // 按钮组之间的间距
                ) {
                    // 复制按钮 (左侧独立)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50)) // 只需要 clip 裁剪涟漪即可，不需要显式设置背景色
                            .clickable {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                                    onCopy?.invoke()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_gpt_copy),
                            contentDescription = copyDescription,
                            tint = headerContentColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // 预览相关按钮组 (如果有预览功能的话，右侧包裹在一个胶囊内)
                    if (canPreview) {
                        val indicatorWidth = 36.dp
                        val capsuleWidth = 72.dp + 4.dp // 2 buttons + 4dp padding total (2dp each side)
                        val indicatorOffset by animateDpAsState(
                            targetValue = if (isPreviewMode) indicatorWidth else 0.dp,
                            animationSpec = tween(durationMillis = 300),
                            label = "indicatorOffset"
                        )

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = capsuleBgColor, // 胶囊底色
                            modifier = Modifier.size(width = capsuleWidth, height = 40.dp) // 36dp button + 4dp total padding
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                // 滑块指示器
                                Box(
                                    modifier = Modifier
                                        .padding(2.dp)
                                        .offset {
                                            IntOffset(
                                                x = indicatorOffset.roundToPx(),
                                                y = 0,
                                            )
                                        }
                                        .size(36.dp)
                                        .background(capsuleSelectedBgColor, RoundedCornerShape(50))
                                )

                                // 按钮图标层
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 代码按钮
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(50))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { isPreviewMode = false },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_gpt_terminal),
                                            contentDescription = codeLabel,
                                            tint = headerContentColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    // 预览按钮
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(50))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                // 已在预览中时不伪造新的加载状态，否则高度会缩小而页面并未重载。
                                                if (!isPreviewMode) {
                                                    previewLoadState = WebPreviewLoadState.LOADING
                                                    previewContentHeightDp = null
                                                }
                                                isPreviewMode = true
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_gpt_play),
                                            contentDescription = previewDescription,
                                            tint = headerContentColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            var contentBoxHeightPx by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            val isAtMaxHeight = with(density) { contentBoxHeightPx >= 400.dp.toPx() - 1f }
            val topFadeRampPx = with(density) { CODE_BLOCK_TOP_FADE_RAMP_DP.dp.toPx() }
            val topHeaderOverlapPx = with(density) { CODE_BLOCK_TOP_FADE_HEADER_OVERLAP_DP.dp.toPx() }
            val codeScrollState = rememberScrollState()

            LaunchedEffect(code, isStreaming, codeScrollState.maxValue) {
                codeScrollState.scrollTo(
                    resolveCodeBlockScrollTarget(
                        isStreaming = isStreaming,
                        maxValue = codeScrollState.maxValue
                    )
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (animateContentResize) {
                            Modifier.animateContentSize(
                                animationSpec = tween(durationMillis = 220)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .background(Color.Transparent)
                    .codeBlockScrollFade(
                        topStrength = {
                            if (isPreviewMode) {
                                0f
                            } else {
                                val stickyOffsetPx = resolveCodeBlockStickyOffsetPx(
                                    stickyTopPx = stickyTop,
                                    cardTopPx = cardTopPx,
                                    cardHeightPx = cardHeightPx,
                                    headerHeightPx = headerHeightPx,
                                )
                                resolveCodeBlockTopFadeStrength(
                                    stickyOffsetPx = stickyOffsetPx,
                                    scrollOffsetPx = codeScrollState.value,
                                    rampPx = topFadeRampPx,
                                )
                            }
                        },
                        bottomStrength = {
                            if (!isPreviewMode && isAtMaxHeight) 1f else 0f
                        },
                        clearAbovePx = {
                            val stickyOffsetPx = resolveCodeBlockStickyOffsetPx(
                                stickyTopPx = stickyTop,
                                cardTopPx = cardTopPx,
                                cardHeightPx = cardHeightPx,
                                headerHeightPx = headerHeightPx,
                            )
                            (stickyOffsetPx - topHeaderOverlapPx).coerceAtLeast(0f)
                        },
                    )
            ) {
                if (isPreviewMode) {
                    // 内联渲染网页
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(resolveInlineWebPreviewHeightDp(previewLoadState, previewContentHeightDp).dp)
                            .padding(horizontal = 0.dp, vertical = 0.dp)
                            .graphicsLayer {
                                alpha = previewRevealAlpha
                            },
                        shape = RoundedCornerShape(24.dp), // 修改为四角均为 24.dp 以符合直觉
                        color = previewBgColor
                    ) {
                        // 在此表面叠加一个透明遮罩，用于统一捕获点击事件
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (!showFullScreenPreview) {
                                WebPreviewContent(
                                    code = code,
                                    language = language ?: "",
                                    previewBackgroundColor = previewBgColor,
                                    previewTextColor = previewTextColor,
                                    modifier = Modifier.fillMaxSize(),
                                    onLoadStateChanged = { previewLoadState = it },
                                    onContentHeightChanged = { previewContentHeightDp = it },
                                )
                                // 拦截层，位于 WebView 之上
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Transparent)
                                        .semantics {
                                            contentDescription = openFullScreenPreviewDescription
                                        }
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = openFullScreenPreviewDescription,
                                            onClick = { showFullScreenPreview = true },
                                        )
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .clipToBounds()
                            .verticalScroll(codeScrollState, enabled = false)
                            .onSizeChanged { contentBoxHeightPx = it.height }
                    ) {
                        Text(
                            text = visibleCode,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            softWrap = true,
                            modifier = Modifier
                                .padding(GPT_CODE_BLOCK_CONTENT_PADDING_DP.dp)
                        )
                    }
                }
            }
            
            if (showFullScreenPreview) {
                FullScreenCodeViewerDialog(
                    code = code,
                    language = language ?: "",
                    initialPreviewMode = isPreviewMode,
                    sourceBounds = cardBoundsInWindow,
                    isStreaming = isStreaming,
                    onDismiss = { showFullScreenPreview = false }
                )
            }
        }
    }
    }
}

/**
 * 检查语言是否支持 Web 预览
 */
fun isPreviewSupported(language: String?): Boolean {
    val lang = language?.trim()?.lowercase() ?: return false
    return when (lang) {
        "html", "svg", "xml", 
        "mermaid", 
        "echarts", 
        "chartjs", 
        "flowchart", "flow", 
        "vega", "vega-lite",
        "infographic" -> true
        else -> false
    }
}
