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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Pi Anthropic Messages 的 Kotlin 等价 Adapter。 */
internal object PiAnthropicMessageAdapter {
    const val UPSTREAM_COMMIT = PiMessageTransformer.UPSTREAM_COMMIT
    private val INVALID_TOOL_CALL_ID_CHARACTER = Regex("[^a-zA-Z0-9_-]")
    private val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    fun buildMessages(messages: List<AbstractApiMessage>, request: ChatRequest): List<JsonObject> {
        val transformed = PiMessageTransformer.transform(messages, request) { id, _ ->
            normalizeToolCallId(id)
        }
        return buildList {
            var index = 0
            while (index < transformed.size) {
                val message = transformed[index]
                if (message is AgentToolResultApiMessage) {
                    val results = transformed.drop(index)
                        .takeWhile { it is AgentToolResultApiMessage }
                        .filterIsInstance<AgentToolResultApiMessage>()
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            results.forEach { result -> addToolResult(result) }
                        })
                    })
                    index += results.size
                    continue
                }
                message.toMessage(request)?.let(::add)
                index++
            }
        }
    }

    private fun AbstractApiMessage.toMessage(request: ChatRequest): JsonObject? {
        val content = when (this) {
            is SimpleTextApiMessage -> buildJsonArray {
                if (this@toMessage.content.isNotBlank()) add(buildJsonObject {
                    put("type", "text")
                    put("text", this@toMessage.content)
                })
            }
            is PartsApiMessage -> buildJsonArray {
                parts.forEach { part ->
                    when (part) {
                        is ApiContentPart.Text -> if (part.text.isNotBlank()) add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        })
                        is ApiContentPart.InlineData -> addInlineData(part)
                        is ApiContentPart.FileUri -> add(buildJsonObject {
                            put("type", "text")
                            put("text", "[附件: ${part.uri}]")
                        })
                    }
                }
            }
            is AgentAssistantApiMessage -> assistantBlocks(request)
            is AgentToolResultApiMessage -> error("ToolResult 必须按连续批次转换")
        }
        if (content.isEmpty()) return null
        return buildJsonObject {
            put("role", if (role.equals("assistant", true) || role.equals("model", true)) "assistant" else "user")
            put("content", content)
        }
    }

    private fun AgentAssistantApiMessage.assistantBlocks(request: ChatRequest): JsonArray = buildJsonArray {
        val sameSource = isSamePiSource(request)
        val blocks = if (contentParts.isNotEmpty()) contentParts else buildList {
            reasoning.takeIf(String::isNotBlank)?.let { add(AgentAssistantContentApiPart.Reasoning(it)) }
            text.takeIf(String::isNotBlank)?.let { add(AgentAssistantContentApiPart.Text(it)) }
            toolCalls.forEach { add(AgentAssistantContentApiPart.ToolCall(it)) }
        }
        blocks.forEach { block ->
            when (block) {
                is AgentAssistantContentApiPart.Text -> if (block.text.isNotBlank()) add(buildJsonObject {
                    put("type", "text")
                    put("text", block.text)
                })
                is AgentAssistantContentApiPart.Reasoning -> {
                    val signature = block.thoughtSignature?.takeIf { sameSource && it.isNotBlank() }
                    if (block.redacted && signature != null) add(buildJsonObject {
                        put("type", "redacted_thinking")
                        put("data", signature)
                    }) else if (signature != null) add(buildJsonObject {
                        put("type", "thinking")
                        put("thinking", block.text)
                        put("signature", signature)
                    }) else if (sameSource && request.localAllowEmptyAnthropicThinkingSignature) add(buildJsonObject {
                        put("type", "thinking")
                        put("thinking", block.text)
                        put("signature", "")
                    }) else if (block.text.isNotBlank()) add(buildJsonObject {
                        put("type", "text")
                        put("text", block.text)
                    })
                }
                is AgentAssistantContentApiPart.ToolCall -> add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", block.call.id)
                    put("name", block.call.name)
                    put("input", block.call.arguments)
                })
            }
        }
    }

    private fun JsonArrayBuilder.addToolResult(result: AgentToolResultApiMessage) {
        val blocks = result.canonicalContentBlocks()
        val rawImages = blocks.filterIsInstance<AgentToolResultContentApiPart.Image>()
        val images = rawImages.mapNotNull { image ->
            image.copy(mimeType = image.mimeType.normalizeAnthropicImageMime())
                .takeIf { it.mimeType in SUPPORTED_IMAGE_MIME_TYPES }
        }
        val text = buildList {
            addAll(blocks.filterIsInstance<AgentToolResultContentApiPart.Text>().map { it.text })
            rawImages.filter { it.mimeType.normalizeAnthropicImageMime() !in SUPPORTED_IMAGE_MIME_TYPES }
                .forEach { image -> add("[unsupported tool image type: ${image.mimeType}]") }
        }.joinToString("\n")
        add(buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", result.toolCallId)
            if (images.isEmpty()) {
                put("content", text)
            } else {
                put("content", buildJsonArray {
                    if (text.isNotEmpty()) add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                    images.forEach { image ->
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", image.mimeType)
                                put("data", image.data.substringAfter(";base64,", image.data))
                            }
                        })
                    }
                    if (text.isEmpty()) add(buildJsonObject {
                        put("type", "text")
                        put("text", "(see attached image)")
                    })
                })
            }
            if (result.isError) put("is_error", true)
        })
    }

    private fun JsonArrayBuilder.addInlineData(part: ApiContentPart.InlineData) {
        val mimeType = if (part.mimeType.equals("image/jpg", true)) "image/jpeg" else part.mimeType.lowercase()
        if (mimeType in setOf("image/jpeg", "image/png", "image/gif", "image/webp")) {
            add(buildJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", mimeType)
                    put("data", part.base64Data.substringAfter(";base64,", part.base64Data))
                }
            })
        } else {
            add(buildJsonObject {
                put("type", "text")
                put("text", "[不支持直接发送的附件类型: $mimeType]")
            })
        }
    }

    private fun normalizeToolCallId(id: String): String =
        id.replace(INVALID_TOOL_CALL_ID_CHARACTER, "_").take(64)

    private fun String.normalizeAnthropicImageMime(): String =
        if (equals("image/jpg", true)) "image/jpeg" else lowercase()
}
