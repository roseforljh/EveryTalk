package com.android.everytalk.ui.components.markdown

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

// 可开关：CJK 连写粗体兼容（默认关闭，按需灰度开启）
private const val ENABLE_CJK_BOLD_COMPAT = true

// 基础预处理：清理不可见字符/全角符号/CRLF，避免打断 Markdown 解析
private fun sanitizeInvisibleAndWidthChars(input: String): String {
    return input
        .replace("\r\n", "\n")
        .replace('\u00A0', ' ')   // NBSP -> 普通空格
        .replace('\u2007', ' ')   // FIGURE SPACE
        .replace('\u202F', ' ')   // NNBSP
        .replace("\u2060", "")    // WORD JOINER
        .replace("\uFEFF", "")    // BOM / ZERO WIDTH NBSP
        .replace("\u200B", "")    // ZWSP
        .replace("\u200C", "")    // ZWNJ
        .replace("\u200D", "")    // ZWJ
        .replace('\u3000', ' ')   // 全角空格 -> 半角
        // 常见“星号变体”归一为 ASCII *
        .replace('\uFF0A', '*')   // 全角＊
        .replace('\u2217', '*')   // ∗
        .replace('\u204E', '*')   // ⁎
        .replace('\u2731', '*')   // ✱
        .replace('\u066D', '*')   // ٭
}

