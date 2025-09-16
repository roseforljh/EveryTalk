package com.example.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.theme.chatColors
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * 优化的文本布局组件 - 支持Markdown渲染但不处理数学公式
 * 
 * 主要功能:
 * 1. 完整的Markdown格式支持
 * 2. 代码块语法高亮
 * 3. 表格、列表、标题等格式
 * 4. 紧凑的布局设计
 * 5. 响应式字体大小
 */
@Composable
fun OptimizedTextLayout(
    message: Message,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val isDarkTheme = isSystemInDarkTheme()
    
    // 解析消息内容，分离文本和代码块
    val contentParts = remember(message.text, message.parts) {
        parseMessageContent(
            if (message.parts.isNotEmpty()) {
                message.parts.joinToString("\n") { part ->
                    when (part) {
                        is MarkdownPart.Text -> part.content
                        is MarkdownPart.CodeBlock -> "```" + part.language + "\n" + part.content + "\n```"
                        else -> ""
                    }
                }
            } else {
                message.text
            }
        )
    }
    
    // 优化的文本样式
    val optimizedTextStyle = remember(style) {
        style.copy(
            lineHeight = (style.fontSize.value * 1.3f).sp,
            letterSpacing = (-0.1).sp,
            fontWeight = FontWeight.Normal
        )
    }
    
    SelectionContainer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            contentParts.forEach { part ->
                when (part.type) {
                    ContentType.TEXT -> {
                        // 使用 MarkdownText 进行完整的 Markdown 渲染
                        MarkdownText(
                            markdown = normalizeBasicMarkdown(part.content),
                            style = optimizedTextStyle.copy(color = textColor),
                            modifier = Modifier.fillMaxWidth()
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
}

/**
 * 代码块组件
 */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        SelectionContainer {
            Text(
                text = code,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                ),
                color = textColor,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

/**
 * 内容类型枚举 - 只保留文本和代码，不包含数学公式
 */
enum class ContentType {
    TEXT,
    CODE
}

/**
 * 内容部分数据类
 */
data class ContentPart(
    val type: ContentType,
    val content: String,
    val metadata: String? = null
)

/**
 * 解析消息内容，分离文本和代码块
 */
fun parseMessageContent(text: String): List<ContentPart> {
    val parts = mutableListOf<ContentPart>()
    var currentIndex = 0
    
    // 只匹配代码块，不处理数学公式
    val codeBlockPattern = Regex("""```(\w*)\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
    
    // 查找所有代码块
    val matches = mutableListOf<MatchItem>()
    
    codeBlockPattern.findAll(text).forEach { match ->
        matches.add(MatchItem(match.range, ContentType.CODE, match.groupValues[2], match.groupValues[1]))
    }
    
    // 按位置排序
    matches.sortBy { it.range.first }
    
    // 提取内容
    matches.forEach { match ->
        // 添加前面的文本
        if (currentIndex < match.range.first) {
            val textContent = text.substring(currentIndex, match.range.first)
            if (textContent.isNotEmpty()) {
                parts.add(ContentPart(ContentType.TEXT, textContent))
            }
        }
        
        // 添加匹配的代码块
        parts.add(ContentPart(match.type, match.content, match.metadata))
        currentIndex = match.range.last + 1
    }
    
    // 添加剩余的文本
    if (currentIndex < text.length) {
        val textContent = text.substring(currentIndex)
        if (textContent.isNotEmpty()) {
            parts.add(ContentPart(ContentType.TEXT, textContent))
        }
    }
    
    // 如果没有找到任何代码块，返回整个文本作为Markdown处理
    if (parts.isEmpty()) {
        parts.add(ContentPart(ContentType.TEXT, text))
    }
    
    return parts
}

/**
 * 匹配项数据类
 */
private data class MatchItem(
    val range: IntRange,
    val type: ContentType,
    val content: String,
    val metadata: String? = null
)