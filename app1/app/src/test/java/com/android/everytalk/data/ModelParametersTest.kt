package com.android.everytalk.data

import com.android.everytalk.data.DataClass.CustomModelParameter
import com.android.everytalk.data.DataClass.CustomParameterType
import com.android.everytalk.data.DataClass.DEFAULT_MAX_CONTEXT_TOKENS
import com.android.everytalk.data.DataClass.DEFAULT_MAX_OUTPUT_TOKENS
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ReasoningMode
import com.android.everytalk.data.DataClass.openAICompatibleRequestParameters
import com.android.everytalk.data.DataClass.toThinkingConfig
import com.android.everytalk.data.DataClass.validateModelTokenLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelParametersTest {
    @Test
    fun `all new model parameters default to medium`() {
        val parameters = ModelParameters()

        assertEquals("medium", parameters.reasoningEffort)
        assertEquals("medium", parameters.toThinkingConfig("Codex", "gpt-5.6")?.reasoningEffort)
        assertEquals("medium", parameters.toThinkingConfig("Anthropic", "claude-sonnet-4-6")?.reasoningEffort)
        assertEquals("medium", parameters.toThinkingConfig("Gemini", "gemini-3-flash")?.thinkingLevel)
        assertEquals(DEFAULT_MAX_CONTEXT_TOKENS, parameters.maxContextTokens)
    }

    @Test
    fun `旧版模型参数数据缺少自定义列表时仍可解析`() {
        val parameters = Json.decodeFromString<ModelParameters>("{\"reasoningEffort\":\"high\"}")

        assertEquals("high", parameters.reasoningEffort)
        assertEquals(emptyList<String>(), parameters.customReasoningEfforts)
        assertEquals(DEFAULT_MAX_CONTEXT_TOKENS, parameters.maxContextTokens)
    }

    @Test
    fun `模型token限制接受用户自定义值`() {
        val limits = validateModelTokenLimits(
            maxOutputTokens = 16_384,
            maxContextTokens = 1_000_000,
        )

        assertEquals(16_384, limits.maxOutputTokens)
        assertEquals(1_000_000, limits.maxContextTokens)
        assertEquals(4096, DEFAULT_MAX_OUTPUT_TOKENS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `最大输出不能占满上下文窗口`() {
        validateModelTokenLimits(maxOutputTokens = 4096, maxContextTokens = 4096)
    }

    @Test
    fun `gemini 25 medium maps to token budget`() {
        val thinking = ModelParameters().toThinkingConfig("Gemini", "gemini-2.5-pro")

        assertEquals(8192, thinking?.thinkingBudget)
        assertNull(thinking?.thinkingLevel)
    }

    @Test
    fun `官方渠道不支持的思考程度回退到medium`() {
        val thinking = ModelParameters(reasoningEffort = "ultra")
            .toThinkingConfig("Anthropic", "claude-sonnet-4-6")

        assertEquals("medium", thinking?.reasoningEffort)
    }

    @Test
    fun `openai compatible preset keeps JSON types`() {
        val result = ModelParameters(
            customParameters = listOf(
                CustomModelParameter("reasoning_effort", "medium"),
                CustomModelParameter("thinking_budget", "4096", CustomParameterType.NUMBER),
                CustomModelParameter("enable_thinking", "true", CustomParameterType.BOOLEAN),
            )
        ).openAICompatibleRequestParameters()

        assertEquals("medium", result.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals(4096, result.getValue("thinking_budget").jsonPrimitive.content.toInt())
        assertEquals(true, result.getValue("enable_thinking").jsonPrimitive.content.toBoolean())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `openai compatible cannot override managed fields`() {
        ModelParameters(
            reasoningMode = ReasoningMode.EFFORT,
            customParameters = listOf(CustomModelParameter("messages", "[]", CustomParameterType.JSON)),
        ).openAICompatibleRequestParameters()
    }
}
