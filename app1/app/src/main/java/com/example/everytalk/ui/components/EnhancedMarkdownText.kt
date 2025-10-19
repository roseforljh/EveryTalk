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
 * 🔧 架构说明（重构后）：
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
    viewModel: AppViewModel? = null
) {
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    // 🎯 获取实时流式内容
    // 使用 collectAsState 订阅Flow，实现流式效果
    val content by if (isStreaming && viewModel != null) {
        // 流式阶段：订阅StateFlow，实时获取增量内容
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

    // 🎯 委托给 ContentCoordinator 统一调度
    // 优势：
    // 1. 职责分离：数学、表格、纯文本各自独立
    // 2. 易于维护：修改某个模块不影响其他模块
    // 3. 易于扩展：添加新类型（如图表）只需添加新模块
    ContentCoordinator(
        text = content,
        style = style,
        color = textColor,
        isStreaming = isStreaming,
        modifier = modifier.fillMaxWidth()
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
