package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantContentApiPart
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultContentApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pi `packages/ai/src/api/google-shared.ts` 的 Kotlin 等价 Adapter。
 *
 * Interface 只接收 EveryTalk 的中立消息和当前请求，返回 Gemini Content[]。
 * 版本、工具 ID、签名、原生 continuation 和 FunctionResponse 规则全部封装在这里。
 */
internal object PiGeminiMessageAdapter {
    /** 当前实现逐项对齐的 Pi 上游提交。升级时必须先更新金标测试。 */
    const val UPSTREAM_COMMIT = "b8b873b9872db04a938fb4357b5e8e824ddc051c"

    private const val LOCAL_TOOL_CALL_ID_PREFIX = "fc_local_"
    private val GEMINI_MODEL_VERSION = Regex("^gemini(?:-live)?-(\\d+)", RegexOption.IGNORE_CASE)
    private val INVALID_TOOL_CALL_ID_CHARACTER = Regex("[^a-zA-Z0-9_-]")
    private val PART_DATA_FIELDS = setOf(
        "text",
        "inlineData",
        "fileData",
        "functionCall",
        "functionResponse",
        "executableCode",
        "codeExecutionResult",
    )

    fun buildContents(
        messages: List<AbstractApiMessage>,
        request: ChatRequest,
    ): JsonArray {
        val transformedMessages = PiMessageTransformer.transformForGemini(messages, request)
        val latestAssistant = transformedMessages.filterIsInstance<AgentAssistantApiMessage>().lastOrNull()
        val nativeAssistant = request.localProviderContinuation
            ?.takeIf { it.protocol == ModelParameterProtocol.GEMINI }
            ?.takeIf { latestAssistant?.canReplayNativeContinuation(request) == true }
            ?.takeIf { it.assistantMessageId == null || it.assistantMessageId == latestAssistant?.id }
            ?.payloadJson
            ?.let { raw -> runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() }
            ?.takeIf { content -> latestAssistant != null && content.matchesAssistant(latestAssistant) }
        val nativeAssistantId = latestAssistant?.id?.takeIf { nativeAssistant != null }
        val includeToolCallIds = requiresToolCallId(request.model)

        val contents = mutableListOf<JsonObject>()
        var messageIndex = 0
        while (messageIndex < transformedMessages.size) {
            val message = transformedMessages[messageIndex]
            if (message is AgentToolResultApiMessage) {
                val results = transformedMessages.drop(messageIndex)
                    .takeWhile { it is AgentToolResultApiMessage }
                    .filterIsInstance<AgentToolResultApiMessage>()
                results.forEach { result ->
                    appendFunctionResponse(
                        contents = contents,
                        part = result.toFunctionResponsePart(request.model, includeToolCallIds),
                    )
                    if (!supportsMultimodalFunctionResponse(request.model)) {
                        result.toolResultImages().takeIf(List<*>::isNotEmpty)?.let { images ->
                            appendContent(contents, buildJsonObject {
                                put("role", "user")
                                putJsonArray("parts") {
                                    add(buildJsonObject { put("text", "Tool result image:") })
                                    images.forEach { image -> add(image.toInlineDataPart()) }
                                }
                            })
                        }
                    }
                }
                messageIndex += results.size
                continue
            }

            val content = when {
                message is AgentAssistantApiMessage && message.id == nativeAssistantId -> {
                    checkNotNull(nativeAssistant).withToolCallIds(
                        assistant = message,
                        includeToolCallIds = includeToolCallIds,
                    )
                }
                message is AgentAssistantApiMessage -> message.toAssistantContent(
                    request = request,
                    includeToolCallIds = includeToolCallIds,
                )
                else -> message.toContent()
            }
            appendContent(contents, content)
            messageIndex++
        }
        return JsonArray(contents)
    }

