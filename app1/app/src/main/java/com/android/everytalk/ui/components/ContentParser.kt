package com.android.everytalk.ui.components

import com.android.everytalk.data.DataClass.ContentPart as DataContentPart
import com.android.everytalk.ui.components.ContentPart
import android.util.Log

/**
 * 负责将 Markdown 文本解析为结构化的 ContentPart 列表。
 * 核心逻辑：将纯文本、代码块、表格等不同内容拆分开，以便 UI 层使用最合适的组件进行渲染。
 */
object ContentParser {
    private const val TAG = "ContentParser"

    /**
     * 将原始 Markdown 文本解析为 ContentPart 列表。
     *
     * @param text 原始 Markdown 文本
     * @param isStreaming 是否处于流式传输模式（用于优化解析策略）
     * @return 解析后的 ContentPart 列表
     */
    /**
     * 将原始 Markdown 文本解析为 ContentPart 列表。
     *
     * @param text 原始 Markdown 文本
     * @param isStreaming 是否处于流式传输模式（用于优化解析策略）
     * @return 解析后的 ContentPart 列表
     */
    fun parseCompleteContent(text: String, isStreaming: Boolean = false): List<ContentPart> {
        val parts = mutableListOf<ContentPart>()
        if (text.isEmpty()) {
            return parts
        }

        val lines = text.lines()
        var i = 0
        var lastTextStart = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmedLine = line.trimStart() // 仅移除开头的空白，保留缩进结构可能更安全，但 markdown 块级元素对缩进敏感

            // 1. 检测代码块起始 ```
            if (trimmedLine.startsWith("```")) {
                // 如果有之前的文本，先添加
                if (i > lastTextStart) {
                    // 注意：这里需要保留换行符，否则多行文本会变成一行
                    // 并且，如果文本末尾有空行，也应该保留，以保持布局一致
                    val textContent = lines.subList(lastTextStart, i).joinToString("\n")
                    if (textContent.isNotEmpty()) {
                        parts.add(ContentPart.Text(textContent))
                    }
                }

                // 提取语言
                var language: String? = trimmedLine.removePrefix("```").trim()
                if (language.isNullOrEmpty()) language = null

                // 寻找代码块结束
                val codeStart = i + 1
                var codeEnd = -1
                
                // 向下寻找闭合的 ```
                for (j in codeStart until lines.size) {
                    if (lines[j].trimStart().startsWith("```")) {
                        codeEnd = j
                        break
                    }
                }

                if (codeEnd != -1) {
                    // 找到完整闭合的代码块
                    val codeContent = lines.subList(codeStart, codeEnd).joinToString("\n")
                    parts.add(ContentPart.Code(language, codeContent))
                    i = codeEnd + 1
                    lastTextStart = i
                    continue
                } else {
                    // 未找到闭合
                    // 策略：无论是流式还是非流式，只要是以 ``` 开头，都优先解析为代码块
                    // 这样可以避免流式过程中代码块先显示为文本，等闭合符出现后突然变成代码块的跳变
                    // 同时也能容忍生成不完整的情况
                    val codeContent = lines.subList(codeStart, lines.size).joinToString("\n")
                    parts.add(ContentPart.Code(language, codeContent))
                    
                    // 标记已处理到末尾
                    i = lines.size
                    lastTextStart = i
                    break
                }
            }
            
            // 2. 检测数学公式块 $$ (暂略，可复用 MarkdownRenderer)
            // 3. 检测表格 (暂略，可复用 MarkdownRenderer 或增加 TablePart)

            i++
        }

        // 添加剩余的文本
        if (lastTextStart < lines.size) {
            val textContent = lines.subList(lastTextStart, lines.size).joinToString("\n")
            if (textContent.isNotEmpty()) {
                parts.add(ContentPart.Text(textContent))
            }
        }

        return parts
    }
}

/**
 * UI 层使用的密封类，与 Data 层区分
 */
sealed class ContentPart {
    data class Text(val content: String) : ContentPart()
    data class Code(val language: String?, val content: String) : ContentPart()
    data class Table(val lines: List<String>) : ContentPart()
    data class Math(val content: String) : ContentPart()
}