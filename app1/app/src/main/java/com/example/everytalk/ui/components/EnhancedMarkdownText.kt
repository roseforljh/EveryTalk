package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import com.example.everytalk.data.DataClass.Message
import dev.jeziellago.compose.markdowntext.MarkdownText

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

    // 轻量清理 + 去除内联代码反引号，避免库的默认高亮底色
    val processed = remember(message.text) {
        removeInlineCodeBackticks(sanitizeAiOutput(message.text))
    }

    // 拆分为“文本 + 代码块”分段，代码块用自定义样式渲染
    val parts = remember(processed) { parseMessageContent(processed) }

    Column(modifier = modifier.fillMaxWidth()) {
        parts.forEach { part ->
            when (part.type) {
                ContentType.TEXT -> {
                    MarkdownText(
                        markdown = part.content,
                        style = style.copy(
                            color = textColor,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                }
                ContentType.CODE -> {
                    CodeBlock(
                        code = part.content,
                        language = part.metadata,
                        textColor = textColor,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun StableMarkdownText(
    markdown: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val cleaned = remember(markdown) {
        removeInlineCodeBackticks(sanitizeAiOutput(markdown))
    }
    val partsStable = remember(cleaned) { parseMessageContent(cleaned) }

    Column(modifier = modifier.fillMaxWidth()) {
        partsStable.forEach { part ->
            when (part.type) {
                ContentType.TEXT -> {
                    MarkdownText(
                        markdown = part.content,
                        style = style.copy(
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                }
                ContentType.CODE -> {
                    CodeBlock(
                        code = part.content,
                        language = part.metadata,
                        textColor = style.color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
