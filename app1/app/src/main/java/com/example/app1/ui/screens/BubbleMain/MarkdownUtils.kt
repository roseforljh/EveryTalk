package com.example.app1.ui.screens.BubbleMain // 替换为你的实际包名基础 + .util.markdown

import android.util.Log

// --- 文本段定义 ---
// 用于将Markdown文本分割成普通文本段和代码块文本段
sealed class TextSegment {
    data class Normal(val text: String) : TextSegment() // 普通文本段
    data class CodeBlock(val language: String?, val code: String) : TextSegment() // 代码块段
}

// MODIFIED & IMPROVED: 用于Markdown解析，现在会更健壮地处理未闭合的代码块，包括前导普通文本
// 此函数将输入的Markdown字符串分割成TextSegment列表。
// 它能识别GFM风格的闭合代码块 (```lang\ncode\n```) 和潜在的未闭合代码块。
// 注意：此解析器不处理或渲染复杂的数学公式 (如LaTeX)，数学公式将被视为普通文本。
fun parseMarkdownSegments(markdownInput: String): List<TextSegment> {
    val markdown = markdownInput
    val segments = mutableListOf<TextSegment>()
    // GFM风格的闭合代码块正则表达式：捕获语言提示和代码内容
    val GFM_CLOSED_CODE_BLOCK_PATTERN = "```([a-zA-Z0-9_.-]*)\\n([\\s\\S]*?)\\n```"
    val closedBlockRegex = Regex(GFM_CLOSED_CODE_BLOCK_PATTERN)
    var currentIndex = 0 // 当前在Markdown字符串中的解析位置

    Log.d(
        "ParseMarkdown_Debug",
        "Input (len ${markdown.length}):\nSTART_MD\n${
            markdown.take(500).replace("\n", "\\n")
        }\nEND_MD"
    )

    var matchResult = closedBlockRegex.find(markdown, currentIndex)
    Log.d("ParseMarkdown_Debug", "Initial match: ${matchResult?.value?.take(50)}")

    // 循环查找并处理所有闭合的代码块
    while (matchResult != null) {
        Log.d(
            "ParseMarkdown_Debug",
            "Found closed block. Range: ${matchResult.range}. CurrentIndex before: $currentIndex"
        )
        // 如果代码块之前有普通文本，则添加为NormalSegment
        if (matchResult.range.first > currentIndex) {
            val normalText = markdown.substring(currentIndex, matchResult.range.first)
            if (normalText.isNotBlank()) {
                segments.add(TextSegment.Normal(normalText.trim()))
                Log.d(
                    "ParseMarkdown_Debug",
                    "Added Normal (before closed): '${normalText.trim().take(50)}'"
                )
            }
        }

        // 提取语言和代码内容，添加为CodeBlockSegment
        val language = matchResult.groups[1]?.value?.takeIf { it.isNotBlank() }
        val code = matchResult.groups[2]?.value ?: ""
        segments.add(TextSegment.CodeBlock(language, code.trim()))
        Log.d(
            "ParseMarkdown_Debug",
            "Added Closed CodeBlock: Lang='$language', Code='${code.trim().take(50)}'"
        )

        currentIndex = matchResult.range.last + 1 // 更新解析位置到当前代码块之后
        Log.d("ParseMarkdown_Debug", "CurrentIndex after closed block: $currentIndex")
        matchResult = closedBlockRegex.find(markdown, currentIndex) // 查找下一个代码块
        Log.d("ParseMarkdown_Debug", "Next match: ${matchResult?.value?.take(50)}")
    }

    Log.d(
        "ParseMarkdown_Debug",
        "After closed block loop. CurrentIndex: $currentIndex, MarkdownLength: ${markdown.length}"
    )
    // 处理最后一个闭合代码块之后剩余的文本
    if (currentIndex < markdown.length) {
        val remainingText = markdown.substring(currentIndex)
        Log.d(
            "ParseMarkdown_Debug",
            "Remaining text (len ${remainingText.length}): '${
                remainingText.take(100).replace("\n", "\\n")
            }'"
        )
        if (remainingText.isBlank()) {
            Log.d("ParseMarkdown_Debug", "Remaining text is blank.")
        } else {
            // 检查剩余文本中是否包含 "```"，可能是一个未闭合的代码块的开始
            val firstTripleTickInRemaining = remainingText.indexOf("```")
            if (firstTripleTickInRemaining != -1) {
                Log.d(
                    "ParseMarkdown_Debug",
                    "Found '```' in remaining at index $firstTripleTickInRemaining."
                )
                // "```" 之前的部分是普通文本
                if (firstTripleTickInRemaining > 0) {
                    val normalPrefix = remainingText.substring(0, firstTripleTickInRemaining)
                    if (normalPrefix.isNotBlank()) {
                        segments.add(TextSegment.Normal(normalPrefix.trim()))
                        Log.d(
                            "ParseMarkdown_Debug",
                            "Added Normal (prefix to open block): '${normalPrefix.trim().take(50)}'"
                        )
                    }
                }
                // "```" 之后的部分被视为未闭合代码块的内容
                val codeBlockCandidate = remainingText.substring(firstTripleTickInRemaining)
                val contentAfterTripleTicks = codeBlockCandidate.substring(3)
                val firstNewlineIndex = contentAfterTripleTicks.indexOf('\n')
                var lang: String? = null
                var codeContent: String

                if (firstNewlineIndex != -1) { // 如果 "```" 后面有换行
                    val langLine = contentAfterTripleTicks.substring(0, firstNewlineIndex).trim()
                    // 简单的语言提示词验证：只允许字母、数字、下划线、点、中横线
                    if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }) {
                        lang = langLine.takeIf { it.isNotBlank() }
                    }
                    codeContent = contentAfterTripleTicks.substring(firstNewlineIndex + 1)
                } else { // "```" 后面没有换行，整行都可能是语言提示
                    val langLine = contentAfterTripleTicks.trim()
                    if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }) {
                        lang = langLine.takeIf { it.isNotBlank() }
                    }
                    codeContent = "" // 没有代码内容，只有语言提示
                }
                segments.add(TextSegment.CodeBlock(lang, codeContent))
                Log.d(
                    "ParseMarkdown_Debug",
                    "Added Open CodeBlock from remaining: Lang='$lang', CodePreview='${
                        codeContent.take(
                            50
                        )
                    }'"
                )
            } else { // 剩余文本中没有 "```"，全部是普通文本
                if (remainingText.isNotBlank()) {
                    segments.add(TextSegment.Normal(remainingText.trim()))
                    Log.d(
                        "ParseMarkdown_Debug",
                        "Remaining text is Normal: '${remainingText.trim().take(50)}'"
                    )
                }
            }
        }
    }

    // 特殊情况：如果解析后没有段落，但输入不为空
    if (segments.isEmpty() && markdown.isNotBlank()) {
        Log.d(
            "ParseMarkdown_Debug",
            "Segments empty, markdown not empty. Checking if starts with ```."
        )
        if (markdown.startsWith("```")) { // 整个输入可能是一个未闭合的代码块
            Log.d("ParseMarkdown_Debug", "Entire input is an open code block.")
            val contentAfterTripleTicks = markdown.substring(3)
            val firstNewlineIndex = contentAfterTripleTicks.indexOf('\n')
            var lang: String? = null
            var codeContent: String

            if (firstNewlineIndex != -1) {
                val langLine = contentAfterTripleTicks.substring(0, firstNewlineIndex).trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }) {
                    lang = langLine.takeIf { it.isNotBlank() }
                }
                codeContent = contentAfterTripleTicks.substring(firstNewlineIndex + 1)
            } else {
                val langLine = contentAfterTripleTicks.trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }) {
                    lang = langLine.takeIf { it.isNotBlank() }
                }
                codeContent = ""
            }
            segments.add(TextSegment.CodeBlock(lang, codeContent))
            Log.d(
                "ParseMarkdown_Debug",
                "Added Entire input as Open CodeBlock: Lang='$lang', CodePreview='${
                    codeContent.take(
                        50
                    )
                }'"
            )
        } else { // 整个输入是普通文本
            segments.add(TextSegment.Normal(markdown.trim()))
            Log.d(
                "ParseMarkdown_Debug",
                "Entire input is Normal text (no segments and not starting with ```)."
            )
        }
    }
    Log.d(
        "ParseMarkdown_Debug",
        "Final segments count: ${segments.size}, Types: ${segments.map { it::class.simpleName }}"
    )
    return segments
}


