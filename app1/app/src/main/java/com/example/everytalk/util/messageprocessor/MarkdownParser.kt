package com.example.everytalk.util.messageprocessor

import com.example.everytalk.ui.components.MarkdownPart
import java.util.UUID

/**
 * 🚀 增强的Markdown解析器 - 支持专业数学公式渲染和表格识别
 */
internal fun parseMarkdownParts(markdown: String, inTableContext: Boolean = false): List<MarkdownPart> {
    android.util.Log.d("MarkdownParser", "=== Enhanced parseMarkdownParts START ===")
    android.util.Log.d("MarkdownParser", "Input markdown length: ${markdown.length}")
    android.util.Log.d("MarkdownParser", "Input preview: ${markdown.take(200)}...")
    
    if (markdown.isBlank()) {
        android.util.Log.d("MarkdownParser", "Markdown为空,返回空文本part")
        return listOf(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = ""))
    }

    // 🎯 优先检测表格 - 表格检测应该在数学预处理之前
    if (isTableContent(markdown)) {
        android.util.Log.d("MarkdownParser", "检测到表格内容,直接解析为表格")
        return parseTableContent(markdown)
    }

    // 🎯 然后进行数学内容智能预处理
    val preprocessed = preprocessMarkdownForMath(markdown)
    android.util.Log.d("MarkdownParser", "Math preprocessed preview: ${preprocessed.take(200)}...")
    
    // 🎯 智能内容分类 - 检测是否包含复杂数学内容
    val contentType = detectContentType(preprocessed)
    android.util.Log.d("MarkdownParser", "Detected content type: $contentType")
    
    return when (contentType) {
        ContentType.MATH_HEAVY -> parseMathHeavyContent(preprocessed)
        ContentType.MIXED_MATH -> parseMixedMathContent(preprocessed, inTableContext)
        ContentType.SIMPLE_TEXT -> parseSimpleMarkdown(preprocessed, inTableContext)
        ContentType.TABLE -> parseTableContent(preprocessed)
    }
}

/**
 * 内容类型枚举
 */
private enum class ContentType {
    MATH_HEAVY,     // 数学公式为主,使用专业渲染器
    MIXED_MATH,     // 包含数学公式的混合内容
    SIMPLE_TEXT,    // 简单文本,使用原生渲染
    TABLE          // 表格内容
}

/**
 * 🎯 改进的表格内容检测
 */
private fun isTableContent(content: String): Boolean {
    val lines = content.trim().lines()
    if (lines.size < 2) return false
    
    // 检查是否有足够的管道符
    val pipeCount = content.count { it == '|' }
    if (pipeCount < 3) return false
    
    // 查找表格分隔符行(必须包含至少2个 --- 分隔的单元格)
    val separatorRegex = Regex("^\\s*\\|?\\s*:?[-]{2,}:?\\s*(\\|\\s*:?[-]{2,}:?\\s*)+\\|?\\s*$")
    val hasSeparator = lines.any { line -> 
        val trimmed = line.trim()
        separatorRegex.matches(trimmed)
    }
    
    if (!hasSeparator) return false
    
    // 验证是否有表头和数据行
    val tableLines = lines.filter { it.contains("|") }
    if (tableLines.size < 3) return false // 至少需要:表头、分隔符、数据行
    
    android.util.Log.d("MarkdownParser", "✅ 检测到有效表格: ${tableLines.size}行, ${pipeCount}个管道符")
    return true
}

/**
 * 🎯 数学内容预处理（安全直通版）
 * 根因修复：
 * 先前实现会无差别“清空/替换” \commands、$ 定界、上下标等，且未跳过 ``` 代码围栏，
 * 导致含代码块或命令示例的长文在最终阶段被彻底改写，结构混乱。
 * 这里改为“零改写直通”，具体渲染由后续管线与渲染组件处理（前端已有数学/代码/表格分流）。
 */
private fun preprocessMarkdownForMath(markdown: String): String {
    return markdown
}

/**
 * 🎯 智能内容类型检测
 */
