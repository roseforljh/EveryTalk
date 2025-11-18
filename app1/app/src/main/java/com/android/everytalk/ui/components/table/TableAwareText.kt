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
 * - 方案二：统一渲染流水线（终极方案）
 *   - 全程（流式 + 结束）都使用分段解析和渲染。
 *   - 实时使用 ContentParser.parseCodeBlocksOnly（轻量）解析文本和代码块。
 *   - 统一使用 CodeBlock 渲染代码块，统一使用 MarkdownRenderer 渲染文本。
 *   - 彻底消除流式结束时的组件替换，从而根除跳动。
 *
 * 缓存机制：通过contentKey持久化解析结果，避免LazyColumn回收导致重复解析
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
    // 🎯 方案二：实时分段解析与统一渲染
    // 无论是否流式，都尝试进行轻量级分段解析（仅分离代码块，表格仍由MarkdownRenderer处理或后续优化）
    
    // 1. 解析状态管理
    // 在流式期间，使用轻量级解析（parseCodeBlocksOnly）；结束后，使用完整解析（parseCompleteContent）。
    // 为了性能，流式期间的解析结果不缓存到全局，仅在组件内记忆。
    val parsedParts = remember(text) {
        // 每次文本变化时重新解析
        // 🎯 修复重影与闪烁 + 恢复表格渲染：
        // 统一使用 parseCompleteContent，无论流式还是非流式都提取表格。
        // 这样可以：
        // 1. 满足用户使用 Compose 表格的需求。
        // 2. 保证流式期间和结束后的渲染结构一致（都是 ContentPart.Table），消除组件切换导致的闪烁。
        // 虽然流式期间完整解析有一定性能开销，但对于一般长度的回复是可以接受的。
        if (isStreaming) {
            // 流式期间不读写全局缓存，直接解析
            ContentParser.parseCompleteContent(text)
        } else {
            // 非流式：尝试从全局缓存获取，否则完整解析并缓存
            ContentParseCache.get(contentKey) ?: ContentParser.parseCompleteContent(text).also {
                if (contentKey.isNotBlank()) ContentParseCache.put(contentKey, it)
            }
        }
    }

    // 2. 统一渲染逻辑
    // 不再区分 isStreaming 的大分支，而是统一遍历 parsedParts 进行渲染
    Column(modifier = modifier.fillMaxWidth()) {
        parsedParts.forEach { part ->
            when (part) {
                is ContentPart.Text -> {
                    // 纯文本部分：用MarkdownRenderer渲染
                    MarkdownRenderer(
                        markdown = part.content,
                        style = style,
                        color = color,
                        modifier = Modifier.fillMaxWidth(),
                        isStreaming = isStreaming, // 传递流式状态给MarkdownRenderer（用于内部优化）
                        onLongPress = onLongPress
                    )
                }
                is ContentPart.Code -> {
                    // 代码块部分：始终用 CodeBlock 渲染
                    // 流式期间可能没有语言标识或未闭合，CodeBlock 需能处理
                    CodeBlock(
                        code = part.content,
                        language = part.language,
                        textColor = color,
                        enableHorizontalScroll = true, // 始终启用滚动
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        maxHeight = 600
                    )
                }
                is ContentPart.Table -> {
                    // 表格部分：仅在完整解析（非流式）时出现
                    // 流式期间表格会被视为 Text 由 MarkdownRenderer 渲染（Markwon支持基础表格）
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
