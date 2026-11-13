package com.android.everytalk.data.agent

import com.android.everytalk.data.DataClass.*
import com.android.everytalk.data.network.LlmEndpointResolver
import com.android.everytalk.data.network.PiMessageTransformer
import com.android.everytalk.data.network.isSamePiSource
import kotlinx.serialization.json.*
import java.net.URI

/** 把现有中立消息转换为 Pi 1.5.2 合同；签名清洗、工具配对仍由公共 Transformer 完成。 */
internal fun cloudAgentInput(
    request: ChatRequest,
    runId: String,
    sessionUuid: String,
    userUuid: String,
    assistantUuid: String,
    epoch: Long,
    secretsEpoch: Long,
    timestamp: Long,
    timezone: String,
    tools: JsonArray,
    contextUsage: ContextUsageSnapshot? = null,
): JsonObject {
    listOf(runId, sessionUuid, userUuid, assistantUuid).forEach(::requireCloudUuid)
    require(epoch >= 0 && secretsEpoch >= 0)
    val protocol = modelParameterProtocol(request.channel)
    val api = when (protocol) {
        ModelParameterProtocol.CODEX -> "openai-responses"
        ModelParameterProtocol.ANTHROPIC -> "anthropic-messages"
        ModelParameterProtocol.GEMINI -> "google-generative-ai"
        ModelParameterProtocol.OPENAI_COMPATIBLE -> "openai-completions"
    }
    val endpoint = LlmEndpointResolver.resolve(protocol, request.apiAddress.orEmpty(), request.model)
    val uri = URI(endpoint)
    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null && uri.port in setOf(-1, 443)) {
        "云端模型需要 HTTPS 地址；本机网络地址请使用本地模式"
    }
    // SDK 自己追加协议路径；完整自定义地址由受控 Worker 在真正发请求时替换，不能丢代理前缀或查询参数。
    val suffix = when (protocol) {
        ModelParameterProtocol.CODEX -> "/responses"
        ModelParameterProtocol.ANTHROPIC -> "/messages"
        ModelParameterProtocol.GEMINI -> "/models/"
        ModelParameterProtocol.OPENAI_COMPATIBLE -> "/chat/completions"
    }
    val basePath = if (protocol == ModelParameterProtocol.GEMINI) uri.rawPath.substringBeforeLast(suffix, "")
        else uri.rawPath.removeSuffix(suffix)
    val base = "${uri.scheme}://${uri.rawAuthority}$basePath"
    val config = request.generationConfig
    val thinking = config?.thinkingConfig
    val custom = request.customModelParameters.orEmpty() + request.customExtraBody.orEmpty()
    val effort = (thinking?.thinkingLevel ?: thinking?.reasoningEffort
        ?: custom["reasoning_effort"] as? String)?.lowercase()
    val disabled = thinking?.reasoningMode == ReasoningMode.DISABLED || thinking?.includeThoughts == false || effort in setOf("none", "off")
    val level = if (disabled || (thinking == null && effort == null)) "off"
        else effort?.takeIf { it in setOf("minimal", "low", "medium", "high", "xhigh", "max") } ?: "medium"
    val transformed = PiMessageTransformer.transform(request.messages, request)
    return buildJsonObject {
        put("version", 1); put("id", runId); put("sessionUuid", sessionUuid)
        put("userMessageUuid", userUuid); put("assistantMessageUuid", assistantUuid)
        put("epoch", epoch); put("secretsEpoch", secretsEpoch); put("timestamp", timestamp); put("timezone", timezone)
        put("apiKey", request.apiKey); put("endpoint", endpoint); put("providerName", request.provider)
        put("model", buildJsonObject {
            put("id", request.model); put("api", api); put("provider", "everytalk"); put("baseUrl", base)
            put("reasoning", thinking != null || level != "off"); put("contextWindow", request.contextManagement?.maxContextTokens ?: DEFAULT_MAX_CONTEXT_TOKENS)
            put("maxTokens", config?.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS)
            put("input", JsonArray(request.localInputModalities.sorted().map(::JsonPrimitive)))
            if (protocol == ModelParameterProtocol.ANTHROPIC) put("compat", buildJsonObject {
                put("allowEmptySignature", request.localAllowEmptyAnthropicThinkingSignature)
                put("forceAdaptiveThinking", thinking?.reasoningMode == ReasoningMode.EFFORT && !disabled)
            })
        })
        put("messages", JsonArray(transformed.map { cloudPiMessage(it, request, api, timestamp) }))
        put("tools", tools); put("thinkingLevel", level)
        contextUsage?.let { put("contextUsage", cloudAgentJson.encodeToJsonElement(ContextUsageSnapshot.serializer(), it)) }
        thinking?.thinkingBudget?.takeIf { it > 0 && !disabled }?.let { budget ->
            put("thinkingBudgets", buildJsonObject { listOf("minimal", "low", "medium", "high").forEach { put(it, budget) } })
        }
        config?.temperature?.let { put("temperature", it) }
        config?.maxOutputTokens?.let { put("maxTokens", it) }
        // Gemini 的采样/思考字段属于 generationConfig；与 SDK 生成值按字段合并，不能抹掉 maxOutputTokens。
        val extra = mutableMapOf<String, Any>()
        if (protocol == ModelParameterProtocol.GEMINI) {
            val generation = buildJsonObject {
                config?.topP?.let { put("topP", it) }
                thinking?.let { put("thinkingConfig", buildJsonObject {
                    it.includeThoughts?.let { put("includeThoughts", it) }
                    it.thinkingBudget?.let { put("thinkingBudget", it) }
                    it.thinkingLevel?.let { put("thinkingLevel", it) }
                }) }
            }
            if (generation.isNotEmpty()) extra["generationConfig"] = generation
        } else config?.topP?.let { extra["top_p"] = it }
        extra.putAll(custom)
        if (extra.isNotEmpty()) put("extraBody", anyToJsonElement(extra))
    }
}

