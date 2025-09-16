package com.example.everytalk.util.messageprocessor

import com.example.everytalk.ui.components.MarkdownPart
import java.util.UUID

internal fun parseMarkdownParts(markdown: String, inTableContext: Boolean = false): List<MarkdownPart> {
    android.util.Log.d("MarkdownParser", "=== parseMarkdownParts START ===")
    android.util.Log.d("MarkdownParser", "Input markdown length: ${markdown.length}")
    android.util.Log.d("MarkdownParser", "Input preview: ${markdown.take(200)}...")
    
    if (markdown.isBlank()) {
        android.util.Log.d("MarkdownParser", "Markdown为空，返回空文本part")
        return listOf(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = ""))
    }

    val preprocessed = preprocessMarkdownForAndroid(markdown)
    android.util.Log.d("MarkdownParser", "Preprocessed preview: ${preprocessed.take(200)}...")
    
    val parts = mutableListOf<MarkdownPart>()
    val buffer = StringBuilder()

    fun flushBuffer() {
        if (buffer.isNotEmpty()) {
            parts.add(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = buffer.toString()))
            android.util.Log.d("MarkdownParser", "Flushed text buffer: ${buffer.toString().take(50)}...")
            buffer.clear()
        }
    }

    fun isEscaped(idx: Int): Boolean {
        var bs = 0
        var i = idx - 1
        while (i >= 0 && preprocessed[i] == '\\') { bs++; i-- }
        return bs % 2 == 1
    }

    fun findUnescaped(target: String, start: Int): Int {
        var j = start
        while (true) {
            val k = preprocessed.indexOf(target, j)
            if (k == -1) return -1
            if (!isEscaped(k)) return k
            j = k + 1
        }
    }

    val n = preprocessed.length
    var i = 0
    while (i < n) {
        // 1) 解析 ```code fences```
        if (preprocessed.startsWith("```", i)) {
            android.util.Log.d("MarkdownParser", "Found ``` at position $i")
            val langStart = i + 3
            var j = langStart
            while (j < n && (preprocessed[j] == ' ' || preprocessed[j] == '\t')) j++
            var k = j
            while (k < n && preprocessed[k] != '\n' && preprocessed[k] != '\r') k++
            val language = preprocessed.substring(j, k).trim().trim('`')
            android.util.Log.d("MarkdownParser", "Code block language: '$language'")
            var codeStart = k
            if (codeStart < n && (preprocessed[codeStart] == '\n' || preprocessed[codeStart] == '\r')) {
                codeStart += 1
            }
            val close = preprocessed.indexOf("```", codeStart)
            if (close == -1) {
                // 未闭合，作为普通文本追加
                buffer.append(preprocessed.substring(i))
                i = n
                break
            }
            val code = preprocessed.substring(codeStart, close)
            flushBuffer()
            val partId = "part_${UUID.randomUUID()}"
            // 将 ```math 或 ```latex 识别为 MathBlock
            if (language.equals("math", ignoreCase = true) || language.equals("latex", ignoreCase = true)) {
                android.util.Log.d("MarkdownParser", "✅ Creating MathBlock from ```$language block: ${code.take(50)}...")
                parts.add(MarkdownPart.MathBlock(id = partId, latex = code.trim(), displayMode = true))
            } else {
                parts.add(MarkdownPart.CodeBlock(id = partId, content = code, language = language))
            }
            i = close + 3
            continue
        }

        // 2) 解析 $$...$$ 块级数学
        if (preprocessed.startsWith("$$", i)) {
            android.util.Log.d("MarkdownParser", "Found $$ at position $i")
            val start = i + 2
            val end = findUnescaped("$$", start)
            if (end != -1) {
                val latex = preprocessed.substring(start, end)
                android.util.Log.d("MarkdownParser", "✅ Creating display MathBlock: ${latex.take(50)}...")
                flushBuffer()
                parts.add(MarkdownPart.MathBlock(id = "math_${UUID.randomUUID()}", latex = latex.trim(), displayMode = true))
                i = end + 2
                continue
            } else {
                // 未闭合，作为普通文本
                buffer.append(preprocessed.substring(i, i + 2))
                i += 2
                continue
            }
        }

        // 2.1) 解析 \\[ ... \\] 块级数学（LaTeX 常见写法）
        if (preprocessed.startsWith("\\[", i)) {
            android.util.Log.d("MarkdownParser", "Found \\[ at position $i")
            val start = i + 2
            val end = preprocessed.indexOf("\\]", start)
            if (end != -1) {
                val latex = preprocessed.substring(start, end)
                android.util.Log.d("MarkdownParser", "✅ Creating \\[\\] MathBlock: ${latex.take(50)}...")
                flushBuffer()
                parts.add(MarkdownPart.MathBlock(id = "math_${'$'}{UUID.randomUUID()}", latex = latex.trim(), displayMode = true))
                i = end + 2
                continue
            }
        }

        // 3) 解析 $...$ 行内数学（放宽规则：仅避免与货币符号冲突，允许字母/中文贴邻）
        if (preprocessed[i] == '$') {
            val prev = if (i > 0) preprocessed[i - 1] else ' '
            // 仅当紧邻为数字时视为可能的货币符号
            if (!prev.isDigit()) {
                android.util.Log.d("MarkdownParser", "Found $ at position $i")
                val start = i + 1
                val end = findUnescaped("$", start)
                if (end != -1) {
                    val next = if (end + 1 < n) preprocessed[end + 1] else ' '
                    if (!next.isDigit()) {
                        val latex = preprocessed.substring(start, end)
                        if (latex.isNotBlank()) {
                            android.util.Log.d("MarkdownParser", "✅ Creating inline MathBlock: ${latex.take(50)}...")
                            flushBuffer()
                            parts.add(MarkdownPart.MathBlock(id = "math_${'$'}{UUID.randomUUID()}", latex = latex.trim(), displayMode = false))
                            i = end + 1
                            continue
                        }
                    }
                }
            }
            // 非数学上下文，按普通字符处理
            buffer.append(preprocessed[i])
            i++
            continue
        }

        // 3.1) 解析 \\( ... \\) 行内数学（LaTeX 常见写法）
        if (preprocessed.startsWith("\\(", i)) {
            android.util.Log.d("MarkdownParser", "Found \\( at position $i")
            val start = i + 2
            val end = preprocessed.indexOf("\\)", start)
            if (end != -1) {
                val latex = preprocessed.substring(start, end)
                if (latex.isNotBlank()) {
                    android.util.Log.d("MarkdownParser", "✅ Creating \\(\\) MathBlock: ${latex.take(50)}...")
                    flushBuffer()
                    parts.add(MarkdownPart.MathBlock(id = "math_${'$'}{UUID.randomUUID()}", latex = latex.trim(), displayMode = false))
                    i = end + 2
                    continue
                }
            }
        }

        // 4) 行内代码与其它
        if (preprocessed[i] == '`') {
            var tickCount = 1
            var t = i + 1
            while (t < n && preprocessed[t] == '`') { tickCount++; t++ }
            var searchPos = t
            var found = -1
            val needle = "`".repeat(tickCount)
            while (searchPos < n) {
                val k = markdown.indexOf(needle, searchPos)
                if (k == -1) break
                found = k
                break
            }
            if (found != -1) {
                buffer.append(preprocessed.substring(i, found + tickCount))
                i = found + tickCount
                continue
            } else {
                buffer.append(preprocessed[i])
                i++
                continue
            }
        }

        buffer.append(preprocessed[i])
        i++
    }

    flushBuffer()

    val filteredParts = parts.filterNot { it is MarkdownPart.Text && it.content.isBlank() }
    
    android.util.Log.d("MarkdownParser", "Final parts count: ${filteredParts.size}")
    filteredParts.forEachIndexed { index, part ->
        android.util.Log.d("MarkdownParser", "Part $index: ${part::class.simpleName} - ${part.toString().take(100)}...")
    }
    
    if (filteredParts.isEmpty() && markdown.isNotBlank()) {
        android.util.Log.d("MarkdownParser", "No valid parts found, creating fallback text part")
        android.util.Log.d("MarkdownParser", "=== parseMarkdownParts END (fallback) ===")
        return listOf(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = markdown))
    }

    android.util.Log.d("MarkdownParser", "=== parseMarkdownParts END ===")
    return filteredParts
}

