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

private const val MIN_RETAINED_TOOL_OUTPUT_TOKENS = 64L

internal fun estimateToolLoopJsonTokens(element: JsonElement): Long =
    estimateToolLoopTextTokens(element.toString())

internal fun estimateToolLoopTextTokens(text: String): Long {
    var ascii = 0L
    var nonAscii = 0L
    text.forEach { character ->
        if (character.code <= 0x7f) ascii++ else nonAscii++
    }
    return (ascii + 3L) / 4L + nonAscii
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
    if (management?.autoCompressionEnabled != true) return false
    val candidates = history.indices.filter { isToolOutput(history[it]) }
    if (candidates.isEmpty()) return false

    var changed = false
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

internal fun truncateToolOutput(text: String, maxTokens: Long): String {
    if (estimateToolLoopTextTokens(text) <= maxTokens) return text
    val marker = "\n…工具输出已截断…\n"
    val targetChars = (maxTokens.coerceAtLeast(1L) * 3L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    if (targetChars <= marker.length + 2) return TRUNCATED_TOOL_OUTPUT_TEXT
    val bodyChars = targetChars - marker.length
    val headChars = bodyChars / 2
    val tailChars = bodyChars - headChars
    return text.take(headChars) + marker + text.takeLast(tailChars)
}

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
