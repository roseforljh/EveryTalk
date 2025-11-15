package com.android.everytalk.ui.components.table

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.ui.components.CodeBlock
import com.android.everytalk.ui.components.ContentParser
import com.android.everytalk.ui.components.ContentPart
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.android.everytalk.util.ContentParseCache
import com.android.everytalk.util.PerformanceMonitor

/**
 * 表格感知文本渲染器（优化版 + 跳动修复）
 *
 * 核心策略：
 * - 流式阶段：直接用MarkdownRenderer渲染，零解析开销
 *   - 🎯 新增：等高占位策略 - 为含代码块/表格的消息添加与完成态一致的占位高度
 * - 流式结束：延迟异步解析完整内容
 *   - 🎯 新增：单次切换策略 - 等待解析完成后一次性替换，避免中间态回退
 * - 缓存机制：通过contentKey持久化解析结果，避免LazyColumn回收导致重复解析
 *
 * 跳动修复原理：
 * 1. 流式期间检测```或表格，给MarkdownRenderer外层添加等高占位（匹配CodeBlock工具条与padding）
 * 2. 完成后等待解析就绪，一次性从占位Markdown切换到分段渲染（Column+CodeBlock/TableRenderer）
 * 3. 消除从"单一TextView"到"多Compose子树"的高度突变，避免LazyColumn项高度跳变
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
    // 🎯 新增：等高占位策略 - 为含代码块/表格添加占位高度
    if (isStreaming) {
        // 检测是否包含代码块或表格
        val hasCodeBlock = text.contains("```")
        val hasTable = (text.contains("|") && text.contains("---"))
        val needsPlaceholder = PerformanceConfig.ENABLE_STREAMING_HEIGHT_PLACEHOLDER &&
                               (hasCodeBlock || hasTable)
        
        if (PerformanceConfig.ENABLE_RENDER_TRANSITION_LOGGING) {
            android.util.Log.d("TableAwareText",
                "🔄 Streaming render: key=$contentKey, hasCode=$hasCodeBlock, hasTable=$hasTable, placeholder=$needsPlaceholder")
        }
        
        if (needsPlaceholder) {
            // 添加等高占位：模拟CodeBlock的顶部工具条与额外padding
            Column(modifier = modifier.fillMaxWidth()) {
                // 顶部占位空间（匹配CodeBlock工具条高度）
                Spacer(modifier = Modifier.height(PerformanceConfig.CODE_BLOCK_TOOLBAR_HEIGHT_DP.dp))
                
                // 实际内容
                Box(modifier = Modifier.padding(vertical = PerformanceConfig.CODE_BLOCK_EXTRA_VERTICAL_PADDING_DP.dp)) {
                    MarkdownRenderer(
                        markdown = text,
                        style = style,
                        color = color,
                        modifier = Modifier.fillMaxWidth(),
                        isStreaming = true,
                        onLongPress = onLongPress
                    )
                }
            }
        } else {
            // 无需占位，直接渲染
            MarkdownRenderer(
                markdown = text,
                style = style,
                color = color,
                modifier = modifier.fillMaxWidth(),
                isStreaming = true,
                onLongPress = onLongPress
            )
        }
        return
    }
    
    // 🎯 流式结束：异步解析，分段渲染
    // 🔥 使用 contentKey 作为缓存键，确保 LazyColumn 回收后不丢失解析结果（结合全局 LRU 缓存）
    val parsedParts = remember(contentKey) {
        mutableStateOf<List<ContentPart>>(ContentParseCache.get(contentKey) ?: emptyList())
    }
    
    // 🎯 新增：解析状态标记，用于单次切换策略
    val isParsingComplete = remember(contentKey) { mutableStateOf(false) }

    // 🎯 性能优化：使用derivedStateOf减少LaunchedEffect触发频率
    // 仅当真正需要解析时才触发（非流式 + 文本非空 + 未解析完成）
    val shouldParse = remember {
        derivedStateOf {
            !isStreaming && text.isNotBlank() && !isParsingComplete.value
        }
    }

    // 🔥 关键修复：使用derivedStateOf优化触发条件
    // 修复前：contentKey、isStreaming、text任一变化都触发，导致过度重组
    // 修复后：仅在shouldParse真正变为true时触发解析
    LaunchedEffect(contentKey, shouldParse.value) {
        // 如果不需要解析，直接返回
        if (!shouldParse.value) {
            return@LaunchedEffect
        }
        
        if (PerformanceConfig.ENABLE_RENDER_TRANSITION_LOGGING) {
            android.util.Log.d("TableAwareText",
                "🔄 Streaming ended, start parsing: key=$contentKey, textLen=${text.length}")
        }

        // 先尝试读取全局缓存
        ContentParseCache.get(contentKey)?.let { cached ->
            if (cached.isNotEmpty()) {
                parsedParts.value = cached
                isParsingComplete.value = true
                // 埋点：缓存命中
                PerformanceMonitor.recordCacheHit(component = "ContentParse", durationMs = 0, key = contentKey)
                if (PerformanceConfig.ENABLE_RENDER_TRANSITION_LOGGING) {
                    android.util.Log.d("TableAwareText", "✅ Cache hit for key=$contentKey (parts=${cached.size})")
                }
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
        isParsingComplete.value = true
        ContentParseCache.put(contentKey, parsed)
        
        if (PerformanceConfig.ENABLE_RENDER_TRANSITION_LOGGING) {
            android.util.Log.d("TableAwareText",
                "✅ Parsed & cached: parts=${parsed.size}, len=${text.length}, ${parseTime}ms (key=$contentKey)")
        }

        // 🔥 性能警告：超过500ms记录警告
        if (parseTime > 500) {
            android.util.Log.w("TableAwareText", "⚠️ Slow parse: ${parseTime}ms for ${text.length} chars (key=$contentKey)")
        }
    }
    
    // 🎯 单次切换策略：
    // - 如果启用单次切换且解析未完成：继续显示等高占位的Markdown（避免中间态回退）
    // - 如果解析完成或未启用单次切换：按原逻辑处理
    if (PerformanceConfig.ENABLE_SINGLE_SWAP_RENDERING && !isParsingComplete.value) {
        // 解析进行中：显示等高占位的Markdown（与流式态一致）
        val hasCodeBlock = text.contains("```")
        val hasTable = (text.contains("|") && text.contains("---"))
        val needsPlaceholder = PerformanceConfig.ENABLE_STREAMING_HEIGHT_PLACEHOLDER &&
                               (hasCodeBlock || hasTable)
        
        if (needsPlaceholder) {
            Column(modifier = modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(PerformanceConfig.CODE_BLOCK_TOOLBAR_HEIGHT_DP.dp))
                Box(modifier = Modifier.padding(vertical = PerformanceConfig.CODE_BLOCK_EXTRA_VERTICAL_PADDING_DP.dp)) {
                    MarkdownRenderer(
                        markdown = text,
                        style = style,
                        color = color,
                        modifier = Modifier.fillMaxWidth(),
                        isStreaming = false,
                        onLongPress = onLongPress
                    )
                }
            }
        } else {
            MarkdownRenderer(
                markdown = text,
                style = style,
                color = color,
                modifier = modifier.fillMaxWidth(),
                isStreaming = false,
                onLongPress = onLongPress
            )
        }
        return
    }
    
    // 解析完成前且未启用单次切换：显示原始Markdown（旧逻辑）
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