/**
 * 从以 "```" 开头的、已经修剪过的文本中提取流式代码块的语言提示和内容。
 * @param textAlreadyTrimmedAndStartsWithTripleQuote 确保传入的字符串是 `.trimStart().startsWith("```")` 为true的。
 * @return Pair<语言提示?, 代码内容>
 */
fun extractStreamingCodeContent(textAlreadyTrimmedAndStartsWithTripleQuote: String): Pair<String?, String> {
    Log.d(
        "ExtractStreamCode",
        "Input for extraction: \"${
            textAlreadyTrimmedAndStartsWithTripleQuote.take(30).replace("\n", "\\n")
        }\""
    )
    // 跳过 "```"
    val contentAfterTripleTicks = textAlreadyTrimmedAndStartsWithTripleQuote.substring(3)
    val firstNewlineIndex = contentAfterTripleTicks.indexOf('\n')

    if (firstNewlineIndex != -1) { // "```" 后的第一行是语言提示
        val langHint = contentAfterTripleTicks.substring(0, firstNewlineIndex).trim()
        val code = contentAfterTripleTicks.substring(firstNewlineIndex + 1)
        // 简单的语言提示词验证
        val validatedLangHint =
            if (langHint.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }) {
                langHint.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return Pair(validatedLangHint, code)
    } else { // "```" 后面没有换行，则整行可能是语言提示，代码内容为空
        val langLine = contentAfterTripleTicks.trim()
        val validatedLangHint =
            if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }) {
                langLine.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return Pair(validatedLangHint, "") // 代码内容为空
    }
}