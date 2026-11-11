package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.network.AppStreamEvent
import java.io.IOException

internal enum class RequestErrorCategory {
    INPUT_CONTEXT_TOO_LONG,
    OUTPUT_LIMIT_TOO_HIGH,
    RATE_LIMITED,
    AUTHENTICATION,
    NETWORK,
    OTHER,
}

internal data class ProviderErrorInfo(
    val status: Int? = null,
    val code: String? = null,
    val type: String? = null,
    val parameter: String? = null,
    val message: String,
    val maxContextTokens: Int? = null,
    val maxOutputTokens: Int? = null,
)

internal data class ContextRecoveryDecision(
    val category: RequestErrorCategory,
    val request: ChatRequest,
    val effectiveMaxContextTokens: Int? = null,
    val effectiveMaxOutputTokens: Int? = null,
)

internal object ContextErrorClassifier {
    private val inputCodes = setOf(
        "context_length_exceeded",
        "input_too_long",
        "prompt_too_long",
        "request_too_large",
    )
    private val outputCodes = setOf(
        "max_tokens_exceeded",
        "max_tokens_too_high",
        "max_output_tokens_exceeded",
        "invalid_max_tokens",
    )

    fun classify(info: ProviderErrorInfo): RequestErrorCategory {
        if (info.status == 401 || info.status == 403) return RequestErrorCategory.AUTHENTICATION
        if (info.status == 429) return RequestErrorCategory.RATE_LIMITED
        // 413 是请求体大小超限，可能来自图片而不是 token；仍只使用已有的一次有界恢复。
        if (info.status == 413) return RequestErrorCategory.INPUT_CONTEXT_TOO_LONG

        val code = info.code.normalized()
        val type = info.type.normalized()
        val parameter = info.parameter.normalized()
        return when {
            code in inputCodes || type in inputCodes -> RequestErrorCategory.INPUT_CONTEXT_TOO_LONG
            code in outputCodes || type in outputCodes -> RequestErrorCategory.OUTPUT_LIMIT_TOO_HIGH
            parameter == "max_tokens" || parameter == "max_output_tokens" ->
                RequestErrorCategory.OUTPUT_LIMIT_TOO_HIGH
            else -> classifyMessageFallback(info.message)
        }
    }

    fun classify(error: Throwable): RequestErrorCategory = when (error) {
        is IOException -> RequestErrorCategory.NETWORK
        else -> classifyMessageFallback(error.message.orEmpty())
    }

    private fun classifyMessageFallback(message: String): RequestErrorCategory {
        val normalized = message.lowercase()
        return when {
            normalized.contains("rate limit") || normalized.contains("too many requests") -> RequestErrorCategory.RATE_LIMITED
            normalized.startsWith("throttling error:") || normalized.startsWith("service unavailable:") -> RequestErrorCategory.OTHER
            outputMessageMarkers.any(normalized::contains) -> RequestErrorCategory.OUTPUT_LIMIT_TOO_HIGH
            inputMessagePatterns.any { it.containsMatchIn(message) } -> RequestErrorCategory.INPUT_CONTEXT_TOO_LONG
            else -> RequestErrorCategory.OTHER
        }
    }

    /** 对齐 Pi 的明确超窗表达；HTTP 状态只匹配开头，避免把请求 ID 或正文中的 413 当状态码。 */
    private val inputMessagePatterns = listOf(
        "prompt (?:is )?too long",
        "prompt exceeds max length",
        "request_too_large",
        "input is too long for requested model",
        "exceeds (?:the )?(?:model'?s )?(?:maximum context length|context window)",
        "context.?window.?exceeded",
        "input token count.*exceeds the maximum",
        "maximum prompt length is \\d+",
        "reduce the length of the messages",
        "maximum context length",
        "exceeds (?:the )?maximum allowed input length",
        "input \\(.*tokens\\) is longer than the model'?s context length",
        "exceeds the available context size",
        "greater than the context length",
        "prompt token count.*exceeds the limit",
        "context window exceeds limit",
        "request exceeded model token limit",
        "prompt has.*tokens.*configured context size",
        "range of input length should be",
        "context[_ ]length[_ ]exceeded",
        "token limit exceeded",
        "输入上下文过长",
        "^(?:[\\w -]+ API error \\(413\\):|413(?:\\s|:|$))",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val outputMessageMarkers = listOf(
        "max_tokens is too large",
        "maximum allowed number of output tokens",
        "max_output_tokens",
        "输出上限过大",
    )
}

internal object ContextRecoveryPolicy {
    fun recover(
        request: ChatRequest,
        error: ProviderErrorInfo,
        hasPartialOutput: Boolean,
        attemptedCategories: Set<RequestErrorCategory>,
    ): ContextRecoveryDecision? {
        val category = ContextErrorClassifier.classify(error)
        if (hasPartialOutput || category in attemptedCategories) return null
        return when (category) {
            RequestErrorCategory.INPUT_CONTEXT_TOO_LONG -> {
                val trimmedMessages = removeOldestConversationTurn(request) ?: return null
                ContextRecoveryDecision(
                    category = category,
                    request = request.copy(messages = trimmedMessages),
                    effectiveMaxContextTokens = error.maxContextTokens,
                )
            }
            RequestErrorCategory.OUTPUT_LIMIT_TOO_HIGH -> lowerOutputLimit(request, error, category)
            else -> null
        }
    }

    private fun removeOldestConversationTurn(request: ChatRequest) = run {
        val conversationIndexes = request.messages.indices.filterNot { index ->
            request.messages[index].role.equals("system", ignoreCase = true)
        }
        if (conversationIndexes.isEmpty()) return@run null

        val turns = mutableListOf<MutableList<Int>>()
        conversationIndexes.forEach { index ->
            val startsNewTurn = request.messages[index].role.equals("user", ignoreCase = true)
            if (turns.isEmpty() || startsNewTurn) turns.add(mutableListOf())
            turns.last() += index
        }
        if (turns.size <= 1) return@run null

        val removed = turns.first().toSet()
        request.messages.filterIndexed { index, _ -> index !in removed }
    }

    private fun lowerOutputLimit(
        request: ChatRequest,
        error: ProviderErrorInfo,
        category: RequestErrorCategory,
    ): ContextRecoveryDecision? {
        val current = request.generationConfig?.maxOutputTokens ?: return null
        if (current <= 1) return null
        val reportedLimit = error.maxOutputTokens?.takeIf { it > 0 && it < current }
        val lowered = reportedLimit ?: (current / 2).coerceAtLeast(1)
        val generationConfig = request.generationConfig.copy(
            maxOutputTokens = lowered
        )
        return ContextRecoveryDecision(
            category = category,
            request = request.copy(generationConfig = generationConfig),
            effectiveMaxOutputTokens = lowered,
        )
    }
}

internal fun AppStreamEvent.Error.toProviderErrorInfo(): ProviderErrorInfo = ProviderErrorInfo(
    status = upstreamStatus,
    code = code,
    type = type,
    parameter = parameter,
    message = rawMessage ?: message,
    maxContextTokens = maxContextTokens,
    maxOutputTokens = maxOutputTokens,
)

private fun String?.normalized(): String = this?.trim()?.lowercase().orEmpty()