private fun cloudPiMessage(message: AbstractApiMessage, request: ChatRequest, api: String, timestamp: Long): JsonObject = buildJsonObject {
    put("role", if (message is AgentToolResultApiMessage) "toolResult" else message.role)
    put("timestamp", timestamp)
    fun text(value: String) = buildJsonObject { put("type", "text"); put("text", value) }
    fun image(data: String, mime: String) = buildJsonObject { put("type", "image"); put("data", data); put("mimeType", mime) }
    fun call(value: AgentToolCallApiPart) = buildJsonObject {
        put("type", "toolCall"); put("id", value.id); put("name", value.name); put("arguments", value.arguments)
        value.thoughtSignature?.let { put("thoughtSignature", it) }
        value.namespace?.let { put("namespace", it) }
    }
    when (message) {
        is SimpleTextApiMessage -> put("content", if (message.role == "assistant") JsonArray(listOf(text(message.content))) else JsonPrimitive(message.content))
        is PartsApiMessage -> put("content", JsonArray(message.parts.map { part -> when (part) {
            is ApiContentPart.Text -> text(part.text)
            is ApiContentPart.InlineData -> { require(part.mimeType.startsWith("image/")) { "附件需先转换为可读取的文本或图片" }; image(part.base64Data, part.mimeType) }
            is ApiContentPart.FileUri -> error("附件尚未准备好，不能把手机文件地址交给云端")
        } }))
        is AgentAssistantApiMessage -> put("content", JsonArray(message.contentParts.map { part -> when (part) {
            is AgentAssistantContentApiPart.Text -> buildJsonObject { put("type", "text"); put("text", part.text); part.thoughtSignature?.let { put("textSignature", it) } }
            is AgentAssistantContentApiPart.Reasoning -> buildJsonObject {
                put("type", "thinking"); put("thinking", part.text); put("redacted", part.redacted)
                part.thoughtSignature?.let { put("thinkingSignature", it) }
            }
            is AgentAssistantContentApiPart.ToolCall -> call(part.call)
        } }))
        is AgentToolResultApiMessage -> {
            put("toolCallId", message.toolCallId); put("toolName", message.toolName); put("isError", message.isError)
            put("content", JsonArray(message.contentBlocks.ifEmpty {
                listOf(AgentToolResultContentApiPart.Text((message.content as? JsonPrimitive)?.content ?: message.content.toString()))
            }.map { part -> when (part) {
                is AgentToolResultContentApiPart.Text -> text(part.text)
                is AgentToolResultContentApiPart.Image -> image(part.data, part.mimeType)
            } }))
        }
    }
    if (message.role == "assistant") {
        val original = message as? AgentAssistantApiMessage
        put("api", api); put("provider", if (original == null || original.isSamePiSource(request)) "everytalk" else original.sourceProvider ?: "history")
        put("model", original?.sourceModel ?: request.model)
        put("stopReason", original?.stopReason ?: if (original?.toolCalls?.isNotEmpty() == true) "toolUse" else "stop")
        put("usage", buildJsonObject {
            listOf("input", "output", "cacheRead", "cacheWrite", "totalTokens").forEach { put(it, 0) }
            put("cost", buildJsonObject { listOf("input", "output", "cacheRead", "cacheWrite", "total").forEach { put(it, 0) } })
        })
    }
}
