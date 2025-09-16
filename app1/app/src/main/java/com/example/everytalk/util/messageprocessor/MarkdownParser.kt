package com.example.everytalk.util.messageprocessor

import com.example.everytalk.ui.components.TableData
import com.example.everytalk.ui.components.MarkdownPart
import com.example.everytalk.ui.components.parseMarkdownTable
import com.example.everytalk.ui.components.splitMarkdownTableRow
import java.util.UUID

// 主解析：一个通用的解析器，可以识别代码块、数学公式和表格
internal fun parseMarkdownParts(markdown: String, inTableContext: Boolean = false): List<MarkdownPart> {
    if (markdown.isBlank()) return listOf(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = ""))

    // 🎯 新增：Android前端预处理
    val preprocessed = preprocessMarkdownForAndroid(markdown)
    
    val parts = mutableListOf<MarkdownPart>()
    val buffer = StringBuilder()

    fun flushBuffer() {
        if (buffer.isNotEmpty()) {
            parts.addAll(extractTablesAsParts(buffer.toString(), inTableContext))
            buffer.clear()
        }
    }

    fun addInlineMath(latex: String) {
        val partId = "part_${UUID.randomUUID()}"
        parts.add(MarkdownPart.InlineMath(id = partId, latex = latex))
    }
    fun addDisplayMath(latex: String) {
        val partId = "part_${UUID.randomUUID()}"
        parts.add(MarkdownPart.MathBlock(id = partId, latex = latex, isDisplay = true))
    }

    // 判断 idx 位置的分隔符是否被反斜杠转义（奇数个反斜杠）
    fun isEscaped(idx: Int): Boolean {
        var bs = 0
        var i = idx - 1
        while (i >= 0 && preprocessed[i] == '\\') { bs++; i-- }
        return bs % 2 == 1
    }

    // 查找未被转义的目标子串
    fun findUnescaped(target: String, start: Int): Int {
        var j = start
        while (true) {
            val k = preprocessed.indexOf(target, j)
            if (k == -1) return -1
            if (!isEscaped(k)) return k
            j = k + 1
        }
    }

    // 判断 $...$ 的内容是否像数学，避免把 $20 当作公式
    fun looksLikeInlineMathPayload(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.contains('\n') || t.contains('\r')) return false

        // Rule 1: It's NOT math if it's just a number that could be currency.
        if (Regex("""^\d+([,.]\d+)*$""").matches(t)) {
            return false
        }

        // Rule 2: It IS math if it has LaTeX commands, braces, or super/subscripts.
        if (Regex("""\\[a-zA-Z]+""").containsMatchIn(t) || t.contains('{') || t.contains('}') || t.contains('^') || t.contains('_')) {
            return true
        }

        // Rule 3: It IS math if it's a single letter.
        if (t.length == 1 && t[0].isLetter()) {
            return true
        }

        // Rule 4: It IS math if it contains common operators, and isn't just a sentence.
        if (t.contains('=') || t.contains('<') || t.contains('>') || t.contains('+') || t.contains('-') || t.contains('*') || t.contains('/')) {
            if (t.contains(' ')) {
                if (Regex("""[a-zA-Z0-9\)]\s*[=<>+\-*/]\s*[a-zA-Z0-9\(]""").containsMatchIn(t)) {
                    return true
                }
            } else {
                return true
            }
        }

        // Rule 5: A mix of letters and numbers is likely math.
        if (t.any(Char::isLetter) && t.any(Char::isDigit) && !t.contains(" ")) {
            return true
        }

        return false
    }

    val n = preprocessed.length
    var i = 0
    while (i < n) {
        // 代码围栏 ```lang ... ```
        if (preprocessed.startsWith("```", i)) {
            val langStart = i + 3
            var j = langStart
            while (j < n && (preprocessed[j] == ' ' || preprocessed[j] == '\t')) j++
            var k = j
            while (k < n && preprocessed[k] != '\n' && preprocessed[k] != '\r') k++
            val language = preprocessed.substring(j, k).trim().trim('`')
            var codeStart = k
            if (codeStart < n && (preprocessed[codeStart] == '\n' || preprocessed[codeStart] == '\r')) {
                codeStart += 1
                if (codeStart < n && preprocessed[codeStart] == '\n' && preprocessed[codeStart - 1] == '\r') {
                    // CRLF 情况已经处理过一个字符，保持简单
                }
            }
            val close = preprocessed.indexOf("```", codeStart)
            if (close == -1) {
                buffer.append(preprocessed.substring(i))
                i = n
                break
            }
            val code = preprocessed.substring(codeStart, close)
            flushBuffer()
            val partId = "part_${UUID.randomUUID()}"
            val langLower = language.lowercase()
            when {
                langLower == "markdown" || langLower == "md" -> {
                    parts.addAll(parseMarkdownParts(code, inTableContext))
                }
                langLower == "mdpreview" || langLower == "markdown_preview" -> {
                    parts.add(MarkdownPart.CodeBlock(id = partId, content = code, language = "markdown"))
                }
                langLower.isBlank() || langLower == "text" -> {
                    val linesForCheck = code.trim().split("\n")
                    val looksLikeTable = linesForCheck.size >= 2 &&
                        looksLikeTableHeader(linesForCheck[0]) &&
                        isAlignmentRow(linesForCheck[1])
                    if (looksLikeTable) {
                        parts.addAll(extractTablesAsParts(code, inTableContext))
                    } else {
                        parts.add(MarkdownPart.CodeBlock(id = partId, content = code, language = language))
                    }
                }
                else -> {
                    parts.add(MarkdownPart.CodeBlock(id = partId, content = code, language = language))
                }
            }
            i = close + 3
            continue
        }

        // 行内代码段：`...` 或 多反引号包裹，避免其中的 $ 触发数学解析
        if (preprocessed[i] == '`') {
            // 统计连续反引号数量
            var tickCount = 1
            var t = i + 1
            while (t < n && preprocessed[t] == '`') { tickCount++; t++ }
            // 查找匹配的结束反引号序列
            var searchPos = t
            var found = -1
            val needle = "`".repeat(tickCount)
            while (searchPos < n) {
                val k = markdown.indexOf(needle, searchPos)
                if (k == -1) break
                // inline code 结束符不考虑反斜杠转义，取首次匹配
                found = k
                break
            }
            if (found != -1) {
                // 将代码段整体原样写入缓冲，跳过内部数学解析
                buffer.append(preprocessed.substring(i, found + tickCount))
                i = found + tickCount
                continue
            } else {
                // 未闭合时当作普通字符处理
                buffer.append(preprocessed[i])
                i++
                continue
            }
        }

        // 块级数学 $$ ... $$
        if (preprocessed[i] == '$' && !isEscaped(i)) {
            var dollarCount = 1
            var t = i + 1
            while (t < n && preprocessed[t] == '$') { dollarCount++; t++ }
            if (dollarCount >= 2) {
                val end = findUnescaped("$$", t)
                if (end != -1) {
                    val latex = preprocessed.substring(t, end).trim()
                    flushBuffer()
                    addDisplayMath(latex)
                    i = end + 2
                    continue
                } else {
                    buffer.append(preprocessed[i])
                    i++
                    continue
                }
            } else {
                val end = findUnescaped("$", i + 1)
                if (end != -1) {
                    val payload = preprocessed.substring(i + 1, end)
                    if (looksLikeInlineMathPayload(payload)) {
                        flushBuffer()
                        addInlineMath(payload.trim())
                        i = end + 1
                        continue
                    } else {
                        buffer.append(preprocessed[i])
                        i++
                        continue
                    }
                } else {
                    buffer.append(preprocessed[i])
                    i++
                    continue
                }
            }
        }

        // 行内数学 \( ... \)
        if (i + 1 < n && markdown.startsWith("\\(", i) && !isEscaped(i)) {
            val end = findUnescaped("\\)", i + 2)
            if (end != -1) {
                val latex = preprocessed.substring(i + 2, end).trim()
                flushBuffer()
                addInlineMath(latex)
                i = end + 2
                continue
            }
        }

        // 块级数学 \[ ... \]
        if (i + 1 < n && markdown.startsWith("\\[", i) && !isEscaped(i)) {
            val end = findUnescaped("\\]", i + 2)
            if (end != -1) {
                val latex = preprocessed.substring(i + 2, end).trim()
                flushBuffer()
                addDisplayMath(latex)
                i = end + 2
                continue
            }
        }

        // 默认：累积为普通文本
        buffer.append(preprocessed[i])
        i++
    }

    flushBuffer()

    // 过滤掉空的文本部分，但如果解析后什么都没有，则返回原文，以防UI空白
    val filteredParts = parts.filterNot { it is MarkdownPart.Text && it.content.isBlank() }
    if (filteredParts.isEmpty() && markdown.isNotBlank()) {
        return listOf(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = markdown))
    }

    return filteredParts
}

