package com.android.everytalk.ui.components.table

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
import com.android.everytalk.ui.components.CodeBlock
import com.android.everytalk.ui.components.ContentParser
import com.android.everytalk.ui.components.ContentPart
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.android.everytalk.util.ContentParseCache
import com.android.everytalk.util.PerformanceMonitor

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
    contentKey: String = "",  // 🎯 新增：用于缓存key（通常为消息ID）
    onLongPress: (() -> Unit)? = null
) {
    // ⚡ 流式阶段：直接渲染Markdown，不分段解析（避免递归+性能问题）
    if (isStreaming) {
        MarkdownRenderer(
            markdown = text,
            style = style,
            color = color,
            modifier = modifier.fillMaxWidth(),
            isStreaming = true,
            onLongPress = onLongPress
        )
        return
    }
    
    // 🎯 流式结束：异步解析，分段渲染
    // 🔥 使用 contentKey 作为缓存键，确保 LazyColumn 回收后不丢失解析结果（结合全局 LRU 缓存）
    val parsedParts = remember(contentKey) {
        mutableStateOf<List<ContentPart>>(ContentParseCache.get(contentKey) ?: emptyList())
    }

    // 🔥 关键修复：同时监听 contentKey、isStreaming 和 text，确保拿到最终文本后再解析
    // 🎯 优先命中全局缓存；仅在未命中且非流式时解析并写回缓存，避免重复解析
    LaunchedEffect(contentKey, isStreaming, text) {
        if (isStreaming) {
            // 流式开始：仅清空本地渲染态，不清理全局缓存（等待最终文本）
            parsedParts.value = emptyList()
            return@LaunchedEffect
        }

        if (text.isBlank()) return@LaunchedEffect

        // 先尝试读取全局缓存
        ContentParseCache.get(contentKey)?.let { cached ->
            if (cached.isNotEmpty()) {
                parsedParts.value = cached
                // 埋点：缓存命中
                PerformanceMonitor.recordCacheHit(component = "ContentParse", durationMs = 0, key = contentKey)
                android.util.Log.d("TableAwareText", "✅ Cache hit for key=$contentKey (parts=${cached.size})")
                return@LaunchedEffect
            }
        }
        // 埋点：缓存未命中
        PerformanceMonitor.recordCacheMiss(component = "ContentParse", durationMs = 0, key = contentKey)

        // 缓存未命中：触发解析（后台线程），并在完成后写入缓存
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
        // 埋点：解析耗时
        PerformanceMonitor.recordParsing(component = "ContentParse", durationMs = parseTime, inputSize = text.length)

        parsedParts.value = parsed
        ContentParseCache.put(contentKey, parsed)
        android.util.Log.d("TableAwareText", "✅ Parsed & cached: parts=${parsed.size}, len=${text.length}, ${parseTime}ms (key=$contentKey)")

        // 🔥 性能警告：超过500ms记录警告
        if (parseTime > 500) {
            android.util.Log.w("TableAwareText", "⚠️ Slow parse: ${parseTime}ms for ${text.length} chars (key=$contentKey)")
        }
    }
    
    // 解析完成前：显示原始Markdown
    if (parsedParts.value.isEmpty()) {
        MarkdownRenderer(
            markdown = text,
            style = style,
            color = color,
            modifier = modifier.fillMaxWidth(),
            isStreaming = false,
            onLongPress = onLongPress
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
                        isStreaming = false,
                        onLongPress = onLongPress
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
