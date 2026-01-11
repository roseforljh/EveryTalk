package com.android.everytalk.provider

import com.android.everytalk.data.DataClass.ChatRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.mockk.mockk
import io.ktor.client.HttpClient

class ProviderSelectionTest {
    
    private lateinit var geminiProvider: GeminiProvider
    private lateinit var openAIProvider: OpenAICompatibleProvider
    private val mockHttpClient = mockk<HttpClient>(relaxed = true)
    
    @Before
    fun setup() {
        geminiProvider = GeminiProvider(mockHttpClient)
        openAIProvider = OpenAICompatibleProvider(mockHttpClient)
    }
    
    @Test
    fun `gemini provider handles gemini channel`() {
        val request = createRequest(channel = "Gemini", model = "gemini-2.0-flash")
        
        assertTrue(geminiProvider.canHandle(request))
        assertFalse(openAIProvider.canHandle(request))
    }
    
    @Test
    fun `openai provider handles openai channel`() {
        val request = createRequest(channel = "OpenAI", model = "gpt-4o")
        
        assertFalse(geminiProvider.canHandle(request))
        assertTrue(openAIProvider.canHandle(request))
    }
    
    @Test
    fun `openai provider handles deepseek channel`() {
        val request = createRequest(channel = "deepseek", model = "deepseek-chat")
        
        assertFalse(geminiProvider.canHandle(request))
        assertTrue(openAIProvider.canHandle(request))
    }
    
    @Test
    fun `gemini model in openai channel uses openai provider`() {
        val request = createRequest(channel = "openai-compatible", model = "gemini-pro")
        
        assertFalse(geminiProvider.canHandle(request))
        assertTrue(openAIProvider.canHandle(request))
    }
    
    @Test
    fun `default provider falls back to openai compatible`() {
        val request = createRequest(channel = "unknown", model = "some-model")
        
        assertFalse(geminiProvider.canHandle(request))
        assertTrue(openAIProvider.canHandle(request))
    }
    
    private fun createRequest(
        channel: String,
        model: String,
        provider: String = "default"
    ): ChatRequest {
        return ChatRequest(
            messages = emptyList(),
            provider = provider,
            channel = channel,
            apiAddress = "https://api.example.com",
            apiKey = "test-key",
            model = model,
            deviceId = "test-device"
        )
    }
}