// 将文本中的表格提取为 Table，其余保持为 Text（不在表格单元格上下文时才做块级表格）
private fun extractTablesAsParts(text: String, inTableContext: Boolean): List<MarkdownPart> {
    if (text.isBlank()) return emptyList()
    if (inTableContext) return listOf(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = text))

    val lines = text.split("\n")
    val parts = mutableListOf<MarkdownPart>()
    val buffer = StringBuilder()

    var i = 0
    while (i < lines.size) {
        val rawLine = lines[i]
        val next = if (i + 1 < lines.size) lines[i + 1] else null

        // 预处理：尝试剥离表头行前的说明性前缀或列表标记（如 “- ”、“* ”、“1. ”、“说明：” 等）
        var headerLine = rawLine
        var leadingIntroText: String? = null
        run {
            val firstPipeIdx = rawLine.indexOf('|')
            if (firstPipeIdx > 0) {
                val prefix = rawLine.substring(0, firstPipeIdx)
                val prefixTrim = prefix.trim()
                val isListMarker = prefixTrim.matches(Regex("[-*+]\\s+.*")) ||
                    prefixTrim.matches(Regex("\\d+[.)]\\s+.*"))
                val looksIntro = prefixTrim.endsWith(":") || prefixTrim.endsWith("：") ||
                    prefixTrim.endsWith("。") || prefixTrim.endsWith("！") || prefixTrim.endsWith("？") ||
                    prefixTrim.length >= 12 || isListMarker
                if (looksIntro) {
                    leadingIntroText = prefixTrim
                    headerLine = rawLine.substring(firstPipeIdx)
                }
            }
        }

        val hasAlignmentNext = next?.let { isAlignmentRow(it) } == true
        val headerLooksLike = looksLikeTableHeader(headerLine)

       // 头部列数
        val colCountHeader = splitMarkdownTableRow(headerLine).size

        // 情况A：标准表格（第二行是对齐分隔行）
        val isStandardTableStart = headerLooksLike && hasAlignmentNext

        // 情况B：宽松表格（缺失对齐分隔行，但下一行看起来就是数据行，且列数一致）
        val isImplicitTableStart = headerLooksLike && !hasAlignmentNext && next != null &&
            next.contains('|') && colCountHeader >= 2 &&
            colCountHeader == splitMarkdownTableRow(next).size

        // 情况C：对齐分隔行与首条数据行被误写在同一行，如：
        // "| :--- | :--- | :--- || cell1 | cell2 | cell3 |"
        val combinedPair = if (headerLooksLike && !hasAlignmentNext && next != null) {
            splitCombinedAlignmentAndFirstRow(next, colCountHeader)
        } else null
        val isCombinedAlignmentAndFirstRow = combinedPair != null

        if (isStandardTableStart || isImplicitTableStart || isCombinedAlignmentAndFirstRow) {
            // 先把缓冲的普通文本刷出
            if (buffer.isNotEmpty()) {
                parts += MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = buffer.toString().trimEnd('\n'))
                buffer.clear()
            }
            // 如有说明性前缀，单独作为文本输出（避免被当作第一列）
            if (!leadingIntroText.isNullOrBlank()) {
                parts += MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = leadingIntroText!!.trim())
            }

            val tableLines = mutableListOf<String>()
            tableLines += headerLine
            var j = i + 1

            when {
                isCombinedAlignmentAndFirstRow -> {
                    val (alignmentRow, firstDataRow) = combinedPair!!
                    tableLines += alignmentRow
                    tableLines += firstDataRow
                    j = i + 2
                }
                isImplicitTableStart -> {
                    // 自动补一行对齐分隔行
                    val alignmentRow = buildString {
                        append("| ")
                        append(List(colCountHeader) { "---" }.joinToString(" | "))
                        append(" |")
                    }
                    tableLines += alignmentRow
                    // 把 next 作为第一行数据
                    tableLines += next!!
                    j = i + 2
                }
                else -> {
                    // 标准表格：第二行已是分隔行
                    tableLines += next!!
                    j = i + 2
                }
            }

            // 收集后续数据行（直到空行或不再包含竖线）
            while (j < lines.size) {
                val row = lines[j]
                if (row.trim().isEmpty()) break
                if (!row.contains("|")) break
                tableLines += row
                j += 1
            }

            val tableMd = tableLines.joinToString("\n")
            val tableData = parseMarkdownTable(tableMd)
            if (tableData != null) {
                parts += MarkdownPart.Table(id = "table_${UUID.randomUUID()}", tableData = tableData)
                i = j
                continue
            } else {
                // 解析失败则退回为普通文本
                buffer.append(tableMd).append('\n')
                i = j
                continue
            }
        }

        // 非表格起始，累积到缓冲
        buffer.append(rawLine).append('\n')
        i += 1
    }
    if (buffer.isNotEmpty()) {
        parts += MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = buffer.toString().trimEnd('\n'))
    }
    return parts
}

