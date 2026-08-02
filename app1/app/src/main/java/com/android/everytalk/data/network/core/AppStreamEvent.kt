package com.android.everytalk.data.network
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.android.everytalk.data.DataClass.WebSearchResult
import kotlinx.serialization.json.JsonObject

@Serializable
enum class TokenUsageSource {
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    GEMINI,
    ANTHROPIC,
    ESTIMATED,
}

@Serializable
data class TokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val totalTokens: Long? = null,
    val isFinal: Boolean,
    val source: TokenUsageSource,
    val requestOrdinal: Int? = null,
)

@Serializable
sealed class AppStreamEvent {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : AppStreamEvent()

    @Serializable
    @SerialName("content")
    data class Content(val text: String, val output_type: String? = null, val block_type: String? = null, val timestamp: String? = null) : AppStreamEvent()
    
    @Serializable
    @SerialName("content_final")
    data class ContentFinal(val text: String, val output_type: String? = null, val block_type: String? = null, val timestamp: String? = null) : AppStreamEvent()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val text: String) : AppStreamEvent()

    @Serializable
    @SerialName("reasoning_finish")
    data class ReasoningFinish(val timestamp: String? = null) : AppStreamEvent()

    @Serializable
    @SerialName("usage")
    data class Usage(val usage: TokenUsage) : AppStreamEvent()

    @Serializable
    @SerialName("native_context_compaction")
    data class NativeContextCompaction(
        val inputJson: String,
        val configId: String,
        val provider: String,
        val channel: String,
        val model: String,
        val compactionItemId: String? = null,
        val estimatedTokens: Long = 0L,
        val reset: Boolean = false,
    ) : AppStreamEvent()

    @Serializable
    @SerialName("output_type")
    data class OutputType(val type: String) : AppStreamEvent()

    @Serializable
    @SerialName("stream_end")
    data class StreamEnd(val messageId: String) : AppStreamEvent()

    @Serializable
    @SerialName("web_search_status")
    data class WebSearchStatus(val stage: String) : AppStreamEvent()

    @Serializable
    @SerialName("web_search_results")
    data class WebSearchResults(val results: List<WebSearchResult>) : AppStreamEvent()

    @Serializable
    @SerialName("status_update")
    data class StatusUpdate(val stage: String) : AppStreamEvent()

    @Serializable
    @SerialName("execution_status_update")
    data class ExecutionStatusUpdate(val status: String?) : AppStreamEvent()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsObj: JsonObject,
        val isReasoningStep: Boolean? = null,
        val status: String? = null
    ) : AppStreamEvent()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String,
        val upstreamStatus: Int? = null,
        val code: String? = null,
        val type: String? = null,
        val parameter: String? = null,
        val rawMessage: String? = null,
        val maxContextTokens: Int? = null,
        val maxOutputTokens: Int? = null,
    ) : AppStreamEvent()
    @Serializable
    @SerialName("finish")
    data class Finish(val reason: String) : AppStreamEvent()

    @Serializable
    @SerialName("image_generation")
    data class ImageGeneration(val imageUrl: String) : AppStreamEvent()

    @Serializable
    @SerialName("code_execution_result")
    data class CodeExecutionResult(
        @SerialName("codeExecutionOutput") val codeExecutionOutput: String? = null,
        @SerialName("codeExecutionOutcome") val codeExecutionOutcome: String? = null,
        @SerialName("imageUrl") val imageUrl: String? = null
    ) : AppStreamEvent()

    @Serializable
    @SerialName("code_executable")
    data class CodeExecutable(
        @SerialName("executableCode") val executableCode: String? = null,
        @SerialName("codeLanguage") val codeLanguage: String? = null
    ) : AppStreamEvent()
}

internal fun AppStreamEvent.withRequestOrdinal(ordinal: Int): AppStreamEvent = when (this) {
    is AppStreamEvent.Usage -> copy(usage = usage.copy(requestOrdinal = ordinal))
    else -> this
}
