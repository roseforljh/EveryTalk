package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.webSearchToggleLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchSupportTest {

    @Test
    fun `gemini model supports native web search`() {
        val config = createConfig(channel = "Gemini", model = "gemini-2.5-flash")

        assertTrue(WebSearchSupport.supportsNativeWebSearch(config))
        assertFalse(WebSearchSupport.shouldEnableQwenNativeSearch(config, isWebSearchEnabled = true))
    }

    @Test
    fun `prefixed gemini model is recognized in openai compatible channel`() {
        val config = createConfig(channel = "OpenAI兼容", model = "custom-12newapi/gemini-3-flash-preview")

        assertTrue(WebSearchSupport.isGeminiModel(config))
        assertFalse(WebSearchSupport.supportsNativeWebSearch(config))
    }

    @Test
    fun `qwen model supports native web search`() {
        val config = createConfig(channel = "OpenAI兼容", model = "qwen-max")

        assertTrue(WebSearchSupport.supportsNativeWebSearch(config))
        assertTrue(WebSearchSupport.shouldEnableQwenNativeSearch(config, isWebSearchEnabled = true))
    }

    @Test
    fun `non gemini and non qwen models do not support web search`() {
        val config = createConfig(channel = "OpenAI兼容", model = "gpt-4o")

        assertFalse(WebSearchSupport.supportsNativeWebSearch(config))
        assertFalse(WebSearchSupport.shouldEnableQwenNativeSearch(config, isWebSearchEnabled = true))
    }

    @Test
    fun `unsupported model shows unavailable search label`() {
        assertEquals("搜索不可用", webSearchToggleLabel(isSupported = false, isEnabled = false))
    }

    @Test
    fun `supported and disabled toggle shows online search label`() {
        assertEquals("联网搜索", webSearchToggleLabel(isSupported = true, isEnabled = false))
    }

    @Test
    fun `disabled toggle does not enable qwen native search`() {
        val config = createConfig(channel = "OpenAI兼容", model = "qwen-plus")

        assertFalse(WebSearchSupport.shouldEnableQwenNativeSearch(config, isWebSearchEnabled = false))
    }

    private fun createConfig(channel: String, model: String): ApiConfig {
        return ApiConfig(
            address = "https://api.example.com",
            key = "test-key",
            model = model,
            provider = "test",
            name = "test-config",
            channel = channel
        )
    }
}
