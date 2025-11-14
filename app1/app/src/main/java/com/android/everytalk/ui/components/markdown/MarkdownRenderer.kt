package com.android.everytalk.ui.components.markdown

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText

/**
 * 纯文本 Markdown 渲染器（不含表格与代码块组件，职责单一）
 * - 外部库渲染基础 Markdown（标题、加粗、斜体、列表、链接、行内代码等）
 * - 非流式时执行一次轻量级格式修复
 * - 流式阶段跳过格式修复，保证实时性
 */
private const val MARKDOWN_FIX_MIN_LEN = 20
// 针对“列表可视化仍不生效”的兜底开关：在流式阶段也始终使用完整 Markdown 渲染
// 开启后将跳过轻量通道，直接走外部库（更稳但可能略降流式性能）
private const val FORCE_FULL_MARKDOWN_IN_STREAM = true

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

/**
 * 规范化引用与列表行的空格：
 * - 将 \">**文本\" → \"> **文本\"
 * - 将 \">>**文本\" → \">> **文本\"
 * - 将 \"-**文本\"、\"+**文本\"、\"***文本\" → \"- **文本\" 等
 * - 跳过 ``` 代码围栏
 */
private fun normalizeBlockQuoteAndListSpacing(input: String): String {
    val lines = input.split('\n')
    if (lines.isEmpty()) return input
    val sb = StringBuilder(input.length + 16)
    var inFence = false
    val fenceRe = Regex("^\\s*```")
    val quoteRe = Regex("^(\\s*>+)(\\S)(.*)$")
    val listRe  = Regex("^(\\s*)([-+*])(\\S)(.*)$")
    for ((i, line) in lines.withIndex()) {
        val trimmedStart = line.trimStart()
        val outLine = if (fenceRe.containsMatchIn(trimmedStart)) {
            inFence = !inFence
            line
        } else if (!inFence) {
            // 处理引用：> 后必须有空格
            val mq = quoteRe.matchEntire(line)
            if (mq != null) {
                val prefix = mq.groupValues[1]
                val first  = mq.groupValues[2]
                val rest   = mq.groupValues[3]
                // 若 '>' 后直接是非空白则补一个空格
                "$prefix $first$rest"
            } else {
                // 处理列表：-, +, * 后必须有空格
                val ml = listRe.matchEntire(line)
                if (ml != null) {
                    val indent = ml.groupValues[1]
                    val marker = ml.groupValues[2]
                    val first  = ml.groupValues[3]
                    val rest   = ml.groupValues[4]
                    "$indent$marker $first$rest"
                } else {
                    line
                }
            }
        } else {
            line
        }
        if (i > 0) sb.append('\n')
        sb.append(outLine)
    }
    return sb.toString()
}

/**
 * 调试：在 Debug 构建下记录每个规范化阶段是否改变了文本，以及长度差异
 */
private fun debugLogTransform(stage: String, before: String, after: String) {
    if (com.android.everytalk.BuildConfig.DEBUG && before !== after && before != after) {
        android.util.Log.d(
            "MarkdownRenderer",
            "stage='$stage' changed length: ${before.length} -> ${after.length}"
        )
    }
}

/**
 * 规范化粗体标记内的多余空格，修复 "** 文本 **" 未被识别为粗体的问题
 * - 仅处理 fence 外文本；不进入 ``` 代码块中
 * - 三步修正（从最具体到最宽松）：
 *   1) "** 词 **" -> "**词**"
 *   2) "** 词**"  -> "**词**"
 *   3) "**词 **"  -> "**词**"
 * - 谨慎匹配：避免跨越到下一个 '**'，不处理空内容
 */
private fun normalizeBoldInnerSpaces(input: String): String {
    // 快速跳过
    if (!input.contains("**")) return input

    fun fixOneSegment(seg: String): String {
        var s = seg
        // 仅删除粗体**边界**的空格，不删除内部空格/换行
        // 使用 (?!\*) 负向前瞻确保不匹配到内部的空格
        // 1) 开头有空格："** 词" -> "**词"
        val leftPattern = Regex("\\*\\*\\s+(?=\\S)")
        s = s.replace(leftPattern, "**")
        // 2) 结尾有空格："词 **" -> "词**"
        val rightPattern = Regex("(?<=\\S)\\s+\\*\\*")
        s = s.replace(rightPattern, "**")
        return s
    }

    // 按 ``` fence 切分，仅处理 fence 外
    val segments = splitByCodeFence(input)
    if (segments.size == 1 && !segments[0].first) {
        return fixOneSegment(segments[0].second)
    }
    val out = StringBuilder(input.length)
    for ((inFence, seg) in segments) {
        if (inFence) {
            out.append("```").append(seg).append("```")
        } else {
            out.append(fixOneSegment(seg))
        }
    }
    return out.toString()
}

