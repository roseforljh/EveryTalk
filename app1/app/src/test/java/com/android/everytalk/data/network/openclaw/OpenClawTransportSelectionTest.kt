package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.provider.OpenClawProvider
import io.ktor.client.HttpClient
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenClawTransportSelectionTest {

    private lateinit var provider: TestableOpenClawProvider
    private val httpClient = mockk<HttpClient>(relaxed = true)

    @Before
    fun setup() {
        provider = TestableOpenClawProvider(httpClient)
    }

    @Test
    fun `bridge mode selects bridge transport`() {
        val transport = provider.resolveTransportForTest(
            createRequest(
                apiAddress = "ws://gateway.local:18789",
                openClawAccessMode = "bridge",
                openClawBridgeUrl = "wss://bridge.example.com/ws"
            )
        )

        assertTrue(transport is OpenClawBridgeTransport)
    }

    @Test
    fun `direct mode selects gateway transport`() {
        val transport = provider.resolveTransportForTest(
            createRequest(
                apiAddress = "ws://gateway.local:18789",
                openClawAccessMode = "direct"
            )
        )

        assertTrue(transport is OpenClawGatewayClient)
    }

    private fun createRequest(
        apiAddress: String,
        openClawAccessMode: String,
        openClawBridgeUrl: String? = null
    ): ChatRequest {
        return ChatRequest(
            messages = emptyList(),
            provider = "openclaw",
            channel = "openclaw",
            apiAddress = apiAddress,
            apiKey = "token",
            model = "main",
            deviceId = "device-1",
            openClawAccessMode = openClawAccessMode,
            openClawBridgeUrl = openClawBridgeUrl
        )
    }

    private class TestableOpenClawProvider(httpClient: HttpClient) : OpenClawProvider(httpClient) {
        fun resolveTransportForTest(request: ChatRequest): OpenClawChatTransport = resolveTransport(request)
    }
}
