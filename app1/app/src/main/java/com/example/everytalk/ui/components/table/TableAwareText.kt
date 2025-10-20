package com.example.everytalk.ui.components.table

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.everytalk.ui.components.CodeBlock
import com.example.everytalk.ui.components.ContentParser
import com.example.everytalk.ui.components.ContentPart
import com.example.everytalk.ui.components.markdown.MarkdownRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 表格感知文本渲染器（优化版）
 * 
 * 核心策略：
 * - 流式阶段：直接用MarkdownRenderer渲染，零解析开销
 * - 流式结束：延迟异步解析完整内容
 * - 缓存机制：通过contentKey持久化解析结果，避免LazyColumn回收导致重复解析
 */
@Composable
fun TableAwareText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    recursionDepth: Int = 0,
    contentKey: String = ""  // 🎯 新增：用于缓存key（通常为消息ID）
) {
    // ⚡ 流式阶段：直接渲染Markdown，不分段解析（避免递归+性能问题）
    if (isStreaming) {
        MarkdownRenderer(
            markdown = text,
            style = style,
            color = color,
            modifier = modifier.fillMaxWidth(),
            isStreaming = true
        )
        return
    }
    
    // 🎯 流式结束：异步解析，分段渲染
    // 🔥 使用 contentKey 作为缓存键，确保 LazyColumn 回收后不丢失解析结果
    val parsedParts = remember(contentKey, text) { mutableStateOf<List<ContentPart>>(emptyList()) }
    
    // 🔥 关键修复：同时监听 contentKey、isStreaming 和 text，确保拿到最终文本后再解析
    // 🎯 只在缓存为空且非流式时解析，避免重复解析
    LaunchedEffect(contentKey, isStreaming, text) {
        if (!isStreaming && text.isNotBlank() && parsedParts.value.isEmpty()) {
            // 🔥 性能优化：大型内容延迟更久，避免流式结束瞬间卡顿
            val isLargeContent = text.length > 8000
            val delayMs = if (isLargeContent) 250L else 100L
            
            kotlinx.coroutines.delay(delayMs)
            
            val startTime = System.currentTimeMillis()
            val parsed = withContext(Dispatchers.Default) {
                try {
                    ContentParser.parseCompleteContent(text)
                } catch (e: Throwable) {
                    android.util.Log.e("TableAwareText", "Parse error", e)
                    listOf(ContentPart.Text(text))
                }
            }
            val parseTime = System.currentTimeMillis() - startTime
            
            parsedParts.value = parsed
            android.util.Log.d("TableAwareText", "✅ Parsed: ${parsed.size} parts, ${text.length} chars, ${parseTime}ms")
            
            // 🔥 性能警告：超过500ms记录警告
            if (parseTime > 500) {
                android.util.Log.w("TableAwareText", "⚠️ Slow parse: ${parseTime}ms for ${text.length} chars")
            }
        } else if (isStreaming) {
            // 流式开始：重置解析结果
            parsedParts.value = emptyList()
        }
    }
    
    // 解析完成前：显示原始Markdown
    if (parsedParts.value.isEmpty()) {
        MarkdownRenderer(
            markdown = text,
            style = style,
            color = color,
            modifier = modifier.fillMaxWidth(),
            isStreaming = false
        )
        return
    }
    
    // 解析完成后：分段渲染
    Column(modifier = modifier.fillMaxWidth()) {
        parsedParts.value.forEach { part ->
            when (part) {
                is ContentPart.Text -> {
                    // 纯文本部分：用MarkdownRenderer渲染（不递归）
                    MarkdownRenderer(
                        markdown = part.content,
                        style = style,
                        color = color,
                        modifier = Modifier.fillMaxWidth(),
                        isStreaming = false
                    )
                }
                is ContentPart.Code -> {
                    CodeBlock(
                        code = part.content,
                        language = part.language,
                        textColor = color,
                        enableHorizontalScroll = part.content.lines()
                            .maxOfOrNull { it.length } ?: 0 > 80,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        maxHeight = 600
                    )
                }
                is ContentPart.Table -> {
                    TableRenderer(
                        lines = part.lines,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        isStreaming = false
                    )
                }
            }
        }
    }
}
