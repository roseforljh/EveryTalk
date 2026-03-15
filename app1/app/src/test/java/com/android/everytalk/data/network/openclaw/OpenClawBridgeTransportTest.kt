package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import io.ktor.client.HttpClient
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenClawBridgeTransportTest {

    private lateinit var transport: OpenClawBridgeTransport
    private val httpClient = mockk<HttpClient>(relaxed = true)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    fun setup() {
        transport = OpenClawBridgeTransport(httpClient, json)
        OpenClawRuntimeState.clear()
    }

    @Test
    fun `build connect request emits bridge connect envelope`() {
        val envelope = transport.buildConnectRequest(requestId = "req-1", sessionKey = "et:conv-1")
        val encoded = json.encodeToString(envelope)

        assertTrue(encoded.contains("\"type\":\"bridge.connect\""))
        assertTrue(encoded.contains("\"sessionKey\":\"et:conv-1\""))
    }

    @Test
    fun `build chat send envelope keeps text and conversation id`() {
        val envelope = transport.buildChatSendEnvelope(
            request = createRequest(),
            requestId = "req-1",
            sessionKey = "et:conv-1",
            text = "hello bridge"
        )
        val encoded = json.encodeToString(envelope)

        assertTrue(encoded.contains("\"type\":\"bridge.chat.send\""))
        assertTrue(encoded.contains("\"text\":\"hello bridge\""))
        assertTrue(encoded.contains("\"conversationId\":\"conv-1\""))
    }

    @Test
    fun `bridge delta event maps to app content event`() {
        val event = transport.mapBridgeEvent(
            """
            {"type":"bridge.chat.delta","requestId":"req-1","sessionKey":"et:conv-1","runId":"run-1","payload":{"text":"partial"}}
            """.trimIndent()
        )

        assertTrue(event is com.android.everytalk.data.network.AppStreamEvent.Content)
        assertEquals("partial", (event as com.android.everytalk.data.network.AppStreamEvent.Content).text)
        assertEquals("et:conv-1", OpenClawRuntimeState.current()?.sessionKey)
        assertEquals("run-1", OpenClawRuntimeState.current()?.runId)
    }

    @Test
    fun `abort marks runtime state as requested`() {
        OpenClawRuntimeState.update(sessionKey = "et:conv-1", runId = "run-1")

        kotlinx.coroutines.runBlocking {
            transport.abortCurrentRun()
        }

        assertEquals(true, OpenClawRuntimeState.current()?.abortRequested)
    }

    private fun createRequest(): ChatRequest {
        return ChatRequest(
            messages = emptyList(),
            provider = "openclaw",
            channel = "openclaw",
            apiAddress = "ws://127.0.0.1:18789",
            apiKey = "token",
            model = "main",
            deviceId = "device-1",
            conversationId = "conv-1",
            openClawAccessMode = "bridge",
            openClawBridgeUrl = "wss://bridge.example.com/ws"
        )
    }
}
