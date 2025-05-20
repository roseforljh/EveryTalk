package com.example.everytalk.util.markdown

import android.util.Log

//强预处理 + 经典正则分段 + 兜底判断
// 类型定义
sealed class TextSegment {
    data class Normal(val text: String) : TextSegment()
    data class CodeBlock(val language: String?, val code: String) : TextSegment()
}

private val GFM_CLOSED_CODE_BLOCK_REGEX =
    Regex("```([\\w.+-]*)[ \\t]*\\n([\\s\\S]*?)\\n[ \\t]*```")
private val CODE_BLOCK_START_REGEX = Regex("```")

/**
 * 强力兜底版 Markdown 片段解析函数
 * - 能预处理AI各种奇葩输出（闭合/未闭合/代码块+描述粘连）
 * - 输出TextSegment切分段落，适合UI逐段渲染
 * - 全流程状态日志，方便调试和追踪实际分割效果
 */
fun parseMarkdownSegments(markdownInput: String): List<TextSegment> {
    if (markdownInput.isBlank()) return emptyList()

    // --- Step 1: 预处理异常markdown（AI标记/描述/粘连全面修复） ---
    var fixedMd = markdownInput
        // 让```后不是换行的，都自动换行（如```python代码 会变 ```\npython代码）
        .replace(Regex("(```)([^\n])"), "$1\n$2")
        // 让正文or其它后面连着```的结尾，也断开
        .replace(Regex("([^\n])(```)"), "$1\n$2")
        // 确保所有```都是独立一行（兜底兜底！）
        .replace(Regex("[ \\t]*```[ \\t]*"), "\n```\n")
        // 若```描述、```python输出效果这类形式也都分行
        .replace(Regex("(```)[ \t]*([^\n ])([^`\n]*)"), "$1\n$2$3")
        // 清空无意义的缩进空行
        .replace(Regex("(?m)^[ \\t]+\n"), "\n")

    Log.d(
        "MarkdownParser",
        "自动修正Markdown预览（前200字）：${fixedMd.take(200).replace("\n", "\\n")}"
    )

    val segments = mutableListOf<TextSegment>()
    var currentIndex = 0
    var segmentNum = 1

    // --- Step 2: 按标准GFM闭合代码块正则分割 ---
    var matchResult = GFM_CLOSED_CODE_BLOCK_REGEX.find(fixedMd, currentIndex)
    while (matchResult != null) {
        val matchStart = matchResult.range.first
        val matchEnd = matchResult.range.last

        // 普通文本
        if (matchStart > currentIndex) {
            val normalText = fixedMd.substring(currentIndex, matchStart)
            if (normalText.isNotBlank()) {
                segments.add(TextSegment.Normal(normalText.trim()))
                Log.d("MarkdownParser", "【$segmentNum】普通文本片段 len=${normalText.trim().length}")
                segmentNum++
            }
        }
        // 代码块
        val language = matchResult.groups[1]?.value?.trim()?.takeIf { it.isNotEmpty() }
        val code = matchResult.groups[2]?.value ?: ""
        segments.add(TextSegment.CodeBlock(language, code))
        Log.d(
            "MarkdownParser",
            "【$segmentNum】代码块(闭合) 语言=${language ?: "<无>"} len=${code.length}"
        )
        segmentNum++

        currentIndex = matchEnd + 1
        matchResult = GFM_CLOSED_CODE_BLOCK_REGEX.find(fixedMd, currentIndex)
    }

    // --- Step 3: 处理末尾未闭合/残留代码块/尾文本 ---
    if (currentIndex < fixedMd.length) {
        val remainingText = fixedMd.substring(currentIndex)
        Log.d("MarkdownParser", "剩余待分析: ${remainingText.take(80).replace("\n", "\\n")}")
        val openBlockMatch = CODE_BLOCK_START_REGEX.find(remainingText)
        if (openBlockMatch != null) {
            // 前面的普通文本
            val openBlockStartInRemaining = openBlockMatch.range.first
            if (openBlockStartInRemaining > 0) {
                val normalPrefix = remainingText.substring(0, openBlockStartInRemaining)
                if (normalPrefix.isNotBlank()) {
                    segments.add(TextSegment.Normal(normalPrefix.trim()))
                    Log.d(
                        "MarkdownParser",
                        "【$segmentNum】普通文本片段(未闭合前) len=${normalPrefix.trim().length}"
                    )
                    segmentNum++
                }
            }
            // 后半截未闭合代码块
            val codeBlockCandidate = remainingText.substring(openBlockStartInRemaining + 3)
            val firstNewlineIndex = codeBlockCandidate.indexOf('\n')
            var lang: String? = null
            var codeContent: String
            if (firstNewlineIndex != -1) {
                val langLine = codeBlockCandidate.substring(0, firstNewlineIndex).trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = codeBlockCandidate.substring(firstNewlineIndex + 1)
            } else {
                val langLine = codeBlockCandidate.trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = ""
            }
            segments.add(TextSegment.CodeBlock(lang, codeContent))
            Log.d(
                "MarkdownParser",
                "【$segmentNum】未闭合代码块 语言=${lang ?: "<无>"} len=${codeContent.length}"
            )
            segmentNum++
        } else if (remainingText.isNotBlank()) {
            // 普通文本片段
            segments.add(TextSegment.Normal(remainingText.trim()))
            Log.d(
                "MarkdownParser",
                "【$segmentNum】尾普通文本片段 len=${remainingText.trim().length}"
            )
            segmentNum++
        }
    }

    // --- Step 4: 万一全被吃没了兜底一刀 ---
    if (segments.isEmpty() && fixedMd.isNotBlank()) {
        if (fixedMd.startsWith("```")) {
            val codeBlockCandidate = fixedMd.substring(3)
            val firstNewlineIndex = codeBlockCandidate.indexOf('\n')
            var lang: String? = null
            var codeContent: String
            if (firstNewlineIndex != -1) {
                val langLine = codeBlockCandidate.substring(0, firstNewlineIndex).trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = codeBlockCandidate.substring(firstNewlineIndex + 1)
            } else {
                val langLine = codeBlockCandidate.trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = ""
            }
            segments.add(TextSegment.CodeBlock(lang, codeContent))
            Log.d(
                "MarkdownParser",
                "【兜底】全局未闭合代码块 语言=${lang ?: "<无>"} len=${codeContent.length}"
            )
        } else {
            segments.add(TextSegment.Normal(fixedMd.trim()))
            Log.d(
                "MarkdownParser",
                "【兜底】全局普通文本 len=${fixedMd.trim().length}"
            )
        }
    }

    Log.i(
        "MarkdownParser",
        "分段完毕，最终共${segments.size}段。类型简表: ${
            segments.map { it::class.simpleName }
        }"
    )
    return segments
}

