package com.example.app1.ui.screens.MainScreen.drawer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlin.math.max
import kotlin.math.min

/**
 * 根据搜索查询生成高亮的预览文本片段。
 * @param messageText 原始消息文本。
 * @param query 搜索查询。
 * @param contextChars 查询关键词前后显示的上下文的字符数。
 * @return 带高亮样式的 AnnotatedString，如果查询为空或未找到匹配则返回 null。
 */
@Composable
internal fun rememberGeneratedPreviewSnippet(
    messageText: String, query: String, contextChars: Int = 10 // 上下文预览字符数
): AnnotatedString? {
    val highlightColor = MaterialTheme.colorScheme.primary // 高亮颜色使用主题色
    return remember(messageText, query, highlightColor, contextChars) { // 依赖项正确，确保仅在必要时重新计算
        if (query.isBlank()) return@remember null // 查询为空则不生成片段
        val queryLower = query.lowercase() // 查询转小写以忽略大小写匹配
        val textLower = messageText.lowercase() // 消息文本转小写
        val startIndex = textLower.indexOf(queryLower) // 查找查询词在消息中的起始位置
        if (startIndex == -1) return@remember null // 未找到匹配则不生成片段

        // 计算片段的起始和结束位置
        val snippetStart = max(0, startIndex - contextChars)
        val snippetEnd = min(messageText.length, startIndex + query.length + contextChars)
        val prefix = if (snippetStart > 0) "..." else "" // 如果片段不是从文本开头，则加前缀 "..."
        val suffix = if (snippetEnd < messageText.length) "..." else "" // 如果片段不是到文本末尾，则加后缀 "..."
        val rawSnippet = messageText.substring(snippetStart, snippetEnd) // 截取原始片段

        buildAnnotatedString { // 构建带注解的字符串
            append(prefix)
            val queryIndexInRawSnippet = rawSnippet.lowercase().indexOf(queryLower) // 查询词在片段内的位置
            if (queryIndexInRawSnippet != -1) { // 如果在片段内能找到（理论上应该总能找到）
                append(rawSnippet.substring(0, queryIndexInRawSnippet)) // 添加查询词之前的部分
                withStyle( // 对查询词应用高亮样式
                    style = SpanStyle(
                        fontWeight = FontWeight.SemiBold, // 字体半粗
                        color = highlightColor // 高亮颜色
                    )
                ) {
                    append( // 添加查询词本身
                        rawSnippet.substring(
                            queryIndexInRawSnippet,
                            queryIndexInRawSnippet + query.length
                        )
                    )
                }
                append(rawSnippet.substring(queryIndexInRawSnippet + query.length)) // 添加查询词之后的部分
            } else { // 理论上不应发生，作为回退直接添加原始片段
                append(rawSnippet)
            }
            append(suffix)
        }
    }
}