    /** 请求发送前最后一道 Part 边界检查，覆盖 contents 和 systemInstruction。 */
    internal fun normalizePayload(payload: JsonObject): JsonObject {
        val normalized = JsonObject(payload.toMutableMap().apply {
            val contents = this["contents"] as? JsonArray
            if (contents != null) {
                put("contents", JsonArray(contents.mapNotNull { element ->
                    normalizeContentObject(element as? JsonObject ?: return@mapNotNull null)
                }))
            }
            val systemInstruction = this["systemInstruction"] as? JsonObject
            if (systemInstruction != null) {
                normalizeContentObject(systemInstruction)?.let { put("systemInstruction", it) }
                    ?: remove("systemInstruction")
            }
        })
        validatePayloadParts(normalized)
        return normalized
    }

    /** 与 Pi requiresToolCallId 保持一致。 */
    internal fun requiresToolCallId(modelId: String): Boolean {
        val majorVersion = GEMINI_MODEL_VERSION.find(modelId)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return modelId.startsWith("claude-", ignoreCase = true) ||
            modelId.startsWith("gpt-oss-", ignoreCase = true) ||
            majorVersion?.let { it >= 3 } == true
    }

    /** Pi 对未知兼容模型保持支持；明确的 Gemini 1/2 才走独立图片消息。 */
    internal fun supportsMultimodalFunctionResponse(modelId: String): Boolean {
        val majorVersion = GEMINI_MODEL_VERSION.find(modelId)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return majorVersion == null || majorVersion >= 3
    }

    internal fun normalizeCrossProviderToolCallId(id: String): String =
        id.replace(INVALID_TOOL_CALL_ID_CHARACTER, "_").take(64)

    private fun normalizeToolCallId(id: String, required: Boolean): String =
        if (!required) id else normalizeCrossProviderToolCallId(id)

    /**
     * 原生 Part 和顺序继续作为事实源，只按 Pi 的目标模型规则补齐或移除工具 ID。
     * Gemini 3 流里缺少服务端 ID 时，AgentLoop 生成的稳定 ID 会同时写回 Call 与 Response。
     */
    private fun JsonObject.withToolCallIds(
        assistant: AgentAssistantApiMessage,
        includeToolCallIds: Boolean,
    ): JsonObject {
        val parts = this["parts"] as? JsonArray ?: return this
        var toolCallIndex = 0
        return JsonObject(toMutableMap().apply {
            put("parts", JsonArray(parts.map { element ->
                val part = element as? JsonObject ?: return@map element
                val functionCall = part["functionCall"] as? JsonObject ?: return@map part
                val call = assistant.toolCalls.getOrNull(toolCallIndex++)
                JsonObject(part.toMutableMap().apply {
                    put("functionCall", JsonObject(functionCall.toMutableMap().apply {
                        if (includeToolCallIds && call != null) {
                            // buildContents 前已按 Pi transformMessages 完成跨源 ID 清洗。
                            // 原生 continuation 的同源 ID 必须原样回放，禁止二次改写。
                            put("id", JsonPrimitive(call.id))
                        } else {
                            remove("id")
                        }
                    }))
                })
            }))
        })
    }

