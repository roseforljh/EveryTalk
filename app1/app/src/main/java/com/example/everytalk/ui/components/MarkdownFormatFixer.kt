package com.example.everytalk.ui.components

/**
 * 🔧 Markdown格式修复工具
 * 
 * 专门用于修复AI输出的Markdown格式问题
 * 
 * 使用场景：
 * - AI输出的Markdown格式不规范（如缺少空格、换行）
 * - 标题和内容粘连在一起
 * - 列表格式不正确
 * - 代码块和分隔线需要空行分隔
 */
object MarkdownFormatFixer {
    
    // 当为 true 时，将 Markdown 表格转换为等宽代码块；默认关闭以允许外部库原生渲染表格
    var forceTableAsCodeBlock: Boolean = false

    /**
     * 修复Markdown格式
     * 
     * @param markdown 原始Markdown文本
     * @return 修复后的Markdown文本
     */
    fun fix(markdown: String): String {
        // 🎯 冲突与性能保护（避免与代码块/数学/流式实时渲染冲突）
        // - 含代码围栏、内联反引号、数学符号或超长文本时，直接返回原文，避免破坏结构或造成重型正则开销
        // - 表格相关的规范化仅在文本较短时执行（阈值可调）
        val text = markdown
        val len = text.length
        val hasFence = text.contains("```")
        val hasInlineBacktick = text.contains('`')
        val hasMath = text.contains("$") || text.contains("$$")
        val isVeryLong = len > 5000

        if (isVeryLong || hasFence || hasInlineBacktick || hasMath) {
            // 最保守：不做任何修复，交由最终渲染/外部库处理，避免与流式/代码/数学冲突
            return text
        }

        var fixed = text

        // ==================== 标题相关修复 ====================
        
        // 规则1: 修复标题格式 - 在 # 后面添加空格（包括emoji情况）
        // 示例: ##标题 -> ## 标题
        fixed = fixHeadingSpace(fixed)
        
        // 规则2: 标题后面直接跟内容（不是换行），添加换行
        // 示例: ## 标题内容继续 -> ## 标题\n内容继续
        fixed = separateHeadingFromContent(fixed)
        
        // 规则3: 分离连续的标题
        // 示例: ##标题1##标题2 -> ##标题1\n##标题2
        fixed = separateConsecutiveHeadings(fixed)

        // 新规则A: 在行中部出现的标题标记前补换行
        // 示例: 文末文字## 标题 -> 文末文字\n## 标题
        fixed = addNewlineBeforeInlineHeading(fixed)

        // 新规则B: 标题行中紧跟的列表起手，拆分到下一行
        // 示例: ## 标题- 项A -> ## 标题\n- 项A
        fixed = splitHeadingFollowedByList(fixed)
        
        // 规则8: 标题后添加空行（如果后面不是空行）
        // 示例: ## 标题\n内容 -> ## 标题\n\n内容
        fixed = addEmptyLineAfterHeading(fixed)
        
        // ==================== 列表相关修复 ====================
        
        // 规则4: 列表项前面没有换行，添加换行
        // 示例: 内容- 列表项 -> 内容\n- 列表项
        fixed = addNewlineBeforeList(fixed)
        
        // 规则5: 修复列表格式 - 在 - 后面添加空格
        // 示例: -列表项 -> - 列表项
        fixed = fixListItemSpace(fixed)
        
        // 规则6: 修复列表项中的多个空格
        // 示例: -  列表项 -> - 列表项
        fixed = fixListItemMultipleSpaces(fixed)
        
        // 规则9: 列表前添加空行（如果前面不是空行或标题）
        // 示例: 内容\n- 列表 -> 内容\n\n- 列表
        fixed = addEmptyLineBeforeList(fixed)
        
        // 额外规则：清理被错误包装为列表的表格/代码/标题起始行
        // 例如：'• |---'、'- |...'、'* ```'、'• ## 标题'
        fixed = unwrapListMarkerBeforeTableAndCode(fixed)
        
        // ==================== 代码块相关修复 ====================
        
        // 规则11: 代码块前后添加空行
        // 示例: 内容\n```代码``` -> 内容\n\n```代码```\n\n
        fixed = addEmptyLinesAroundCodeBlock(fixed)
        
        // ==================== 分隔线相关修复 ====================
        
        // 规则12: 分隔线前后添加空行
        // 示例: 内容\n--- -> 内容\n\n---\n\n
        fixed = addEmptyLinesAroundHorizontalRule(fixed)
        
        // ==================== 表格相关修复（仅对较短文本执行，避免重型解析） ====================
        if (fixed.length <= 3000) {
            // 顺序很重要：先规范表格行 -> 修复分隔行 -> 再在表格块外侧加空行
            // 规则16: 规范化表格行格式（添加首尾管道符）
            // 示例: 列1 | 列2 -> | 列1 | 列2 |
            fixed = normalizeTableRows(fixed)

            // 规则15: 修复表格分隔行格式
            // 示例: |:-|:--| -> | :--- | :--- |
            fixed = fixTableSeparatorRow(fixed)

            // 规则14: 表格前后添加空行（块外空行，块内不插空行）
            // 示例: 内容\n| 列1 | 列2 | -> 内容\n\n| 列1 | 列2 |\n\n
            fixed = addEmptyLinesAroundTable(fixed)
        }

        // 兼容性处理：如需强制用等宽代码块替代表格，可打开开关
        if (forceTableAsCodeBlock) {
            fixed = convertTablesToMonospaceCodeBlock(fixed)
        }
        
        return fixed
    }
    
