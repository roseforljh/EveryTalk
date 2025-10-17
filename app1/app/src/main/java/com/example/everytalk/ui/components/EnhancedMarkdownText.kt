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
    
    // 🔥 修复：更准确的流式状态判断
    // 优先使用 streamingText，只要它不为空
    val isActuallyStreaming = remember(isStreaming, streamingText, message.text) {
        isStreaming && streamingText.isNotEmpty()
    }
    
    // 🔥 关键修复：正确判断是否应该触发最终渲染
    // 当 isStreaming=false 时（流式真正结束），无论其他条件，都应该 isFinal=true
    val shouldBeFinal = remember(isStreaming, isActuallyStreaming, message.text) {
        !isStreaming || (!isActuallyStreaming && message.text.isNotEmpty())
    }
    
    // 🔍 [STREAM_DEBUG] 记录文本源选择
    // 🎯 关键修复：监听流式状态变化，确保从流式切换到最终状态时强制触发渲染
    val previouslyStreaming = remember { mutableStateOf(isActuallyStreaming) }
    LaunchedEffect(isActuallyStreaming, streamingText, message.text, shouldBeFinal) {
        android.util.Log.i("STREAM_DEBUG", "[EnhancedMarkdownText] msgId=${message.id}, isStreaming=$isStreaming, isActuallyStreaming=$isActuallyStreaming, shouldBeFinal=$shouldBeFinal, streamingLen=${streamingText.length}, msgTextLen=${message.text.length}, preview='${streamingText.take(30)}'")
        
        // 🎯 检测流式状态从true切换到false（流式结束）
        if (previouslyStreaming.value && !isActuallyStreaming && message.text.isNotEmpty()) {
            android.util.Log.i("STREAM_DEBUG", "[EnhancedMarkdownText] 🔥 STREAMING FINISHED for msgId=${message.id}, forcing final render")
        }
        previouslyStreaming.value = isActuallyStreaming
    }
    
    // 🔥 关键修复：流式期间优先使用 streamingText
    val displayText = if (isActuallyStreaming) {
        android.util.Log.d("STREAM_DEBUG", "[EnhancedMarkdownText] 🔥 Using streamingText: msgId=${message.id}, len=${streamingText.length}, preview='${streamingText.take(50)}'")
        streamingText
    } else if (message.text.isNotEmpty()) {
        android.util.Log.d("STREAM_DEBUG", "[EnhancedMarkdownText] Using message.text: msgId=${message.id}, len=${message.text.length}")
        message.text
    } else {
        // 如果 message.text 为空，但有 streamingText，也使用 streamingText
        android.util.Log.d("STREAM_DEBUG", "[EnhancedMarkdownText] Fallback to streamingText: msgId=${message.id}, len=${streamingText.length}")
        streamingText
    }
    
    val md = normalizeBasicMarkdown(displayText)
    MarkdownHtmlView(
        markdown = md,
        isStreaming = isActuallyStreaming,  // 🔥 修复：使用更准确的流式状态
        isFinal = shouldBeFinal,  // 🔥 修复：使用正确的最终状态判断
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
