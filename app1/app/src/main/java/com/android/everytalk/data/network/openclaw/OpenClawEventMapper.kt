package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.network.AppStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object OpenClawEventMapper {

    fun mapChatEvent(payload: String, json: Json): AppStreamEvent? {
        return try {
            val root = json.parseToJsonElement(payload).jsonObject
            val data = root["data"]?.jsonObject ?: root
            val type = data["type"]?.jsonPrimitive?.content.orEmpty()

            when (type) {
                "content.delta", "text.delta", "message.delta" -> {
                    val text = data["text"]?.jsonPrimitive?.content.orEmpty()
                    if (text.isBlank()) null else AppStreamEvent.Content(text)
                }
                "run.completed", "message.completed", "completed" -> {
                    AppStreamEvent.Finish("completed")
                }
                "error", "run.failed" -> {
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
}
