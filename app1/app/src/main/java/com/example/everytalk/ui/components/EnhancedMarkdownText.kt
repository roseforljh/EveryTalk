package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.util.CodeHighlighter

@Composable
fun EnhancedMarkdownText(
    message: Message,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    messageOutputType: String = "",
    inTableContext: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    inSelectionDialog: Boolean = false
) {
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }

    // 流式期间跳过复杂规范化，避免主线程阻塞导致ANR
    if (isStreaming) {
        OptimizedTextLayout(
            message = message.copy(
                // 流式期间仅做最轻量字形清理，跳过正则密集的sanitizeAiOutput
                text = normalizeMarkdownGlyphs(message.text)
            ),
            modifier = modifier.fillMaxWidth(),
            textColor = if (color != Color.Unspecified) color else textColor,
            style = style
        )
    } else {
        // 仅在非流式/最终态时，用 WebView 渲染完整 Markdown + 数学
        val md = normalizeBasicMarkdown(message.text)
        MarkdownHtmlView(
            markdown = md,
            isStreaming = false,
            isFinal = true,
            modifier = modifier.fillMaxWidth()
        )
    }
}

@Composable
fun StableMarkdownText(
    markdown: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val md = normalizeBasicMarkdown(markdown)
    // 使用基于 CDN 的 Markdown 富文本渲染，避免原始文本直出
    MarkdownHtmlView(
        markdown = md,
        isStreaming = false,
        isFinal = true,
        modifier = modifier
    )
}
