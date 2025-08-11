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
    
    // 预处理：修复破损的LaTeX语法
    var processedMarkdown = markdown
        .replace(Regex("\\\\\\}\\s*-\\s*\\\\\\]"), "") // 修复 \} - \] 错误
        .replace(Regex("\\{(\\d+)\\}\\{(\\d+)\\s*imes\\s*(\\d+)\\}"), "{\$1 \\times \$2 \\times \$3}") // 修复乘法表达式
        .replace(Regex("\\\\frac\\s*([^{])"), "\\\\frac{\$1") // 修复缺失的大括号
        .replace(Regex("([^}])\\}\\s*([^{])\\{"), "\$1}{\$2}") // 修复分数表达式的大括号

    var currentIndex = 0
    
    while (currentIndex < processedMarkdown.length) {
        // 检查是否遇到代码块开始标记
        val codeBlockStart = processedMarkdown.indexOf("```", currentIndex)
        
        if (codeBlockStart != -1) {
            // 添加代码块之前的文本
            if (codeBlockStart > currentIndex) {
                val textBefore = processedMarkdown.substring(currentIndex, codeBlockStart)
                val textBlocks = parseNonCodeContent(textBefore)
                blocks.addAll(textBlocks)
            }
            
            // 解析代码块
            val lineEnd = processedMarkdown.indexOf('\n', codeBlockStart)
            val language = if (lineEnd != -1 && lineEnd > codeBlockStart + 3) {
                processedMarkdown.substring(codeBlockStart + 3, lineEnd).trim().ifEmpty { null }
            } else {
                null
            }
            
            val codeStart = if (lineEnd != -1) lineEnd + 1 else codeBlockStart + 3
            
            // 查找代码块结束标记
            val codeBlockEnd = processedMarkdown.indexOf("```", codeStart)
            
            if (codeBlockEnd != -1) {
                // 完整的代码块
                val code = processedMarkdown.substring(codeStart, codeBlockEnd)
                blocks.add(ContentBlock.CodeBlock(code, language))
                currentIndex = codeBlockEnd + 3
            } else {
                // 未完成的代码块 - 实时渲染关键部分
                val code = processedMarkdown.substring(codeStart)
                if (code.isNotEmpty()) {
                    blocks.add(ContentBlock.CodeBlock(code, language))
                }
                currentIndex = processedMarkdown.length
            }
        } else {
            // 没有更多代码块，处理剩余的文本和数学公式
            val remainingText = processedMarkdown.substring(currentIndex)
            if (remainingText.isNotEmpty()) {
                val textBlocks = parseNonCodeContent(remainingText)
                blocks.addAll(textBlocks)
            }
            break
        }
    }
    
    return blocks
}

/**
 * 解析不包含代码块的内容（文本和数学公式）
 */
private fun parseNonCodeContent(text: String): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()
    
    // 数学公式的正则表达式
    val mathPattern = "(?s)" +
            "\\$\\$(.*?)\\$\\$" +              // Block Math $$
            "|\\\\\\[(.*?)\\\\\\]" +              // Block Math \[
            "|(?<!\\\\)\\$([^\\$\\n]+?)\\$" +   // Inline Math $ - 避免转义的$
            "|\\\\\\((.*?)\\\\\\)"               // Inline Math \(
    
    val regex = Regex(mathPattern)
    var lastIndex = 0
    
    regex.findAll(text).forEach { match ->
        // 添加数学公式之前的文本
        if (match.range.first > lastIndex) {
            val textContent = text.substring(lastIndex, match.range.first)
            if (textContent.isNotEmpty()) {
                blocks.add(ContentBlock.TextBlock(textContent))
            }
        }
        
        val groups = match.groupValues
        
        when {
            // 块级数学公式 $$...$$
            groups.size > 1 && groups[1].isNotEmpty() -> {
                val latex = groups[1].trim()
                if (latex.isNotEmpty()) {
                    blocks.add(ContentBlock.MathBlock(latex, isDisplay = true))
                }
            }
            // 块级数学公式 \[...\]
            groups.size > 2 && groups[2].isNotEmpty() -> {
                val latex = groups[2].trim()
                if (latex.isNotEmpty()) {
                    blocks.add(ContentBlock.MathBlock(latex, isDisplay = true))
                }
            }
            // 行内数学公式 $...$
            groups.size > 3 && groups[3].isNotEmpty() -> {
                val latex = groups[3].trim()
                if (latex.isNotEmpty()) {
                    blocks.add(ContentBlock.MathBlock(latex, isDisplay = false))
                }
            }
            // 行内数学公式 \(...\)
            groups.size > 4 && groups[4].isNotEmpty() -> {
                val latex = groups[4].trim()
                if (latex.isNotEmpty()) {
                    blocks.add(ContentBlock.MathBlock(latex, isDisplay = false))
                }
            }
        }
        lastIndex = match.range.last + 1
    }
    
    // 添加剩余的文本
    if (lastIndex < text.length) {
        val remainingText = text.substring(lastIndex)
        if (remainingText.isNotEmpty()) {
            blocks.add(ContentBlock.TextBlock(remainingText))
        }
    }
    
    return blocks
}