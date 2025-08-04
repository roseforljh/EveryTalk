package com.example.everytalk.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.regex.Pattern

/**
 * Markdown文本渲染组件
 * 支持基础的Markdown语法渲染
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val uriHandler = LocalUriHandler.current
    val annotatedString = remember(markdown, color, style) {
        buildMarkdownAnnotatedString(markdown, color, style)
    }
    
    // 检查是否包含链接
    val hasLinks = remember(annotatedString) {
        annotatedString.getStringAnnotations("URL", 0, annotatedString.length).isNotEmpty()
    }
    
    if (hasLinks) {
        // 只有包含链接时才使用ClickableText，并禁用选择
        DisableSelection {
            ClickableText(
                text = annotatedString,
                modifier = modifier,
                style = style.copy(color = color),
                onClick = { offset ->
                    // 处理链接点击
                    annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            try {
                                uriHandler.openUri(annotation.item)
                            } catch (e: Exception) {
                                // 忽略无效链接
                            }
                        }
                }
            )
        }
    } else {
        // 没有链接时使用普通Text，完全避免文本选择
        Text(
            text = annotatedString,
            modifier = modifier,
            style = style.copy(color = color)
        )
    }
}

/**
 * 构建Markdown的AnnotatedString
 */
private fun buildMarkdownAnnotatedString(
    markdown: String,
    baseColor: Color,
    baseStyle: TextStyle
): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0
        val text = markdown.replace(Regex("`([^`]+)`"), "$1") // 移除代码块的反引号
        
        // 处理各种Markdown语法
        val patterns = listOf(
            // 链接 [text](url)
            MarkdownPattern(
                pattern = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)"),
                processor = { match, builder ->
                    val linkText = match.groupValues[1]
                    val url = match.groupValues[2]
                    
                    builder.pushStringAnnotation(tag = "URL", annotation = url)
                    builder.withStyle(
                        style = SpanStyle(
                            color = Color(0xFF2196F3),
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(linkText)
                    }
                    builder.pop()
                }
            ),
            
            // 粗体 **text**
            MarkdownPattern(
                pattern = Pattern.compile("\\*\\*([^*]+)\\*\\*"),
                processor = { match, builder ->
                    builder.withStyle(
                        style = SpanStyle(fontWeight = FontWeight.Bold)
                    ) {
                        append(match.groupValues[1])
                    }
                }
            ),
            
            // 斜体 *text*
            MarkdownPattern(
                pattern = Pattern.compile("\\*([^*]+)\\*"),
                processor = { match, builder ->
                    builder.withStyle(
                        style = SpanStyle(fontStyle = FontStyle.Italic)
                    ) {
                        append(match.groupValues[1])
                    }
                }
            ),
            
            // 行内代码 `code`
            MarkdownPattern(
                pattern = Pattern.compile("`([^`]+)`"),
                processor = { match, builder ->
                    builder.withStyle(
                        style = SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x1A000000),
                            fontSize = baseStyle.fontSize * 0.9f
                        )
                    ) {
                        append(match.groupValues[1])
                    }
                }
            ),
            
            // 删除线 ~~text~~
            MarkdownPattern(
                pattern = Pattern.compile("~~([^~]+)~~"),
                processor = { match, builder ->
                    builder.withStyle(
                        style = SpanStyle(textDecoration = TextDecoration.LineThrough)
                    ) {
                        append(match.groupValues[1])
                    }
                }
            )
        )
        
        // 查找所有匹配项
        val matches = mutableListOf<MarkdownMatch>()
        for (pattern in patterns) {
            val matcher = pattern.pattern.matcher(text)
            while (matcher.find()) {
                matches.add(
                    MarkdownMatch(
                        start = matcher.start(),
                        end = matcher.end(),
                        matchResult = MatchResult(
                            value = matcher.group(),
                            groupValues = (0..matcher.groupCount()).map { matcher.group(it) ?: "" }
                        ),
                        processor = pattern.processor
                    )
                )
            }
        }
        
        // 按位置排序
        matches.sortBy { it.start }
        
        // 处理文本
        var lastIndex = 0
        for (match in matches) {
            // 添加匹配前的普通文本
            if (match.start > lastIndex) {
                append(text.substring(lastIndex, match.start))
            }
            
            // 应用匹配的样式
            match.processor(match.matchResult, this)
            
            lastIndex = match.end
        }
        
        // 添加剩余的文本
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

/**
 * Markdown模式数据类
 */
private data class MarkdownPattern(
    val pattern: Pattern,
    val processor: (MatchResult, AnnotatedString.Builder) -> Unit
)

/**
 * Markdown匹配结果
 */
private data class MarkdownMatch(
    val start: Int,
    val end: Int,
    val matchResult: MatchResult,
    val processor: (MatchResult, AnnotatedString.Builder) -> Unit
)

/**
 * 匹配结果数据类
 */
private data class MatchResult(
    val value: String,
    val groupValues: List<String>
)