package com.example.everytalk.ui.components

// Math-related normalization removed. Basic AI output cleanup retained.

/**
 * Minimal AI output sanitizer:
 * - Remove dangling backslashes at line ends (outside code fences)
 * - Deduplicate consecutive identical lines/paragraphs
 * - Deduplicate adjacent structural lines (headings/lists) with same text
 * - Repair fenced code blocks (split inline code after ```lang and auto-close missing fences)
 */
fun sanitizeAiOutput(text: String): String {
    if (text.isEmpty()) return text
    val noBackslashes = normalizeDanglingBackslashes(text)
    val deduped = dedupeConsecutiveContent(noBackslashes)
    val structuralCleaned = dedupeStructuralLines(deduped)
    return repairFencedCodeBlocks(structuralCleaned)
}

/**
 * Remove inline code backticks (not affecting fenced code blocks):
 * - Replace `xxx` with xxx outside of ``` fences
 * - Multiple occurrences per line supported
 */
fun removeInlineCodeBackticks(md: String): String {
    if (md.isEmpty()) return md
    val lines = md.split("\n")
    val out = StringBuilder()
    var fence = false
    val inlineCode = Regex("`([^`]+)`")
    lines.forEachIndexed { idx, raw ->
        var s = raw
        if (s.contains("```")) {
            val c = "```".toRegex().findAll(s).count()
            if (!fence) {
                // clean inline code before entering fence (same line may contain normal text)
                s = s.replace(inlineCode) { mr -> mr.groupValues[1] }
            }
            fence = (c % 2 == 1) xor fence
            out.append(s)
        } else {
            if (!fence) {
                s = s.replace(inlineCode) { mr -> mr.groupValues[1] }
            }
            out.append(s)
        }
        if (idx != lines.lastIndex) out.append('\n')
    }
    return out.toString()
}

/**
 * Remove solitary trailing '\' at end of non-fenced lines.
 */
private fun normalizeDanglingBackslashes(md: String): String {
    if (md.isEmpty()) return md
    val lines = md.split("\n")
    val out = StringBuilder()
    var fence = false
    lines.forEachIndexed { idx, raw ->
        var s = raw
        if (s.contains("```")) {
            val c = "```".toRegex().findAll(s).count()
            fence = (c % 2 == 1) xor fence
        }
        if (!fence) {
            // Remove trailing spaces + single backslash at EOL
            s = s.replace(Regex("""\s*\\\s*$"""), "")
        }
        out.append(s)
        if (idx != lines.lastIndex) out.append('\n')
    }
    return out.toString()
}

/**
 * Deduplicate consecutive identical non-fenced lines/paragraphs.
 */
fun dedupeConsecutiveContent(text: String): String {
    if (text.isEmpty()) return text
    val lines = text.split("\n")
    val out = StringBuilder()
    var fence = false
    var lastNonFenceLine: String? = null
    lines.forEachIndexed { idx, raw ->
        var s = raw
        if (s.contains("```")) {
            val c = "```".toRegex().findAll(s).count()
            fence = (c % 2 == 1) xor fence
            // write fence line as-is and reset last-line memory
            lastNonFenceLine = null
            out.append(s)
            if (idx != lines.lastIndex) out.append('\n')
            return@forEachIndexed
        }
        if (!fence) {
            val trimmed = s.trimEnd()
            val isEmpty = trimmed.isEmpty()
            if (!isEmpty) {
                if (lastNonFenceLine != null && lastNonFenceLine == s) {
                    // skip duplicate line
                } else {
                    out.append(s)
                    if (idx != lines.lastIndex) out.append('\n')
                }
                lastNonFenceLine = s
            } else {
                // empty line resets comparison baseline
                out.append(s)
                if (idx != lines.lastIndex) out.append('\n')
                lastNonFenceLine = null
            }
        } else {
            // inside fence: write as-is
            out.append(s)
            if (idx != lines.lastIndex) out.append('\n')
        }
    }
    return out.toString()
}

/**
 * Deduplicate adjacent structural lines (headings/lists) whose normalized text matches.
 * - Ignores differences in punctuation and extra whitespace
 * - Skips code fences
 */
