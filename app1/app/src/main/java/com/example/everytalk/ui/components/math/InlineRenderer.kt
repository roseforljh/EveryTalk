package com.example.everytalk.ui.components.math

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.everytalk.ui.components.MarkdownRenderer
import com.example.everytalk.ui.components.math.MathInlineText

/**
 * 数学感知渲染（最小侵入）：
 *
 * 设计目标：
 * - 块级 $$...$$ 使用 WebView+KaTeX 渲染（MathBlock）
 * - 内联 $...$ 暂不使用 WebView（Compose 无法将 AndroidView 内联到段落基线，易造成“断行/错位”）
 *   因此当文本只包含“内联数学”时，整体退回 MarkdownRenderer 以保持段落完整性；
 *   仅当存在“块级数学”时才按片段拆分渲染：Text 段落仍走 Markdown，块级数学用 MathBlock。
 */
@Composable
fun MathAwareText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 原始切分
    val rawSpans = MathParser.splitToSpans(text)

    // 规范化：移除由于上游换行/边界造成的“游离 $ 行”
    // 症状：在块级公式前/后出现独立一行的 "$"（实为闭合 $$ 被拆成两行）
    // 策略：删除只包含 "$"（含空白）的 Text 片段，且其前后有块级 Math 的场景；
    // 同时合并相邻的 Text 片段，避免碎片化。
    val spans = run {
        val tmp = mutableListOf<MathParser.Span>()
        // 先合并相邻 Text
        for (s in rawSpans) {
            val last = tmp.lastOrNull()
            if (s is MathParser.Span.Text && last is MathParser.Span.Text) {
                tmp[tmp.lastIndex] = MathParser.Span.Text(last.content + s.content)
            } else {
                tmp += s
            }
        }
        // 再清理“游离 $ 行”
        val cleaned = mutableListOf<MathParser.Span>()
        fun isLoneDollarLine(t: String): Boolean {
            val trimmed = t.trim()
            // 精确等于一个 $，或 $ 前后仅有换行/空白
            return trimmed == "$"
        }
        for (i in tmp.indices) {
            val cur = tmp[i]
            if (cur is MathParser.Span.Text && isLoneDollarLine(cur.content)) {
                val prev = tmp.getOrNull(i - 1)
                val next = tmp.getOrNull(i + 1)
                val nearBlock =
                    (prev is MathParser.Span.Math && !prev.inline) ||
                    (next is MathParser.Span.Math && !next.inline)
                if (nearBlock) {
                    // 跳过该“游离 $ 行”
                    continue
                }
            }
            cleaned += cur
        }
        // 再次合并相邻 Text（清理后可能相邻）
        val merged = mutableListOf<MathParser.Span>()
        for (s in cleaned) {
            val last = merged.lastOrNull()
            if (s is MathParser.Span.Text && last is MathParser.Span.Text) {
                merged[merged.lastIndex] = MathParser.Span.Text(last.content + s.content)
            } else {
                merged += s
            }
        }
        merged
    }

    // 快速路径：没有任何数学，直接 Markdown
    val hasAnyMath = spans.any { it is MathParser.Span.Math }
    if (!hasAnyMath) {
        MarkdownRenderer(
            markdown = text,
            style = style,
            color = color,
            modifier = modifier.fillMaxWidth(),
            isStreaming = isStreaming
        )
        return
    }

    // 仅内联数学：使用 Text + inlineContent 做真正“行内”渲染
    val hasBlock = spans.any { it is MathParser.Span.Math && !it.inline }
    val hasInline = spans.any { it is MathParser.Span.Math && it.inline }
    if (hasInline && !hasBlock) {
        MathInlineText(
            inlineSpans = spans,
            style = style,
            color = color,
            isStreaming = isStreaming,
            modifier = modifier.fillMaxWidth()
        )
        return
    }

    // 混合情况：将连续的 Text/InlineMath 片段聚合为一个段落，用 MathInlineText 渲染；
    // 遇到 BlockMath 时先冲刷上一个段落，再渲染块级数学。
    Column(modifier = modifier.fillMaxWidth()) {
        val buffer = mutableListOf<MathParser.Span>()
        @Composable
        fun flushBuffer() {
            if (buffer.isEmpty()) return
            val onlyText = buffer.all { it is MathParser.Span.Text }
            if (onlyText) {
                // 全是普通文本，直接 Markdown
                MarkdownRenderer(
                    markdown = buffer.joinToString(separator = "") {
                        (it as MathParser.Span.Text).content
                    },
                    style = style,
                    color = color,
                    modifier = Modifier.fillMaxWidth(),
                    isStreaming = isStreaming
                )
            } else {
                // 包含内联数学，使用内联渲染器
                MathInlineText(
                    inlineSpans = buffer.toList(),
                    style = style,
                    color = color,
                    isStreaming = isStreaming,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            buffer.clear()
        }

        spans.forEach { span ->
            when (span) {
                is MathParser.Span.Text -> buffer.add(span)
                is MathParser.Span.Math -> {
                    if (span.inline) {
                        buffer.add(span)
                    } else {
                        // 块级数学：先冲刷上一个段落，再渲染块
                        flushBuffer()
                        MathBlock(
                            latex = span.content,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
        flushBuffer()
    }
}