/**
 * 合并粗体内部的软换行：处理 "** 文本\n更多 **" 这类跨行粗体，转为单行 "**文本 更多**"
 * - 仅对 fence 外文本生效
 * - 不改变普通换行，仅替换粗体对内部的换行为单空格并去掉首尾多余空白
 */
private fun normalizeSoftBreaksInsideBold(input: String): String {
    if (!input.contains("**")) return input
    fun compact(seg: String): String {
        val pattern = Regex("\\*\\*((?:.|\\n)+?)\\*\\*", setOf(RegexOption.DOT_MATCHES_ALL))
        return seg.replace(pattern) { m ->
            val inner = m.groupValues[1]
                .replace(Regex("[ \\t]*\\n[ \\t]*"), " ")
                .trim()
            "**$inner**"
        }
    }
    val segments = splitByCodeFence(input)
    if (segments.size == 1 && !segments[0].first) return compact(segments[0].second)
    val out = StringBuilder(input.length + 16)
    for ((inFence, seg) in segments) {
        if (inFence) out.append("```").append(seg).append("```") else out.append(compact(seg))
    }
    return out.toString()
}

/**
 * 折叠粗体边界处的所有空白（含换行）：
 * 处理 "**  内容\n  **"、"**\n内容  **" 等，归一为 "**内容**"
 * - 仅对 ``` 围栏外生效
 * - 不改变粗体内部普通换行（该逻辑已在 normalizeSoftBreaksInsideBold 里处理）
 */
private fun normalizeBoldBoundaryWhitespace(input: String): String {
    if (!input.contains("**")) return input

    fun collapse(seg: String): String {
        // 在开头 ** 之后与结尾 ** 之前允许存在任意空白（含换行），统一折叠去除
        val pattern = Regex("\\*\\*\\s*((?:.|\\n)*?\\S)\\s*\\*\\*", setOf(RegexOption.DOT_MATCHES_ALL))
        return seg.replace(pattern) { m -> "**${m.groupValues[1]}**" }
    }

    val segments = splitByCodeFence(input)
    if (segments.size == 1 && !segments[0].first) return collapse(segments[0].second)

    val out = StringBuilder(input.length + 16)
    for ((inFence, seg) in segments) {
        if (inFence) out.append("```").append(seg).append("```") else out.append(collapse(seg))
    }
    return out.toString()
}

/**
 * 引用行首“**词**：”专用修复：
 * - 目标：把 `> **简单比喻**：` 规范为 `> <strong>简单比喻</strong>：`
 * - 仅处理代码围栏外的行；不影响其它行/结构
 */
private fun fixBlockquoteLeadingBoldWithColon(input: String): String {
    if (!input.contains("**")) return input

    fun fixOneBlock(block: String): String {
        val lines = block.split('\n')
        val out = StringBuilder(block.length + 16)
        // 仅匹配 > 之后紧跟的 "** 词 **"（允许两侧空白）且后面跟冒号
        val tailPattern = Regex("^\\s*\\*\\*\\s*(.+?)\\s*\\*\\*(?=[:：])")

        for ((i, raw) in lines.withIndex()) {
            val gt = raw.indexOf('>')
            if (gt >= 0) {
                val prefix = raw.substring(0, gt + 1) // 包含 '>'
                val tail = raw.substring(gt + 1)
                val m = tailPattern.find(tail.trimStart())
                if (m != null) {
                    val tailTrimmed = tail.trimStart()
                    val start = m.range.first
                    val end = m.range.last + 1
                    val inner = m.groupValues[1]
                    val replacedHead = "**${inner}**"
                    val newTail =
                        tailTrimmed.substring(0, start) +
                        replacedHead +
                        tailTrimmed.substring(end)
                    val finalLine = prefix + " " + newTail
                    if (i > 0) out.append('\n')
                    out.append(finalLine)
                    continue
                }
            }
            if (i > 0) out.append('\n')
            out.append(raw)
        }
        return out.toString()
    }

    val segments = splitByCodeFence(input)
    if (segments.size == 1 && !segments[0].first) {
        val res = fixOneBlock(segments[0].second)
        if (com.android.everytalk.BuildConfig.DEBUG && res != segments[0].second) {
            android.util.Log.d("MarkdownRenderer", "fixBlockquoteLeadingBoldWithColon: changed")
        }
        return res
    }
    val out = StringBuilder(input.length + 16)
    for ((inFence, seg) in segments) {
        if (inFence) out.append("```").append(seg).append("```") else out.append(fixOneBlock(seg))
    }
    return out.toString()
}