    // ========== 标题修复函数 ==========
    
    /**
     * 规则1: 修复标题格式 - 在 # 后面添加空格
     */
    private fun fixHeadingSpace(text: String): String {
        return text.replace(
            Regex("""^(#{1,6})([^\s#\n])""", RegexOption.MULTILINE),
            "$1 $2"
        )
    }
    
    /**
     * 规则2: 标题后面直接跟内容，添加换行
     *
     * - 如果标题后直接是 `$` 开头，说明是公式，不拆分
     */
    private fun separateHeadingFromContent(text: String): String {
        return text.replace(
            Regex("""^(#{1,6}\s+[^\n]+?)([^#\n$]{20,})""", RegexOption.MULTILINE)
        ) { match ->
            val title = match.groupValues[1]
            val content = match.groupValues[2]
            // 在标题和内容之间找到合适的分割点（如：标题emoji后、标题结束处）
            val titleEnd = title.indexOfLast { it.toString().matches(Regex("[\\p{So}\\p{Emoji}]")) }
            if (titleEnd > 0 && titleEnd < title.length - 1) {
                "${title.substring(0, titleEnd + 1)}\n${title.substring(titleEnd + 1)}$content"
            } else {
                match.value
            }
        }
    }
    
    /**
     * 规则3: 分离连续的标题
     */
    private fun separateConsecutiveHeadings(text: String): String {
        return text.replace(
            Regex("""(#{1,6}\s+[^\n]+?)(#{1,6})""")
        ) { match ->
            "${match.groupValues[1]}\n${match.groupValues[2]}"
        }
    }
    
    /**
     * 规则8: 标题后添加空行（如果后面不是空行）
     */
    private fun addEmptyLineAfterHeading(text: String): String {
        // 匹配：标题行后面紧跟非空行（不是#、|、-、```、$$开头）
        return text.replace(
            Regex("""^(#{1,6}\s+[^\n]+)\n([^#\n|`$\-\s])""", RegexOption.MULTILINE)
        ) { match ->
            "${match.groupValues[1]}\n\n${match.groupValues[2]}"
        }
    }

    /**
     * 新规则A: 标题若非行首出现（被前句粘连），在其前面补换行
     * - 仅处理至少两个 # 的情况，避免误伤 C# 等文本
     * - 例："...不足## 文学贡献" -> "...不足\n## 文学贡献"
     */
    private fun addNewlineBeforeInlineHeading(text: String): String {
        return text.replace(
            Regex("""(?m)([^\n])(\#{2,6})(\s*)([^\n].*)""")
        ) { m ->
            "${m.groupValues[1]}\n${m.groupValues[2]} ${m.groupValues[4]}"
        }
    }

    /**
     * 新规则B: 同一行内：标题后直接出现列表起始（- * +），拆成两行
     * - 例："## 标题- **统一北方**" -> "## 标题\n- **统一北方**"
     */
    private fun splitHeadingFollowedByList(text: String): String {
        return text.replace(
            Regex("""(?m)^(#{1,6}\s+[^\n]*?)(\s*[-*+]\s+.+)$""")
        ) { m ->
            val head = m.groupValues[1].trimEnd()
            val list = m.groupValues[2].trimStart()
            "$head\n$list"
        }
    }
    
    // ========== 列表修复函数 ==========
    
    /**
     * 规则4: 列表项前面没有换行，添加换行
     *
     * - 跳过 `- **文本**` 格式（加粗列表项）
     * - 跳过 `$$` 块内的内容
     */
    private fun addNewlineBeforeList(text: String): String {
        return text.replace(
            Regex("""([^\n$*])(- )(?!\*\*)""")
        ) { match ->
            "${match.groupValues[1]}\n${match.groupValues[2]}"
        }
    }
    
