package com.example.everytalk.ui.components.math
import com.example.everytalk.ui.components.markdown.MarkdownRenderer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * 数学感知渲染器
 *
 * 职责：
 * - 检测并渲染数学公式（块级 $$...$$ 和内联 $...$）
 * - 块级数学使用 WebView+KaTeX 渲染
 * - 内联数学使用 MarkdownRenderer 保持段落完整性
 * 
 * 设计原则：
 * - 单一职责：只处理数学公式，不处理表格
 * - 开闭原则：易于扩展新的数学渲染方式
 * 
 * 🛡️ 递归深度保护：
 * - 由 ContentCoordinator 统一管理，此处不再检查
 * 
 * 🎯 缓存机制：
 * - 通过contentKey持久化解析结果，避免LazyColumn回收导致重复解析
 */
@Composable
fun MathAwareText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    recursionDepth: Int = 0,  // 保留参数以兼容调用方
    contentKey: String = ""  // 🎯 新增：用于缓存key（通常为消息ID）
) {
    // 🎯 解析数学公式
    // 🔥 使用 contentKey 缓存解析结果，避免 LazyColumn 回收后重复解析
    val rawSpans = remember(contentKey, text) { 
        MathParser.splitToSpans(text) 
    }

    // 规范化：移除由于上游换行/边界造成的"游离 $ 行"
    val spans = remember(contentKey, text) { run {
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
        // 再清理"游离 $ 行"
        val cleaned = mutableListOf<MathParser.Span>()
        fun isLoneDollarLine(t: String): Boolean {
            val trimmed = t.trim()
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
                    continue
                }
            }
            cleaned += cur
        }
        // 再次合并相邻 Text
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
    } }

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

    // 仅内联数学：使用 Text + inlineContent 做真正"行内"渲染
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