/**
 * 行首单星号安全处理（仅最终渲染态使用的预处理）
 * 将形如 "*结论" 这种“单星号紧跟非空白且该行无配对星号”的场景，转换为 "* 结论"
 * 目的：用户常见书写错误不会被误当作斜体/粗体，同时让其按列表项渲染，避免裸露的星号。
 * 规则：
 * - 仅处理行首的单个 '*'，且后面紧接 CJK/字母/数字（非空白）
 * - 若该行包含第二个 '*'（可能是配对），则不处理，避免误修复
 * - 代码围栏中的内容不处理
 */
private fun fixLeadingSingleAsterisk(input: String): String {
    // 同时兼容全角星号 '＊'
    if (!input.contains('*') && !input.contains('＊')) return input
    val lines = input.lines()
    val out = StringBuilder(input.length + 32)
    var inFence = false
    var prevWasBlank = true
    for ((i, rawLine) in lines.withIndex()) {
        var line = rawLine
        val trimmedStart = line.trimStart()
        // 代码围栏开关
        if (trimmedStart.startsWith("```")) {
            inFence = !inFence
            if (i > 0) out.append('\n')
            out.append(line)
            prevWasBlank = false
            continue
        }
        if (!inFence) {
            // 计算行首空白
            val wsCount = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            val afterLeading = if (wsCount < line.length) line.substring(wsCount) else ""
            // 仅处理行首单星号后直接跟非空白字符的情况（'*结论' / '＊结论'）
            val startsWithStar = afterLeading.startsWith("*") || afterLeading.startsWith("＊")
            val nextChar = afterLeading.drop(1).firstOrNull()
            val nextIsNonSpace = nextChar != null && !nextChar.isWhitespace()
            if (startsWithStar && nextIsNonSpace) {
                // 若该行存在可能的配对星号（'*...*' / '＊...＊'），则不修改，避免误伤强调语法
                val rest = afterLeading.drop(1)
                val hasClosingAscii = rest.contains('*')
                val hasClosingFull = rest.contains('＊')
                val hasClosing = hasClosingAscii || hasClosingFull
                if (!hasClosing) {
                    // 一律在转换前补一个空行，提升不同 Markdown 实现的兼容性
                    if (i > 0) {
                        if (!prevWasBlank) out.append('\n')
                        out.append('\n')
                    }
                    val prefix = if (wsCount > 0) line.substring(0, wsCount) else ""
                    val body = afterLeading.drop(1)
                    val converted = "$prefix* $body"
                    // 调试日志（仅 Debug 生效）：帮助确认是否触发了转换
                    if (com.android.everytalk.BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "MarkdownRenderer",
                            "fixLeadingSingleAsterisk: converted='$converted'"
                        )
                    }
                    line = converted
                    out.append(line)
                    prevWasBlank = line.isBlank()
                    continue
                }
            }
        }
        if (i > 0) out.append('\n')
        out.append(line)
        prevWasBlank = line.isBlank()
    }
    return out.toString()
}
// 将整行“强调语句”提升为三级标题（仅当整行为 *词* / ＊词＊）
// 明确排除正常 Markdown 列表（^\s*[*＊]\s+）与不闭合的 "*词" 形式，避免把项目符号误升为标题。
private fun promoteStandaloneEmphasisToHeading(input: String): String {
    if (input.isBlank()) return input
    val lines = input.lines()
    val out = StringBuilder(input.length + 16)
    var inFence = false
    for ((i, raw) in lines.withIndex()) {
        val trimmedStart = raw.trimStart()
        if (trimmedStart.startsWith("```")) {
            inFence = !inFence
            if (i > 0) out.append('\n')
            out.append(raw)
            continue
        }
        if (!inFence) {
            val t = raw.trim()
            // 直接排除真正的列表行：* 空格... / ＊ 空格...
            if (t.startsWith("* ") || t.startsWith("＊ ")) {
                if (i > 0) out.append('\n')
                out.append(raw)
                continue
            }
            // 仅当整行完全被成对星号包裹时才提升为标题
            val mEmAscii = Regex("^\\*([^*]+)\\*\$").matchEntire(t)
            val mEmFull  = Regex("^＊([^＊]+)＊\$").matchEntire(t)
            val heading = when {
                mEmAscii != null -> mEmAscii.groupValues[1]
                mEmFull  != null -> mEmFull.groupValues[1]
                else -> null
            }
            if (heading != null) {
                if (i > 0) out.append('\n')
                out.append("### ").append(heading.trim())
                continue
            }
        }
        if (i > 0) out.append('\n')
        out.append(raw)
    }
    return out.toString()
}

