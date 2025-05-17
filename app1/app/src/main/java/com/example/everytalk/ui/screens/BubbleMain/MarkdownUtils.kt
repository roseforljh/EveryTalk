package com.example.everytalk.ui.screens.BubbleMain // 请将此行替换为您的实际包名基础 + .util.markdown

import android.util.Log

// --- 文本片段数据类定义 ---
sealed class TextSegment { // 密封类，表示文本可以分割成的不同类型片段
    data class Normal(val text: String) : TextSegment() // 普通文本片段
    data class CodeBlock(val language: String?, val code: String) : TextSegment() // 代码块片段，包含语言和代码
}

// --- 预编译的正则表达式 ---
// GFM (GitHub Flavored Markdown) 风格的闭合代码块正则表达式：
// 捕获语言提示（可选）和代码块内容。
// `\\w` 包含 [a-zA-Z0-9_]，已额外添加 `.+`
private val GFM_CLOSED_CODE_BLOCK_REGEX =
    Regex("```([\\w.+-]*)[ \t]*\\n([\\s\\S]*?)\\n[ \t]*```")

// 匹配代码块开始标记 "```" 的正则表达式，用于处理剩余文本中可能的未闭合代码块
private val CODE_BLOCK_START_REGEX = Regex("```")

/**
 * 优化版的 Markdown 解析函数。
 * 将输入的Markdown字符串分割成 TextSegment 对象列表。
 * 它能识别GFM风格的闭合代码块以及在文本末尾可能存在的未闭合代码块。
 */