    private fun AgentAssistantApiMessage.toAssistantContent(
        request: ChatRequest,
        includeToolCallIds: Boolean,
    ): JsonObject = buildJsonObject {
        put("role", "model")
        putJsonArray("parts") {
            // EveryTalk 还校验 endpoint，防止同 provider/model 的不同代理共享不可移植签名。
            val sameSource = isSamePiSource(request)
            if (contentParts.isNotEmpty()) {
                contentParts.forEach { part ->
                    when (part) {
                        is AgentAssistantContentApiPart.Text -> {
                            val signature = part.thoughtSignature.validForReplay(sameSource)
                            if (part.text.isNotBlank() || signature != null) add(buildJsonObject {
                                put("text", part.text)
                                signature?.let { put("thoughtSignature", it) }
                            })
                        }
                        is AgentAssistantContentApiPart.Reasoning -> {
                            val signature = part.thoughtSignature.validForReplay(sameSource)
                            if (sameSource && (part.text.isNotBlank() || signature != null)) {
                                add(buildJsonObject {
                                    put("thought", true)
                                    put("text", part.text)
                                    signature?.let { put("thoughtSignature", it) }
                                })
                            } else if (part.text.isNotBlank()) {
                                add(buildJsonObject { put("text", part.text) })
                            }
                        }
                        is AgentAssistantContentApiPart.ToolCall -> addFunctionCall(
                            call = part.call,
                            includeId = includeToolCallIds,
                            includeSignature = sameSource,
                            normalizeId = !sameSource,
                        )
                    }
                }
            } else {
                reasoning.takeIf(String::isNotBlank)?.let { add(buildJsonObject { put("text", it) }) }
                text.takeIf(String::isNotBlank)?.let { add(buildJsonObject { put("text", it) }) }
                toolCalls.forEach { call ->
                    addFunctionCall(call, includeToolCallIds, sameSource, normalizeId = !sameSource)
                }
            }
        }
    }

    private fun JsonArrayBuilder.addFunctionCall(
        call: AgentToolCallApiPart,
        includeId: Boolean,
        includeSignature: Boolean,
        normalizeId: Boolean,
    ) {
        add(buildJsonObject {
            putJsonObject("functionCall") {
                if (includeId) {
                    put("id", if (normalizeId) normalizeToolCallId(call.id, required = true) else call.id)
                }
                put("name", call.name)
                put("args", call.arguments)
            }
            call.thoughtSignature.validForReplay(includeSignature)?.let { put("thoughtSignature", it) }
        })
    }

    private fun String?.validForReplay(sameSource: Boolean): String? = takeIf { signature ->
        sameSource && signature.isValidGoogleThoughtSignature()
    }

    /** Google 的 bytes JSON 字段只接受标准 Base64。原生 continuation 也必须走同一校验。 */
    private fun String?.isValidGoogleThoughtSignature(): Boolean {
        val value = this ?: return false
        if (value.isEmpty() || value.length % 4 != 0) return false
        val firstPadding = value.indexOf('=')
        if (firstPadding >= 0 && (
                firstPadding < value.length - 2 ||
                    value.substring(firstPadding).any { it != '=' }
                )
        ) return false
        if (value.any { character ->
                character !in 'A'..'Z' && character !in 'a'..'z' && character !in '0'..'9' &&
                    character != '+' && character != '/' && character != '='
            }
        ) return false
        return runCatching { Base64.getDecoder().decode(value) }.isSuccess
    }

    private fun JsonObject.matchesAssistant(assistant: AgentAssistantApiMessage): Boolean {
        if (this["role"]?.jsonPrimitive?.contentOrNull != "model") return false
        if (assistant.toolCalls.isEmpty()) {
            val nativeParts = (this["parts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val nativeText = nativeParts
                .filter { it["thought"]?.jsonPrimitive?.booleanOrNull != true }
                .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                .joinToString("")
            val nativeReasoning = nativeParts
                .filter { it["thought"]?.jsonPrimitive?.booleanOrNull == true }
                .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                .joinToString("")
            return nativeText == assistant.text && nativeReasoning == assistant.reasoning
        }
        val nativeCalls = (this["parts"] as? JsonArray).orEmpty().mapNotNull { part ->
            (part as? JsonObject)?.get("functionCall") as? JsonObject
        }
        if (nativeCalls.size != assistant.toolCalls.size) return false
        return nativeCalls.zip(assistant.toolCalls).all { (nativeCall, call) ->
            val nativeName = nativeCall["name"]?.jsonPrimitive?.contentOrNull
            val nativeId = nativeCall["id"]?.jsonPrimitive?.contentOrNull
            nativeName == call.name && if (call.id.startsWith(LOCAL_TOOL_CALL_ID_PREFIX)) {
                nativeId.isNullOrBlank()
            } else {
                nativeId == call.id
            }
        }
    }

    private fun AbstractApiMessage.toContent(): JsonObject = buildJsonObject {
        put("role", if (role == "assistant") "model" else role)
        putJsonArray("parts") {
            when (this@toContent) {
                is SimpleTextApiMessage -> add(buildJsonObject { put("text", content) })
                is PartsApiMessage -> parts.forEach { part ->
                    when (part) {
                        is ApiContentPart.Text -> add(buildJsonObject { put("text", part.text) })
                        is ApiContentPart.InlineData -> add(buildJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", part.mimeType)
                                put("data", part.base64Data)
                            }
                        })
                        is ApiContentPart.FileUri -> add(buildJsonObject {
                            put("text", "[Image: ${part.uri}]")
                        })
                    }
                }
                is AgentAssistantApiMessage -> error("Assistant 应由 toAssistantContent 处理")
                is AgentToolResultApiMessage -> error("工具结果应按连续批次处理")
            }
        }
    }