// 将“孤立的列表标记行”与下一行合并，修复
// 形如：
//   *
//   **下载并安装应用**
// 组合为：
//   * **下载并安装应用**
// 同时兼容 "-", "+" 以及前导缩进；跳过 ``` 围栏与表格行（含两个以上管道符）。
private fun fixOrphanListMarkers(input: String): String {
    val lines = input.split('\n')
    if (lines.isEmpty()) return input
    val sb = StringBuilder(input.length + 16)
    var inFence = false
    val fenceRe = Regex("^\\s*```")
    val markerOnlyRe = Regex("^\\s*([-*+])\\s*$")
    val tableLike: (String) -> Boolean = { it.count { ch -> ch == '|' } >= 2 }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        if (fenceRe.containsMatchIn(trimmed)) {
            inFence = !inFence
            sb.append(line)
            i++
            if (i < lines.size) sb.append('\n')
            continue
        }
        if (inFence) {
            sb.append(line)
            i++
            if (i < lines.size) sb.append('\n')
            continue
        }

        if (tableLike(line)) {
            sb.append(line)
            i++
            if (i < lines.size) sb.append('\n')
            continue
        }

        if (markerOnlyRe.matches(line) && i + 1 < lines.size) {
            val next = lines[i + 1]
            // 下一行不是继续的标记/不是空白/不是表格/不是围栏
            if (next.isNotBlank() && !markerOnlyRe.matches(next) && !tableLike(next) && !fenceRe.containsMatchIn(next.trimStart())) {
                val indent = line.takeWhile { it.isWhitespace() }
                sb.append(indent).append("• ").append(next.trimStart())
                i += 2
                if (i < lines.size) sb.append('\n')
                continue
            }
        }

        sb.append(line)
        i++
        if (i < lines.size) sb.append('\n')
    }
    return sb.toString()
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
        "(?:(?<=^[\\u0000])|(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}“”‘’「」『』《》（）、，。；：！？…\\s]))" +
        "\\*\\*(.+?)\\*\\*" +
        "(?=(?:$)|(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}”’」』》） 、，。；：！？…\\s]))",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    val colonTail = Regex("\\*\\*(.+?)\\*\\*(?=[:：](\\s|$))")
 
    // 新增规则：修复“左侧残留 **”的场景（如 ——**SNI** / 开头 **HOSTS**）
    // 仅在内容内部包含至少一个 CJK 字符时启用，避免误伤纯英文粗体
    val emDashOrBracketLeft = Regex("([ \\t\\u2013\\u2014\\u2015\\-–—\\(\\[（【《「『])\\*\\*([^*]*?[\\p{IsHan}][^*]*?)\\*\\*")
    val lineStartLeft = Regex("^\\*\\*([^*]*?[\\p{IsHan}][^*]*?)\\*\\*")
 
    for (line in lines) {
        // 对标题/列表等仍然跳过，但“引用行 > ”允许做 CJK/冒号修正
        val isBlockQuote = line.trimStart().startsWith(">")
        val shouldSkip = shouldSkipLine(line) && !isBlockQuote
        if (shouldSkip) {
            sb.append(line)
        } else {
            var s = line
            // 先处理冒号收尾（支持“：”） → 保持 Markdown 语法
            s = s.replace(colonTail) { m -> "**${m.groupValues[1]}**" }
            // 再处理 CJK 邻接（含全角括号等） → 保持 Markdown 语法
            s = s.replace(common) { m -> "**${m.groupValues[1]}**" }
            // 处理“破折号/括号/空白后紧跟 **词**（含CJK）” → 左界定符 + **词**
            s = s.replace(emDashOrBracketLeft) { m -> "${m.groupValues[1]}**${m.groupValues[2]}**" }
            // 处理“行首 **词**（含CJK）” → **词**
            s = s.replace(lineStartLeft) { m -> "**${m.groupValues[1]}**" }
            sb.append(s)
        }
        sb.append('\n')
    }
    if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
    return sb.toString()
}

