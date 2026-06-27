package com.android.everytalk.ui.components.content

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.zIndex
import java.lang.Float.max
import java.lang.Float.min
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.android.everytalk.R
import com.android.everytalk.ui.theme.chatColors
import com.android.everytalk.ui.components.syntax.SyntaxHighlighter
import com.android.everytalk.ui.components.syntax.SyntaxHighlightTheme
import com.android.everytalk.ui.components.FullScreenCodeViewerDialog
import com.android.everytalk.ui.components.WebPreviewContent
import com.android.everytalk.ui.components.syntax.HighlightCache
import kotlinx.coroutines.launch

/**
 * 提供列表顶部位置的 CompositionLocal，用于代码块吸顶计算
 * 单位：px
 */
val LocalStickyHeaderTop = compositionLocalOf { Float.NaN }

internal const val GPT_CODE_BLOCK_CONTENT_PADDING_DP = 16f

internal fun resolveCodeBlockScrollTarget(isStreaming: Boolean, maxValue: Int): Int {
    return if (isStreaming) maxValue.coerceAtLeast(0) else 0
}

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
    onCopy: (() -> Unit)? = null,
    onPreviewRequested: (() -> Unit)? = null,
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null
) {
    val shape = RoundedCornerShape(24.dp)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val isDarkTheme = isSystemInDarkTheme()
    val bg = MaterialTheme.chatColors.codeBlockBackground
    // 夜间模式使用白色边框，白天模式使用 outline 颜色
    val outline = if (isDarkTheme) {
        Color.White.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
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
    
    // 语法高亮处理（带缓存）
    // 流式模式：降低高亮频率，只在代码较短或变化较大时高亮
    val highlightedCode = remember(code, language, isDarkTheme, isStreaming) {
        // 流式模式下，代码较长时跳过高亮，直接返回纯文本
        // 这样可以避免频繁高亮导致的闪烁
        if (isStreaming && code.length > 500) {
            AnnotatedString(code)
        } else {
            val cacheKey = HighlightCache.generateKey(code, language, isDarkTheme)

            // 尝试从缓存获取
            HighlightCache.get(cacheKey) ?: run {
                // 缓存未命中，执行高亮
                val result = SyntaxHighlighter.highlight(code, language, syntaxTheme)
                // 存入缓存
                HighlightCache.put(cacheKey, result)
                result
            }
        }
    }
    
    // 规范化语言标签
    val displayLanguage = language?.trim()?.ifBlank { "CODE" }?.uppercase() ?: "CODE"
    
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
                    .background(Color.Transparent)  // 移除显式背景色，跟随 Surface
                    .zIndex(1f) // 确保在内容之上绘制
                    // 动态吸顶偏移：使用 graphicsLayer 在绘制阶段计算偏移
                    .graphicsLayer {
                        // 直接在绘制阶段读取状态并计算偏移
                        translationY = if (stickyTop.isNaN()) {
                            0f
                        } else {
                            val diff = stickyTop - cardTopPx
                            val offset = max(0f, diff)
                            val maxOffset = max(0f, (cardHeightPx - headerHeightPx).toFloat())
                            min(offset, maxOffset)
                        }
                    }
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
                                if (onCopy != null) {
                                    onCopy()
                                } else {
                                    scope.launch {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_gpt_copy),
                            contentDescription = "复制",
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
                                        .offset(x = indicatorOffset)
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
                                            contentDescription = "代码",
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
                                            ) { isPreviewMode = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_gpt_play),
                                            contentDescription = "预览",
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
                    .animateContentSize(
                        animationSpec = tween(durationMillis = 220)
                    )
                    .background(Color.Transparent)
                    .drawWithContent {
                        drawContent()
                        if (isAtMaxHeight) {
                            val totalH = 24.dp.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, bg),
                                    startY = size.height - totalH,
                                    endY = size.height
                                ),
                                topLeft = Offset(0f, size.height - totalH),
                                size = Size(size.width, totalH)
                            )
                        }
                    }
            ) {
                if (isPreviewMode) {
                    // 内联渲染网页
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(450.dp)
                            .padding(horizontal = 0.dp, vertical = 0.dp)
                            .graphicsLayer {
                                alpha = previewRevealAlpha
                            },
                        shape = RoundedCornerShape(24.dp), // 修改为四角均为 24.dp 以符合直觉
                        color = previewBgColor
                    ) {
                        // 在此表面叠加一个透明遮罩，用于统一捕获点击事件
                        Box(modifier = Modifier.fillMaxSize()) {
                            WebPreviewContent(
                                code = code,
                                language = language ?: "",
                                previewBackgroundColor = previewBgColor,
                                previewTextColor = previewTextColor,
                                modifier = Modifier.fillMaxSize()
                            )
                            // 拦截层，位于 WebView 之上
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Transparent)
                                    .clickable { showFullScreenPreview = true }
                            )
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
                            text = highlightedCode,
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
