package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.statecontroller.AppViewModel
import com.example.everytalk.ui.components.math.MathAwareText

/**
 * 增强的Markdown文本显示组件
 * 
 * 支持功能：
 * - Markdown格式（标题、列表、粗体、斜体等）- 通过外部库实时转换
 * - 代码块（自适应滚动）
 * - 表格渲染
 * - 流式实时更新
 * 
 * 🔧 优化说明（终极方案）：
 * - 使用 collectAsState 订阅流式内容，实现实时更新
 * - 单向数据流：Flow → State → UI（无反向依赖，避免无限重组）
 * - 每次Flow发射新值 → 触发一次重组 → 渲染新内容 → 结束
 * - 让外部库 dev.jeziellago.compose.markdowntext.MarkdownText 自动处理MD转换
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
    viewModel: AppViewModel? = null
) {
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    // 🎯 关键改动：使用 collectAsState 订阅流式内容
    // 这会在每次Flow发射新值时触发重组，实现流式效果
    // 但不会形成无限循环，因为是单向数据流
    val content by if (isStreaming && viewModel != null) {
        // 流式阶段：订阅StateFlow，实时获取增量内容
        // collectAsState 会在Flow发射新值时触发重组
        viewModel.streamingMessageStateManager
            .getOrCreateStreamingState(message.id)
            .collectAsState(initial = message.text)
    } else {
        // 非流式：使用remember包装，避免不必要的重组
        remember(message.text) { mutableStateOf(message.text) }
    }
    
    // 🛡️ 重组监控（调试用）
    // 流式阶段允许多次重组（每次新内容一次），但不应超过合理范围
    val recompositionCount = remember(message.id) { mutableStateOf(0) }
    SideEffect {
        recompositionCount.value++
        // 流式阶段可能有几十到几百次重组（取决于Flow发射频率）
        // 如果超过1000次，说明可能有问题
        if (recompositionCount.value > 1000) {
            android.util.Log.e(
                "EnhancedMarkdownText",
                "⚠️ 异常重组: ${recompositionCount.value} 次，messageId=${message.id}, contentLength=${content.length}"
            )
        }
        // 每100次打印一次日志，便于监控
        if (recompositionCount.value % 100 == 0) {
            android.util.Log.d(
                "EnhancedMarkdownText",
                "重组次数: ${recompositionCount.value}, messageId=${message.id}, isStreaming=$isStreaming"
            )
        }
    }

    // 🎯 直接渲染，让 MathAwareText → MarkdownRenderer 处理MD转换
    // 优势：
    // 1. 实时MD转换（外部库自动处理 **粗体**、*斜体*、列表等）
    // 2. 流式效果（collectAsState 订阅Flow，每次新值触发重组）
    // 3. 不会无限重组（单向数据流，无状态回写）
    // 4. 代码简单，维护成本低
    MathAwareText(
        text = content,
        style = style,
        color = textColor,
        modifier = modifier.fillMaxWidth(),
        isStreaming = isStreaming
    )
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
