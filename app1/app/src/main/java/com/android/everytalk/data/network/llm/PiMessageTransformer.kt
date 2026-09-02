package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantContentApiPart
import com.android.everytalk.data.DataClass.AgentToolResultContentApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.modelParameterProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pi `transformMessages` 的 Kotlin 等价实现。
 *
 * 这是所有 Provider Adapter 共用的消息整理 Module。它只处理消息语义：
 * 签名是否可跨模型复用、工具结果是否配对、缺失结果如何补齐。Gemini/OpenAI/Anthropic
 * 的外层 JSON 仍由各自 Adapter 负责，避免协议字段互相污染。
 */
internal object PiMessageTransformer {
    const val UPSTREAM_COMMIT = PiGeminiMessageAdapter.UPSTREAM_COMMIT

    fun transformForGemini(
        messages: List<AbstractApiMessage>,
        request: ChatRequest,
    ): List<AbstractApiMessage> = transform(messages, request) { id, _ ->
        if (PiGeminiMessageAdapter.requiresToolCallId(request.model)) {
            PiGeminiMessageAdapter.normalizeCrossProviderToolCallId(id)
        } else {
            id
        }
    }

    fun transform(
        messages: List<AbstractApiMessage>,
        request: ChatRequest,
        normalizeToolCallId: ((String, AgentAssistantApiMessage) -> String)? = null,
    ): List<AbstractApiMessage> {
        val normalized = messages.map { message -> message.sanitizeProtocolText(request) }
        val result = mutableListOf<AbstractApiMessage>()
        var pendingToolCalls = emptyList<com.android.everytalk.data.DataClass.AgentToolCallApiPart>()
        var existingToolResultIds = mutableSetOf<String>()

        /** 用户消息或下一条 Assistant 会关闭上一批工具；只给确实缺失的调用补失败结果。 */
        fun insertSyntheticToolResults() {
            pendingToolCalls.forEach { call ->
                if (call.id !in existingToolResultIds) {
                    result += AgentToolResultApiMessage(
                        id = "synthetic:${call.id}",
                        toolCallId = call.id,
                        toolName = call.name,
                        content = JsonPrimitive("No result provided"),
                        isError = true,
                    )
                }
            }
            pendingToolCalls = emptyList()
            existingToolResultIds = mutableSetOf()
        }

        normalized.forEach { message ->
            when (message) {
                is AgentAssistantApiMessage -> {
                    insertSyntheticToolResults()
                    if (message.stopReason !in setOf("error", "aborted")) {
                        pendingToolCalls = message.toolCalls
                        result += message
                    }
                }
                is AgentToolResultApiMessage -> {
                    existingToolResultIds += message.toolCallId
                    // 旧数据库可能只改了 Assistant 摘要，ToolResult 仍留着旧工具名。
                    // ID 已经完成配对时以真实 ToolCall 为准，避免 Gemini 的 name 校验失败。
                    val callName = pendingToolCalls.firstOrNull { it.id == message.toolCallId }?.name
                    result += if (callName == null) message else message.copy(toolName = callName, name = callName)
                }
                else -> {
                    if (message.role.equals("user", ignoreCase = true)) insertSyntheticToolResults()
                    result += message
                }
            }
        }
        insertSyntheticToolResults()
        return if (normalizeToolCallId == null) {
            result
        } else {
            normalizeToolCallIds(result, request, normalizeToolCallId)
        }
    }

    private fun AbstractApiMessage.sanitizeProtocolText(request: ChatRequest): AbstractApiMessage = when (this) {
        is SimpleTextApiMessage -> copy(content = content.piSanitizeSurrogates())
        is PartsApiMessage -> copy(parts = parts
            .map { part ->
                when (part) {
                    is ApiContentPart.Text -> part.copy(text = part.text.piSanitizeSurrogates())
                    is ApiContentPart.InlineData -> if ("image" in request.localInputModalities) {
                        part
                    } else {
                        ApiContentPart.Text("(image omitted: model does not support images)")
                    }
                    is ApiContentPart.FileUri -> part
                }
            }
            .collapseRepeatedImagePlaceholder("(image omitted: model does not support images)"))
        is AgentAssistantApiMessage -> canonicalize().let { canonical ->
            canonical.copy(
                text = canonical.text.piSanitizeSurrogates(),
                reasoning = canonical.reasoning.piSanitizeSurrogates(),
                contentParts = canonical.contentParts.map { part ->
                    when (part) {
                        is AgentAssistantContentApiPart.Text -> part.copy(text = part.text.piSanitizeSurrogates())
                        is AgentAssistantContentApiPart.Reasoning -> part.copy(text = part.text.piSanitizeSurrogates())
                        is AgentAssistantContentApiPart.ToolCall -> part
                    }
                },
            ).forTarget(request)
        }
        is AgentToolResultApiMessage -> copy(
            content = content.piSanitizeJsonStrings(),
            contentBlocks = canonicalContentBlocks()
                .map { block ->
                    when (block) {
                        is AgentToolResultContentApiPart.Text -> block.copy(text = block.text.piSanitizeSurrogates())
                        is AgentToolResultContentApiPart.Image -> if ("image" in request.localInputModalities) {
                            block
                        } else {
                            AgentToolResultContentApiPart.Text("(tool image omitted: model does not support images)")
                        }
                    }
                }
                .collapseRepeatedToolImagePlaceholder("(tool image omitted: model does not support images)"),
        )
    }

