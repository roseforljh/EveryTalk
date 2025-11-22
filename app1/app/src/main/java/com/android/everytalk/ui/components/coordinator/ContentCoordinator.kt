package com.android.everytalk.ui.components.coordinator
import com.android.everytalk.ui.components.markdown.MarkdownRenderer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.android.everytalk.ui.components.table.TableAwareText
import com.android.everytalk.data.DataClass.Sender

/**
 * 内容协调器（搬迁版）
 * 原文件位置：ui/components/ContentCoordinator.kt
 * 说明：统一调度表格/数学/代码块/纯文本渲染；提供递归深度保护。
 * 缓存机制：通过contentKey持久化解析结果，避免LazyColumn回收导致重复解析
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContentCoordinator(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    recursionDepth: Int = 0,
    contentKey: String = "",  // 新增：用于缓存key（通常为消息ID）
    onLongPress: (() -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null, // 新增
    sender: Sender = Sender.AI  // 新增：发送者信息，默认为AI
) {
    // 根据发送者决定宽度策略
    val widthModifier = if (sender == Sender.User) {
        Modifier.wrapContentWidth()
    } else {
        Modifier.fillMaxWidth()
    }
    
    // 防止无限递归：超过3层直接渲染
    if (recursionDepth > 3) {
        android.util.Log.w(
            "ContentCoordinator",
            "递归深度超限($recursionDepth)，直接渲染以避免ANR"
        )
        // 统一包裹长按（即便在深层也可触发）
        // 移除 combinedClickable，因为它会拦截子 View 的点击事件
        MarkdownRenderer(
            markdown = text,
            style = style,
            color = color,
            modifier = modifier
                .then(widthModifier),
            isStreaming = isStreaming,
            onLongPress = onLongPress,
            onImageClick = onImageClick,
            sender = sender,
            contentKey = contentKey
        )
        return
    }
    
    // 轻量检测
    val hasCodeBlock = text.contains("```")
    // 表格检测：简单的启发式检查，避免复杂的正则匹配
    // 只有同时包含 | 和 - 才可能是表格（表头分隔线至少包含 --- 或 :---）
    val hasTable = text.contains("|") && text.contains("-")

    // 流式阶段：使用轻量模式，避免频繁解析
    // 流式结束后：触发完整解析，将代码块转换为CodeBlock组件
    // 性能保护：
    //   - TableAwareText 延迟250ms解析大型内容（>8000字符）
    //   - 使用后台线程（Dispatchers.Default）避免阻塞UI
    if (hasCodeBlock || hasTable) {
        // 只根据流式状态判断是否使用轻量模式
        // 如果包含表格，即使是流式也建议走 TableAwareText 以便正确渲染表格（虽然 TableAwareText 内部对流式有优化）
        // 但为了性能，流式期间如果只有表格没有代码块，也可以考虑暂缓？
        // 不，TableAwareText 内部会处理流式。
        val shouldUseLightweight = isStreaming

        TableAwareText(
            text = text,
            style = style,
            color = color,
            isStreaming = shouldUseLightweight, // true=轻量；false=完整（仅纯表格）
            modifier = modifier
                .then(widthModifier),
            recursionDepth = recursionDepth,
            contentKey = contentKey,  // 传递缓存key
            onLongPress = onLongPress,
            onImageClick = onImageClick
        )
        return
    }
    
    // 优先级3：纯文本（无代码块、表格）
    // 数学公式 $...$ 与 $$...$$ 由 MarkdownRenderer 的 JLatexMathPlugin 统一处理
    MarkdownRenderer(
        markdown = text,
        style = style,
        color = color,
        modifier = modifier
            .then(widthModifier),
        isStreaming = isStreaming,
        onLongPress = onLongPress,
        onImageClick = onImageClick,
        sender = sender,
        contentKey = contentKey
    )
}