fun parseMarkdownSegments(markdownInput: String): List<TextSegment> {
    if (markdownInput.isBlank()) { // 如果输入为空或仅包含空白，返回空列表
        return emptyList()
    }

    val segments = mutableListOf<TextSegment>() // 存储解析出的片段
    var currentIndex = 0 // 当前处理位置

    Log.d(
        "ParseMarkdownOpt",
        "输入 (长度 ${markdownInput.length}):\nSTART_MD\n${
            markdownInput.take(200).replace("\n", "\\n") // 日志：预览输入文本
        }...\nEND_MD"
    )

    // 查找第一个GFM风格的闭合代码块
    var matchResult = GFM_CLOSED_CODE_BLOCK_REGEX.find(markdownInput, currentIndex)

    while (matchResult != null) { // 循环处理找到的闭合代码块
        val matchStart = matchResult.range.first // 匹配起始索引
        val matchEnd = matchResult.range.last   // 匹配结束索引

        Log.d(
            "ParseMarkdownOpt",
            "找到闭合代码块. 范围: ${matchResult.range}. 当前索引(之前): $currentIndex"
        )

        // 1. 处理闭合代码块之前的普通文本
        if (matchStart > currentIndex) {
            val normalText = markdownInput.substring(currentIndex, matchStart)
            if (normalText.isNotBlank()) { // 仅添加非纯空白的普通文本
                segments.add(TextSegment.Normal(normalText.trim()))
                Log.d(
                    "ParseMarkdownOpt",
                    "添加普通文本 (闭合块之前): '${
                        normalText.trim().take(50).replace("\n", "\\n") // 日志：记录普通文本
                    }'"
                )
            }
        }

        // 2. 处理当前找到的闭合代码块
        val language = matchResult.groups[1]?.value?.trim()
            ?.takeIf { it.isNotEmpty() } // 提取语言提示，去除空白，非空则使用
        val code = matchResult.groups[2]?.value ?: "" // 提取代码内容，保持原始格式
        segments.add(TextSegment.CodeBlock(language, code))
        Log.d(
            "ParseMarkdownOpt",
            "添加闭合代码块: 语言='$language', 代码='${
                code.take(50).replace("\n", "\\n") // 日志：记录代码块
            }'"
        )

        currentIndex = matchEnd + 1 // 更新当前处理位置
        matchResult = GFM_CLOSED_CODE_BLOCK_REGEX.find(markdownInput, currentIndex) // 继续查找
    }

    Log.d(
        "ParseMarkdownOpt",
        "闭合代码块循环结束. 当前索引: $currentIndex, Markdown长度: ${markdownInput.length}"
    )

    // 3. 处理最后一个闭合代码块之后剩余的文本
    if (currentIndex < markdownInput.length) {
        val remainingText = markdownInput.substring(currentIndex) // 获取剩余文本
        Log.d(
            "ParseMarkdownOpt",
            "剩余文本 (长度 ${remainingText.length}): '${
                remainingText.take(100).replace("\n", "\\n") // 日志：记录剩余文本
            }'"
        )

        // 尝试在剩余文本中查找未闭合的代码块开始标记 "```"
        val openBlockMatch = CODE_BLOCK_START_REGEX.find(remainingText)
        if (openBlockMatch != null) { // 如果找到 "```"
            val openBlockStartInRemaining = openBlockMatch.range.first // "```" 在剩余文本中的起始位置

            // a. "```" 之前的部分是普通文本
            if (openBlockStartInRemaining > 0) {
                val normalPrefix = remainingText.substring(0, openBlockStartInRemaining)
                if (normalPrefix.isNotBlank()) {
                    segments.add(TextSegment.Normal(normalPrefix.trim()))
                    Log.d(
                        "ParseMarkdownOpt",
                        "添加普通文本 (开放代码块前缀): '${
                            normalPrefix.trim().take(50).replace("\n", "\\n") // 日志
                        }'"
                    )
                }
            }

            // b. "```" 之后的部分被视为未闭合代码块的内容
            val codeBlockCandidate =
                remainingText.substring(openBlockStartInRemaining + 3) // 跳过 "```" 本身
            val firstNewlineIndex = codeBlockCandidate.indexOf('\n')
            var lang: String? = null
            var codeContent: String

            if (firstNewlineIndex != -1) { // 如果 "```lang" 形式后有换行
                val langLine = codeBlockCandidate.substring(0, firstNewlineIndex).trim()
                // 验证语言提示 (允许字母、数字、下划线、点、中横线、加号)
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = codeBlockCandidate.substring(firstNewlineIndex + 1)
            } else { // "```lang" 后无换行，整行可能为语言，代码为空或后续流式输入
                val langLine = codeBlockCandidate.trim()
                if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                    lang = langLine.takeIf { it.isNotEmpty() }
                }
                codeContent = "" // 对于无换行跟随的开放代码块，初始代码视为空
            }
            segments.add(TextSegment.CodeBlock(lang, codeContent)) // 代码内容不trim
            Log.d(
                "ParseMarkdownOpt",
                "从剩余文本添加开放代码块: 语言='$lang', 代码预览='${
                    codeContent.take(50).replace("\n", "\\n") // 日志
                }'"
            )

        } else { // 剩余文本中没有 "```"，全部是普通文本
            if (remainingText.isNotBlank()) {
                segments.add(TextSegment.Normal(remainingText.trim()))
                Log.d(
                    "ParseMarkdownOpt",
                    "剩余文本是普通文本: '${
                        remainingText.trim().take(50).replace("\n", "\\n") // 日志
                    }'"
                )
            }
        }
    }

    // 特殊处理：若解析后segments为空，但原始markdownInput非空白
    if (segments.isEmpty() && markdownInput.isNotBlank()) {
        Log.w(
            "ParseMarkdownOpt",
            "片段列表为空，但Markdown非空白. Markdown是否以 '```' 开头: ${
                markdownInput.startsWith("```") // 日志
            }"
        )
        // 通常意味着整个输入是一个未闭合代码块或纯普通文本
        if (markdownInput.startsWith("```")) { // 整个输入以 "```" 开头
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
                "将整个输入添加为开放代码块: 语言='$lang', 代码预览='${
                    codeContent.take(50).replace("\n", "\\n") // 日志
                }'"
            )
        } else { // 否则，整个输入是普通文本
            segments.add(TextSegment.Normal(markdownInput.trim()))
            Log.d(
                "ParseMarkdownOpt",
                "整个输入是普通文本 (片段为空且不以 ``` 开头)." // 日志
            )
        }
    }

    Log.i(
        "ParseMarkdownOpt",
        "最终片段数量: ${segments.size}, 类型: ${segments.map { it::class.simpleName }}" // 日志：最终结果
    )
    return segments // 返回解析出的片段列表
}

/**
 * 从已裁剪且以 "```" 开头的文本中提取流式代码内容和语言提示。
 * 此函数在当前AiMessageContent中不直接调用，但保留以备他用或参考。
 */
fun extractStreamingCodeContent(textAlreadyTrimmedAndStartsWithTripleQuote: String): Pair<String?, String> {
    Log.d(
        "ExtractStreamCode",
        "用于提取的输入: \"${
            textAlreadyTrimmedAndStartsWithTripleQuote.take(30).replace("\n", "\\n") // 日志
        }\""
    )
    val contentAfterTripleTicks =
        textAlreadyTrimmedAndStartsWithTripleQuote.substring(3)
    val firstNewlineIndex = contentAfterTripleTicks.indexOf('\n')

    if (firstNewlineIndex != -1) { // ```lang\ncode 形式
        val langHint = contentAfterTripleTicks.substring(0, firstNewlineIndex).trim()
        val code = contentAfterTripleTicks.substring(firstNewlineIndex + 1)
        // 验证语言提示
        val validatedLangHint =
            if (langHint.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                langHint.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return Pair(validatedLangHint, code)
    } else { // ```lang 或 ``` 形式
        val langLine = contentAfterTripleTicks.trim()
        val validatedLangHint =
            if (langLine.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' || it == '+' }) {
                langLine.takeIf { it.isNotBlank() }
            } else {
                null
            }
        return Pair(validatedLangHint, "") // 代码内容视为空
    }
}