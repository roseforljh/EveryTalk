package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import io.ktor.client.HttpClient
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenClawBridgeFallbackTest {

    @Test
    fun `bridge transport uses api address as fallback bridge url`() {
        val transport = OpenClawBridgeTransport(mockk<HttpClient>(relaxed = true))
        val bridgeUrl = transport.resolveBridgeUrl(
            ChatRequest(
                messages = emptyList(),
                provider = "openclaw",
                channel = "openclaw",
                apiAddress = "wss://bridge.example.com/ws",
                apiKey = "token",
                model = "main",
                openClawAccessMode = "bridge",
                openClawBridgeUrl = null
            )
        )

        assertEquals("wss://bridge.example.com/ws", bridgeUrl)
    }

    @Test
    fun `bridge transport reports error when both bridge url and api address missing`() = runBlocking {
        val transport = OpenClawBridgeTransport(mockk<HttpClient>(relaxed = true))
        val events = transport.streamChat(
            ChatRequest(
                messages = emptyList(),
                provider = "openclaw",
                channel = "openclaw",
                apiAddress = null,
                apiKey = "token",
                model = "main",
                openClawAccessMode = "bridge",
                openClawBridgeUrl = null
            )
        ).toList()

        assertEquals(
            "OpenClaw Bridge address is unreachable.",
            (events.first() as com.android.everytalk.data.network.AppStreamEvent.Error).message
        )
    }
}
