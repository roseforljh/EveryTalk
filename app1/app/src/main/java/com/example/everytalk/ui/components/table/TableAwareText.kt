package com.example.everytalk.ui.components.table

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.everytalk.ui.components.CodeBlock
import com.example.everytalk.ui.components.coordinator.ContentCoordinator
import com.example.everytalk.ui.components.ContentParser
import com.example.everytalk.ui.components.ContentPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 表格感知文本渲染器
 * 
 * 职责：
 * - 检测并解析包含表格的文本
 * - 分段渲染：表格、代码块、普通文本
 * - 递归调用 ContentCoordinator 处理文本片段
 * 
 * 设计原则：
 * - 单一职责：只处理表格相关逻辑
 * - 依赖倒置：依赖 ContentCoordinator 而不是具体实现
 */
@Composable
fun TableAwareText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    recursionDepth: Int = 0
) {
    // 异步解析：流式阶段使用轻量路径（仅代码块），非流式使用完整解析
    val parts = produceState(initialValue = emptyList<ContentPart>(), text, isStreaming) {
        value = withContext(Dispatchers.Default) {
            try {
                if (isStreaming) {
                    ContentParser.parseCodeBlocksOnly(text)
                } else {
                    ContentParser.parseCompleteContent(text)
                }
            } catch (_: Throwable) {
                listOf(ContentPart.Text(text))
            }
        }
    }.value
    
    // 分段渲染
    Column(modifier = modifier.fillMaxWidth()) {
        parts.forEach { part ->
            when (part) {
                is ContentPart.Text -> {
                    // 递归调用协调器处理文本（可能包含数学公式）
                    ContentCoordinator(
                        text = part.content,
                        style = style,
                        color = color,
                        isStreaming = isStreaming,
                        modifier = Modifier.fillMaxWidth(),
                        recursionDepth = recursionDepth + 1  // 深度+1
                    )
                }
                is ContentPart.Code -> {
                    CodeBlock(
                        code = part.content,
                        language = part.language,
                        textColor = color,
                        enableHorizontalScroll = part.content.lines()
                            .maxOfOrNull { it.length } ?: 0 > 80,
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
                            .padding(vertical = 8.dp),
                        isStreaming = isStreaming
                    )
                }
            }
        }
    }
}