    /**
     * 规则5: 修复列表格式 - 在 - 后面添加空格
     */
    private fun fixListItemSpace(text: String): String {
        return text.replace(
            Regex("""^-([^\s-\n])""", RegexOption.MULTILINE),
            "- $1"
        )
    }
    
    /**
     * 规则6: 修复列表项中的多个空格
     */
    private fun fixListItemMultipleSpaces(text: String): String {
        return text.replace(
            Regex("""^-\s{2,}""", RegexOption.MULTILINE),
            "- "
        )
    }
    
    /**
     * 规则9: 列表前添加空行（如果前面不是空行或标题或列表）
     *
     * 🎯 关键修复：
     * - 只在列表块的开始位置添加空行，不在列表项之间添加
     * - 跳过 `$$` 后面的内容（块级公式）
     */
    private fun addEmptyLineBeforeList(text: String): String {
        // 匹配：非列表行 + 换行 + 列表项
        // 确保前一行不是列表项（不以 -, *, + 开头，且不缩进）
        return text.replace(
            Regex("""([^\n$])\n([-*+]\s)(?!\*\*)""")
        ) { match ->
            val prevLine = match.groupValues[1]
            val listItem = match.groupValues[2]
            
            // 如果前一行以列表标记结尾，说明这是列表中的一项，不添加空行
            // 如果前一行是 $$，说明是公式块，不添加空行
            if (prevLine.trimEnd().matches(Regex(""".*[-*+]\s.*""")) ||
                prevLine.trimEnd().endsWith("$$")) {
                match.value
            } else {
                "$prevLine\n\n$listItem"
            }
        }
    }
    
    /**
     * 额外规则：移除在表格/代码/标题行前被误加的列表符号
     *
     * 典型问题：
     * - "• | ---" 或 "- | ...": 表格行被渲染为列表项，导致表格破坏
     * - "• ```" 或 "- ```": 代码块起始被当作列表包裹
     * - "• ## 标题": 标题前被加了无意义的列表符号
     *
     * 处理策略（MULTILINE）：
     * - 去掉行首可选空白 + 列表前缀(•|-|*) + 空格，若后面紧跟表格/分隔行/代码/标题标记，则剥离该列表前缀
     */
    private fun unwrapListMarkerBeforeTableAndCode(text: String): String {
        var fixed = text
        // 表格数据行：如 "• | a | b |" 或 "- | a | b |"
        fixed = fixed.replace(
            Regex("""(?m)^\s*[•\-\*]\s+(\|[^\n]+)$"""),
            "$1"
        )
        // 表格分隔行：如 "• |---|---|" 或 "- | :--- | :--- |"
        fixed = fixed.replace(
            Regex("""(?m)^\s*[•\-\*]\s+(\|[\s:\-\|]+)$"""),
            "$1"
        )
        // 代码块围栏：如 "• ```" 或 "- ```lang"
        fixed = fixed.replace(
            Regex("""(?m)^\s*[•\-\*]\s+(```.*)$"""),
            "$1"
        )
        // 标题：如 "• ## 标题"
        fixed = fixed.replace(
            Regex("""(?m)^\s*[•\-\*]\s+(#{1,6}\s+[^\n]+)$"""),
            "$1"
        )
        return fixed
    }
    
    // ========== 代码块修复函数 ==========
    
    /**
     * 规则11: 代码块前后添加空行
     */
    private fun addEmptyLinesAroundCodeBlock(text: String): String {
        var fixed = text
        
        // 代码块开始标记前添加空行
        fixed = fixed.replace(
            Regex("""([^\n])\n(```)""")
        ) { match ->
            "${match.groupValues[1]}\n\n${match.groupValues[2]}"
        }
        
        // 代码块结束标记后添加空行
        fixed = fixed.replace(
            Regex("""(```)\n([^\n`])""")
        ) { match ->
            "${match.groupValues[1]}\n\n${match.groupValues[2]}"
        }
        
        return fixed
    }
    
    // ========== 分隔线修复函数 ==========
    
