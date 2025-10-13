package com.example.everytalk.ui.components

// Math-related normalization removed. Basic AI output cleanup retained.

// ===== Performance-safe helpers & cache (avoid frequent Regex allocations on mobile) =====

// Precompiled, reused across calls (ICU Regex allocations are expensive on Android)
private val INLINE_CODE_REGEX = Regex("`([^`]+)`")

// Fast counter for triple backticks occurrences on a single line (no Regex)
private fun countTripleBackticks(s: String): Int {
    if (s.isEmpty()) return 0
    var i = 0
    var c = 0
    while (i < s.length) {
        val idx = s.indexOf("```", i)
        if (idx == -1) break
        c += 1
        i = idx + 3
    }
    return c
}

// Tiny LRU cache for sanitized markdown (keyed by content hash), reduces repeated processing
private object MdSanCache {
    private const val MAX = 64
    private val map = object : java.util.LinkedHashMap<Int, String>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean = size > MAX
    }
    fun getOrPut(text: String, f: () -> String): String {
        val key = text.hashCode()
        return map[key] ?: f().also { map[key] = it }
    }
}

/**
 * Minimal AI output sanitizer:
 * - Remove dangling backslashes at line ends (outside code fences)
 * - Deduplicate consecutive identical lines/paragraphs
 * - Deduplicate adjacent structural lines (headings/lists) with same text
 * - Repair fenced code blocks (split inline code after ```lang and auto-close missing fences)
 */