private fun preprocessMarkdownForAndroid(markdown: String): String {
    if (markdown.isEmpty()) return markdown
    
    // 🎯 第一步：标准化换行，保护LaTeX公式
    var processed = markdown
        .replace("\r\n", "\n")  // 统一换行符
        .replace("\r", "\n")   // 处理旧Mac换行
    
    // 🎯 第二步：保护LaTeX公式不被换行影响
    // 临时替换LaTeX公式为占位符，避免换行干扰
    val latexPlaceholders = mutableMapOf<String, String>()
    var placeholderIndex = 0
    
    // 保护 $$...$$
    processed = processed.replace(Regex("\\$\\$([\\s\\S]*?)\\$\\$")) { match ->
        val placeholder = "__LATEX_DISPLAY_${placeholderIndex++}__"
        latexPlaceholders[placeholder] = match.value
        placeholder
    }
    
    // 保护 $...$
    processed = processed.replace(Regex("\\$([^\\$\\n]+?)\\$")) { match ->
        val placeholder = "__LATEX_INLINE_${placeholderIndex++}__"
        latexPlaceholders[placeholder] = match.value
        placeholder
    }
    
    // 保护 \[...\]
    processed = processed.replace(Regex("\\\\\\[([\\s\\S]*?)\\\\\\]")) { match ->
        val placeholder = "__LATEX_BRACKET_${placeholderIndex++}__"
        latexPlaceholders[placeholder] = match.value
        placeholder
    }
    
    // 保护 \(...\)
    processed = processed.replace(Regex("\\\\\\(([^\\)]+?)\\\\\\)")) { match ->
        val placeholder = "__LATEX_PAREN_${placeholderIndex++}__"
        latexPlaceholders[placeholder] = match.value
        placeholder
    }
    
    // 🎯 第三步：处理Markdown格式规范化
    processed = processed
        .replace(Regex("(?m)^(#{1,6})([^#\\s])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        .replace('＊', '*')
        .replace('﹡', '*')
        .replace('｜', '|')
        .replace('│', '|')
        .replace('┃', '|')
        .let { content ->
            content.lines().joinToString("\n") { line ->
                when {
                    line.contains("|") && !line.trim().startsWith("|") && !line.trim().startsWith("```") -> {
                        if (line.trim().endsWith("|")) "| ${line.trim()}" else "| ${line.trim()} |"
                    }
                    line.contains("|") && line.trim().startsWith("|") && !line.trim().endsWith("|") -> {
                        "${line.trim()} |"
                    }
                    else -> line
                }
            }
        }
        .replace("\u200B", "")
        .replace("\u200C", "")
        .replace("\u200D", "")
        .replace("\uFEFF", "")
    
    // 🎯 第四步：恢复LaTeX公式
    latexPlaceholders.forEach { (placeholder, original) ->
        processed = processed.replace(placeholder, original)
    }
    
    return processed
}