    /**
     * 规则12: 分隔线前后添加空行
     * 支持 ---, ***, ___ 三种分隔线格式
     * 
     * 注意：分隔线必须单独一行，只包含 3 个或更多的 -, *, 或 _（可能有空格）
     */
    private fun addEmptyLinesAroundHorizontalRule(text: String): String {
        var fixed = text
        
        // 分隔线前添加空行（如果前面不是空行）
        // 匹配：行首 + 至少3个同种字符 + 可选空格 + 行尾
        fixed = fixed.replace(
            Regex("""([^\n])\n(^[-*_]{3,}\s*$)""", RegexOption.MULTILINE)
        ) { match ->
            "${match.groupValues[1]}\n\n${match.groupValues[2]}"
        }
        
        // 分隔线后添加空行（如果后面不是空行）
        fixed = fixed.replace(
            Regex("""(^[-*_]{3,}\s*$)\n([^\n])""", RegexOption.MULTILINE)
        ) { match ->
            "${match.groupValues[1]}\n\n${match.groupValues[2]}"
        }
        
        return fixed
    }
    
    
    // 已移除所有数学公式相关转换逻辑

    // ========== 表格修复函数 ==========
    
    /**
     * 规则14: 表格前后添加空行
     * 
     * 检测表格的开始和结束，确保表格块前后都有空行
     * 表格特征：连续的以 | 开头或包含 | 的行
     */
    private fun addEmptyLinesAroundTable(text: String): String {
        val lines = text.split("\n")
        if (lines.isEmpty()) return text

        val result = mutableListOf<String>()
        var i = 0

        fun isTableLine(raw: String): Boolean {
            val t = raw.trimStart()
            // 允许首尾有无管道，但至少包含一个管道，且不是水平分隔线
            if (!t.contains("|")) return false
            if (t.matches(Regex("""^[-*_]{3,}\s*$"""))) return false
            return true
        }

        while (i < lines.size) {
            val line = lines[i]
            if (isTableLine(line)) {
                val start = i
                var end = i
                while (end + 1 < lines.size && isTableLine(lines[end + 1])) {
                    end++
                }

                // 在表格块前加空行（若上一输出行非空）
                if (result.isNotEmpty() && result.last().isNotBlank()) {
                    result.add("")
                }

                // 原样输出整个表格块
                for (k in start..end) result.add(lines[k])

                // 在表格块后加空行（若后续存在且下一行非空）
                if (end + 1 < lines.size && lines[end + 1].isNotBlank()) {
                    result.add("")
                }

                i = end + 1
            } else {
                result.add(line)
                i++
            }
        }

        return result.joinToString("\n")
    }
    
    /**
     * 规则15: 修复表格分隔行格式
     * 
     * 将紧凑的分隔符扩展为标准格式
     * 示例：
     * - |:-|:--| -> | :--- | :--- |
     * - |---|---| -> | --- | --- |
     * - | :--- | :---- | -> | :--- | :---- |（保持原样）
     */
    private fun fixTableSeparatorRow(text: String): String {
        return text.replace(
            Regex("""^\|([:\-]+\|)+\s*$""", RegexOption.MULTILINE)
        ) { match ->
            val row = match.value.trim()
            
            // 分割各列
            val cells = row.split("|").filter { it.isNotEmpty() }
            
            // 重新格式化每列
            val formattedCells = cells.map { cell ->
                val trimmed = cell.trim()
                
                when {
                    // 左对齐: :---
                    trimmed.startsWith(":") && !trimmed.endsWith(":") -> {
                        " :${"-".repeat(maxOf(3, trimmed.count { it == '-' }))} "
                    }
                    // 右对齐: ---:
                    !trimmed.startsWith(":") && trimmed.endsWith(":") -> {
                        " ${"-".repeat(maxOf(3, trimmed.count { it == '-' }))}: "
                    }
                    // 居中对齐: :---:
                    trimmed.startsWith(":") && trimmed.endsWith(":") -> {
                        " :${"-".repeat(maxOf(3, trimmed.count { it == '-' }))}: "
                    }
                    // 默认对齐: ---
                    else -> {
                        " ${"-".repeat(maxOf(3, trimmed.count { it == '-' }))} "
                    }
                }
            }
            
            // 组装成标准格式
            "|${formattedCells.joinToString("|")}|"
        }
    }
    