fun sanitizeAiOutput(text: String): String {
    if (text.isEmpty()) return text
    
    // 防御：对于超大文本（>500KB），跳过缓存和复杂处理，直接返回基础清理后的内容
    if (text.length > 500_000) {
        return normalizeMarkdownGlyphs(text)
    }
    
    return MdSanCache.getOrPut(text) {
        // 先做字形/不可见字符清理，确保标题/列表起始符能被正确识别（去除零宽字符、全角空格等）
        val glyphs = normalizeMarkdownGlyphs(text)

        // EOL 反斜杠清理与内容/结构去重
        val noBackslashes = normalizeDanglingBackslashes(glyphs)
        val deduped = dedupeConsecutiveContent(noBackslashes)
        val structuralCleaned = dedupeStructuralLines(deduped)

        // 统一标题与列表的缩进，并将"•/●"等项目符号规范为 Markdown "- "
        // 仅在非代码围栏中生效，避免破坏代码块
        fun normalizeHeadingsAndLists(md: String): String {
            if (md.isEmpty()) return md
            val lines = md.split("\n")
            val out = StringBuilder()
            var fence = false
            var inTable = false
            val fullwidthHash = '＃' // U+FF03
            
            // 检测是否为 Markdown 表格行
            fun isTableRow(line: String): Boolean {
                val trimmed = line.trim()
                // 表格行包含 | 分隔符
                if (!trimmed.contains("|")) return false
                // 排除单个 | 的情况（可能是普通文本）
                val pipeCount = trimmed.count { it == '|' }
                return pipeCount >= 2
            }
            
            // 检测是否为表格分隔行（如 |---|---|）
            fun isTableSeparator(line: String): Boolean {
                val trimmed = line.trim()
                if (!trimmed.contains("|")) return false
                // 分隔行主要包含 |、-、: 和空格
                val validChars = trimmed.all { it in setOf('|', '-', ':', ' ') }
                return validChars && trimmed.count { it == '|' } >= 2
            }

            lines.forEachIndexed { idx, raw ->
                var s = raw
                // 简易围栏切换（计数三反引号，不用Regex）
                val c = countTripleBackticks(s)
                if (c > 0) fence = (c % 2 == 1) xor fence
                
                // 检测表格状态
                if (!fence) {
                    if (isTableRow(s) || isTableSeparator(s)) {
                        inTable = true
                    } else if (inTable && s.trim().isEmpty()) {
                        // 空行可能表示表格结束
                        inTable = false
                    } else if (inTable && !s.trim().startsWith("|") && !isTableRow(s)) {
                        // 不以 | 开头且不是表格行，表格结束
                        inTable = false
                    }
                }

                if (!fence && !inTable) {
                    // 1) 将全角井号统一为半角 '#'
                    if (s.indexOf(fullwidthHash) != -1) {
                        s = s.replace(fullwidthHash.toString(), "#")
                    }
                    // 2) 标题：去掉过深缩进（>=4），保证 atx 标题能被识别
                    var tmp = s
                    var leadSpaces = 0
                    while (leadSpaces < tmp.length && tmp[leadSpaces] == ' ') leadSpaces++
                    if (leadSpaces >= 4 && leadSpaces < tmp.length && tmp[leadSpaces] == '#') {
                        s = tmp.trimStart()
                        tmp = s
                    }
                    // 3) 标题：清除 # 前的不可见/控制字符，并把 "##标题" → "## 标题"
                    // 同时清理错误的行内 ## 标记（如 "### ## 标题" → "### 标题"）
                    // 注意：表格内的 # 不处理，因为可能是有意义的数据
                    run {
                        val cleaned = tmp.trimStart { ch ->
                            ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF' ||
                            ch == '\u200E' || ch == '\u200F' ||
                            (ch in '\u202A'..'\u202E') || (ch in '\u2066'..'\u2069')
                        }
                        
                        if (cleaned.startsWith("#")) {
                            // 找到行首的标题标记
                            var p = 0
                            while (p < cleaned.length && cleaned[p] == '#') p++
                            
                            if (p in 1..6) {
                                // 获取标题级别和剩余内容
                                val headingMarker = cleaned.substring(0, p)
                                var remaining = cleaned.substring(p).trimStart()
                                
                                // 清理剩余内容中开头的所有 # 标记（这些是错误的）
                                // 例如：### ## 标题 → ### 标题
                                while (remaining.startsWith("#")) {
                                    // 跳过这些错误的 #
                                    var q = 0
                                    while (q < remaining.length && remaining[q] == '#') q++
                                    remaining = if (q < remaining.length) remaining.substring(q).trimStart() else ""
                                }
                                
                                // 重新组合：标题标记 + 空格 + 清理后的内容
                                s = if (remaining.isNotEmpty()) {
                                    "$headingMarker $remaining"
                                } else {
                                    headingMarker
                                }
                            } else {
                                // 超过6个#，保持原样
                                s = cleaned
                            }
                        } else {
                            s = cleaned
                        }
                    }
                    // 3.1) 去掉右侧“收尾 #”序列（如 “## 标题##” → “## 标题”）
                    run {
                        val t = s.trimEnd()
                        var k = t.length - 1
                        while (k >= 0 && t[k].isWhitespace()) k--
                        var q = k
                        while (q >= 0 && t[q] == '#') q--
                        // 保留前部正文，裁掉尾部 # 序列（不要求空格规则）
                        if (q < k && t.substring(0, q + 1).contains("#")) {
                            s = t.substring(0, q + 1)
                        } else {
                            s = t
                        }
                    }
                    // 4) 列表：将 “•/● ” 规范为 “- ”
                    val trimmed = s.trimStart()
                    if (trimmed.startsWith("• ") || trimmed.startsWith("● ")) {
                        val idxStart = s.indexOf(trimmed)
                        s = s.substring(0, idxStart) + "- " + trimmed.substring(2)
                    }
                }
                out.append(s)
                if (idx != lines.lastIndex) out.append('\n')
            }
            return out.toString()
        }

        val normalized = normalizeHeadingsAndLists(structuralCleaned)

        // 修复代码围栏（拆分 "```lang inline"、补齐/早闭合）
        val fencedFixed = repairFencedCodeBlocks(normalized)

        // 保证文末换行，部分 Markdown 解析器在无终止换行时可能忽略末尾结构行
        if (fencedFixed.endsWith("\n")) fencedFixed else fencedFixed + "\n"
    }
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
    lines.forEachIndexed { idx, raw ->
        var s = raw
        val c = countTripleBackticks(s)
        if (c > 0) {
            if (!fence) {
                s = s.replace(INLINE_CODE_REGEX) { mr -> mr.groupValues[1] }
            }
            fence = (c % 2 == 1) xor fence
            out.append(s)
        } else {
            if (!fence) {
                s = s.replace(INLINE_CODE_REGEX) { mr -> mr.groupValues[1] }
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
            .replace(Regex("\\s+"), " ")                        // collapse only spaces, keep punctuation as-is
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
    // 目标：零正则实现，避免 ICU 正则在移动端频繁分配导致的内存/性能问题
    if (md.isEmpty()) return md
    
    // 防御：对于超大文本，限制处理以避免OOM
    if (md.length > 500_000) {
        // 超过500KB的文本直接返回，不做修复处理
        return md
    }

    // 使用ArrayList并预分配容量，减少扩容次数
    val originalLines = md.split("\n")
    val lines = ArrayList<String>(originalLines.size + 100) // 预留空间给可能插入的围栏
    lines.addAll(originalLines)
    
    var i = 0
    var inFence = false
    var fenceChar = '`'
    var fenceLen = 0

    // 统计指定字符在开头连续出现的次数
    fun countLeading(s: String, ch: Char): Int {
        var c = 0
        while (c < s.length && s[c] == ch) c++
        return c
    }

    // 判断是否为围栏开头（``` 或 ~~~），返回语言和余下内容
    fun parseFenceOpen(raw: String): Triple<Boolean, String, String> {
        val s = raw.trimStart()
        if (s.length < 3) return Triple(false, "", "")
        val ch = s[0]
        if (ch != '`' && ch != '~') return Triple(false, "", "")
        val cnt = countLeading(s, ch)
        if (cnt < 3) return Triple(false, "", "")
        var pos = cnt
        while (pos < s.length && s[pos].isWhitespace()) pos++
        val langStart = pos
        while (pos < s.length && !s[pos].isWhitespace()) pos++
        val lang = if (pos > langStart) s.substring(langStart, pos) else ""
        val rest = if (pos < s.length) s.substring(pos).trimStart() else ""
        fenceChar = ch
        fenceLen = cnt
        return Triple(true, lang, rest)
    }

    // 是否为围栏闭合
    fun isFenceClose(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return false
        val cnt = countLeading(t, fenceChar)
        return cnt >= fenceLen && (cnt == t.length || t.substring(cnt).isBlank())
    }

    // 常见结构行（标题/列表/“Commands:”/分隔线），遇到这些需要提前闭合未闭合的围栏
    fun isStructureBoundary(raw: String): Boolean {
        val ts = raw.trimStart()
        if (ts.isEmpty()) return false
        // 标题
        if (ts[0] == '#') return true
        // 无序列表
        if ((ts[0] == '-' || ts[0] == '*' || ts[0] == '+') && (ts.length == 1 || ts[1].isWhitespace())) return true
        // 有序列表
        run {
            var k = 0
            var hasDigit = false
            while (k < ts.length && ts[k].isDigit()) { hasDigit = true; k++ }
            if (hasDigit && k < ts.length && (ts[k] == '.' || ts[k] == ')') && (k + 1 < ts.length && ts[k + 1].isWhitespace())) return true
        }
        // Commands/命令/指令/步骤/Step n
        val lc = ts.lowercase()
        if (lc.startsWith("commands:") || lc == "commands" || lc.startsWith("step ") || lc.startsWith("step")) return true
        if (ts.startsWith("命令") || ts.startsWith("指令") || ts.startsWith("步骤")) return true
        // 分隔线 ---
        val tt = ts.trim()
        if (tt.length >= 3 && tt.all { it == '-' }) return true
        return false
    }

    // 在 index 之前插入闭合围栏
    fun insertFenceCloseBefore(index: Int) {
        // 防御检查：避免列表无限增长
        if (lines.size > originalLines.size + 500) {
            // 异常情况：已插入过多围栏，停止插入
            return
        }
        val fence = fenceChar.toString().repeat(fenceLen)
        val insertAt = if (index >= 0) index else lines.size
        lines.add(insertAt, fence)
    }

    var consecutiveBlanks = 0
    // 防御：若早闭合被异常触发，限制最多插入的围栏数量，避免无限插入导致 OOM
    var insertedFences = 0

    while (i < lines.size) {
        val line = lines[i]
        val isBlank = line.isBlank()
        consecutiveBlanks = if (isBlank) consecutiveBlanks + 1 else 0

        if (!inFence) {
            val (open, lang, rest) = parseFenceOpen(line)
            if (open) {
                // 将 "```lang inline" 的 inline 内容移到下一行
                val newHead = buildString {
                    append(fenceChar.toString().repeat(fenceLen))
                    if (lang.isNotEmpty()) {
                        append(' ')
                        append(lang)
                    }
                }
                lines[i] = newHead
                if (rest.isNotEmpty()) {
                    lines.add(i + 1, rest)
                    i += 1
                }
                inFence = true
                i += 1
                continue
            } else {
                i += 1
                continue
            }
        } else {
            // inFence: 优先匹配显式闭合
            if (isFenceClose(line)) {
                inFence = false
                consecutiveBlanks = 0
                i += 1
                continue
            }

            // 早闭合：遇到明显结构边界行或连续两行空白后进入正文
            if (isStructureBoundary(line) || consecutiveBlanks >= 2) {
                // 在当前行之前插入闭合围栏，并前移索引跳过刚插入的行，避免反复把同一行判定为开围栏→再早闭合的循环
                insertFenceCloseBefore(i)
                insertedFences++
                if (insertedFences > 200) {
                    // 极端防御：降低阈值从2048到200，更早地停止异常插入
                    inFence = false
                    consecutiveBlanks = 0
                    i += 1
                    continue
                }
                inFence = false
                consecutiveBlanks = 0
                i += 1  // 跳过我们刚刚插入的 ```
                // 继续处理当前的结构行（此时它已处于"非围栏"上下文）
                continue
            }

            // 仍在围栏中，继续
            i += 1
        }
    }

    // EOF 未闭合：补闭合
    if (inFence) lines.add(fenceChar.toString().repeat(fenceLen))

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
