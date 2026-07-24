package com.android.everytalk.data.network

internal data class ThinkTagExtraction(
    val content: String,
    val reasoning: String,
    val changed: Boolean,
)

internal fun extractThinkTagContent(text: String): ThinkTagExtraction {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("<think>", ignoreCase = true) &&
        !trimmed.startsWith("<thinking>", ignoreCase = true)) {
        return ThinkTagExtraction(content = text, reasoning = "", changed = false)
    }

    val tagRegex = Regex(
        pattern = "^\\s*<think(?:ing)?>[ \\t]*(?:\\r?\\n)(.*?)\\r?\\n?[ \\t]*</think(?:ing)?>[ \\t]*(?:\\r?\\n)*(.*)$",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    val match = tagRegex.find(text) ?: return ThinkTagExtraction(content = text, reasoning = "", changed = false)
    val reasoning = match.groupValues.getOrElse(1) { "" }.trim()
    val content = match.groupValues.getOrElse(2) { "" }.trimStart('\n', '\r').trimEnd()
    if (reasoning.isBlank()) {
        return ThinkTagExtraction(content = text, reasoning = "", changed = false)
    }

    return ThinkTagExtraction(
        content = content,
        reasoning = reasoning,
        changed = true,
    )
}

internal class ThinkTagStreamRouter {
    private enum class State { INITIAL, INSIDE_THINK, AFTER_THINK, PASSTHROUGH }

    private var state = State.INITIAL
    private val pendingBuffer = StringBuilder()

    private val OPEN_TAGS = listOf("<think>", "<thinking>")
    private val CLOSE_TAGS = listOf("</think>", "</thinking>")
    private val MAX_OPEN_TAG_LEN = OPEN_TAGS.maxOf { it.length }
    private val MAX_DETECT_LEN = MAX_OPEN_TAG_LEN + 4

    data class Chunk(val text: String, val isReasoning: Boolean)

    fun feed(text: String): List<Chunk> {
        if (text.isEmpty()) return emptyList()
        val results = mutableListOf<Chunk>()

        when (state) {
            State.PASSTHROUGH -> results.add(Chunk(text, false))
            State.AFTER_THINK -> results.add(Chunk(text, false))
            State.INSIDE_THINK -> {
                pendingBuffer.append(text)
                extractCloseTag(results)
            }
            State.INITIAL -> {
                pendingBuffer.append(text)
                val buffered = pendingBuffer.toString()
                val trimmed = buffered.trimStart()
                val openTag = OPEN_TAGS.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }

                if (openTag != null) {
                    val afterTag = trimmed.substring(openTag.length)
                    val afterTagWithoutHorizontalSpace = afterTag.trimStart(' ', '\t')
                    when {
                        afterTag.isEmpty() -> Unit
                        afterTagWithoutHorizontalSpace.firstOrNull() == '\n' ||
                            afterTagWithoutHorizontalSpace.firstOrNull() == '\r' -> {
                            state = State.INSIDE_THINK
                            pendingBuffer.clear()
                            pendingBuffer.append(afterTagWithoutHorizontalSpace.trimStart('\n', '\r'))
                            extractCloseTag(results)
                        }
                        else -> {
                            state = State.PASSTHROUGH
                            results.add(Chunk(buffered, false))
                            pendingBuffer.clear()
                        }
                    }
                } else if (OPEN_TAGS.any { it.startsWith(trimmed, ignoreCase = true) } || trimmed.isEmpty()) {
                    if (buffered.length >= MAX_DETECT_LEN) {
                        state = State.PASSTHROUGH
                        results.add(Chunk(buffered, false))
                        pendingBuffer.clear()
                    }
                } else {
                    state = State.PASSTHROUGH
                    results.add(Chunk(buffered, false))
                    pendingBuffer.clear()
                }
            }
        }
        return results
    }

    fun flush(): List<Chunk> {
        val results = mutableListOf<Chunk>()
        val remaining = pendingBuffer.toString()
        pendingBuffer.clear()
        if (remaining.isNotEmpty()) {
            when (state) {
                State.INITIAL, State.PASSTHROUGH, State.AFTER_THINK -> results.add(Chunk(remaining, false))
                State.INSIDE_THINK -> results.add(Chunk(remaining, true))
            }
        }
        if (state == State.INSIDE_THINK) {
            state = State.AFTER_THINK
        }
        return results
    }

    val isInsideThink: Boolean get() = state == State.INSIDE_THINK
    val detectedThinkTag: Boolean get() = state == State.INSIDE_THINK || state == State.AFTER_THINK

    private fun extractCloseTag(results: MutableList<Chunk>) {
        val content = pendingBuffer.toString()
        val closeTag = CLOSE_TAGS
            .mapNotNull { tag ->
                val index = content.indexOf(tag, ignoreCase = true)
                if (index >= 0) index to tag else null
            }
            .minByOrNull { it.first }
        if (closeTag != null) {
            val closeIdx = closeTag.first
            val reasoning = content.substring(0, closeIdx)
            val afterClose = content.substring(closeIdx + closeTag.second.length).trimStart('\n', '\r')
            pendingBuffer.clear()
            state = State.AFTER_THINK
            if (reasoning.isNotEmpty()) results.add(Chunk(reasoning, true))
            if (afterClose.isNotEmpty()) results.add(Chunk(afterClose, false))
        } else {
            val safeLen = (content.length - CLOSE_TAGS.maxOf { it.length }).coerceAtLeast(0)
            if (safeLen > 0) {
                results.add(Chunk(content.substring(0, safeLen), true))
                pendingBuffer.delete(0, safeLen)
            }
        }
    }
}
