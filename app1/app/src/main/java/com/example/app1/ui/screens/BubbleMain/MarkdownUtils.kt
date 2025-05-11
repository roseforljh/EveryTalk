package com.example.app1.ui.screens.BubbleMain // 替换为你的实际包名基础 + .util.markdown

import android.util.Log

// --- 文本段定义 (保持不变) ---
sealed class TextSegment {
    data class Normal(val text: String) : TextSegment()
    data class CodeBlock(val language: String?, val code: String) : TextSegment()
}

// --- 预编译的正则表达式 ---
// GFM风格的闭合代码块正则表达式：捕获语言提示和代码内容
private val GFM_CLOSED_CODE_BLOCK_REGEX =
    Regex("```([a-zA-Z0-9_.-]*)[ \t]*\\n([\\s\\S]*?)\\n```")

// 匹配代码块开始标记的正则表达式，用于处理剩余文本中的未闭合块
private val CODE_BLOCK_START_REGEX = Regex("```")

/**
 * 优化版 Markdown 解析函数。
 * 将输入的Markdown字符串分割成TextSegment列表。
 * 它能识别GFM风格的闭合代码块 (```lang\ncode\n```) 和潜在的未闭合代码块。
 */
fun parseMarkdownSegments(markdownInput: String): List<TextSegment> {
    if (markdownInput.isBlank()) { // 处理空或纯空白输入
        return emptyList()
    }

    val segments = mutableListOf<TextSegment>()
    var currentIndex = 0

    Log.d(
        "ParseMarkdownOpt",
        "Input (len ${markdownInput.length}):\nSTART_MD\n${
            markdownInput.take(200).replace("\n", "\\n")
        }...\nEND_MD"
    )

    var matchResult = GFM_CLOSED_CODE_BLOCK_REGEX.find(markdownInput, currentIndex)

    while (matchResult != null) {
        val matchStart = matchResult.range.first
        val matchEnd = matchResult.range.last

        Log.d(
            "ParseMarkdownOpt",
            "Found closed block. Range: ${matchResult.range}. CurrentIndex before: $currentIndex"
        )

        // 1. 处理闭合代码块之前的普通文本
        if (matchStart > currentIndex) {
            val normalText = markdownInput.substring(currentIndex, matchStart)
            // 只添加非纯空白的普通文本段
            if (normalText.isNotBlank()) {
                segments.add(TextSegment.Normal(normalText.trim()))
                Log.d(
                    "ParseMarkdownOpt",
                    "Added Normal (before closed): '${
                        normalText.trim().take(50).replace("\n", "\\n")
                    }'"
                )
            }
        }

        // 2. 处理闭合代码块
        val language = matchResult.groups[1]?.value?.trim()?.takeIf { it.isNotEmpty() }
        val code = matchResult.groups[2]?.value ?: "" // 代码内容不需要trim，保持原始缩进和换行
        segments.add(TextSegment.CodeBlock(language, code))
        Log.d(
            "ParseMarkdownOpt",
            "Added Closed CodeBlock: Lang='$language', Code='${code.take(50).replace("\n", "\\n")}'"
        )

        currentIndex = matchEnd + 1
        matchResult = GFM_CLOSED_CODE_BLOCK_REGEX.find(markdownInput, currentIndex)
    }

    Log.d(
        "ParseMarkdownOpt",
        "After closed block loop. CurrentIndex: $currentIndex, MarkdownLength: ${markdownInput.length}"
    )

    // 3. 处理最后一个闭合代码块之后剩余的文本
    if (currentIndex < markdownInput.length) {
        val remainingText = markdownInput.substring(currentIndex)
        Log.d(
            "ParseMarkdownOpt",
            "Remaining text (len ${remainingText.length}): '${
                remainingText.take(100).replace("\n", "\\n")
            }'"
        )

        // 尝试在剩余文本中查找未闭合的代码块开始标记
        val openBlockMatch = CODE_BLOCK_START_REGEX.find(remainingText)
        if (openBlockMatch != null) {
            val openBlockStartInRemaining = openBlockMatch.range.first

            // a. "```" 之前的部分是普通文本
            if (openBlockStartInRemaining > 0) {
                val normalPrefix = remainingText.substring(0, openBlockStartInRemaining)
                if (normalPrefix.isNotBlank()) {
                    segments.add(TextSegment.Normal(normalPrefix.trim()))
                    Log.d(
                        "ParseMarkdownOpt",
                        "Added Normal (prefix to open block): '${
                            normalPrefix.trim().take(50).replace("\n", "\\n")
                        }'"
                    )
                }
            }

            // b. "```" 之后的部分被视为未闭合代码块的内容
            val codeBlockCandidate =
                remainingText.substring(openBlockStartInRemaining + 3) // 跳过 "```"
            // 尝试提取语言提示
            val firstNewlineIndex = codeBlockCandidate.indexOf('\n')
            var lang: String? = null
            var codeContent: String

            if (firstNewlineIndex != -1) { // 如果 "```lang" 后面有换行
                val langLine = codeBlockCandidate.substring(0, firstNewlineIndex).trim()
                // 验证语言提示（允许字母、数字、下划线、点、中横线，以及常见的+号如c++）
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = codeBlockCandidate.substring(firstNewlineIndex + 1)
            } else { // "```lang" 后面没有换行，整行都可能是语言提示，代码内容为空或后续行为
                val langLine = codeBlockCandidate.trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                // 如果没有换行，我们假设代码内容从下一行开始（如果存在），
                // 或者如果这就是输入的末尾，则代码内容为空。
                // 实际上，对于流式输入，这部分会持续增长。
                // 对于一次性解析，如果后面没有内容，codeContent 就是空。
                codeContent = "" // 假设对于未闭合且```后无换行的情况，初始代码为空
            }
            segments.add(TextSegment.CodeBlock(lang, codeContent)) // 代码内容不需要trim
            Log.d(
                "ParseMarkdownOpt",
                "Added Open CodeBlock from remaining: Lang='$lang', CodePreview='${
                    codeContent.take(50).replace("\n", "\\n")
                }'"
            )

        } else { // 剩余文本中没有 "```"，全部是普通文本
            if (remainingText.isNotBlank()) {
                segments.add(TextSegment.Normal(remainingText.trim()))
                Log.d(
                    "ParseMarkdownOpt",
                    "Remaining text is Normal: '${
                        remainingText.trim().take(50).replace("\n", "\\n")
                    }'"
                )
            }
        }
    }

    // 特殊情况：如果解析后没有段落，但输入本身不为空且不是纯空白
    // （这个逻辑已大部分被上面的处理覆盖，但作为最后防线）
    if (segments.isEmpty() && markdownInput.isNotBlank()) {
        Log.w(
            "ParseMarkdownOpt",
            "Segments empty, but markdown not blank. Markdown starts with '```': ${
                markdownInput.startsWith("```")
            }"
        )
        // 这种情况通常意味着整个输入是一个未闭合的代码块，且没有前导文本
        // 或者整个输入就是一段普通文本
        if (markdownInput.startsWith("```")) {
            val codeBlockCandidate = markdownInput.substring(3)
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
                "ParseMarkdownOpt",
                "Added Entire input as Open CodeBlock: Lang='$lang', CodePreview='${
                    codeContent.take(50).replace("\n", "\\n")
                }'"
            )
        } else {
            segments.add(TextSegment.Normal(markdownInput.trim()))
            Log.d(
                "ParseMarkdownOpt",
                "Entire input is Normal (no segments and not starting with ```)."
            )
        }
    }

    Log.i(
        "ParseMarkdownOpt",
        "Final segments count: ${segments.size}, Types: ${segments.map { it::class.simpleName }}"
    )
    return segments
}

