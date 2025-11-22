package com.android.everytalk.ui.components.table

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.android.everytalk.ui.components.CodeBlock
import com.android.everytalk.ui.components.ContentParser
import com.android.everytalk.ui.components.ContentPart
import com.android.everytalk.ui.components.WebPreviewDialog
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.android.everytalk.util.ContentParseCache
import com.android.everytalk.ui.components.table.TableUtils

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
    contentKey: String = "",  // 新增：用于缓存key（通常为消息ID）
    onLongPress: (() -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null
) {
    // 预览状态管理
    var previewState by remember { mutableStateOf<Pair<String, String>?>(null) } // (code, language)

    // 方案二：实时分段解析与统一渲染
    // 无论是否流式，都尝试进行轻量级分段解析（仅分离代码块，表格仍由MarkdownRenderer处理或后续优化）
    
    // 1. 解析状态管理
    // 优化：使用 remember + LaunchedEffect 替代 produceState
    // 目的：当 isStreaming 变化时（true -> false），保持当前的 parsedParts 不变，
    // 直到新的解析完成。避免 produceState 重置导致的回退到 initialValue (纯文本) 造成的闪烁/跳动。
    
    // 缓存版本控制：当解析逻辑更新时，通过修改版本号使旧缓存失效
    val effectiveCacheKey = if (contentKey.isNotBlank()) "${contentKey}_v${ContentParseCache.PARSER_VERSION}" else ""

    val parsedPartsState = remember(contentKey) {
        mutableStateOf(
            if (!isStreaming && effectiveCacheKey.isNotBlank()) {
                ContentParseCache.get(effectiveCacheKey) ?: listOf(ContentPart.Text(text))
            } else {
                listOf(ContentPart.Text(text))
            }
        )
    }

    LaunchedEffect(text, isStreaming, contentKey) {
        val newParts = withContext(Dispatchers.Default) {
            if (isStreaming) {
                // 流式期间不读写全局缓存，直接解析
                ContentParser.parseCompleteContent(text, isStreaming = true)
            } else {
                // 非流式：尝试从全局缓存获取，否则完整解析并缓存
                // 策略：如果文本包含表格特征字符 '|'，为了保险起见，可以考虑强制刷新（可选）
                // 但有了版本号控制，通常不需要强制刷新。
                ContentParseCache.get(effectiveCacheKey) ?: ContentParser.parseCompleteContent(text, isStreaming = false).also {
                    if (effectiveCacheKey.isNotBlank()) ContentParseCache.put(effectiveCacheKey, it)
                }
            }
        }
        parsedPartsState.value = newParts
    }
    
    val parsedParts = parsedPartsState.value

    // UI层兜底过滤：移除 ContentPart.Text 中的表格行
    // 即使解析器偶尔漏判，这里也能保证表格源文本不会被渲染出来
    val filteredParts = remember(parsedParts) {
        parsedParts.mapNotNull { part ->
            if (part is ContentPart.Text) {
                // 按行拆分，过滤掉看起来像表格行或分隔行的内容
                val lines = part.content.lines()
                val filteredLines = lines.filterNot { line ->
                    // 过滤条件：是表格行 OR 是分隔行
                    // 注意：这里使用 TableUtils 的宽松检查，宁可错杀不可放过（对于纯文本中的 | 行）
                    // 但为了避免误伤普通文本（如 "A | B"），我们结合上下文判断？
                    // 不，这里是兜底，假设 ContentParser 已经把真正的表格提取走了。
                    // 剩下的 Text 里如果还有类似表格行的东西，大概率是解析残留。
                    // 只要包含 | 且符合表格行特征，就过滤掉。
                    TableUtils.isTableLine(line)
                }
                
                // 如果过滤后内容为空（说明全是表格行），则丢弃该 Text 片段
                // 如果还有内容，重新组合
                val newContent = filteredLines.joinToString("\n")
                if (newContent.isBlank()) null else ContentPart.Text(newContent)
            } else {
                part
            }
        }
    }

    // 2. 统一渲染逻辑
    // 不再区分 isStreaming 的大分支，而是统一遍历 parsedParts 进行渲染
    Column(modifier = modifier.fillMaxWidth()) {
        filteredParts.forEach { part ->
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
                        contentKey = if (contentKey.isNotBlank()) "${contentKey}_part_${parsedParts.indexOf(part)}" else "" // 传递子Key
                    )
                }
                is ContentPart.Code -> {
                    // 代码块部分：始终用 CodeBlock 渲染
                    // 流式期间可能没有语言标识或未闭合，CodeBlock 需能处理
                    
                    // 检查是否支持预览
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
                    // 表格部分：使用 TableRenderer 渲染
                    TableRenderer(
                        lines = part.lines,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        isStreaming = isStreaming,
                        contentKey = if (contentKey.isNotBlank()) "${contentKey}_table_${parsedParts.indexOf(part)}" else "",
                        onLongPress = onLongPress,
                        // 使用与文本一致的样式
                        headerStyle = style.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        cellStyle = style
                    )
                }
            }
        }
    }

    // 显示预览对话框
    previewState?.let { (code, language) ->
        WebPreviewDialog(
            code = code,
            language = language,
            onDismiss = { previewState = null }
        )
    }
}