private fun looksLikeTableHeader(line: String): Boolean {
    val t = line.replace('｜','|').replace('│','|').trim()
    if (!t.contains("|")) return false
    val cells = t.trim('|').split("|")
    return cells.size >= 2
}

private fun isAlignmentRow(line: String): Boolean {
    val t = line.replace('｜','|').replace('│','|').replace('—','-').replace('－','-').replace('：', ':').trim()
    if (!t.contains("|")) return false
    val cells = t.trim('|').split("|").map { it.trim() }
    if (cells.size < 2) return false
    // 修正：兼容至少1个破折号，符合Markdown标准，例如 | - |
    val cellRegex = Regex("[:：]?[-—－－]{1,}[:：]?")
    return cells.all { it.matches(cellRegex) }
}

/**
 * 处理把对齐行与首条数据行写在同一行的情况：
 * 形如："| :--- | :--- | :--- || cell1 | cell2 | cell3 |"
 * 返回 Pair(标准化的对齐行, 标准化的首条数据行)；否则返回 null
 */
private fun splitCombinedAlignmentAndFirstRow(line: String, expectedCols: Int): Pair<String, String>? {
    val normalized = line
        .replace('｜','|')
        .replace('│','|')
        .replace('：', ':')
        .replace('—','-')
        .replace('－','-')
        .trim()

    // 与 isAlignmentRow 一致：允许至少2个破折号
    val cellPat = "[:：]?[-—－－]{2,}[:：]?"
    val regexStr = "^\\|?\\s*((?:$cellPat\\s*\\|\\s*){${expectedCols - 1}}$cellPat)\\s*\\|\\|\\s*(.*)$"
    val regex = Regex(regexStr)
    val m = regex.find(normalized) ?: return null

    val alignPart = m.groupValues[1].trim()
    val rowPartRaw = m.groupValues[2].trim()

    // 规范化对齐行
    val alignLineWithBars = if (alignPart.startsWith("|")) alignPart else "| $alignPart |"
    val cells = splitMarkdownTableRow(alignLineWithBars)
    if (cells.size != expectedCols) return null
    val alignmentRow = "| " + cells.joinToString(" | ") + " |"

    // 规范化首条数据行
    val firstRow = if (rowPartRaw.startsWith("|")) rowPartRaw else "| $rowPartRaw |"

    return alignmentRow to firstRow
}

