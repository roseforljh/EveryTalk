package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.RequestContextManagement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val TOOL_CONTEXT_COMPRESSION_STATUS = "正在压缩上下文"
internal const val TRUNCATED_TOOL_OUTPUT_TEXT = "工具输出已为上下文窗口缩减，关键结论请参考后续回答"
private const val TOOL_ROUND_CLASSIFICATION_WINDOW_CHARS = 64

/**
 * 暂存单轮模型正文，等本轮是否调用工具明确后再决定去向。
 * 工具轮正文属于执行说明，会转换成 reasoning；最终轮正文仍按 Content 输出。
 */
internal class ToolRoundContentBuffer(
    private val emitEvent: suspend (AppStreamEvent) -> Unit,
) {
    private val pendingContent = mutableListOf<AppStreamEvent>()
    private var pendingFinalContent: AppStreamEvent.ContentFinal? = null
    private var toolCallObserved = false
    private var contentCommitted = false
    private var pendingContentChars = 0

    suspend fun accept(event: AppStreamEvent) {
        when (event) {
            is AppStreamEvent.Content,
            is AppStreamEvent.Text,
            -> if (toolCallObserved) {
                emitAsReasoning(event)
            } else if (contentCommitted) {
                emitEvent(event)
            } else {
                pendingContent += event
                pendingContentChars += event.textLength()
                // ponytail: 只保留 64 字符分类窗口。工具轮的常见短前导语仍进入执行过程，
                // 长正文会尽快下发；若供应商先输出超长前导语再调工具，前 64 字符会作为正文。
                if (pendingContentChars >= TOOL_ROUND_CLASSIFICATION_WINDOW_CHARS) {
                    flushContent(asReasoning = false)
                    contentCommitted = true
                }
            }
            is AppStreamEvent.ContentFinal -> pendingFinalContent = event
            is AppStreamEvent.ToolCall -> {
                flushContent(asReasoning = true)
                toolCallObserved = true
                emitEvent(event)
            }
            else -> emitEvent(event)
        }
    }

    suspend fun finish(hasToolCalls: Boolean) {
        val isToolRound = hasToolCalls || toolCallObserved
        flushContent(asReasoning = isToolRound)
        if (!isToolRound) pendingFinalContent?.let { emitEvent(it) }
        pendingFinalContent = null
    }

    private suspend fun flushContent(asReasoning: Boolean) {
        pendingContent.forEach { event ->
            if (asReasoning) emitAsReasoning(event) else emitEvent(event)
        }
        pendingContent.clear()
        pendingContentChars = 0
    }

    private suspend fun emitAsReasoning(event: AppStreamEvent) {
        val text = when (event) {
            is AppStreamEvent.Content -> event.text
            is AppStreamEvent.Text -> event.text
            else -> return
        }
        if (text.isNotEmpty()) emitEvent(AppStreamEvent.Reasoning(text))
    }

    private fun AppStreamEvent.textLength(): Int = when (this) {
        is AppStreamEvent.Content -> text.length
        is AppStreamEvent.Text -> text.length
        else -> 0
    }
}

private const val MIN_RETAINED_TOOL_OUTPUT_TOKENS = 64L
// ponytail: 固定硬预算防止兼容服务上报错误上下文规格；以后接入统一 tokenizer 时可替换估算器。
private const val MAX_RETAINED_TOOL_OUTPUT_TOKENS = 64_000L

internal fun estimateToolLoopJsonTokens(element: JsonElement): Long =
    estimateToolLoopTextTokens(element.toString())

internal fun estimateToolLoopTextTokens(text: String): Long {
    var compactAscii = 0L
    var structuralAscii = 0L
    var nonAscii = 0L
    text.forEach { character ->
        when {
            character.code > 0x7f -> nonAscii++
            character.isLetterOrDigit() || character.isWhitespace() -> compactAscii++
            else -> structuralAscii++
        }
    }
    return compactAscii.ceilDiv(4L) + structuralAscii.ceilDiv(2L) + nonAscii
}

