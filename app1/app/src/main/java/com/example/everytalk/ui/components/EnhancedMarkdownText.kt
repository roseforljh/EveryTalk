package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
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

    // 🎯 关键修复：使用 derivedStateOf 来稳定解析结果
    // 流式输出时，只在文本有实质性变化时才重新解析，避免频繁重组
    val parts by remember(processed) {
        derivedStateOf {
            // 对于流式输出的表格等复杂内容，延迟完整解析直到内容稳定
            if (isStreaming && processed.contains("|") && processed.count { it == '\n' } < 3) {
                // 表格开始但还不完整时，暂时显示为纯文本，避免频繁重解析
                listOf(ContentPart(ContentType.TEXT, processed))
            } else {
                parseMessageContent(processed)
            }
        }
    }

    // 使用分段渲染：普通文本交给 MarkdownText，代码块用自定义 CodeBlock（深色样式、避免"大白块"）
    Column(modifier = modifier.fillMaxWidth()) {
        parts.forEachIndexed { index, part ->
            val prevType = if (index > 0) parts[index - 1].type else null
            val nextType = if (index < parts.lastIndex) parts[index + 1].type else null
            
            when (part.type) {
                ContentType.TEXT -> {
                    val topPadding = if (prevType == ContentType.CODE) 12.dp else 0.dp
                    val bottomPadding = if (nextType == ContentType.CODE) 12.dp else 0.dp
                    
                    Box(modifier = Modifier.padding(top = topPadding, bottom = bottomPadding)) {
                        MarkdownText(
                            markdown = part.content,
                            style = style.copy(
                                color = textColor,
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                }
                ContentType.CODE -> {
                    val topPadding = when (prevType) {
                        ContentType.CODE -> 16.dp
                        ContentType.TEXT -> 12.dp
                        null -> 0.dp
                    }
                    val bottomPadding = when (nextType) {
                        ContentType.CODE -> 0.dp
                        ContentType.TEXT -> 12.dp
                        null -> 0.dp
                    }
                    
                    CodeBlock(
                        code = part.content,
                        language = part.metadata,
                        textColor = textColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = topPadding, bottom = bottomPadding)
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
    
    // 🎯 同样使用 derivedStateOf 来稳定解析
    val partsStable by remember(cleaned) {
        derivedStateOf {
            parseMessageContent(cleaned)
        }
    }

    // 稳定版本也采用分段渲染，确保代码块使用自定义深色样式，避免"大白块"
    Column(modifier = modifier.fillMaxWidth()) {
        partsStable.forEachIndexed { index, part ->
            val prevType = if (index > 0) partsStable[index - 1].type else null
            val nextType = if (index < partsStable.lastIndex) partsStable[index + 1].type else null
            
            when (part.type) {
                ContentType.TEXT -> {
                    val topPadding = if (prevType == ContentType.CODE) 12.dp else 0.dp
                    val bottomPadding = if (nextType == ContentType.CODE) 12.dp else 0.dp
                    
                    Box(modifier = Modifier.padding(top = topPadding, bottom = bottomPadding)) {
                        MarkdownText(
                            markdown = part.content,
                            style = style.copy(
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                }
                ContentType.CODE -> {
                    val topPadding = when (prevType) {
                        ContentType.CODE -> 16.dp
                        ContentType.TEXT -> 12.dp
                        null -> 0.dp
                    }
                    val bottomPadding = when (nextType) {
                        ContentType.CODE -> 0.dp
                        ContentType.TEXT -> 12.dp
                        null -> 0.dp
                    }
                    
                    CodeBlock(
                        code = part.content,
                        language = part.metadata,
                        textColor = style.color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = topPadding, bottom = bottomPadding)
                    )
                }
            }
        }
    }
}
