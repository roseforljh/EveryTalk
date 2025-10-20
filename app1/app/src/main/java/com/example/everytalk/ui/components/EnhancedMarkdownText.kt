package com.example.everytalk.ui.components
import com.example.everytalk.ui.components.coordinator.ContentCoordinator

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
    
    // 🔍 调试：记录content更新
    if (isStreaming && com.example.everytalk.BuildConfig.DEBUG) {
        androidx.compose.runtime.SideEffect {
            // 每次content变化都记录
            android.util.Log.d("EnhancedMarkdownText", 
                "📝 Content updated: msgId=${message.id.take(8)}, len=${content.length}, preview=${content.take(30)}")
        }
    }

    // 🎯 委托给 ContentCoordinator 统一调度
    // 优势：
    // 1. 职责分离：数学、表格、纯文本各自独立
    // 2. 易于维护：修改某个模块不影响其他模块
    // 3. 易于扩展：添加新类型（如图表）只需添加新模块
    // 4. 缓存机制：使用消息ID作为key，避免LazyColumn回收后重复解析
    ContentCoordinator(
        text = content,
        style = style,
        color = textColor,
        isStreaming = isStreaming,
        modifier = modifier.fillMaxWidth(),
        contentKey = message.id  // 🎯 传递消息ID作为缓存key
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
