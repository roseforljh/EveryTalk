package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.util.CodeHighlighter
import com.example.everytalk.statecontroller.AppViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
    inSelectionDialog: Boolean = false,
    viewModel: AppViewModel? = null
) {
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }

    // 🎯 核心修复：正确地observe流式内容
    // 始终subscribe到StreamingMessageStateManager，即使不在流式期间
    val streamingText by remember(message.id, viewModel) {
        if (viewModel != null) {
            viewModel.getStreamingContent(message.id)
        } else {
            kotlinx.coroutines.flow.MutableStateFlow(message.text)
        }
    }.collectAsState()
    
    // 根据流式状态决定使用哪个文本源
    val displayText = if (isStreaming && streamingText.isNotEmpty()) {
        streamingText
    } else {
        message.text
    }
    
    val md = normalizeBasicMarkdown(displayText)
    MarkdownHtmlView(
        markdown = md,
        isStreaming = isStreaming,
        isFinal = !isStreaming,
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
        isStreaming = false,
        isFinal = true,
        modifier = modifier
    )
}