// extractStreamingCodeContent 函数保持不变，因为它主要用于流式场景，逻辑相对独立。
// 如果需要，也可以对其语言提示验证做类似调整。
fun extractStreamingCodeContent(textAlreadyTrimmedAndStartsWithTripleQuote: String): Pair<String?, String> {
    // ... (保持你原来的实现，或根据需要应用类似的语言提示词验证逻辑) ...
    Log.d(
        "ExtractStreamCode",
        "Input for extraction: \"${
            textAlreadyTrimmedAndStartsWithTripleQuote.take(30).replace("\n", "\\n")
        }\""
    )
    val contentAfterTripleTicks = textAlreadyTrimmedAndStartsWithTripleQuote.substring(3)
    val firstNewlineIndex = contentAfterTripleTicks.indexOf('\n')

    if (firstNewlineIndex != -1) {
        val langHint = contentAfterTripleTicks.substring(0, firstNewlineIndex).trim()
        val code = contentAfterTripleTicks.substring(firstNewlineIndex + 1)
        val validatedLangHint =
            if (langHint.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) { // 调整验证
                langHint.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return Pair(validatedLangHint, code)
    } else {
        val langLine = contentAfterTripleTicks.trim()
        val validatedLangHint =
            if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) { // 调整验证
                langLine.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return Pair(validatedLangHint, "")
    }
}