    /**
     * 规则16: 规范化表格行格式
     *
     * 确保表格行首尾都有管道符 |
     * 示例：
     * - "列1 | 列2" -> "| 列1 | 列2 |"
     * - "| 列1 | 列2" -> "| 列1 | 列2 |"
     *
     * 关键：不破坏分隔行（如 |---|---| 或 | :--- | ---: |）
     */
    private fun normalizeTableRows(text: String): String {
        val lines = text.split("\n")
        val result = mutableListOf<String>()
        
        fun isSeparatorRow(line: String): Boolean {
            val trimmed = line.trim()
            // 分隔行特征：主要由 | - : 和空格组成
            val withoutSpaces = trimmed.replace(" ", "")
            return withoutSpaces.matches(Regex("""^\|?[-:]+(\|[-:]+)*\|?$"""))
        }
        
        for (line in lines) {
            // 检测是否可能是表格行（包含 | 但不是水平分隔线）
            if (line.contains("|") && !line.trim().matches(Regex("""^[-*_]{3,}\s*$"""))) {
                var normalized = line.trim()
                
                // 如果是分隔行，跳过规范化（保持原样，避免破坏 --- 结构）
                if (isSeparatorRow(normalized)) {
                    // 只确保首尾有管道符
                    if (!normalized.startsWith("|")) {
                        normalized = "| $normalized"
                    }
                    if (!normalized.endsWith("|")) {
                        normalized = "$normalized |"
                    }
                    result.add(normalized)
                    continue
                }
                
                // 数据行：添加首尾管道符
                if (!normalized.startsWith("|")) {
                    normalized = "| $normalized"
                }
                if (!normalized.endsWith("|")) {
                    normalized = "$normalized |"
                }
                
                // 规范化单元格间距（确保 | 两边有空格）
                // 使用更安全的方式：先分割，再重组
                val cells = normalized.split("|")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                
                normalized = "| ${cells.joinToString(" | ")} |"
                
                result.add(normalized)
            } else {
                result.add(line)
            }
       
        }
        return result.joinToString("\n")
    }
    
}


    /**
     * 将规范的 Markdown 表格转换为“等宽代码块”以获得稳定对齐显示
     * 仅在 forceTableAsCodeBlock = true 时由 fix() 调用
     */
    fun convertTablesToMonospaceCodeBlock(text: String): String {
        val lines = text.lines()
        if (lines.isEmpty()) return text

        val out = StringBuilder()
        var i = 0

        fun isPipeLine(s: String): Boolean = s.trim().startsWith("|") && s.contains("|")
        fun isSeparatorRow(line: String): Boolean {
            val compact = line.trim().replace(" ", "")
            return compact.matches(Regex("""^\|?[-:]+(\|[-:]+)*\|?$"""))
        }
        fun splitCells(line: String): List<String> {
            var t = line.trim()
            if (t.startsWith("|")) t = t.substring(1)
            if (t.endsWith("|")) t = t.substring(0, t.length - 1)
            return t.split("|").map { it.trim() }
        }

        while (i < lines.size) {
            val line = lines[i]
            if (isPipeLine(line) && i + 1 < lines.size && isSeparatorRow(lines[i + 1])) {
                val header = line
                val rows = mutableListOf<String>()
                rows.add(header)
                rows.add(lines[i + 1])
                var j = i + 2
                while (j < lines.size && isPipeLine(lines[j])) {
                    rows.add(lines[j])
                    j++
                }

                val headerCells = splitCells(header)
                val colCount = headerCells.size
                val widths = IntArray(colCount) { 0 }

                fun updateWidths(cells: List<String>) {
                    for (k in 0 until colCount) {
                        val cell = cells.getOrNull(k)?.trim() ?: ""
                        if (cell.length > widths[k]) widths[k] = cell.length
                    }
                }

                updateWidths(headerCells)
                for (rIndex in 2 until rows.size) {
                    updateWidths(splitCells(rows[rIndex]))
                }

                out.appendLine("```text")
                run {
                    val cells = headerCells
                    val lineBuf = buildString {
                        append("| ")
                        for (k in 0 until colCount) {
                            val cell = cells.getOrNull(k) ?: ""
                            append(cell.padEnd(widths[k], ' '))
                            if (k < colCount - 1) append(" | ") else append(" |")
                        }
                    }
                    out.appendLine(lineBuf)
                }
                run {
                    val lineBuf = buildString {
                        append("| ")
                        for (k in 0 until colCount) {
                            append("-".repeat(maxOf(3, widths[k])))
                            if (k < colCount - 1) append(" | ") else append(" |")
                        }
                    }
                    out.appendLine(lineBuf)
                }
                for (rIndex in 2 until rows.size) {
                    val cells = splitCells(rows[rIndex])
                    val lineBuf = buildString {
                        append("| ")
                        for (k in 0 until colCount) {
                            val cell = cells.getOrNull(k) ?: ""
                            append(cell.padEnd(widths[k], ' '))
                            if (k < colCount - 1) append(" | ") else append(" |")
                        }
                    }
                    out.appendLine(lineBuf)
                }
                out.appendLine("```")

                if (j < lines.size && lines[j].isNotBlank()) out.appendLine()

                i = j
            } else {
                out.appendLine(line)
                i++
            }
        }

        return out.toString().trimEnd()
    }
