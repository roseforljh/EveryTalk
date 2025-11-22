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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.ui.components.CodeBlock
import com.android.everytalk.ui.components.ContentParser
import com.android.everytalk.ui.components.ContentPart
import com.android.everytalk.ui.components.WebPreviewDialog
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
    onLongPress: (() -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null
) {
    // 🎯 预览状态管理
    var previewState by remember { mutableStateOf<Pair<String, String>?>(null) } // (code, language)

    // 🎯 方案二：实时分段解析与统一渲染
    // 无论是否流式，都尝试进行轻量级分段解析（仅分离代码块，表格仍由MarkdownRenderer处理或后续优化）
    
    // 1. 解析状态管理
    // 🎯 优化：同步初始化状态，避免闪烁
    // 如果缓存中有数据，直接作为初始值，而不是先显示 Text 再切换
    val initialParts = remember(text, contentKey, isStreaming) {
        if (!isStreaming && contentKey.isNotBlank()) {
            ContentParseCache.get(contentKey) ?: listOf(ContentPart.Text(text))
        } else {
            listOf(ContentPart.Text(text))
        }
    }

    val parsedParts by produceState<List<ContentPart>>(initialValue = initialParts, key1 = text, key2 = isStreaming, key3 = contentKey) {
        // 如果初始值已经是缓存值（且非默认Text），则不需要立即重新解析，除非是流式更新
        // 但为了保险起见（比如缓存可能为空），我们还是执行解析逻辑，但 Compose 的 State 机制会避免相同值的重组
        value = withContext(Dispatchers.Default) {
            if (isStreaming) {
                // 流式期间不读写全局缓存，直接解析
                ContentParser.parseCompleteContent(text, isStreaming = true)
            } else {
                // 非流式：尝试从全局缓存获取，否则完整解析并缓存
                // 注意：这里再次 get 是为了处理 initialParts 为默认值的情况，或者并发更新
                ContentParseCache.get(contentKey) ?: ContentParser.parseCompleteContent(text, isStreaming = false).also {
                    if (contentKey.isNotBlank()) ContentParseCache.put(contentKey, it)
                }
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
                        onLongPress = onLongPress,
                        onImageClick = onImageClick,
                        contentKey = if (contentKey.isNotBlank()) "${contentKey}_part_${parsedParts.indexOf(part)}" else "" // 🎯 传递子Key
                    )
                }
                is ContentPart.Code -> {
                    // 代码块部分：始终用 CodeBlock 渲染
                    // 流式期间可能没有语言标识或未闭合，CodeBlock 需能处理
                    
                    // 🎯 检查是否支持预览
                    // 新增 xml：让 ```xml 代码块也显示“预览”按钮（走 html 模板）
                    val supportedLanguages = setOf(
                        "mermaid",
                        "echarts",
                        "chartjs",
                        "flowchart",
                        "flow",
                        "vega",
                        "vega-lite",
                        "html",
                        "svg",
                        "xml"
                    )
                    val isPreviewSupported = part.language?.lowercase() in supportedLanguages
                    
                    CodeBlock(
                        code = part.content,
                        language = part.language,
                        textColor = color,
                        enableHorizontalScroll = true, // 始终启用滚动
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        maxHeight = 600,
                        onPreviewClick = if (isPreviewSupported) {
                            { previewState = part.content to (part.language ?: "") }
                        } else null
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
                        isStreaming = false,
                        contentKey = if (contentKey.isNotBlank()) "${contentKey}_part_${parsedParts.indexOf(part)}" else ""
                    )
                }
            }
        }
    }

    // 🎯 显示预览对话框
    previewState?.let { (code, language) ->
        WebPreviewDialog(
            code = code,
            language = language,
            onDismiss = { previewState = null }
        )
    }
}
