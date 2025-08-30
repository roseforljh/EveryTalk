package com.example.everytalk.data.network
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.example.everytalk.data.DataClass.WebSearchResult
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class AppStreamEvent {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : AppStreamEvent()

    @Serializable
    @SerialName("content")
    data class Content(val text: String, val output_type: String? = null) : AppStreamEvent()
    
    @Serializable
    @SerialName("content_final")
    data class ContentFinal(val text: String) : AppStreamEvent()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val text: String) : AppStreamEvent()

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
    @SerialName("tool_call")
    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsObj: JsonObject,
        val isReasoningStep: Boolean? = null
    ) : AppStreamEvent()

    @Serializable
    @SerialName("error")
    data class Error(val message: String, val upstreamStatus: Int? = null) : AppStreamEvent()
    @Serializable
    @SerialName("finish")
    data class Finish(val reason: String) : AppStreamEvent()

    @Serializable
    @SerialName("image_generation")
    data class ImageGeneration(val imageUrl: String) : AppStreamEvent()
}

@Serializable
data class OpenAiToolCall(
    @SerialName("index")
    val index: Int? = null,
    @SerialName("id")
    val id: String? = null,
    @SerialName("type")
    val type: String? = null,
    @SerialName("function")
    val function: OpenAiFunctionCall? = null
)

@Serializable
data class OpenAiFunctionCall(
    @SerialName("name")
    val name: String? = null,
    @SerialName("arguments")
    val arguments: String? = null
)