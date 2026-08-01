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
            inputMessageMarkers.any(normalized::contains) -> RequestErrorCategory.INPUT_CONTEXT_TOO_LONG
            outputMessageMarkers.any(normalized::contains) -> RequestErrorCategory.OUTPUT_LIMIT_TOO_HIGH
            else -> RequestErrorCategory.OTHER
        }
    }

    private val inputMessageMarkers = listOf(
        "context length exceeded",
        "maximum context length",
        "prompt is too long",
        "input token count",
        "输入上下文过长",
    )
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