// 兼容性预处理：
// 某些解析器在 ** 开头紧跟全角引号（“『「等）时无法识别粗体。
// 将 **“文本”** 规范化为 “**文本**”，以及 **『文本』** -> 『**文本**』 等。
private fun normalizeCjkQuoteBold(input: String): String {
    var s = input
    val pairs = listOf(
        '“' to '”',
        '‘' to '’',
        '「' to '」',
        '『' to '』',
        '《' to '》' // 新增：书名号成对支持
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

// ========== CJK 粗体启发式局部修复（仅非流式、且开启开关时生效） ==========

// 跳过区域：``` 代码围栏内不做替换
private fun splitByCodeFence(text: String): List<Pair<Boolean, String>> {
    if (!text.contains("```")) return listOf(false to text)
    val parts = mutableListOf<Pair<Boolean, String>>()
    var i = 0
    val n = text.length
    var inFence = false
    var last = 0
    while (i < n) {
        val p = text.indexOf("```", i)
        if (p < 0) break
        if (p > i) {
            // 追加中间段
            val seg = text.substring(i, p)
            // 合并到 previous 段尾
        }
        // 截取 [last, p)
        if (p >= last) {
            val seg = text.substring(last, p)
            parts.add(inFence to seg)
        }
        // 跳过 ```
        i = p + 3
        last = i
        inFence = !inFence
    }
    // 剩余
    if (last <= n - 1) parts.add(inFence to text.substring(last))
    if (parts.isEmpty()) parts.add(false to text)
    return parts
}

// 行级跳过：标题/列表/引用/链接或图片的起始行，保持标准解析
private fun shouldSkipLine(line: String): Boolean {
    val t = line.trimStart()
    return t.startsWith("#") ||
           t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") ||
           t.startsWith("> ") ||
           // 图片或链接语法开头
           t.startsWith("![") || t.startsWith("[")
}

// 将普通段落中的 CJK 连写 **…** 做最小替换：
// 1) 处理“**…**:”或“**…**：”收尾冒号；
// 2) 处理两侧邻接 CJK/全角引号/中文标点的 **…**；
// 使用 HTML <strong>，其余 Markdown 仍交由标准库解析。
private fun applyCjkBoldCompatHeuristics(block: String): String {
    if (!block.contains("**")) return block

    // 逐行处理，跳过标题/列表/引用/链接图片行
    val sb = StringBuilder(block.length + 16)
    val lines = block.split("\n")
    val common = Regex(
        "(?:(?<=^[\\u0000])|(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}“”‘’「」『』《》、，。；：！？…\\s]))" +
        "\\*\\*(.+?)\\*\\*" +
        "(?=(?:$)|(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}”’」』》、，。；：！？…\\s]))",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    val colonTail = Regex("\\*\\*(.+?)\\*\\*(?=[:：](\\s|$))")
 
    // 新增规则：修复“左侧残留 **”的场景（如 ——**SNI** / 开头 **HOSTS**）
    // 仅在内容内部包含至少一个 CJK 字符时启用，避免误伤纯英文粗体
    val emDashOrBracketLeft = Regex("([ \\t\\u2013\\u2014\\u2015\\-–—\\(\\[（【《「『])\\*\\*([^*]*?[\\p{IsHan}][^*]*?)\\*\\*")
    val lineStartLeft = Regex("^\\*\\*([^*]*?[\\p{IsHan}][^*]*?)\\*\\*")
 
    for (line in lines) {
        if (shouldSkipLine(line)) {
            sb.append(line)
        } else {
            var s = line
            // 先处理冒号收尾
            s = s.replace(colonTail) { m -> "<strong>${m.groupValues[1]}</strong>" }
            // 再处理 CJK 邻接
            s = s.replace(common) { m -> "<strong>${m.groupValues[1]}</strong>" }
            // 处理“破折号/括号/空白后紧跟 **词**（含CJK）” → 保留左界定符并整体替换为 <strong>
            s = s.replace(emDashOrBracketLeft) { m -> "${m.groupValues[1]}<strong>${m.groupValues[2]}</strong>" }
            // 处理“行首 **词**（含CJK）” → <strong>词</strong>
            s = s.replace(lineStartLeft) { m -> "<strong>${m.groupValues[1]}</strong>" }
            sb.append(s)
        }
        sb.append('\n')
    }
    if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
    return sb.toString()
}

// 对全文进行 fence 分段，仅对 fence 外的普通段落应用启发式
private fun cjkBoldCompatProcess(text: String): String {
    val segments = splitByCodeFence(text)
    if (segments.size == 1 && !segments[0].first) {
        return applyCjkBoldCompatHeuristics(segments[0].second)
    }
    val out = StringBuilder(text.length + 16)
    for ((inFence, seg) in segments) {
        if (inFence) {
            out.append("```")
            out.append(seg)
            out.append("```")
        } else {
            out.append(applyCjkBoldCompatHeuristics(seg))
        }
    }
    return out.toString()
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

    // 先做字符级清洗（零宽/全角/CRLF 等），再做 CJK 引号粗体规范化
    val preNormalized = remember(markdown) {
        normalizeCjkQuoteBold(sanitizeInvisibleAndWidthChars(markdown))
    }

    // CJK 粗体兼容（可开关 + 启发式 + 局部修复，仅非流式）
    val compatSource = remember(preNormalized, isStreaming) {
        if (!isStreaming && ENABLE_CJK_BOLD_COMPAT) {
            cjkBoldCompatProcess(preNormalized)
        } else {
            preNormalized
        }
    }

    // 判定文本是否“看起来已完结”，用于在仍处于流式时也切换到完整渲染
    fun looksFinalized(text: String): Boolean {
        // 代码围栏是否成对（``` 次数为偶数）
        val fenceCount = Regex("```").findAll(text).count()
        val fencesBalanced = fenceCount % 2 == 0
        // 表格是否具备 header 与分隔线（GFM）
        val hasTableHeader = Regex("(?m)^\\|.+\\|\\s*$").containsMatchIn(text)
        val hasTableSep = Regex("(?m)^\\|[ :\\-\\|]+\\|\\s*$").containsMatchIn(text)
        val tablesComplete = !hasTableHeader || hasTableSep
        return fencesBalanced && tablesComplete
    }
    // 非流式天然为最终；流式中若“看起来已完结”也视为最终
    val finalLike = (!isStreaming) || looksFinalized(preNormalized)

    // 仅当处于流式且“未完结”时才采用轻量渲染；一旦“看起来完结”，直接走完整渲染（无需切 isStreaming=false）
    val triggerLightweightInStream = isStreaming && !finalLike
    if (triggerLightweightInStream) {
        val annotated = remember(preNormalized, isDark, textColor) {
            LightweightInlineMarkdown.renderInlineAnnotated(
                markdown = preNormalized,
                baseStyleColor = textColor,
                isDark = isDark
            )
        }
        Text(
            text = annotated,
            style = style.copy(color = textColor),
            modifier = modifier
        )
        return
    }

    // 修复策略：
    // - 非流式：执行一次修复
    // - 流式但“看起来已完结”（finalLike=true）：也执行一次修复，确保最后一块与AI结束同步为最终渲染
    // - 其他情况或短文本：跳过修复以保证性能与稳定
    val fixedMarkdown = if (!finalLike || compatSource.length < MARKDOWN_FIX_MIN_LEN) {
        compatSource
    } else {
        remember(compatSource) {
            androidx.compose.runtime.derivedStateOf {
                try {
                    val fixed = MarkdownFormatFixer.fix(compatSource)
                    if (com.android.everytalk.BuildConfig.DEBUG && compatSource.length >= 80) {
                        android.util.Log.d(
                            "MarkdownRenderer",
                            "Fixed length: ${compatSource.length} -> ${fixed.length}"
                        )
                    }
                    fixed
                } catch (e: Throwable) {
                    if (com.android.everytalk.BuildConfig.DEBUG) {
                        android.util.Log.e("MarkdownRenderer", "Fix failed, fallback to raw", e)
                    }
                    compatSource
                }
            }
        }.value
    }

    // 行内代码配色（围栏代码块另由 CodeBlock 组件承担）
    // 要求：暗色背景纯黑，亮色纯白；字体颜色 #008ACF
    val inlineCodeBackground = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val inlineCodeTextColor = Color(0xFF008ACF)

    // 交由外部库渲染基础 Markdown
    dev.jeziellago.compose.markdowntext.MarkdownText(
        markdown = fixedMarkdown,
        style = style.copy(color = textColor),
        modifier = modifier,
        syntaxHighlightColor = inlineCodeBackground,
        syntaxHighlightTextColor = inlineCodeTextColor
    )
}
