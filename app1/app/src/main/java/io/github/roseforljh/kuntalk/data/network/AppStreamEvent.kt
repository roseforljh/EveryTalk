package io.github.roseforljh.kuntalk.data.DataClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AppStreamEvent(
    @SerialName("type")
    val type: String,

    @SerialName("stage")
    val stage: String? = null,

    @SerialName("results")
    val results: List<WebSearchResult>? = null,

    @SerialName("text")
    val text: String? = null,

    @SerialName("data")
    val toolCallsData: List<OpenAiToolCall>? = null,

    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String? = null,
    @SerialName("arguments_obj")
    val argumentsObj: JsonObject? = null,

    @SerialName("is_reasoning_step")
    val isReasoningStep: Boolean? = null,

    @SerialName("reason")
    val reason: String? = null,

    @SerialName("message")
    val message: String? = null,
    @SerialName("upstream_status")
    val upstreamStatus: Int? = null,

    @SerialName("timestamp")
    val timestamp: String? = null
)

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