    private fun normalizeToolCallIds(
        messages: List<AbstractApiMessage>,
        request: ChatRequest,
        normalize: (String, AgentAssistantApiMessage) -> String,
    ): List<AbstractApiMessage> {
        val idMap = mutableMapOf<String, String>()
        return messages.map { message ->
            when (message) {
                is AgentAssistantApiMessage -> {
                    // Pi 只清洗跨模型/跨 Provider 的工具 ID。同源 Gemini ID 可能参与
                    // thought signature 的协议绑定，改写后下一轮回放会失去原始关联。
                    val shouldNormalize = !message.isSamePiSource(request)
                    val calls = message.toolCalls.map { call ->
                        val normalizedId = if (shouldNormalize) normalize(call.id, message) else call.id
                        idMap[call.id] = normalizedId
                        call.copy(id = normalizedId)
                    }
                    message.copy(
                        toolCalls = calls,
                        contentParts = message.contentParts.map { part ->
                            if (part is AgentAssistantContentApiPart.ToolCall) {
                                part.copy(
                                    call = part.call.copy(
                                        id = idMap[part.call.id] ?: if (shouldNormalize) {
                                            normalize(part.call.id, message)
                                        } else {
                                            part.call.id
                                        },
                                    ),
                                )
                            } else {
                                part
                            }
                        },
                    )
                }
                is AgentToolResultApiMessage -> message.copy(
                    // 没有对应 Assistant ToolCall 的孤立结果不凭空改 ID，保持 Pi 的映射规则。
                    toolCallId = idMap[message.toolCallId] ?: message.toolCallId,
                )
                else -> message
            }
        }
    }

    /** 旧数据的摘要和真实块可能分叉；迁移期只认按序保存的 contentParts。 */
    private fun AgentAssistantApiMessage.canonicalize(): AgentAssistantApiMessage {
        if (contentParts.isEmpty()) {
            return copy(
                contentParts = buildList {
                    reasoning.takeIf(String::isNotBlank)?.let {
                        add(AgentAssistantContentApiPart.Reasoning(it))
                    }
                    text.takeIf(String::isNotBlank)?.let { add(AgentAssistantContentApiPart.Text(it)) }
                    toolCalls.forEach { add(AgentAssistantContentApiPart.ToolCall(it)) }
                },
            )
        }
        val canonicalCalls = contentParts.mapNotNull { part ->
            (part as? AgentAssistantContentApiPart.ToolCall)?.call
        }
        return when {
            canonicalCalls.isEmpty() && toolCalls.isNotEmpty() -> copy(
                contentParts = contentParts + toolCalls.map { AgentAssistantContentApiPart.ToolCall(it) },
            )
            canonicalCalls == toolCalls -> this
            else -> copy(toolCalls = canonicalCalls)
        }
    }

