package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.provider.OpenClawProvider
import io.ktor.client.HttpClient
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenClawRequestConfigPropagationTest {

    @Test
    fun `request keeps bridge mode and bridge url from api config derived values`() {
        val request = ChatRequest(
            messages = emptyList(),
            provider = "openclaw",
            channel = "OpenClaw",
            apiAddress = "ws://127.0.0.1:18789",
            apiKey = "token",
            model = "main",
            openClawAccessMode = "bridge",
            openClawBridgeUrl = "wss://bridge.example.com/ws"
        )

        assertEquals("bridge", request.openClawAccessMode)
        assertEquals("wss://bridge.example.com/ws", request.openClawBridgeUrl)
        assertEquals(true, OpenClawProvider(mockk<HttpClient>(relaxed = true)).canHandle(request))
    }
}
