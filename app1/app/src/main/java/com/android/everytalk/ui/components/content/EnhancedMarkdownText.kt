package com.android.everytalk.ui.components
import com.android.everytalk.ui.components.coordinator.ContentCoordinator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.android.everytalk.ui.components.content.CodeBlockCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.AppViewModel

/**
 * 增强的Markdown文本显示组件
 * 
 * 支持功能：
 * - Markdown格式（标题、列表、粗体、斜体等）- 通过外部库实时转换
 * - 代码块（自适应滚动）
 * - 表格渲染
 * - 数学公式（KaTeX）
 * - 流式实时更新
 * 
 *  架构说明（重构后）：
 * - 使用 collectAsState 订阅流式内容，实现实时更新
 * - 委托给 ContentCoordinator 统一调度不同类型的内容
 * - 单向数据流：Flow → State → UI（无反向依赖，避免无限重组）
 * - 添加重组监控，及时发现潜在问题
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
    onImageClick: ((String) -> Unit)? = null, //  新增
    onCodePreviewRequested: ((String, String) -> Unit)? = null, // 新增：代码预览回调 (language, code)
    onCodeCopied: (() -> Unit)? = null, // 新增：代码复制回调
    viewModel: AppViewModel? = null
) {
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    //  获取实时流式内容
    // 使用 collectAsState 订阅Flow，实现流式效果
    //  优化：流式结束后继续订阅 StateFlow，直到组件销毁或显式重置
    // 避免 isStreaming 从 true -> false 瞬间切换数据源导致重组闪烁
    val streamingStateFlow = remember(message.id, viewModel) {
        if (viewModel != null) {
            viewModel.streamingMessageStateManager.getOrCreateStreamingState(message.id)
        } else {
            null
        }
    }

    val content by if (streamingStateFlow != null && (isStreaming || viewModel?.streamingMessageStateManager?.isStreaming(message.id) == true)) {
        // 如果有可用的 StateFlow 且 (正在流式 OR 状态管理器认为还在流式)，优先使用流式数据
        // 即使 isStreaming 变为 false，只要 StateFlow 还在，就继续用它，防止切回 message.text 的瞬间闪烁
        streamingStateFlow.collectAsState(initial = message.text)
    } else {
        // 完全非流式或无 ViewModel：使用 remember 包装 message.text
        remember(message.text) { mutableStateOf(message.text) }
    }
    
    // 🔍 调试：记录content更新
    if (isStreaming && com.android.everytalk.BuildConfig.DEBUG) {
        androidx.compose.runtime.SideEffect {
            // 每次content变化都记录
            android.util.Log.d("EnhancedMarkdownText",
                "📝 Streaming content updated: msgId=${message.id.take(8)}, len=${content.length}, preview=${content.take(30)}")
        }
    }

    //  委托给 ContentCoordinator 统一调度
    // 优势：
    // 1. 职责分离：数学、表格、纯文本各自独立
    // 2. 易于维护：修改某个模块不影响其他模块
    // 3. 易于扩展：添加新类型（如图表）只需添加新模块
    // 4. 缓存机制：使用消息ID作为key，避免LazyColumn回收后重复解析
    //  根据发送者决定宽度策略
    val widthModifier = if (message.sender == Sender.User) {
        Modifier.wrapContentWidth()
    } else {
        Modifier.fillMaxWidth()
    }
    
    Box(
        modifier = modifier.then(widthModifier)
    ) {
        // 对所有消息启用分段渲染，以支持代码块和表格的横向滚动
        // 解析内容片段
        val parts = remember(content) {
            // 始终使用 parseCompleteContent，因为流式中间态也需要正确渲染已闭合的代码块
            // ContentParser 内部逻辑已足够健壮
            ContentParser.parseCompleteContent(content, isStreaming)
        }

        androidx.compose.foundation.layout.Column(
            modifier = widthModifier
        ) {
            parts.forEachIndexed { index, part ->
                when (part) {
                    is ContentPart.Code -> {
                        // 渲染代码块卡片
                        val clipboard = LocalClipboardManager.current
                        CodeBlockCard(
                            language = part.language,
                            code = part.content,
                            modifier = Modifier.padding(vertical = 4.dp),
                            onPreviewRequested = if (onCodePreviewRequested != null) {
                                { onCodePreviewRequested(part.language ?: "", part.content) }
                            } else null,
                            onCopy = {
                                clipboard.setText(AnnotatedString(part.content))
                                onCodeCopied?.invoke()
                            }
                        )
                    }
                    is ContentPart.Text -> {
                        // 渲染普通文本
                        // 注意：这里需要为每个片段生成唯一的 contentKey，避免缓存冲突
                        // 使用 message.id + index
                        ContentCoordinator(
                            text = part.content,
                            style = style,
                            color = textColor,
                            isStreaming = isStreaming && index == parts.lastIndex, // 只有最后一段可能是流式未完成
                            modifier = widthModifier,
                            contentKey = "${message.id}_part_$index",
                            onLongPress = onLongPress,
                            onImageClick = onImageClick,
                            sender = message.sender,
                            disableVerticalPadding = true // 禁用垂直padding，由Column控制间距
                        )
                    }
                    is ContentPart.Table -> {
                        // 表格暂按文本处理（ContentCoordinator 内部可能还有表格处理逻辑，或者直接渲染 markdown 表格）
                        // 如果 ContentCoordinator 支持 Table 对象更好，但目前看它只接受 text
                        // 这里把表格行拼回 markdown
                        val tableMarkdown = part.lines.joinToString("\n")
                        ContentCoordinator(
                            text = tableMarkdown,
                            style = style,
                            color = textColor,
                            isStreaming = isStreaming && index == parts.lastIndex,
                            modifier = widthModifier,
                            contentKey = "${message.id}_part_$index",
                            onLongPress = onLongPress,
                            onImageClick = onImageClick,
                            sender = message.sender,
                            disableVerticalPadding = true
                        )
                    }
                    is ContentPart.Math -> {
                        // 数学公式暂按文本处理
                        ContentCoordinator(
                            text = part.content,
                            style = style,
                            color = textColor,
                            isStreaming = isStreaming && index == parts.lastIndex,
                            modifier = widthModifier,
                            contentKey = "${message.id}_part_$index",
                            onLongPress = onLongPress,
                            onImageClick = onImageClick,
                            sender = message.sender,
                            disableVerticalPadding = true
                        )
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
    androidx.compose.material3.Text(
        text = markdown,
        modifier = modifier,
        style = style.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    )
}
