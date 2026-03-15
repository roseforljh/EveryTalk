package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawGatewayClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `build session key keeps conversation scoped prefix`() {
        val sessionKey = OpenClawGatewayClient.buildSessionKey("conversation-123")

        assertEquals("et:conversation-123", sessionKey)
    }

    @Test
    fun `build chat send request contains method and session key`() {
        val request = OpenClawGatewayClient.buildChatSendRequest(
            request = createRequest(),
            messageText = "hello openclaw",
            requestId = "req-1",
            sessionKey = "et:conversation-123",
            idempotencyKey = "msg-1"
        )

        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"method\":\"chat.send\""))
        assertTrue(encoded.contains("\"sessionKey\":\"et:conversation-123\""))
        assertTrue(encoded.contains("\"idempotencyKey\":\"msg-1\""))
        assertTrue(encoded.contains("\"text\":\"hello openclaw\""))
    }

    private fun createRequest(): ChatRequest {
        return ChatRequest(
            messages = emptyList(),
            provider = "openclaw",
            channel = "openclaw",
            apiAddress = "ws://127.0.0.1:18789",
            apiKey = "test-token",
            model = "main",
            deviceId = "device-1"
        )
    }
}
