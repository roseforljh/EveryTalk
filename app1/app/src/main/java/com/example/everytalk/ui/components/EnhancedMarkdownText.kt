package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.statecontroller.AppViewModel

/**
 * 增强的Markdown文本显示组件
 * 
 * 支持功能：
 * - Markdown格式（标题、列表、粗体、斜体等）
 * - 代码块（自适应滚动）
 * - 表格渲染
 * - 流式实时更新
 */
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
    
    // 🎯 流式内容实时获取
    val content = if (isStreaming && viewModel != null) {
        viewModel.streamingMessageStateManager
            .getOrCreateStreamingState(message.id)
            .collectAsState(initial = message.text).value
    } else {
        message.text
    }
    
    // 🎯 解析并分离代码块和表格
    val parsedContent = remember(content) {
        ContentParser.parseCompleteContent(content)
    }
    
    // 检查是否只有文本（无代码块和表格）
    if (parsedContent.size == 1 && parsedContent[0] is ContentPart.Text) {
        // 纯文本：直接用 MarkdownRenderer（支持数学公式）
        MarkdownRenderer(
            markdown = content,
            style = style,
            color = textColor,
            modifier = modifier.fillMaxWidth()
        )
    } else {
        // 混合内容：文本 + 代码块 + 表格
        Column(modifier = modifier.fillMaxWidth()) {
            parsedContent.forEach { part ->
                when (part) {
                    is ContentPart.Text -> {
                        // 文本部分：用 MarkdownRenderer（支持数学公式）
                        MarkdownRenderer(
                            markdown = part.content,
                            style = style,
                            color = textColor,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    is ContentPart.Code -> {
                        // 🎯 代码块：使用自定义组件（无高度限制）
                        val shouldScroll = part.content.lines().maxOfOrNull { it.length } ?: 0 > 80
                        
                        CodeBlock(
                            code = part.content,
                            language = part.language,
                            textColor = textColor,
                            enableHorizontalScroll = shouldScroll,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            maxHeight = 600  // 增加最大高度限制
                        )
                    }
                    
                    is ContentPart.Table -> {
                        // 🎯 表格：使用表格渲染器
                        TableRenderer(
                            lines = part.lines,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 简化的静态文本显示组件
 */
@Composable
fun StableMarkdownText(
    markdown: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    Text(
        text = markdown,
        modifier = modifier,
        style = style.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    )
}