private fun dedupeStructuralLines(md: String): String {
    if (md.isEmpty()) return md
    val lines = md.split("\n")
    val out = StringBuilder()
    var fence = false
    var lastStructNorm: String? = null

    fun normalizeForCompare(s: String): String {
        var t = s.trim()
        t = t.replace(Regex("^(#{1,6}\\s+)"), "")                 // remove heading prefix
            .replace(Regex("^([*+\\-]\\s+)"), "")               // remove unordered list prefix
            .replace(Regex("^(\\d+\\s*[.)]\\s+)"), "")           // remove ordered list prefix
            .replace(Regex("\\s+"), " ")                        // collapse spaces
            .replace('：', ':')                                  // unify punctuation
            .replace('—', '-')                                   // unify dash
        return t
    }

    lines.forEachIndexed { idx, raw ->
        var s = raw
        if (s.contains("```")) {
            val c = "```".toRegex().findAll(s).count()
            fence = (c % 2 == 1) xor fence
            // entering/leaving fence resets structural memory
            lastStructNorm = null
            out.append(s)
        } else if (!fence) {
            val isHeading = s.trimStart().startsWith("#")
            val isList = Regex("^\\s*([*+\\-]|\\d+[.)])\\s+").containsMatchIn(s)
            if (isHeading || isList) {
                val cur = normalizeForCompare(s)
                if (lastStructNorm != null && lastStructNorm!!.equals(cur, ignoreCase = true)) {
                    // skip duplicate
                } else {
                    out.append(s)
                    lastStructNorm = cur
                }
            } else {
                out.append(s)
                lastStructNorm = null
            }
        } else {
            out.append(s)
        }
        if (idx != lines.lastIndex) out.append('\n')
    }
    return out.toString()
}

/**
 * Repair fenced code blocks:
 * - Split inline code after ```lang to the next line
 * - Auto-close unbalanced fences at EOF
 */
fun repairFencedCodeBlocks(md: String): String {
    if (md.isEmpty()) return md
    val lines = md.split("\n").toMutableList()
    var i = 0
    var inFence = false

    // Regex: fence open with optional language and inline code remainder
    val fenceOpen = Regex("""^\s*```([A-Za-z0-9_+\-#.]*)\s*(.*)$""")
    val fenceLine = Regex("""^\s*```+\s*$""")

    while (i < lines.size) {
        val line = lines[i]
        if (!inFence) {
            val m = fenceOpen.find(line)
            if (m != null) {
                inFence = true
                val lang = m.groupValues[1]
                val rest = m.groupValues[2]
                // move inline code remainder to next line
                if (rest.isNotBlank()) {
                    lines[i] = if (lang.isNotBlank()) "```$lang" else "```"
                    lines.add(i + 1, rest.trimStart())
                    i += 1
                }
            }
        } else {
            // close fence
            if (fenceLine.containsMatchIn(line)) {
                inFence = false
            }
        }
        i += 1
    }
    if (inFence) {
        lines.add("```")
    }
    return lines.joinToString("\n")
}


/**
 * 基础 Markdown 规范化（不含数学处理）：
 * - 复用 sanitizeAiOutput 的清理与修复管线
 */
fun normalizeBasicMarkdown(text: String): String {
    return sanitizeAiOutput(text)
}

/**
 * 统一 Markdown 字形与不可见字符的规范化（不涉数学）：
 * - 清理零宽字符与双向控制字符
 * - 归一各类“看起来是空格”的特殊空白为普通空格
 * - 制表符折算为2空格，避免误判为代码块
 * - 全角/小型星号统一为半角星号，提升 **bold** / *italic* 识别率
 */
fun normalizeMarkdownGlyphs(text: String): String {
    if (text.isEmpty()) return text
    return text
        // 去除常见不可见字符，避免打断 **bold** / *italic*
        .replace("\u200B", "") // ZERO WIDTH SPACE
        .replace("\u200C", "") // ZERO WIDTH NON-JOINER
        .replace("\u200D", "") // ZERO WIDTH JOINER
        .replace("\uFEFF", "") // ZERO WIDTH NO-BREAK SPACE (BOM)
        // 额外：移除 Unicode 双向控制字符，防止段落被强制成 RTL
        .replace("\u200E", "") // LRM
        .replace("\u200F", "") // RLM
        .replace("\u202A", "") // LRE
        .replace("\u202B", "") // RLE
        .replace("\u202D", "") // LRO
        .replace("\u202E", "") // RLO
        .replace("\u202C", "") // PDF
        .replace("\u2066", "") // LRI
        .replace("\u2067", "") // RLI
        .replace("\u2068", "") // FSI
        .replace("\u2069", "") // PDI
        // 归一化各类“看起来是空格但不是空格”的字符：防止列表识别失败或被误判为代码块
        .replace('\u00A0', ' ') // NO-BREAK SPACE
        .replace('\u1680', ' ')
        .replace('\u180E', ' ')
        .replace('\u2000', ' ') // EN QUAD
        .replace('\u2001', ' ') // EM QUAD
        .replace('\u2002', ' ') // EN SPACE
        .replace('\u2003', ' ') // EM SPACE
        .replace('\u2004', ' ')
        .replace('\u2005', ' ')
        .replace('\u2006', ' ')
        .replace('\u2007', ' ')
        .replace('\u2008', ' ')
        .replace('\u2009', ' ')
        .replace('\u200A', ' ')
        .replace('\u202F', ' ')
        .replace('\u205F', ' ')
        .replace('\u3000', ' ') // IDEOGRAPHIC SPACE（全角空格）
        .replace("\t", "  ")   // 制表符折算为2空格
        // 统一星号
        .replace('＊', '*')  // 全角星号 -> 半角
        .replace('﹡', '*')  // 小型星号 -> 半角
}