// 对全文进行 fence 分段，仅对 fence 外的普通段落应用启发式
private fun cjkBoldCompatProcess(text: String): String {
    if (com.android.everytalk.BuildConfig.DEBUG) {
        android.util.Log.d("MarkdownRenderer", "cjkBoldCompatProcess: input.length=${text.length}, contains(**=${text.contains("**")})")
    }
    val segments = splitByCodeFence(text)
    if (segments.size == 1 && !segments[0].first) {
        val result = applyCjkBoldCompatHeuristics(segments[0].second)
        if (com.android.everytalk.BuildConfig.DEBUG && result != segments[0].second) {
            android.util.Log.d("MarkdownRenderer", "cjkBoldCompatProcess: single segment changed, before=${segments[0].second.length}, after=${result.length}")
        }
        return result
    }
    val out = StringBuilder(text.length + 16)
    for ((inFence, seg) in segments) {
        if (inFence) {
            out.append("```")
            out.append(seg)
            out.append("```")
        } else {
            val processed = applyCjkBoldCompatHeuristics(seg)
            if (com.android.everytalk.BuildConfig.DEBUG && processed != seg) {
                android.util.Log.d("MarkdownRenderer", "cjkBoldCompatProcess: segment changed, before=${seg.length}, after=${processed.length}")
            }
            out.append(processed)
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

    // 先做字符级清洗（零宽/全角/CRLF 等），再做 引用/列表补空格 → 粗体跨行软换行合并 → CJK 引号粗体规范化
    // → 粗体内空格修复 → 标题提升 → 行首单星号兜底 → 孤立标记合并
    val preNormalized = remember(markdown) {
        // 1) 字符级清洗
        val basic = sanitizeInvisibleAndWidthChars(markdown)
        debugLogTransform("sanitizeInvisibleAndWidthChars", markdown, basic)

        // 2) 引用/列表补空格
        val quoteListSpaced = normalizeBlockQuoteAndListSpacing(basic)
        debugLogTransform("normalizeBlockQuoteAndListSpacing", basic, quoteListSpaced)

        // 3) 合并粗体内部软换行（关键：跨行 **...**）
        val boldSoftMerged = normalizeSoftBreaksInsideBold(quoteListSpaced)
        debugLogTransform("normalizeSoftBreaksInsideBold", quoteListSpaced, boldSoftMerged)

        // 4) 折叠粗体边界空白（含换行），保障 "** 文本 （换行）**" 可解析
        val boldBoundaryCollapsed = normalizeBoldBoundaryWhitespace(boldSoftMerged)
        debugLogTransform("normalizeBoldBoundaryWhitespace", boldSoftMerged, boldBoundaryCollapsed)

        // 5) 引用行首 **词**： → <strong>词</strong>： 专用修复（只在 fence 外）
        val quoteBoldColon = fixBlockquoteLeadingBoldWithColon(boldBoundaryCollapsed)
        debugLogTransform("fixBlockquoteLeadingBoldWithColon", boldBoundaryCollapsed, quoteBoldColon)

        // 6) CJK 引号粗体规范化（**“词”** -> “**词**”）
        val cjk = normalizeCjkQuoteBold(quoteBoldColon)
        debugLogTransform("normalizeCjkQuoteBold", quoteBoldColon, cjk)

        // 7) 修复粗体内首尾空格（"** 词 **" 等）
        val boldTrimmed = normalizeBoldInnerSpaces(cjk)
        debugLogTransform("normalizeBoldInnerSpaces", cjk, boldTrimmed)

        // 6) 将整行强调提升为标题
        val promoted = promoteStandaloneEmphasisToHeading(boldTrimmed)
        debugLogTransform("promoteStandaloneEmphasisToHeading", boldTrimmed, promoted)

        // 7) 行首孤立星号兜底
        val fixedAsterisk = fixLeadingSingleAsterisk(promoted)
        debugLogTransform("fixLeadingSingleAsterisk", promoted, fixedAsterisk)

        // 8) 孤立标记行与下一行合并
        val finalText = fixOrphanListMarkers(fixedAsterisk)
        debugLogTransform("fixOrphanListMarkers", fixedAsterisk, finalText)

        finalText
    }

    // CJK 粗体兼容（可开关 + 启发式 + 局部修复）
    // 修改：流式和非流式均启用，确保引用行和括号场景的粗体正常转换
    val compatSource = remember(preNormalized, isStreaming) {
        if (ENABLE_CJK_BOLD_COMPAT) {
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
    val triggerLightweightInStream = false && isStreaming && !finalLike
    if (triggerLightweightInStream) {

        // 针对流式轻量通道：在进入轻量渲染器前做一次“行首列表可视化”预处理
        // 规则与 LightweightInlineMarkdown.preTransformListVisual 一致，且包含“*无空格容错”
        fun preTransformListVisualForStreaming(text: String): String {
            val lines = text.split('\n')
            val sb = StringBuilder(text.length + 16)
            var inFence = false
            val fenceRe = Regex("^\\s*```")
            val ulRe = Regex("^(\\s*)([-*+])\\s+(\\S.*)$")
            val ulNoSpaceRe = Regex("^(\\s*)\\*(\\S.*)$")
            val olRe = Regex("^(\\s*)(\\d{1,3})\\.\\s+(\\S.*)$")

            for ((idx, line) in lines.withIndex()) {
                val trimmedStart = line.trimStart()
                if (fenceRe.containsMatchIn(trimmedStart)) {
                    inFence = !inFence
                    sb.append(line)
                } else if (!inFence) {
                    if (line.count { it == '|' } >= 2) {
                        sb.append(line)
                    } else {
                        var handled = false
                        val m1 = ulRe.matchEntire(line)
                        if (m1 != null) {
                            val indent = m1.groupValues[1]
                            val rest = m1.groupValues[3]
                            sb.append(indent).append("• ").append(rest)
                            handled = true
                        } else {
                            val m0 = ulNoSpaceRe.matchEntire(line)
                            if (m0 != null) {
                                val indent = m0.groupValues[1]
                                val rest = m0.groupValues[2]
                                if (!rest.contains('*')) {
                                    sb.append(indent).append("• ").append(rest)
                                    handled = true
                                }
                            }
                            if (!handled) {
                                val m2 = olRe.matchEntire(line)
                                if (m2 != null) {
                                    val indent = m2.groupValues[1]
                                    val num = m2.groupValues[2]
                                    val rest = m2.groupValues[3]
                                    sb.append(indent).append(num).append(". ").append(rest)
                                    handled = true
                                }
                            }
                        }
                        if (!handled) sb.append(line)
                    }
                } else {
                    sb.append(line)
                }
                if (idx < lines.size - 1) sb.append('\n')
            }
            return sb.toString()
        }

        val preForList = remember(preNormalized) { preTransformListVisualForStreaming(preNormalized) }

        val annotated = remember(preForList, isDark, textColor) {
            LightweightInlineMarkdown.renderInlineAnnotated(
                markdown = preForList,
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
    // 背景透明（不可见），文字颜色保持 #008ACF
    // 将内联代码背景设为真正透明，避免任何底色
    val inlineCodeBackground = Color.Transparent
    // 将行内代码文字颜色改为 #ABABAB
    val inlineCodeTextColor = Color(0xFFABABAB)


    // 使用 Compose Rich Editor 渲染 Markdown
    val richTextState = rememberRichTextState()
    
    // 配置样式
    LaunchedEffect(Unit) {
        richTextState.config.apply {
            linkColor = Color(0xFF2196F3)
            codeSpanColor = inlineCodeTextColor
            codeSpanBackgroundColor = inlineCodeBackground
        }
    }
    
    // 设置 Markdown 内容
    LaunchedEffect(fixedMarkdown) {
        richTextState.setMarkdown(fixedMarkdown)
    }
    
    // 渲染富文本
    RichText(
        state = richTextState,
        style = style.copy(color = textColor),
        modifier = modifier
    )
}
