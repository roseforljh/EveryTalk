package com.android.everytalk.ui.components
import com.android.everytalk.ui.components.coordinator.ContentCoordinator
import com.android.everytalk.ui.components.streaming.rememberTypewriterState

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.AppViewModel

private fun hasMathSyntax(text: String): Boolean {
    return text.contains('$') || text.contains("\\[") || text.contains("\\(")
}

private fun hasUnclosedMathDelimiter(text: String): Boolean {
    var inInlineCode = false
    var inBlockMath = false
    var inInlineMath = false
    var blockMarker = ""

    var i = 0
    while (i < text.length) {
        val ch = text[i]

        if (ch == '`') {
            inInlineCode = !inInlineCode
            i++
            continue
        }
        if (inInlineCode) {
            i++
            continue
        }

        if (inBlockMath) {
            if (text.startsWith(blockMarker, i)) {
                inBlockMath = false
                i += blockMarker.length
                continue
            }
            i++
            continue
        }

        if (text.startsWith("$$", i)) {
            inBlockMath = true
            blockMarker = "$$"
            i += 2
            continue
        }

        if (ch == '\\' && i + 1 < text.length && text[i + 1] == '[') {
            inBlockMath = true
            blockMarker = "\\]"
            i += 2
            continue
        }

        if (ch == '$') {
            val isCurrency = i + 1 < text.length && text[i + 1].isDigit()
            if (!isCurrency) {
                inInlineMath = !inInlineMath
            }
            i++
            continue
        }

        i++
    }

    return inBlockMath || inInlineMath
}

private fun shouldForceMathBoundaryRefresh(previous: String, current: String): Boolean {
    if (current.length <= previous.length) return true
    if (!current.startsWith(previous)) return true

    val delta = current.substring(previous.length)
    if (delta.isEmpty()) return false

    return delta.contains("$$") ||
        delta.contains("\\]") ||
        delta.contains('\n') ||
        delta.contains('。') ||
        delta.contains('！') ||
        delta.contains('？')
}

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
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
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
    
    // 🔍 调试：仅在 content 实际变化时记录，避免每次重组都打日志
    if (isStreaming && com.android.everytalk.BuildConfig.DEBUG) {
        LaunchedEffect(content) {
            android.util.Log.d(
                "EnhancedMarkdownText",
                "📝 Streaming content updated: msgId=${message.id.take(8)}, len=${content.length}, preview=${content.take(30)}"
            )
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
        val isActuallyStreaming = isStreaming ||
            (viewModel?.streamingMessageStateManager?.isStreaming(message.id) == true)

        var throttledMathContent by remember(message.id) { mutableStateOf(content) }
        var lastRawMathContent by remember(message.id) { mutableStateOf(content) }
        var lastMathUpdateAt by remember(message.id) { mutableStateOf(0L) }
        var lastMathUnclosed by remember(message.id) { mutableStateOf(false) }

        LaunchedEffect(content, isActuallyStreaming) {
            if (!isActuallyStreaming) {
                throttledMathContent = content
                lastRawMathContent = content
                lastMathUnclosed = hasUnclosedMathDelimiter(content)
                lastMathUpdateAt = SystemClock.elapsedRealtime()
                return@LaunchedEffect
            }

            if (!hasMathSyntax(content)) {
                throttledMathContent = content
                lastRawMathContent = content
                lastMathUnclosed = false
                lastMathUpdateAt = SystemClock.elapsedRealtime()
                return@LaunchedEffect
            }

            val now = SystemClock.elapsedRealtime()
            val currentUnclosed = hasUnclosedMathDelimiter(content)
            val boundaryRefresh =
                shouldForceMathBoundaryRefresh(lastRawMathContent, content) || (lastMathUnclosed && !currentUnclosed)
            val throttleWindowMs = if (currentUnclosed) 120L else 80L

            if (boundaryRefresh || now - lastMathUpdateAt >= throttleWindowMs) {
                throttledMathContent = content
                lastMathUpdateAt = now
            }

            lastRawMathContent = content
            lastMathUnclosed = currentUnclosed
        }

        val hasMath = hasMathSyntax(throttledMathContent)
        val useTypewriter = isActuallyStreaming && !hasMath

        val typewriterState = rememberTypewriterState(
            targetText = throttledMathContent,
            isStreaming = useTypewriter,
            charsPerFrame = 6,
            frameDelayMs = 32L,
            maxCharsPerFrame = 60,
            catchUpDivisor = 4
        )

        val displayText = when {
            useTypewriter -> typewriterState.displayedText
            else -> throttledMathContent
        }

        ContentCoordinator(
            text = displayText,
            style = style,
            color = textColor,
            isStreaming = isActuallyStreaming,
            modifier = widthModifier,
            contentKey = message.id,
            onLongPress = onLongPress,
            onImageClick = onImageClick,
            sender = message.sender,
            disableVerticalPadding = true,
            onCodePreviewRequested = onCodePreviewRequested,
            onCodeCopied = onCodeCopied
        )
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