/**
 * 辅助函数：解析"AI流式未闭合代码块"（可选；UI流显示一般不用）
 * Gemini/ChatGLM等模型流返回时容易 `xxx\n```python\ndef ...` 后没闭合
 * 本函数给你解析出来后处理样式(TableView分割/代码高亮专用)
 * @param streamingCode 匹配到的未闭合代码块全部内容
 * @return Pair<程序语言名, 代码内容>
 */
fun extractStreamingCodeContent(streamingCode: String): Pair<String?, String> {
    var lang: String? = null
    var codeBody = ""
    val firstNewLine = streamingCode.indexOf('\n')
    if (firstNewLine != -1) {
        val langLine = streamingCode.substring(0, firstNewLine).trim()
        // 判断是否可能为纯语言声明，不是内容
        if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
            lang = langLine.takeIf { it.isNotEmpty() }
            codeBody = streamingCode.substring(firstNewLine + 1)
        } else {
            // 直接全是代码内容
            codeBody = streamingCode
        }
    } else {
        lang = streamingCode.trim().takeIf { it.isNotEmpty() }
    }
    Log.d(
        "MarkdownParser",
        "extractStreamingCodeContent: language=${lang ?: "<无>"} code.len=${codeBody.length}"
    )
    return lang to codeBody
}