/**
 * 🎯 Android前端Markdown预处理 - 修复后端可能遗漏的格式问题
 */
private fun preprocessMarkdownForAndroid(markdown: String): String {
    if (markdown.isEmpty()) return markdown
    
    return markdown
        // 1. 修复标题格式：确保##后有空格
        .replace(Regex("(?m)^(#{1,6})([^#\\s])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        
        // 2. 修复数学公式格式：确保$$独占行
        .replace(Regex("([^\\n])\\$\\$")) { "${it.groupValues[1]}\n$$" }
        .replace(Regex("\\$\\$([^\\n])")) { "$$\n${it.groupValues[1]}" }
        
        // 3. 规范化符号：全角转半角
        .replace('＊', '*')     // 全角星号
        .replace('﹡', '*')     // 小星号  
        .replace('｜', '|')     // 全角竖线
        .replace('│', '|')     // 框线字符
        .replace('┃', '|')     // 粗框线
        
        // 4. 修复表格格式
        .let { content ->
            content.lines().joinToString("\n") { line ->
                when {
                    // 表格行但缺少起始|
                    line.contains("|") && !line.trim().startsWith("|") && !line.trim().startsWith("```") -> {
                        if (line.trim().endsWith("|")) "| ${line.trim()}" else "| ${line.trim()} |"
                    }
                    // 表格行但缺少结束|
                    line.contains("|") && line.trim().startsWith("|") && !line.trim().endsWith("|") -> {
                        "${line.trim()} |"
                    }
                    else -> line
                }
            }
        }
        
        // 5. 清理不可见字符
        .replace("\u200B", "") // ZERO WIDTH SPACE
        .replace("\u200C", "") // ZERO WIDTH NON-JOINER  
        .replace("\u200D", "") // ZERO WIDTH JOINER
        .replace("\uFEFF", "") // BOM
}