    private fun AgentToolResultApiMessage.toFunctionResponsePart(
        modelId: String,
        includeToolCallIds: Boolean,
    ): JsonObject {
        val blocks = canonicalContentBlocks()
        val text = blocks.filterIsInstance<AgentToolResultContentApiPart.Text>()
            .joinToString("\n", transform = AgentToolResultContentApiPart.Text::text)
        val images = toolResultImages()
        val responseValue = when {
            text.isNotEmpty() -> text
            images.isNotEmpty() -> "(see attached image)"
            else -> ""
        }
        return buildJsonObject {
            putJsonObject("functionResponse") {
                put("name", toolName)
                // ToolResult ID 已由 transformMessages 使用和 ToolCall 相同的映射处理。
                if (includeToolCallIds) put("id", toolCallId)
                putJsonObject("response") {
                    if (isError) put("error", responseValue) else put("output", responseValue)
                }
                if (images.isNotEmpty() && supportsMultimodalFunctionResponse(modelId)) {
                    putJsonArray("parts") { images.forEach { image -> add(image.toInlineDataPart()) } }
                }
            }
        }
    }

    private fun AgentToolResultApiMessage.toolResultImages(): List<AgentToolResultContentApiPart.Image> =
        canonicalContentBlocks().filterIsInstance<AgentToolResultContentApiPart.Image>()

    private fun AgentToolResultContentApiPart.Image.toInlineDataPart(): JsonObject = buildJsonObject {
        putJsonObject("inlineData") {
            put("mimeType", mimeType)
            put("data", data.substringAfter(";base64,", data))
        }
    }

