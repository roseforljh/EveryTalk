package com.android.everytalk.data.DataClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class AbstractApiMessage : IMessage {
    abstract override val role: String
    abstract override val name: String?
}

@Serializable
@SerialName("simple_text_message")
data class SimpleTextApiMessage(
    @SerialName("id")
    override val id: String = java.util.UUID.randomUUID().toString(),
    
    @SerialName("role")
    override val role: String,

    @SerialName("content")
    val content: String,

    @SerialName("name")
    override val name: String? = null
) : AbstractApiMessage()

@Serializable
@SerialName("parts_message")
data class PartsApiMessage(
    @SerialName("id")
    override val id: String = java.util.UUID.randomUUID().toString(),
    
    @SerialName("role")
    override val role: String,

    @SerialName("parts")
    val parts: List<ApiContentPart>,

    @SerialName("name")
    override val name: String? = null
) : AbstractApiMessage()

/**
 * AgentLoop 使用的中立 Assistant 消息。它保存文本、推理和工具调用的真实顺序。
 * 四种 Provider 在发请求时再把它转换成各自协议。
 */
@Serializable
@SerialName("agent_assistant_message")
data class AgentAssistantApiMessage(
    @SerialName("id")
    override val id: String = java.util.UUID.randomUUID().toString(),
    @SerialName("role")
    override val role: String = "assistant",
    @SerialName("text")
    val text: String = "",
    @SerialName("reasoning")
    val reasoning: String = "",
    @SerialName("tool_calls")
    val toolCalls: List<AgentToolCallApiPart> = emptyList(),
    /**
     * Assistant 的真实块顺序。Gemini 的签名属于具体 Part，不能只保存拼接后的正文。
     * 旧记录没有该字段时，Provider 继续使用 text、reasoning、toolCalls 兼容恢复。
     */
    @SerialName("content_parts")
    val contentParts: List<AgentAssistantContentApiPart> = emptyList(),
    /** 下面三个来源字段用于判断加密签名能否安全回放给当前模型。 */
    @SerialName("source_provider")
    val sourceProvider: String? = null,
    @SerialName("source_endpoint")
    val sourceEndpoint: String? = null,
    @SerialName("source_model")
    val sourceModel: String? = null,
    @SerialName("name")
    override val name: String? = null,
) : AbstractApiMessage()

/** Provider 中立的 Assistant 块。签名只作为可选元数据保存，不改变块的业务语义。 */
@Serializable
sealed class AgentAssistantContentApiPart {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        @SerialName("thought_signature") val thoughtSignature: String? = null,
    ) : AgentAssistantContentApiPart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val text: String,
        @SerialName("thought_signature") val thoughtSignature: String? = null,
    ) : AgentAssistantContentApiPart()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(val call: AgentToolCallApiPart) : AgentAssistantContentApiPart()
}

@Serializable
data class AgentToolCallApiPart(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    @SerialName("thought_signature")
    val thoughtSignature: String? = null,
)

/** Tool Result 独立成消息，保证上下文裁剪时能和 Tool Call 成组处理。 */
@Serializable
@SerialName("agent_tool_result_message")
data class AgentToolResultApiMessage(
    @SerialName("id")
    override val id: String = java.util.UUID.randomUUID().toString(),
    @SerialName("role")
    override val role: String = "tool",
    @SerialName("tool_call_id")
    val toolCallId: String,
    @SerialName("tool_name")
    val toolName: String,
    @SerialName("content")
    val content: JsonElement,
    @SerialName("is_error")
    val isError: Boolean = false,
    @SerialName("name")
    override val name: String? = toolName,
) : AbstractApiMessage()
