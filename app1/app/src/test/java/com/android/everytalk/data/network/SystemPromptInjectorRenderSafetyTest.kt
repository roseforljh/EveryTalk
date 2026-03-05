package com.android.everytalk.data.network

import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptInjectorRenderSafetyTest {

    @Test
    fun `zh system prompt should include render stability rules`() {
        val prompt = SystemPromptInjector.getSystemPrompt("zh-CN")
        assertTrue(prompt.contains("Rendering Stability Rules"))
        assertTrue(prompt.contains("&gt;"))
        assertTrue(prompt.contains("&lt;"))
        assertTrue(prompt.contains("\\implies"))
        assertTrue(prompt.contains("mobile readability"))
    }

    @Test
    fun `en system prompt should include render stability rules`() {
        val prompt = SystemPromptInjector.getSystemPrompt("en")
        assertTrue(prompt.contains("Rendering Stability Rules"))
        assertTrue(prompt.contains("&gt;"))
        assertTrue(prompt.contains("&lt;"))
        assertTrue(prompt.contains("\\implies"))
        assertTrue(prompt.contains("mobile readability"))
    }
}

