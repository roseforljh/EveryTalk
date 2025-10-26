package com.android.everytalk.ui.components.math

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.android.everytalk.ui.components.markdown.MarkdownRenderer

/**
 * 兼容版的内联数学渲染（不使用 InlineTextContent 占位，避免旧 Compose 版本 API 缺失导致编译错误）。
 *
 * 策略：
 * - 将传入的 spans 重新拼接为字符串：
 *   - Text 片段：原样追加
 *   - Math.inline：以 $...$ 形式回填
 *   - Math.block：以 $$...$$ 形式回填（理论上该组件只用于“仅内联”场景，上层保证不会出现）
 * - 统一交给 MarkdownRenderer 渲染，保持段落完整与基线稳定。
 *
 * 说明：
 * - 该实现确保在旧版本 Compose 环境下编译通过，同时避免 AndroidView 直接内联导致的断行/错位。
 * - 当项目升级到支持 InlineTextContent 的 Compose 版本后，可把此文件替换回“占位符 + MathInline”的实现。
 */
@Composable
fun MathInlineText(
    inlineSpans: List<MathParser.Span>,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val rebuilt = buildString {
        inlineSpans.forEach { span ->
            when (span) {
                is MathParser.Span.Text -> append(span.content)
                is MathParser.Span.Math -> {
                    if (span.inline) {
                        append("$")
                        append(span.content)
                        append("$")
                    } else {
                        append("$$")
                        append(span.content)
                        append("$$")
                    }
                }
            }
        }
    }

    MarkdownRenderer(
        markdown = rebuilt,
        style = style,
        color = color,
        modifier = modifier.fillMaxWidth(),
        isStreaming = isStreaming
    )
}