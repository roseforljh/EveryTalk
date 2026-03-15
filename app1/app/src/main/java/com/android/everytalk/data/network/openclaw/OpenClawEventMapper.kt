package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.network.AppStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object OpenClawEventMapper {

    fun mapChatEvent(payload: String, json: Json): AppStreamEvent? {
        return try {
            val root = json.parseToJsonElement(payload).jsonObject
            val eventName = root["event"]?.jsonPrimitive?.content.orEmpty()
            val envelopeType = root["type"]?.jsonPrimitive?.content.orEmpty()
            val payloadData = root["payload"]?.jsonObject
            val data = payloadData?.get("data")?.jsonObject ?: root["data"]?.jsonObject ?: root
            val type = data["type"]?.jsonPrimitive?.content.orEmpty()
            val state = data["state"]?.jsonPrimitive?.content.orEmpty()

            when {
                envelopeType == "event" && eventName == "connect.challenge" -> null
                eventName == "agent" && payloadData != null -> {
                    val stream = payloadData["stream"]?.jsonPrimitive?.content.orEmpty()
                    val runId = payloadData["runId"]?.jsonPrimitive?.content
                        ?: data["runId"]?.jsonPrimitive?.content
                    val delta = extractAgentDelta(data)
                    if (stream == "assistant" && delta.isNotBlank()) {
                        AppStreamEvent.Content(delta)
                    } else if (runId != null) {
                        AppStreamEvent.StatusUpdate("agent_run:$runId")
                    } else {
                        null
                    }
                }
                eventName == "connect.result" || type.startsWith("pairing.") || type in setOf("connected", "connect.ok") -> {
                    mapConnectEvent(type = type, data = data)
                }
                eventName == "connect.result" && payloadData != null -> {
                    val payloadType = payloadData["type"]?.jsonPrimitive?.content.orEmpty()
                    mapConnectEvent(type = payloadType, data = payloadData)
                }
                eventName == "chat.history" || type == "history" || type == "chat.history" -> {
                    val count = data["messages"]?.let { element ->
                        runCatching { element.jsonObject.size }.getOrDefault(0)
                    } ?: 0
                    AppStreamEvent.StatusUpdate("history_loaded:$count")
                }
                eventName == "chat.subscribed" || type == "subscribed" || type == "chat.subscribed" -> {
                    AppStreamEvent.StatusUpdate("subscribed")
                }
                eventName == "chat" && payloadData != null -> {
                    val chatState = payloadData["state"]?.jsonPrimitive?.content.orEmpty()
                        .ifBlank { data["state"]?.jsonPrimitive?.content.orEmpty() }
                    when (chatState) {
                        "final" -> AppStreamEvent.Finish(
                            payloadData["reason"]?.jsonPrimitive?.content
                                ?: data["reason"]?.jsonPrimitive?.content
                                ?: "completed"
                        )
                        "delta" -> null
                        else -> null
                    }
                }
                type in setOf("content.delta", "text.delta", "message.delta", "delta") -> {
                    val text = data["text"]?.jsonPrimitive?.content.orEmpty()
                    if (text.isBlank()) null else AppStreamEvent.Content(text)
                }
                type in setOf("tool.call", "tool.invocation", "tool_use") -> {
                    val arguments = data["arguments"] as? JsonObject ?: buildJsonObject { }
                    val toolName = data["name"]?.jsonPrimitive?.content
                        ?: data["tool"]?.jsonPrimitive?.content
                        ?: "tool"
                    val toolId = data["id"]?.jsonPrimitive?.content
                        ?: data["toolCallId"]?.jsonPrimitive?.content
                        ?: toolName
                    AppStreamEvent.ToolCall(
                        id = toolId,
                        name = toolName,
                        argumentsObj = arguments,
                        isReasoningStep = false
                    )
                }
                type in setOf("tool.result", "tool.output", "tool.completed") -> {
                    val toolName = data["name"]?.jsonPrimitive?.content
                        ?: data["tool"]?.jsonPrimitive?.content
                        ?: "tool"
                    val summary = data["summary"]?.jsonPrimitive?.content
                        ?: data["text"]?.jsonPrimitive?.content
                        ?: data["message"]?.jsonPrimitive?.content
                        ?: data["status"]?.jsonPrimitive?.content
                        ?: "已完成"
                    AppStreamEvent.Content("[工具结果] $toolName: $summary")
                }
                type in setOf("run.progress", "progress", "status") -> {
                    val status = data["message"]?.jsonPrimitive?.content
                        ?: data["status"]?.jsonPrimitive?.content
                        ?: data["text"]?.jsonPrimitive?.content
                        ?: return null
                    AppStreamEvent.StatusUpdate(status)
                }
                type in setOf("run.completed", "message.completed", "completed", "done") -> {
                    AppStreamEvent.Finish(data["reason"]?.jsonPrimitive?.content ?: "completed")
                }
                type in setOf("error", "run.failed") -> {
                    val message = data["message"]?.jsonPrimitive?.content
                        ?: data["error"]?.jsonPrimitive?.content
                        ?: "OpenClaw Gateway error"
                    AppStreamEvent.Error(message)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mapConnectEvent(
        type: String,
        data: kotlinx.serialization.json.JsonObject
    ): AppStreamEvent? {
        return when (type) {
            "pairing.pending", "pending", "approval.pending" -> {
                val deviceId = data["deviceId"]?.jsonPrimitive?.content
                    ?: data["device"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                    ?: "unknown"
                AppStreamEvent.StatusUpdate("pairing_pending:$deviceId")
            }
            "pairing.approved", "connected", "connect.ok" -> AppStreamEvent.StatusUpdate("connected")
            "pairing.rejected" -> AppStreamEvent.Error("OpenClaw pairing rejected")
            else -> null
        }
    }

    private fun extractAgentDelta(data: JsonObject): String {
        val directDelta = data["delta"]?.jsonPrimitive?.content.orEmpty()
        if (directDelta.isNotBlank()) return directDelta

        val nestedData = data["data"]?.jsonObject
        val nestedDelta = nestedData?.get("delta")?.jsonPrimitive?.content.orEmpty()
        if (nestedDelta.isNotBlank()) return nestedDelta

        val directText = data["text"]?.jsonPrimitive?.content.orEmpty()
        if (directText.isNotBlank()) return directText

        val nestedText = nestedData?.get("text")?.jsonPrimitive?.content.orEmpty()
        if (nestedText.isNotBlank()) return nestedText

        val contentArray = data["content"]?.jsonArray.orEmpty()
        return contentArray.firstNotNullOfOrNull { element ->
            runCatching {
                element.jsonObject["text"]?.jsonPrimitive?.content
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }
}
