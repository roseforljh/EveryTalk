package com.android.everytalk.provider

import com.android.everytalk.data.DataClass.ChatRequest
import io.ktor.client.HttpClient
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenClawProviderSelectionTest {

    private lateinit var openClawProvider: OpenClawProvider
    private lateinit var openAIProvider: OpenAICompatibleProvider
    private val mockHttpClient = mockk<HttpClient>(relaxed = true)

    @Before
    fun setup() {
        openClawProvider = OpenClawProvider(mockHttpClient)
        openAIProvider = OpenAICompatibleProvider(mockHttpClient)
    }

    @Test
    fun `openclaw provider handles openclaw channel`() {
        val request = createRequest(channel = "openclaw", provider = "default")

        assertTrue(openClawProvider.canHandle(request))
        assertFalse(openAIProvider.canHandle(request))
    }

    @Test
    fun `openclaw provider handles openclaw provider name`() {
        val request = createRequest(channel = "custom", provider = "openclaw")

        assertTrue(openClawProvider.canHandle(request))
        assertFalse(openAIProvider.canHandle(request))
    }

    private fun createRequest(
        channel: String,
        provider: String,
        model: String = "openclaw-default"
    ): ChatRequest {
        return ChatRequest(
            messages = emptyList(),
            provider = provider,
            channel = channel,
            apiAddress = "ws://127.0.0.1:18789",
            apiKey = "test-token",
            model = model,
            deviceId = "test-device"
        )
    }
}
