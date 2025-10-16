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

    // 若消息包含结构化 parts，逐段渲染（文本/代码/数学/混合/表格/HTML）
    // The logic to split parts is no longer needed, as WebView handles everything.

    // 否则按整段 Markdown 文本(规范化后)展示
    val md = normalizeBasicMarkdown(message.text)
    // 整段使用基于 CDN 的 Markdown 富文本渲染
    MarkdownHtmlView(
        markdown = md,
        modifier = modifier.fillMaxWidth()
    )
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
        modifier = modifier
    )
}
