package com.example.everytalk.ui.components.markdown

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 纯文本 Markdown 渲染器（不含表格与代码块组件，职责单一）
 * - 外部库渲染基础 Markdown（标题、加粗、斜体、列表、链接、行内代码等）
 * - 非流式时执行一次轻量级格式修复
 * - 流式阶段跳过格式修复，保证实时性
 */
private const val MARKDOWN_FIX_MIN_LEN = 20

// 兼容性预处理：
// 某些解析器在 ** 开头紧跟全角引号（“『「等）时无法识别粗体。
// 将 **“文本”** 规范化为 “**文本**”，以及 **『文本』** -> 『**文本**』 等。
private fun normalizeCjkQuoteBold(input: String): String {
    var s = input
    val pairs = listOf(
        '“' to '”',
        '‘' to '’',
        '「' to '」',
        '『' to '』'
    )
    for ((l, r) in pairs) {
        // 匹配 **“xxx”** -> “**xxx**”
        val regex = Regex("""\*\*\Q$l\E(.*?)\Q$r\E\*\*""", RegexOption.DOT_MATCHES_ALL)
        s = s.replace(regex) { m ->
            val inner = m.groupValues[1]
            "$l**$inner**$r"
        }
    }
    return s
}

@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false
) {
    val isDark = isSystemInDarkTheme()
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }

    // 先做安全的 CJK 引号 + 粗体 规范化（不会影响代码块/数学）
    val preNormalized = remember(markdown) { normalizeCjkQuoteBold(markdown) }

    // 极长文本在流式阶段直接展示原文，避免阻塞
    val isTooLongForStreaming = isStreaming && preNormalized.length > 1500
    if (isTooLongForStreaming) {
        Text(
            text = preNormalized,
            style = style.copy(color = textColor),
            modifier = modifier
        )
        return
    }

    // 非流式执行一次修复；短文本和流式跳过修复
    val fixedMarkdown = if (isStreaming || preNormalized.length < MARKDOWN_FIX_MIN_LEN) {
        preNormalized
    } else {
        remember(preNormalized) {
            androidx.compose.runtime.derivedStateOf {
                try {
                    val fixed = MarkdownFormatFixer.fix(preNormalized)
                    if (com.example.everytalk.BuildConfig.DEBUG && preNormalized.length >= 80) {
                        android.util.Log.d(
                            "MarkdownRenderer",
                            "Fixed length: ${preNormalized.length} -> ${fixed.length}"
                        )
                    }
                    fixed
                } catch (e: Throwable) {
                    if (com.example.everytalk.BuildConfig.DEBUG) {
                        android.util.Log.e("MarkdownRenderer", "Fix failed, fallback to raw", e)
                    }
                    preNormalized
                }
            }
        }.value
    }

    // 行内代码配色（围栏代码块另由 CodeBlock 组件承担）
    val inlineCodeBackground = Color.Transparent
    val inlineCodeTextColor = if (isDark) {
        Color(0xFF9CDCFE) // 夜间：浅蓝
    } else {
        Color(0xFF005CC5) // 白天：深蓝
    }

    // 交由外部库渲染基础 Markdown
    dev.jeziellago.compose.markdowntext.MarkdownText(
        markdown = fixedMarkdown,
        style = style.copy(color = textColor),
        modifier = modifier,
        syntaxHighlightColor = inlineCodeBackground,
        syntaxHighlightTextColor = inlineCodeTextColor
    )
}
