package com.android.everytalk.ui.components.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import java.lang.Float.max
import java.lang.Float.min
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.ui.theme.chatColors
import com.android.everytalk.ui.components.syntax.SyntaxHighlighter
import com.android.everytalk.ui.components.syntax.SyntaxHighlightTheme
import com.android.everytalk.ui.components.syntax.HighlightCache

/**
 * 提供列表顶部位置的 CompositionLocal，用于代码块吸顶计算
 * 单位：px
 */
val LocalStickyHeaderTop = compositionLocalOf { Float.NaN }

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
    onCopy: (() -> Unit)? = null,
    onPreviewRequested: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(8.dp)
    val clipboard = LocalClipboardManager.current
    val isDarkTheme = isSystemInDarkTheme()
    val bg = MaterialTheme.chatColors.codeBlockBackground
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    
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
    val highlightedCode = remember(code, language, isDarkTheme) {
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
    
    // 规范化语言标签
    val displayLanguage = language?.trim()?.ifBlank { "CODE" }?.uppercase() ?: "CODE"
    
    // 判断是否支持预览
    val canPreview = onPreviewRequested != null && isPreviewSupported(language)

    // 吸顶逻辑状态
    val stickyTop = LocalStickyHeaderTop.current
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var cardTopPx by remember { mutableFloatStateOf(0f) }
    var cardHeightPx by remember { mutableIntStateOf(0) }

    Surface(
    modifier = modifier
        .fillMaxWidth()
        .border(1.dp, outline, shape)
        .onGloballyPositioned { coordinates ->
            val newTop = coordinates.positionInWindow().y
            val newHeight = coordinates.size.height
            // 仅当位置或高度发生显著变化时更新状态，减少不必要的重组
            if (Math.abs(cardTopPx - newTop) > 1f || cardHeightPx != newHeight) {
                cardTopPx = newTop
                cardHeightPx = newHeight
            }
        },
    shape = shape,
        color = Color.Transparent
    ) {
        // 使用 Column 布局：Header 在上，Content 在下，自然堆叠避免遮挡。
        // 通过 translationY 实现 Header 的视觉吸顶，同时利用 zIndex 确保其在滚动时覆盖 Content。
        Column {
            // 顶部操作栏 (Header)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 动态吸顶偏移
                    .zIndex(1f) // 确保在内容之上绘制
                    .graphicsLayer {
                        // 如果 stickyTop 无效 (NaN)，则不进行吸顶偏移，保持原位
                        if (!stickyTop.isNaN()) {
                            // stickyTop 是列表顶部的 Y 坐标（屏幕坐标）
                            // cardTopPx 是当前卡片顶部的 Y 坐标（屏幕坐标）
                            // diff 是卡片顶部已经滚出 stickyTop 的距离 (cardTopPx < stickyTop 时为正数)
                            // 我们需要将 Header 向下平移 diff 的距离，使其保持在 stickyTop 位置
                            
                            val diff = stickyTop - cardTopPx
                            val offset = max(0f, diff)
                            
                            // 限制 Header 不能移出卡片底部
                            // 注意：这里使用的是 Row 的布局高度，不会因为 translation 而改变
                            val maxOffset = max(0f, (cardHeightPx - headerHeightPx).toFloat())
                            
                            translationY = min(offset, maxOffset)
                        } else {
                            translationY = 0f
                        }
                    }
                    .onSizeChanged { headerHeightPx = it.height },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top // 顶部对齐
            ) {
                // 左侧：语言标签
                // 背景直接透明，只显示文字
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = displayLanguage,
                        style = MaterialTheme.typography.labelSmall,
                        color = headerContentColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                // 右侧：按钮组
                // 背景直接透明，只显示图标
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 预览按钮
                    if (canPreview) {
                        IconButton(
                            onClick = { onPreviewRequested?.invoke() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "预览",
                                tint = headerContentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    
                    // 复制按钮
                    IconButton(
                        onClick = {
                            if (onCopy != null) {
                                onCopy()
                            } else {
                                clipboard.setText(AnnotatedString(code))
                            }
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            tint = headerContentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 代码内容区域
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    // 移除内边距 (padding(12.dp))，使代码贴边显示
            ) {
                // 使用 horizontalScroll 允许水平滚动
                val scrollState = rememberScrollState()
                
                // 使用高亮后的 AnnotatedString
                Text(
                    text = highlightedCode,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                        // 添加微小的水平内边距，防止文字紧贴边缘
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * 检查语言是否支持 Web 预览
 */
private fun isPreviewSupported(language: String?): Boolean {
    val lang = language?.trim()?.lowercase() ?: return false
    return when (lang) {
        "html", "svg", "xml", 
        "mermaid", 
        "echarts", 
        "chartjs", 
        "flowchart", "flow", 
        "vega", "vega-lite" -> true
        else -> false
    }
}