internal fun compactOpenAIChatToolHistoryIfNeeded(
    history: MutableList<JsonObject>,
    management: RequestContextManagement?,
    usage: TokenUsage?,
): Boolean = compactHistory(
    history = history,
    management = management,
    usage = usage,
    isToolOutput = { item -> item["role"]?.jsonPrimitive?.contentOrNull == "tool" },
    replaceOutput = { item, replacement ->
        JsonObject(item + ("content" to JsonPrimitive(replacement)))
    },
)

internal fun compactResponsesToolHistoryIfNeeded(
    history: MutableList<JsonElement>,
    management: RequestContextManagement?,
    usage: TokenUsage?,
): Boolean = compactHistory(
    history = history,
    management = management,
    usage = usage,
    isToolOutput = { item ->
        (item as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "function_call_output"
    },
    replaceOutput = { item, replacement ->
        val objectItem = item as JsonObject
        JsonObject(objectItem + ("output" to JsonPrimitive(replacement)))
    },
)

internal fun compactGeminiToolHistoryIfNeeded(
    history: MutableList<JsonObject>,
    management: RequestContextManagement?,
    usage: TokenUsage?,
): Boolean = compactHistory(
    history = history,
    management = management,
    usage = usage,
    isToolOutput = { item ->
        item["role"]?.jsonPrimitive?.contentOrNull == "user" &&
            (item["parts"] as? JsonArray).orEmpty().any { part ->
                (part as? JsonObject)?.containsKey("functionResponse") == true
            }
    },
    replaceOutput = { item, replacement -> replaceGeminiFunctionResponses(item, replacement) },
)

internal fun compactAnthropicToolHistoryIfNeeded(
    history: MutableList<JsonObject>,
    management: RequestContextManagement?,
    usage: TokenUsage?,
): Boolean = compactHistory(
    history = history,
    management = management,
    usage = usage,
    isToolOutput = { item ->
        item["role"]?.jsonPrimitive?.contentOrNull == "user" &&
            (item["content"] as? JsonArray).orEmpty().any { block ->
                (block as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "tool_result"
            }
    },
    replaceOutput = { item, replacement -> replaceAnthropicToolResults(item, replacement) },
)

private fun <T : JsonElement> compactHistory(
    history: MutableList<T>,
    management: RequestContextManagement?,
    usage: TokenUsage?,
    isToolOutput: (T) -> Boolean,
    replaceOutput: (T, String) -> T,
): Boolean {
    val candidates = history.indices.filter { isToolOutput(history[it]) }
    if (candidates.isEmpty()) return false

    var changed = compactToolOutputsToBudget(
        history = history,
        candidates = candidates,
        maxTokens = MAX_RETAINED_TOOL_OUTPUT_TOKENS,
        replaceOutput = replaceOutput,
    )
    if (management?.autoCompressionEnabled != true) return changed

    val newestIndex = candidates.last()
    val measured = usage?.totalTokens
        ?: usage?.inputTokens?.let { input -> input + (usage.outputTokens ?: 0L) }
    var projectedTokens = if (measured != null) {
        measured + estimateToolLoopJsonTokens(history[newestIndex])
    } else {
        management.estimatedInputTokens + history.sumOf(::estimateToolLoopJsonTokens)
    }
    if (projectedTokens < management.compactThresholdTokens) return false

    for (index in candidates.dropLast(1)) {
        val current = history[index]
        val replacement = replaceOutput(current, TRUNCATED_TOOL_OUTPUT_TEXT)
        if (replacement != current) {
            val removedTokens = (
                estimateToolLoopJsonTokens(current) - estimateToolLoopJsonTokens(replacement)
                ).coerceAtLeast(0L)
            history[index] = replacement
            projectedTokens = (projectedTokens - removedTokens).coerceAtLeast(0L)
            changed = true
        }
        if (projectedTokens < management.compactThresholdTokens) return changed
    }

    if (projectedTokens >= management.compactThresholdTokens) {
        val current = history[newestIndex]
        val currentText = current.toString()
        val overflow = (projectedTokens - management.compactThresholdTokens).coerceAtLeast(0L)
        val currentTokens = estimateToolLoopTextTokens(currentText)
        val retainedTokens = (currentTokens - overflow)
            .coerceAtLeast(MIN_RETAINED_TOOL_OUTPUT_TOKENS)
        val truncated = truncateToolOutput(currentText, retainedTokens)
        val replacement = replaceOutput(current, truncated)
        if (replacement != current) {
            history[newestIndex] = replacement
            changed = true
        }
    }
    return changed
}

private fun <T : JsonElement> compactToolOutputsToBudget(
    history: MutableList<T>,
    candidates: List<Int>,
    maxTokens: Long,
    replaceOutput: (T, String) -> T,
): Boolean {
    var retainedTokens = candidates.sumOf { estimateToolLoopJsonTokens(history[it]) }
    if (retainedTokens <= maxTokens) return false

    var changed = false
    for (index in candidates.dropLast(1)) {
        val current = history[index]
        val replacement = replaceOutput(current, TRUNCATED_TOOL_OUTPUT_TEXT)
        if (replacement != current) {
            retainedTokens = (
                retainedTokens - estimateToolLoopJsonTokens(current) + estimateToolLoopJsonTokens(replacement)
                ).coerceAtLeast(0L)
            history[index] = replacement
            changed = true
        }
        if (retainedTokens <= maxTokens) return changed
    }

    val newestIndex = candidates.last()
    val newest = history[newestIndex]
    val newestTokens = estimateToolLoopJsonTokens(newest)
    val otherTokens = (retainedTokens - newestTokens).coerceAtLeast(0L)
    val newestBudget = (maxTokens - otherTokens).coerceAtLeast(MIN_RETAINED_TOOL_OUTPUT_TOKENS)
    val replacement = replaceOutput(
        newest,
        truncateToolOutput(newest.toString(), newestBudget),
    )
    if (replacement != newest) {
        history[newestIndex] = replacement
        changed = true
    }
    return changed
}

internal fun truncateToolOutput(text: String, maxTokens: Long): String {
    if (estimateToolLoopTextTokens(text) <= maxTokens) return text
    val marker = "\n…工具输出已截断…\n"
    if (estimateToolLoopTextTokens(marker) > maxTokens) return TRUNCATED_TOOL_OUTPUT_TEXT

    var low = 0
    var high = text.length
    var best = marker
    while (low <= high) {
        val bodyChars = low + (high - low) / 2
        val headChars = bodyChars / 2
        val candidate = text.take(headChars) + marker + text.takeLast(bodyChars - headChars)
        if (estimateToolLoopTextTokens(candidate) <= maxTokens) {
            best = candidate
            low = bodyChars + 1
        } else {
            high = bodyChars - 1
        }
    }
    return best
}

private fun Long.ceilDiv(divisor: Long): Long =
    if (this == 0L) 0L else (this + divisor - 1L) / divisor

private fun replaceGeminiFunctionResponses(
    item: JsonObject,
    replacement: String,
): JsonObject {
    val parts = item["parts"] as? JsonArray ?: return item
    val replacedParts = JsonArray(parts.map { part ->
        val partObject = part as? JsonObject ?: return@map part
        val functionResponse = partObject["functionResponse"] as? JsonObject ?: return@map part
        val response = JsonObject(mapOf("result" to JsonPrimitive(replacement)))
        JsonObject(partObject + (
            "functionResponse" to JsonObject(functionResponse + ("response" to response))
            ))
    })
    return JsonObject(item + ("parts" to replacedParts))
}

private fun replaceAnthropicToolResults(
    item: JsonObject,
    replacement: String,
): JsonObject {
    val content = item["content"] as? JsonArray ?: return item
    val replaced = JsonArray(content.map { block ->
        val blockObject = block as? JsonObject ?: return@map block
        if (blockObject["type"]?.jsonPrimitive?.contentOrNull != "tool_result") return@map block
        JsonObject(blockObject + ("content" to JsonPrimitive(replacement)))
    })
    return JsonObject(item + ("content" to replaced))
}
