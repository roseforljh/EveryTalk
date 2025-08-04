package com.example.everytalk.util.messageprocessor

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 思考内容处理器，负责处理包含<think>标签的文本
 */
class ThinkingContentProcessor(
    private val thinkingBuffer: AtomicReference<StringBuilder>,
    private val isInsideThinkTag: AtomicBoolean,
    private val hasFoundThinkTag: AtomicBoolean
) {
    /**
     * 处理包含<think>标签的文本，动态分离思考内容和正式内容
     */
    fun processThinkTags(newText: String): Pair<String?, String?> {
        val buffer = thinkingBuffer.get()
        val fullText = buffer.toString() + newText

        var thinkingContent: String? = null
        var regularContent: String? = null

        // 查找<think>标签
        val thinkStartPattern = "<think>"
        val thinkEndPattern = "</think>"

        val thinkStartIndex = fullText.indexOf(thinkStartPattern)
        val thinkEndIndex = fullText.indexOf(thinkEndPattern)

        when {
            // 找到完整的<think>...</think>
            thinkStartIndex != -1 && thinkEndIndex != -1 && thinkEndIndex > thinkStartIndex -> {
                // 提取思考内容（不包括标签）
                val thinkContent = fullText.substring(
                    thinkStartIndex + thinkStartPattern.length,
                    thinkEndIndex
                )
                thinkingContent = thinkContent

                // 提取</think>之后的内容作为正式内容
                val afterThinkIndex = thinkEndIndex + thinkEndPattern.length
                if (afterThinkIndex < fullText.length) {
                    regularContent = fullText.substring(afterThinkIndex)
                }

                // 清空缓冲区，标记已找到完整标签
                thinkingBuffer.set(StringBuilder())
                hasFoundThinkTag.set(true)
                isInsideThinkTag.set(false)
            }
            // 找到<think>但还没找到</think> - 正在思考标签内部
            thinkStartIndex != -1 && thinkEndIndex == -1 -> {
                isInsideThinkTag.set(true)
                // 提取<think>标签后的内容作为正在输出的思考内容
                val thinkingStartIndex = thinkStartIndex + thinkStartPattern.length
                if (thinkingStartIndex < fullText.length) {
                    thinkingContent = fullText.substring(thinkingStartIndex)
                }
                // 更新缓冲区
                thinkingBuffer.set(StringBuilder(fullText))
            }
            // 没有找到<think>标签
            thinkStartIndex == -1 -> {
                if (isInsideThinkTag.get()) {
                    // 仍在<think>标签内，继续缓冲并返回新增的思考内容
                    thinkingContent = newText
                    thinkingBuffer.set(StringBuilder(fullText))
                } else if (hasFoundThinkTag.get()) {
                    // 已经处理过<think>标签，这是正式内容
                    regularContent = newText
                } else {
                    // 还没遇到<think>标签，这是正式内容
                    regularContent = newText
                }
            }
        }

        return Pair(thinkingContent, regularContent)
    }
}