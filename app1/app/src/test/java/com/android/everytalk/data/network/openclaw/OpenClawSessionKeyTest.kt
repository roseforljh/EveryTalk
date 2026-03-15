package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawSessionKeyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `conversation id has priority over device id in session key`() {
        val request = createRequest(conversationId = "conv-42", deviceId = "device-1")

        val sessionKey = OpenClawGatewayClient.resolveSessionKey(request)

        assertEquals("et:conv-42", sessionKey)
    }

    @Test
    fun `openclaw request keeps agent id in payload`() {
        val request = createRequest(conversationId = "conv-42", deviceId = "device-1")

        val payload = OpenClawGatewayClient.buildChatSendRequest(
            request = request,
            messageText = "hello",
            requestId = "req-1",
            sessionKey = OpenClawGatewayClient.resolveSessionKey(request),
            idempotencyKey = "msg-1"
        )

        val encoded = json.encodeToString(payload)
        assertTrue(encoded.contains("\"agentId\":\"main\""))
        assertTrue(encoded.contains("\"sessionKey\":\"et:conv-42\""))
    }

    @Test
    fun `explicit remote session id has priority over local conversation id`() {
        val request = createRequest(
            conversationId = "local-conv-42",
            deviceId = "device-1"
        ).copy(openClawSessionId = "remote-desktop")

        val sessionKey = OpenClawGatewayClient.resolveSessionKey(request)

        assertEquals("et:remote-desktop", sessionKey)
    }

    @Test
    fun `remote provider falls back to deterministic remote session key`() {
        val request = ChatRequest(
            messages = emptyList(),
            provider = "OpenClaw Remote",
            channel = "OpenClaw",
            apiAddress = "wss://gateway.example.com",
            apiKey = "token",
            model = "",
            conversationId = "local-conv-42"
        )

        val sessionKey = OpenClawGatewayClient.resolveSessionKey(request)

        assertEquals("et:remote:gateway.example.com", sessionKey)
    }

    private fun createRequest(conversationId: String, deviceId: String): ChatRequest {
        return ChatRequest(
            messages = emptyList(),
            provider = "openclaw",
            channel = "openclaw",
            apiAddress = "ws://127.0.0.1:18789",
            apiKey = "token",
            model = "main",
            deviceId = deviceId,
            conversationId = conversationId
        )
    }
}
