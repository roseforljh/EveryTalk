package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.statecontroller.AppViewModel

/**
 * 增强的Markdown文本显示组件
 * 
 * 支持功能：
 * - Markdown格式（标题、列表、粗体、斜体等）
 * - 代码块（自适应滚动）
 * - 表格渲染
 * - 流式实时更新
 */
@Composable
fun EnhancedMarkdownText(
    message: Message,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    messageOutputType: String = "",
    inTableContext: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    inSelectionDialog: Boolean = false,
    viewModel: AppViewModel? = null
) {
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    // 🎯 流式内容实时获取 - 使用 derivedStateOf 优化
    val streamingContent = if (isStreaming && viewModel != null) {
        viewModel.streamingMessageStateManager
            .getOrCreateStreamingState(message.id)
            .collectAsState(initial = message.text)
    } else {
        null
    }
    
    // 使用 derivedStateOf 避免内容未真正改变时的重组
    val content by remember {
        derivedStateOf {
            streamingContent?.value ?: message.text
        }
    }
    
    // === 增量安全解析通道（边流边准，避免全文反复扫描） ===
    // 说明：
    // - 不再在流式阶段对“整段全文”做分块解析，而是维护“已提交安全块 + 未闭合尾巴”
    // - 每次只解析“新增长 + 上次尾巴”，使用 ContentParser.parseStreamingContent 控制安全断点
    val parsedParts = remember(message.id) { mutableStateListOf<ContentPart>() }
    var retainedTail by remember(message.id) { mutableStateOf("") }
    var lastLen by remember(message.id) { mutableStateOf(0) }

    // 计算当前文本源：流式优先使用 streamingContent，否则用 message.text
    val fullText by remember(isStreaming, content) {
        derivedStateOf { content }
    }

    // 增量解析：仅在长度增长时解析“新增 + 尾巴”
    LaunchedEffect(fullText, isStreaming) {
        val currentLen = fullText.length
        if (currentLen < lastLen) {
            // 文本被回退（如重置/替换），重置解析状态
            parsedParts.clear()
            retainedTail = ""
            lastLen = 0
        }
        if (currentLen > lastLen) {
            val delta = fullText.substring(lastLen)
            val buffer = retainedTail + delta
            try {
                val (newParts, newRetained) = ContentParser.parseStreamingContent(
                    currentBuffer = buffer,
                    isComplete = false
                )
                if (newParts.isNotEmpty()) {
                    parsedParts.addAll(newParts)
                }
                retainedTail = newRetained
                lastLen = currentLen
            } catch (_: Exception) {
                // 出错时保持安全：不提交块，仅更新lastLen，尾巴按原样展示
                lastLen = currentLen
            }
        }

        // 流结束时（isStreaming=false）做一次最终化（将尾巴消化为块）
        if (!isStreaming && retainedTail.isNotEmpty()) {
            try {
                val (finalParts, finalRetained) = ContentParser.parseStreamingContent(
                    currentBuffer = retainedTail,
                    isComplete = true
                )
                if (finalParts.isNotEmpty()) {
                    parsedParts.addAll(finalParts)
                }
                retainedTail = finalRetained // 应为空
            } catch (_: Exception) {
                // 忽略最终化异常，尾巴依然以纯文本显示
            }
        }
    }

    // 兼容：若增量通道尚未产出任何块，退回旧逻辑（含短路保护）
    val legacyParsedContent by remember {
        derivedStateOf {
            val len = content.length
            val hasFence = content.contains("```")
            val fenceCount = if (hasFence) Regex("```").findAll(content).count() else 0
            val unclosedFence = hasFence && (fenceCount % 2 == 1)
            val hasMathMarkers = content.contains("$$") || content.count { it == '$' } >= 4
            val tooLongForStreaming = len > 2000

            if (isStreaming && (unclosedFence || hasMathMarkers || tooLongForStreaming)) {
                listOf(ContentPart.Text(content))
            } else {
                when {
                    len > 10000 -> listOf(ContentPart.Text(content))
                    isStreaming -> ContentParser.parseCodeBlocksOnly(content)
                    else -> ContentParser.parseCompleteContent(content)
                }
            }
        }
    }
    
    // 优先使用“增量安全解析通道”的结果进行双通道渲染
    val hasIncremental = parsedParts.isNotEmpty() || retainedTail.isNotEmpty()
    if (hasIncremental) {
        Column(modifier = modifier.fillMaxWidth()) {
            // 已提交的安全块
            parsedParts.forEach { part ->
                when (part) {
                    is ContentPart.Text -> {
                        MarkdownRenderer(
                            markdown = part.content,
                            style = style,
                            color = textColor,
                            modifier = Modifier.fillMaxWidth(),
                            isStreaming = true // 安全块；流式轻渲染门槛内会转换
                        )
                    }
                    is ContentPart.Code -> {
                        val shouldScroll = part.content.lines().maxOfOrNull { it.length } ?: 0 > 80
                        CodeBlock(
                            code = part.content,
                            language = part.language,
                            textColor = textColor,
                            enableHorizontalScroll = shouldScroll,
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
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
            // 未闭合的尾巴：稳定起见维持纯文本
            if (retainedTail.isNotEmpty()) {
                MarkdownRenderer(
                    markdown = retainedTail,
                    style = style,
                    color = textColor,
                    modifier = Modifier.fillMaxWidth(),
                    isStreaming = true // 轻渲染：多数情况会回退纯文本
                )
            }
        }
    } else {
        // 兼容路径：沿用原先逻辑（含短路）
        if (legacyParsedContent.size == 1 && legacyParsedContent[0] is ContentPart.Text) {
            MarkdownRenderer(
                markdown = content,
                style = style,
                color = textColor,
                modifier = modifier.fillMaxWidth(),
                isStreaming = isStreaming
            )
        } else {
            Column(modifier = modifier.fillMaxWidth()) {
                legacyParsedContent.forEach { part ->
                    when (part) {
                        is ContentPart.Text -> {
                            MarkdownRenderer(
                                markdown = part.content,
                                style = style,
                                color = textColor,
                                modifier = Modifier.fillMaxWidth(),
                                isStreaming = isStreaming
                            )
                        }
                        is ContentPart.Code -> {
                            val shouldScroll = part.content.lines().maxOfOrNull { it.length } ?: 0 > 80
                            CodeBlock(
                                code = part.content,
                                language = part.language,
                                textColor = textColor,
                                enableHorizontalScroll = shouldScroll,
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
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 简化的静态文本显示组件
 */
@Composable
fun StableMarkdownText(
    markdown: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    Text(
        text = markdown,
        modifier = modifier,
        style = style.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    )
}
