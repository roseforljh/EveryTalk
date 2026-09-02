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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Pi `openai-responses-shared.ts` 的消息转换部分。 */
internal object PiOpenAIResponsesMessageAdapter {
    const val UPSTREAM_COMMIT = PiMessageTransformer.UPSTREAM_COMMIT
    private val INVALID_ID_CHARACTER = Regex("[^a-zA-Z0-9_-]")

    fun buildInput(messages: List<AbstractApiMessage>, request: ChatRequest): List<JsonElement> {
        return buildTransformedInput(transformMessages(messages, request), request)
    }

    internal fun transformMessages(
        messages: List<AbstractApiMessage>,
        request: ChatRequest,
    ): List<AbstractApiMessage> = PiMessageTransformer.transform(messages, request) { id, source ->
            normalizeCrossProviderToolCallId(id, source, request)
        }

    /** 调用方已经统一转换时使用，避免外来 Responses item ID 被重复哈希。 */
    internal fun buildTransformedInput(
        transformed: List<AbstractApiMessage>,
        request: ChatRequest,
    ): List<JsonElement> {
        val toolIdMap = mutableMapOf<String, ResponsesToolId>()
        return buildList {
            transformed.forEachIndexed { messageIndex, message ->
                when (message) {
                    is SimpleTextApiMessage -> if (message.content.isNotEmpty()) {
                        if (message.role.equals("assistant", true) || message.role.equals("model", true)) {
                            add(message.toLegacyAssistantOutput(messageIndex))
                        } else {
                            add(buildJsonObject {
                                put("role", message.role)
                                putJsonArray("content") {
                                    add(buildJsonObject {
                                        put("type", "input_text")
                                        put("text", message.content)
                                    })
                                }
                            })
                        }
                    }
                    is PartsApiMessage -> message.toInputItem()?.let(::add)
                    is AgentAssistantApiMessage -> addAll(
                        message.toOutputItems(messageIndex, request, toolIdMap),
                    )
                    is AgentToolResultApiMessage -> {
                        val id = toolIdMap[message.toolCallId]
                            ?: ResponsesToolId(normalizeIdPart(message.toolCallId.substringBefore('|')), null)
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", id.callId)
                            put("output", message.toToolOutput(request))
                        })
                    }
                }
            }
        }
    }

    /** EveryTalk 旧历史里的 assistant 是纯文本，转成 Responses 的标准 output message。 */
    private fun SimpleTextApiMessage.toLegacyAssistantOutput(messageIndex: Int): JsonObject {
        val rawId = id.takeIf(String::isNotBlank) ?: "msg_pi_$messageIndex"
        val messageId = when {
            rawId.startsWith("msg_") && rawId.length <= 64 -> rawId
            else -> "msg_${PiOpenAIChatMessageAdapter.shortHash(rawId)}"
        }
        return buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            put("status", "completed")
            put("id", messageId)
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "output_text")
                    put("text", content)
                    put("annotations", JsonArray(emptyList()))
                })
            }
        }
    }

    private fun PartsApiMessage.toInputItem(): JsonObject? {
        val supported = parts.filterNot { it is ApiContentPart.FileUri && it.mimeType == "qwen-file-id" }
        if (supported.isEmpty()) return null
        return buildJsonObject {
            put("role", role)
            putJsonArray("content") {
                supported.forEach { part ->
                    when (part) {
                        is ApiContentPart.Text -> add(buildJsonObject {
                            put("type", "input_text")
                            put("text", part.text)
                        })
                        is ApiContentPart.InlineData -> add(buildJsonObject {
                            put("type", "input_image")
                            put("detail", "auto")
                            put("image_url", "data:${part.mimeType};base64,${part.base64Data}")
                        })
                        is ApiContentPart.FileUri -> add(buildJsonObject {
                            put("type", "input_text")
                            put("text", "[Attachment: ${part.uri}]")
                        })
                    }
                }
            }
        }
    }

    private fun AgentAssistantApiMessage.toOutputItems(
        messageIndex: Int,
        request: ChatRequest,
        toolIdMap: MutableMap<String, ResponsesToolId>,
    ): List<JsonElement> {
        val sameProviderAndApi = isSamePiSource(request, requireSameModel = false)
        val sameModel = sameProviderAndApi && sourceModel == request.model
        val differentModel = sameProviderAndApi && sourceModel != null && sourceModel != request.model
        val blocks = if (contentParts.isNotEmpty()) contentParts else buildList {
            reasoning.takeIf(String::isNotBlank)?.let { add(AgentAssistantContentApiPart.Reasoning(it)) }
            text.takeIf(String::isNotBlank)?.let { add(AgentAssistantContentApiPart.Text(it)) }
            toolCalls.forEach { add(AgentAssistantContentApiPart.ToolCall(it)) }
        }
        var textIndex = 0
        return buildList {
            blocks.forEach { block ->
                when (block) {
                    is AgentAssistantContentApiPart.Reasoning -> {
                        if (sameProviderAndApi) block.thoughtSignature?.parseJsonObject()?.let(::add)
                    }
                    is AgentAssistantContentApiPart.Text -> if (block.text.isNotEmpty()) {
                        val signature = block.thoughtSignature.parseTextSignature()
                        var messageId = signature?.id
                            ?: if (textIndex == 0) "msg_pi_$messageIndex" else "msg_pi_${messageIndex}_$textIndex"
                        textIndex++
                        if (messageId.length > 64) {
                            messageId = "msg_${PiOpenAIChatMessageAdapter.shortHash(messageId)}"
                        }
                        add(buildJsonObject {
                            put("type", "message")
                            put("role", "assistant")
                            put("status", "completed")
                            put("id", messageId)
                            signature?.phase?.let { put("phase", it) }
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "output_text")
                                    put("text", block.text)
                                    put("annotations", JsonArray(emptyList()))
                                })
                            }
                        })
                    }
                    is AgentAssistantContentApiPart.ToolCall -> {
                        val id = transformedToolId(block.call.id)
                        toolIdMap[block.call.id] = id
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", id.callId)
                            var itemId = id.itemId
                            if (differentModel && itemId?.startsWith("fc_") == true) itemId = null
                            itemId?.takeIf { it.startsWith("fc_") }?.let { put("id", it) }
                            put("name", block.call.name)
                            put("arguments", block.call.arguments.toString())
                            if (sameModel) block.call.namespace?.let { put("namespace", it) }
                        })
                    }
                }
            }
        }
    }

    private fun normalizeCrossProviderToolCallId(
        rawId: String,
        source: AgentAssistantApiMessage,
        request: ChatRequest,
    ): String {
        val callId = normalizeIdPart(rawId.substringBefore('|'))
        val rawItemId = rawId.substringAfter('|', missingDelimiterValue = "").takeIf(String::isNotEmpty)
            ?: return callId
        val foreign = !source.isSamePiSource(request, requireSameModel = false)
        var itemId = if (foreign) {
            "fc_${PiOpenAIChatMessageAdapter.shortHash(rawItemId)}"
        } else {
            normalizeIdPart(rawItemId)
        }
        if (!itemId.startsWith("fc_")) itemId = normalizeIdPart("fc_$itemId")
        return "$callId|$itemId"
    }

    /** 公共转换层已处理跨源 ID；这里必须原样拆分同源 Responses 的配对标识。 */
    private fun transformedToolId(rawId: String): ResponsesToolId {
        val callId = rawId.substringBefore('|')
        val itemId = rawId.substringAfter('|', missingDelimiterValue = "").takeIf(String::isNotEmpty)
        return ResponsesToolId(callId, itemId)
    }

    private fun normalizeIdPart(value: String): String =
        value.replace(INVALID_ID_CHARACTER, "_").take(64).trimEnd('_')

    private fun AgentToolResultApiMessage.toToolOutput(request: ChatRequest): JsonElement {
        val blocks = canonicalContentBlocks()
        val text = blocks.filterIsInstance<AgentToolResultContentApiPart.Text>()
            .joinToString("\n", transform = AgentToolResultContentApiPart.Text::text)
        val images = blocks.filterIsInstance<AgentToolResultContentApiPart.Image>()
        if (images.isEmpty() || "image" !in request.localInputModalities) {
            return JsonPrimitive(
                when {
                    text.isNotEmpty() -> text
                    images.isNotEmpty() -> "(see attached image)"
                    else -> "(no tool output)"
                },
            )
        }
        return buildJsonArray {
            if (text.isNotEmpty()) add(buildJsonObject {
                put("type", "input_text")
                put("text", text)
            })
            images.forEach { image -> add(buildJsonObject {
                put("type", "input_image")
                put("detail", "auto")
                put("image_url", "data:${image.mimeType};base64,${image.data.substringAfter(";base64,", image.data)}")
            }) }
        }
    }

    private fun String?.parseJsonObject(): JsonObject? = this?.let { raw ->
        runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    private fun String?.parseTextSignature(): TextSignature? {
        if (isNullOrEmpty()) return null
        val parsed = parseJsonObject()
        if (parsed?.get("v")?.toString() == "1") {
            val id = (parsed["id"] as? JsonPrimitive)?.contentOrNull ?: return null
            val phase = (parsed["phase"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it == "commentary" || it == "final_answer" }
            return TextSignature(id, phase)
        }
        return TextSignature(this, null)
    }

    private data class ResponsesToolId(val callId: String, val itemId: String?)
    private data class TextSignature(val id: String, val phase: String?)
}
