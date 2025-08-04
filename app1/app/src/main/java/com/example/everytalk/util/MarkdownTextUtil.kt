package com.example.everytalk.util

import androidx.compose.ui.graphics.Color
import java.util.regex.Pattern

// Sealed interface for different types of content blocks
sealed interface ContentBlock {
    data class TextBlock(val content: String) : ContentBlock
    data class MathBlock(val latex: String, val isDisplay: Boolean) : ContentBlock
    data class CodeBlock(val code: String, val language: String?) : ContentBlock
}

fun parseToContentBlocks(markdown: String): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()
    
    // 改进的正则表达式，更好地处理数学公式边界和转义字符
    val pattern = "(?s)" +
            "```([^\\n]*\\n)?(.*?)```" +        // Code blocks - 改进了语言检测
            "|\\$\\$(.*?)\\$\\$" +              // Block Math $$
            "|\\\\\\[(.*?)\\\\\\]" +              // Block Math \[
            "|(?<!\\\\)\\$([^\\$\\n]+?)\\$" +   // Inline Math $ - 避免转义的$
            "|\\\\\\((.*?)\\\\\\)"               // Inline Math \(
    
    val regex = Regex(pattern)
    var lastIndex = 0

    // 预处理：修复破损的LaTeX语法
    var processedMarkdown = markdown
        .replace(Regex("\\\\\\}\\s*-\\s*\\\\\\]"), "") // 修复 \} - \] 错误
        .replace(Regex("\\{(\\d+)\\}\\{(\\d+)\\s*imes\\s*(\\d+)\\}"), "{\$1 \\times \$2 \\times \$3}") // 修复乘法表达式
        .replace(Regex("\\\\frac\\s*([^{])"), "\\\\frac{\$1") // 修复缺失的大括号
        .replace(Regex("([^}])\\}\\s*([^{])\\{"), "\$1}{\$2}") // 修复分数表达式的大括号

    regex.findAll(processedMarkdown).forEach { match ->
        if (match.range.first > lastIndex) {
            val text = processedMarkdown.substring(lastIndex, match.range.first)
            if (text.isNotEmpty()) {
                blocks.add(ContentBlock.TextBlock(text))
            }
        }

        val groups = match.groupValues
        
        when {
            // 代码块检测 - 改进逻辑
            groups.size > 2 && (groups[1].isNotEmpty() || groups[2].isNotEmpty()) -> {
                val language = groups[1].trim().replace("\n", "").ifEmpty { null }
                val code = groups[2]
                blocks.add(ContentBlock.CodeBlock(code, language))
            }
            // 块级数学公式 $$...$$
            groups.size > 3 && groups[3].isNotEmpty() -> {
                val latex = groups[3].trim()
                if (latex.isNotEmpty()) {
                    blocks.add(ContentBlock.MathBlock(latex, isDisplay = true))
                }
            }
            // 块级数学公式 \[...\]
            groups.size > 4 && groups[4].isNotEmpty() -> {
                val latex = groups[4].trim()
                if (latex.isNotEmpty()) {
                    blocks.add(ContentBlock.MathBlock(latex, isDisplay = true))
                }
            }
            // 行内数学公式 $...$
            groups.size > 5 && groups[5].isNotEmpty() -> {
                val latex = groups[5].trim()
                if (latex.isNotEmpty()) {
                    blocks.add(ContentBlock.MathBlock(latex, isDisplay = false))
                }
            }
            // 行内数学公式 \(...\)
            groups.size > 6 && groups[6].isNotEmpty() -> {
                val latex = groups[6].trim()
                if (latex.isNotEmpty()) {
                    blocks.add(ContentBlock.MathBlock(latex, isDisplay = false))
                }
            }
        }
        lastIndex = match.range.last + 1
    }

    if (lastIndex < processedMarkdown.length) {
        val text = processedMarkdown.substring(lastIndex)
        if (text.isNotEmpty()) {
            blocks.add(ContentBlock.TextBlock(text.trim()))
        }
    }

    return blocks
}