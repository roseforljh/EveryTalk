package com.example.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.theme.chatColors
import com.example.everytalk.ui.theme.ChatDimensions

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
            contentParts.forEachIndexed { index, part ->
                when (part.type) {
                    ContentType.TEXT -> {
                        // 降级：使用 Text 显示规范化后的 Markdown 文本（不做富渲染）
                        Text(
                            text = normalizeBasicMarkdown(part.content),
                            style = optimizedTextStyle.copy(color = textColor),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                    }
                    ContentType.CODE -> {
                        val prevType = if (index > 0) contentParts[index - 1].type else null
                        val nextType = if (index < contentParts.lastIndex) contentParts[index + 1].type else null
                        val topPadding = if (prevType == ContentType.TEXT) 24.dp else 0.dp
                        val bottomPadding = if (nextType == ContentType.TEXT) 24.dp else 0.dp
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
}

/**
 * 代码块组件（自定义样式）
 * - 固定高度（可调），大圆角
 * - 支持水平/垂直双向滚动
 * - 顶部右侧“复制”按钮
 * - 适配明暗主题
 */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    textColor: Color,
    modifier: Modifier = Modifier,
    maxHeight: Int = 220,          // dp
    cornerRadius: Int = 18         // dp
) {
    val isDark = isSystemInDarkTheme()
    val bg = if (isDark) Color(0xFF1E1F22) else Color(0xFFF5F7FA)
    val border = if (isDark) Color(0xFF2A2C2F) else Color(0xFFE6EAF0)
    val headerBg = if (isDark) Color(0xFF2A2C2F) else Color(0xFFEFF3F8)
    val codeColor = if (isDark) Color(0xFFDEE3EA) else Color(0xFF2B2F36)

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .clip(RoundedCornerShape(cornerRadius.dp)),
        color = bg,
        contentColor = codeColor,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            // 顶部栏：语言标签 + 复制按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(headerBg, RoundedCornerShape(topStart = cornerRadius.dp, topEnd = cornerRadius.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val langText = (language?.takeIf { it.isNotBlank() } ?: "").ifBlank { "code" }
                Text(
                    text = langText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = if (isDark) Color(0xFFB7C1D3) else Color(0xFF4B5565)
                    ),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .weight(1f)
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(ctx, "代码已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "复制代码",
                        tint = if (isDark) Color(0xFFB7C1D3) else Color(0xFF4B5565),
                        modifier = Modifier.size(ChatDimensions.COPY_ICON_SIZE)
                    )
                }
            }

            // 代码主体（双向滚动：横向+纵向）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = (maxHeight - 36).dp) // 固定内容区最大高度，顶部栏固定
                    .verticalScroll(vScroll) // 垂直滚动仅作用于代码主体
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                SelectionContainer {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(hScroll) // 横向滚动
                    ) {
                        Text(
                            text = code,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                color = codeColor
                            )
                        )
                    }
                }
            }
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