    /** Cloud Code Assist 和 Gemini 都要求连续 FunctionResponse 合并在同一个 user turn。 */
    private fun appendFunctionResponse(contents: MutableList<JsonObject>, part: JsonObject) {
        val previous = contents.lastOrNull()
        val previousParts = previous?.get("parts") as? JsonArray
        if (
            previous?.get("role")?.jsonPrimitive?.contentOrNull == "user" &&
            previousParts.orEmpty().any { (it as? JsonObject)?.get("functionResponse") != null }
        ) {
            contents[contents.lastIndex] = JsonObject(previous.toMutableMap().apply {
                put("parts", JsonArray(previousParts.orEmpty() + part))
            }).normalizeForRequest()
        } else {
            contents += buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray { add(part) })
            }.normalizeForRequest()
        }
    }

    private fun appendContent(contents: MutableList<JsonObject>, content: JsonObject) {
        val normalizedContent = content.normalizeForRequest()
        val parts = normalizedContent["parts"] as? JsonArray ?: return
        if (parts.isEmpty()) return
        // Pi 保留原消息边界。相邻 user/model 回合不能在协议层偷偷拼成一条消息。
        contents += normalizedContent
    }

    /**
     * 每个 Gemini Part 都必须初始化 data oneof；签名单独成块时用空 text 承载。
     * FunctionResponse.parts 也是 Part 数组，必须递归校验，不能只校验 Content 顶层。
     */
    private fun JsonObject.normalizeForRequest(): JsonObject =
        normalizeContentObject(this) ?: JsonObject(emptyMap())

    private fun normalizeContentObject(content: JsonObject): JsonObject? {
        val parts = content["parts"] as? JsonArray ?: return content
        val normalizedParts = parts.mapNotNull { element ->
            normalizePart(element as? JsonObject ?: return@mapNotNull null)
        }
        if (normalizedParts.isEmpty()) return null
        return JsonObject(content.toMutableMap().apply {
            put("parts", JsonArray(normalizedParts))
        })
    }

    /**
     * 发送前验证最终 JSON，不打印正文。以后任何新消息入口漏过清理时，也会在本地按路径失败，
     * 不再把含空 Part 的请求交给 Google 后才收到含糊的 protobuf oneof 400。
     */
    private fun validatePayloadParts(payload: JsonObject) {
        fun validatePart(part: JsonObject, path: String) {
            val initializedFields = PART_DATA_FIELDS.filter { field ->
                part[field]?.let { it !is JsonNull } == true
            }
            require(initializedFields.size == 1) {
                "$path 必须且只能初始化一个 Gemini Part data 字段"
            }
            val functionResponse = part["functionResponse"] as? JsonObject ?: return
            (functionResponse["parts"] as? JsonArray).orEmpty().forEachIndexed { index, nested ->
                validatePart(nested as? JsonObject ?: error("$path.functionResponse.parts[$index] 不是对象"),
                    "$path.functionResponse.parts[$index]")
            }
        }

        fun validateContent(content: JsonObject, path: String) {
            val parts = content["parts"] as? JsonArray
                ?: error("$path 缺少 parts")
            require(parts.isNotEmpty()) { "$path.parts 不能为空" }
            parts.forEachIndexed { index, element ->
                validatePart(element as? JsonObject ?: error("$path.parts[$index] 不是对象"),
                    "$path.parts[$index]")
            }
        }

        (payload["contents"] as? JsonArray).orEmpty().forEachIndexed { index, element ->
            validateContent(element as? JsonObject ?: error("contents[$index] 不是对象"), "contents[$index]")
        }
        (payload["systemInstruction"] as? JsonObject)?.let { validateContent(it, "systemInstruction") }
    }

    /** 递归清理一个 Part，避免嵌套 FunctionResponse.parts 产生零 data oneof。 */
    private fun normalizePart(part: JsonObject): JsonObject? {
        val normalizedPart = JsonObject(part.toMutableMap().apply {
            val signature = (get("thoughtSignature") as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
            if (!signature.isValidGoogleThoughtSignature()) remove("thoughtSignature")
        })
        val initializedFields = PART_DATA_FIELDS.filter { field ->
            normalizedPart[field]?.let { it !is JsonNull } == true
        }
        if (initializedFields.isEmpty()) {
            val signature = normalizedPart["thoughtSignature"]?.jsonPrimitive?.contentOrNull
            return if (signature.isNullOrEmpty()) {
                null
            } else {
                JsonObject(normalizedPart.toMutableMap().apply { put("text", JsonPrimitive("")) })
            }
        }
        check(initializedFields.size == 1) {
            "Gemini content part 必须且只能初始化一个 data 字段"
        }

        val functionResponse = normalizedPart["functionResponse"] as? JsonObject
        if (functionResponse != null && functionResponse["parts"] is JsonArray) {
            val nested = (functionResponse["parts"] as JsonArray).mapNotNull { element ->
                normalizePart(element as? JsonObject ?: return@mapNotNull null)
            }
            val normalizedResponse = JsonObject(functionResponse.toMutableMap().apply {
                if (nested.isEmpty()) remove("parts") else put("parts", JsonArray(nested))
            })
            return JsonObject(normalizedPart.toMutableMap().apply {
                put("functionResponse", normalizedResponse)
            })
        }
        return normalizedPart
    }
}
