package com.android.everytalk.ui.components
import com.android.everytalk.ui.components.coordinator.ContentCoordinator

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
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.AppViewModel

private fun hasMathSyntax(text: String): Boolean = MathStreamingPolicy.hasMathSyntax(text)

private fun hasUnclosedMathDelimiter(text: String): Boolean =
    MathStreamingPolicy.hasUnclosedMathDelimiter(text)

private fun findMathAffectedRange(previous: String, current: String): MathStreamingPolicy.AffectedRange? =
    MathStreamingPolicy.findMathAffectedRange(previous, current)

private fun shouldForceMathBoundaryRefresh(previous: String, current: String): Boolean =
    MathStreamingPolicy.shouldForceMathBoundaryRefresh(previous, current)

private const val MATH_STREAM_LOG_TAG = "MathStreamThrottle"


/**
 * 增强的 Markdown 文本显示组件
 * 
 * 支持功能：
 * - Markdown 格式（标题、列表、粗体、斜体等）
 * - 代码块（自适应滚动）
 * - 表格渲染
 * - 数学公式（KaTeX）
 * - 流式实时更新
 * 
 * 架构说明（重构后）：
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
    onImageClick: ((String) -> Unit)? = null, // 新增
    onCodePreviewRequested: ((String, String) -> Unit)? = null, // 新增：代码预览回调 (language, code)
    onCodeCopied: (() -> Unit)? = null, // 新增：代码复制回调
    viewModel: AppViewModel? = null,
    contentOverride: String? = null,
    contentKeyOverride: String? = null,
    disableStreamingSubscription: Boolean = false
) {
    val resolvedContentKey = contentKeyOverride ?: message.id
    val staticContent = contentOverride ?: message.text

    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    // 获取实时流式内容
    // 使用 collectAsState 订阅 Flow，实现流式效果
    // 优化：流式结束后继续订阅 StateFlow，直到组件销毁或显式重置
    // 避免 isStreaming 从 true -> false 瞬间切换数据源导致重组闪烁
    val streamingStateFlow = remember(message.id, viewModel, disableStreamingSubscription) {
        if (viewModel != null && !disableStreamingSubscription) {
            viewModel.streamingMessageStateManager.getOrCreateStreamingState(message.id)
        } else {
            null
        }
    }

    val content by if (streamingStateFlow != null && (isStreaming || viewModel?.streamingMessageStateManager?.isStreaming(message.id) == true)) {
        // 如果有可用的 StateFlow 且（正在流式 OR 状态管理器认为还在流式），优先使用流式数据
        // 即使 isStreaming 变为 false，只要 StateFlow 还在，就继续用它，防止切回 message.text 的瞬间闪烁
        streamingStateFlow.collectAsState(initial = staticContent)
    } else {
        // 完全非流式或无 ViewModel：使用 remember 包装 message.text
        remember(staticContent) { mutableStateOf(staticContent) }
    }
    

    // 委托给 ContentCoordinator 统一调度
    // 优势：
    // 1. 职责分离：数学、表格、纯文本各自独立
    // 2. 易于维护：修改某一个模块不影响其他模块
    // 3. 易于扩展：添加新类型（如图表）只需新增模块
    // 4. 缓存机制：使用消息 ID 作为 key，避免 LazyColumn 回收后重复解析
    // 根据发送者决定宽度策略
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

        // 流式结束后给数学节流一个冷却期，避免立即切换渲染模式导致高度突变
        var wasActuallyStreaming by remember(resolvedContentKey) { mutableStateOf(false) }
        var streamEndTimeMs by remember(resolvedContentKey) { mutableStateOf(0L) }
        LaunchedEffect(isActuallyStreaming) {
            if (!isActuallyStreaming && wasActuallyStreaming) {
                streamEndTimeMs = android.os.SystemClock.elapsedRealtime()
            }
            wasActuallyStreaming = isActuallyStreaming
        }
        val inStreamingCooldown = !isActuallyStreaming &&
            streamEndTimeMs > 0L &&
            (android.os.SystemClock.elapsedRealtime() - streamEndTimeMs) < 
                if (content.length < 200) 80L else 300L
        val effectiveIsStreaming = isActuallyStreaming || inStreamingCooldown

        var throttledMathContent by remember(resolvedContentKey) { mutableStateOf(content) }
        var lastRawMathContent by remember(resolvedContentKey) { mutableStateOf(content) }
        var lastMathUpdateAt by remember(resolvedContentKey) { mutableStateOf(0L) }
        var lastMathUnclosed by remember(resolvedContentKey) { mutableStateOf(false) }

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
            val affectedRange = findMathAffectedRange(lastRawMathContent, content)
            val boundaryRefresh =
                shouldForceMathBoundaryRefresh(lastRawMathContent, content) ||
                    affectedRange != null ||
                    (lastMathUnclosed && !currentUnclosed)
            val throttleWindowMs = if (currentUnclosed) 120L else 80L
            val elapsedSinceLastUpdate = now - lastMathUpdateAt
            val shouldUpdate = boundaryRefresh || elapsedSinceLastUpdate >= throttleWindowMs
            val shouldLogMathStreaming = com.android.everytalk.BuildConfig.DEBUG &&
                PerformanceConfig.ENABLE_RENDER_TRANSITION_LOGGING

            if (shouldLogMathStreaming) {
                val deltaLen = content.length - lastRawMathContent.length
                val reason = when {
                    boundaryRefresh -> "boundary"
                    shouldUpdate -> "throttle-window"
                    else -> "skip"
                }
                android.util.Log.d(
                    MATH_STREAM_LOG_TAG,
                    "msgId=${resolvedContentKey.take(24)} action=${if (shouldUpdate) "update" else "skip"} " +
                        "reason=$reason len=${content.length} delta=$deltaLen " +
                        "range=${affectedRange?.start ?: -1}-${affectedRange?.endExclusive ?: -1} " +
                        "unclosed=$currentUnclosed prevUnclosed=$lastMathUnclosed " +
                        "elapsed=${elapsedSinceLastUpdate}ms window=${throttleWindowMs}ms"
                )
            }

            if (shouldUpdate) {
                throttledMathContent = content
                lastMathUpdateAt = now
            }

            lastRawMathContent = content
            lastMathUnclosed = currentUnclosed
        }

        val displayText = throttledMathContent

        ContentCoordinator(
            text = displayText,
            style = style,
            color = textColor,
            isStreaming = effectiveIsStreaming,
            modifier = widthModifier,
            contentKey = resolvedContentKey,
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