    private fun AgentAssistantApiMessage.forTarget(request: ChatRequest): AgentAssistantApiMessage {
        if (isSamePiSource(request)) return this

        val hasSourceMetadata = sourceProvider != null || sourceEndpoint != null ||
            sourceModel != null || sourceProtocol != null
        if (!hasSourceMetadata) {
            // 旧版 Agent 记录没有 Provider 来源。OpenAI 兼容端点仍要保留 reasoning 类型，
            // 否则 DeepSeek 下一轮会丢失 reasoning_content；其余协议无法原生回放无签名思考，
            // 按 Pi 的跨协议规则降级成普通文本。所有不可证明来源的私有元数据都必须清除。
            val preserveCompatibleReasoning =
                modelParameterProtocol(request.channel) == com.android.everytalk.data.DataClass.ModelParameterProtocol.OPENAI_COMPATIBLE
            return copy(
                contentParts = contentParts.mapNotNull { part ->
                    when (part) {
                        is AgentAssistantContentApiPart.Text -> part.copy(thoughtSignature = null)
                        is AgentAssistantContentApiPart.Reasoning -> when {
                            part.redacted -> null
                            part.text.isBlank() -> null
                            preserveCompatibleReasoning -> part.copy(thoughtSignature = null)
                            else -> AgentAssistantContentApiPart.Text(part.text)
                        }
                        is AgentAssistantContentApiPart.ToolCall -> part.copy(
                            call = part.call.copy(thoughtSignature = null, namespace = null),
                        )
                    }
                },
                toolCalls = toolCalls.map { it.copy(thoughtSignature = null, namespace = null) },
            )
        }

        val transformedParts = contentParts.mapNotNull { part ->
            when (part) {
                is AgentAssistantContentApiPart.Text -> part.copy(thoughtSignature = null)
                is AgentAssistantContentApiPart.Reasoning -> when {
                    part.redacted -> null
                    part.text.isBlank() -> null
                    else -> AgentAssistantContentApiPart.Text(part.text)
                }
                is AgentAssistantContentApiPart.ToolCall -> part.copy(
                    call = part.call.copy(thoughtSignature = null),
                )
            }
        }
        return copy(
            contentParts = transformedParts,
            toolCalls = toolCalls.map { it.copy(thoughtSignature = null) },
        )
    }
}

/**
 * Pi 用 provider、api、model 判断原生元数据能否回放。EveryTalk 额外绑定 endpoint，
 * 防止两个代理地址之间误传签名。旧记录没有 sourceProtocol 时保持兼容；新记录必须协议一致。
 */
internal fun AgentAssistantApiMessage.isSamePiSource(
    request: ChatRequest,
    requireSameModel: Boolean = true,
): Boolean = sourceProvider == request.provider &&
    sourceEndpoint == request.apiAddress &&
    (!requireSameModel || sourceModel == request.model) &&
    (sourceProtocol == null || sourceProtocol == modelParameterProtocol(request.channel))

/** 旧记录完全没有来源字段时兼容；只要已经记录任一来源字段，就必须完整匹配当前目标。 */
internal fun AgentAssistantApiMessage.canReplayNativeContinuation(request: ChatRequest): Boolean {
    val hasSourceMetadata = sourceProvider != null || sourceEndpoint != null || sourceModel != null || sourceProtocol != null
    return !hasSourceMetadata || isSamePiSource(request)
}

private fun List<ApiContentPart>.collapseRepeatedImagePlaceholder(placeholder: String): List<ApiContentPart> =
    filterIndexed { index, part ->
        part !is ApiContentPart.Text || part.text != placeholder ||
            getOrNull(index - 1).let { previous -> previous !is ApiContentPart.Text || previous.text != placeholder }
    }

private fun List<AgentToolResultContentApiPart>.collapseRepeatedToolImagePlaceholder(
    placeholder: String,
): List<AgentToolResultContentApiPart> = filterIndexed { index, part ->
    part !is AgentToolResultContentApiPart.Text || part.text != placeholder ||
        getOrNull(index - 1).let { previous ->
            previous !is AgentToolResultContentApiPart.Text || previous.text != placeholder
        }
}

/** Pi sanitizeSurrogates，无正则扫描，合法 emoji 的代理对保持不变。 */
internal fun String.piSanitizeSurrogates(): String {
    var index = 0
    var changed = false
    val output = StringBuilder(length)
    while (index < length) {
        val character = this[index]
        when {
            character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                output.append(character).append(this[index + 1])
                index += 2
            }
            character.isSurrogate() -> {
                changed = true
                index++
            }
            else -> {
                output.append(character)
                index++
            }
        }
    }
    return if (changed) output.toString() else this
}

private fun JsonElement.piSanitizeJsonStrings(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.piSanitizeJsonStrings() })
    is JsonArray -> JsonArray(map(JsonElement::piSanitizeJsonStrings))
    is JsonPrimitive -> if (isString) JsonPrimitive(content.piSanitizeSurrogates()) else this
    JsonNull -> JsonNull
}

/**
 * Pi 的 ToolResult.content 永远是有序内容块。旧版 EveryTalk 只保存一个 JsonElement，
 * 这里在协议边界统一升级，避免四个 Provider 各自猜测空值和 JSON 字符串的含义。
 */
internal fun AgentToolResultApiMessage.canonicalContentBlocks(): List<AgentToolResultContentApiPart> =
    contentBlocks.ifEmpty {
        val text = when (content) {
            JsonNull -> ""
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            else -> content.toString()
        }
        listOf(AgentToolResultContentApiPart.Text(text))
    }
