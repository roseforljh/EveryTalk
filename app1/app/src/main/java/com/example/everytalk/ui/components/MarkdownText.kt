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
import androidx.compose.ui.graphics.luminance
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
    // 获取当前主题的颜色方案，用于内联代码适配
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val uriHandler = LocalUriHandler.current
    val annotatedString = remember(markdown, color, style, isDarkTheme) {
        buildMarkdownAnnotatedString(markdown, color, style, isDarkTheme)
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
    baseStyle: TextStyle,
    isDarkTheme: Boolean = false
): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0
        val text = markdown
        
        // 处理各种Markdown语法
        val patterns = listOf(
            // 标题 # ## ### (行首)
            MarkdownPattern(
                pattern = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE),
                processor = { match, builder ->
                    val headerLevel = match.groupValues[1].length
                    val headerText = match.groupValues[2]
                    
                    val fontSize = when (headerLevel) {
                        1 -> baseStyle.fontSize * 1.5f
                        2 -> baseStyle.fontSize * 1.3f
                        3 -> baseStyle.fontSize * 1.2f
                        4 -> baseStyle.fontSize * 1.1f
                        5 -> baseStyle.fontSize * 1.05f
                        else -> baseStyle.fontSize
                    }
                    
                    builder.withStyle(
                        style = SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = fontSize
                        )
                    ) {
                        append(headerText)
                    }
                }
            ),
            
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
                            background = if (isDarkTheme) Color(0xFF1A1A1A) else Color.White,
                            color = if (isDarkTheme) Color.White else Color.Black,
                            fontSize = baseStyle.fontSize * 0.9f,
                            fontWeight = FontWeight.Bold
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
        
        // 按位置排序，并解决重叠问题
        matches.sortBy { it.start }
        
        // 移除重叠的匹配项，优先保留更长的匹配
        val filteredMatches = mutableListOf<MarkdownMatch>()
        for (match in matches) {
            val hasOverlap = filteredMatches.any { existing ->
                // 检查是否有重叠
                (match.start < existing.end && match.end > existing.start)
            }
            
            if (!hasOverlap) {
                filteredMatches.add(match)
            } else {
                // 如果有重叠，选择更长的匹配项或更具体的匹配项
                val overlapping = filteredMatches.filter { existing ->
                    match.start < existing.end && match.end > existing.start
                }
                
                // 找到最长的匹配，如果长度相同，优先保留已有的
                val longestExisting = overlapping.maxByOrNull { it.end - it.start }
                val currentLength = match.end - match.start
                val existingLength = longestExisting?.let { it.end - it.start } ?: 0
                
                if (currentLength > existingLength) {
                    // 移除被重叠的较短匹配，添加当前较长匹配
                    filteredMatches.removeAll(overlapping)
                    filteredMatches.add(match)
                    filteredMatches.sortBy { it.start }
                }
                // 否则保持现状，不添加当前匹配
            }
        }
        
        // 处理文本
        var lastIndex = 0
        for (match in filteredMatches) {
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