private fun detectContentType(content: String): ContentType {
    // 🎯 修复:排除表格中的●等符号,避免误判为数学内容
    val mathSymbols = listOf("∫", "∑", "√", "π", "α", "β", "γ", "δ", "Δ", "σ", "μ", "λ")
    val hasMathSymbols = mathSymbols.any { content.contains(it) }
    val hasComplexMath = content.contains("²") || content.contains("³") || content.contains("½")
    
    // 🎯 改进的表格检测
    val hasTable = isTableContent(content)
    
    return when {
        hasTable -> ContentType.TABLE
        hasMathSymbols || hasComplexMath -> ContentType.MATH_HEAVY
        else -> ContentType.SIMPLE_TEXT
    }
}

/**
 * 🎯 解析数学密集型内容 - 使用专业渲染器
 */
private fun parseMathHeavyContent(content: String): List<MarkdownPart> {
    android.util.Log.d("MarkdownParser", "Parsing math-heavy content with professional renderer")
    
    return listOf(
        MarkdownPart.MathBlock(
            id = "math_${UUID.randomUUID()}",
            content = content,
            renderMode = "professional"
        )
    )
}

/**
 * 🎯 解析混合数学内容
 */
private fun parseMixedMathContent(content: String, inTableContext: Boolean): List<MarkdownPart> {
    android.util.Log.d("MarkdownParser", "Parsing mixed math content")
    
    // 简化处理:直接返回文本部分
    return listOf(
        MarkdownPart.Text(
            id = "text_${UUID.randomUUID()}",
            content = content
        )
    )
}

/**
 * 🎯 解析表格内容 - 完全重写
 */
private fun parseTableContent(content: String): List<MarkdownPart> {
    android.util.Log.d("MarkdownParser", "🎯 Parsing table content")
    android.util.Log.d("MarkdownParser", "Table content: ${content.take(200)}...")
    
    // 返回专用的 Table 分片，交由表格渲染器处理
    return listOf(
        MarkdownPart.Table(
            id = "table_${UUID.randomUUID()}",
            content = content
        )
    )
}

/**
 * 轻量围栏代码解析：将 ```lang\n...\n``` 提取为 CodeBlock，其余为 Text
 */
private fun parseFencedCodeBlocks(content: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    // 更宽松的围栏匹配：允许结尾无换行；兼容 \r\n / \n；语言可空
    // 形态示例：
    // ```lang\nCODE\n```
    // ```\nCODE\n```
    // ```lang\r\nCODE\r\n```
    // ```lang CODE ``` （极端少见，也能匹配）
    val regex = Regex("(?s)```\\s*([a-zA-Z0-9_+\\-#.]*)[ \\t]*\\r?\\n?([\\s\\S]*?)\\r?\\n?```")
    var lastIndex = 0
    val textId = { "text_${UUID.randomUUID()}" }
    val codeId = { "code_${UUID.randomUUID()}" }

    regex.findAll(content).forEach { mr ->
        val range = mr.range
        // 前置普通文本
        if (range.first > lastIndex) {
            val before = content.substring(lastIndex, range.first)
            if (before.isNotBlank()) {
                parts += MarkdownPart.Text(id = textId(), content = before)
            }
        }
        val lang = mr.groups[1]?.value?.trim().orEmpty()
        val code = mr.groups[2]?.value ?: ""
        parts += MarkdownPart.CodeBlock(id = codeId(), content = code, language = if (lang.isBlank()) "" else lang)
        lastIndex = range.last + 1
    }

    // 末尾剩余文本
    if (lastIndex < content.length) {
        val tail = content.substring(lastIndex)
        if (tail.isNotBlank()) {
            parts += MarkdownPart.Text(id = textId(), content = tail)
        }
    }

    // 若无匹配则返回空列表，调用方兜底
    return parts
}

/**
 * 🎯 解析简单文本内容 - 优先识别 Markdown 围栏代码，使前端走自定义 CodePreview 渲染
 */
private fun parseSimpleMarkdown(content: String, inTableContext: Boolean): List<MarkdownPart> {
    android.util.Log.d("MarkdownParser", "Parsing simple markdown with fenced code support")

    // 先尝试提取围栏代码块
    val fenced = parseFencedCodeBlocks(content)
    if (fenced.isNotEmpty()) {
        return fenced
    }

    // 兜底：无围栏时作为纯文本
    return listOf(
        MarkdownPart.Text(
            id = "text_${UUID.randomUUID()}",
            content = content
        )
    )
}

/**
 * 预处理Markdown以兼容Android前端
 */
private fun preprocessMarkdownForAndroid(markdown: String): String {
    if (markdown.isEmpty()) return markdown
    
    return markdown
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .trim()
}
