package com.android.everytalk.statecontroller

import com.android.everytalk.data.network.openclaw.ModelsCatalogQueryResult
import com.android.everytalk.data.network.openclaw.OpenClawProviderModelsGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCommandDisplayFormatterTest {

    @Test
    fun `model command clearly reports gateway lacks structured runtime state api`() {
        assertEquals(
            "当前 Gateway 暂未暴露可供 ET 使用的结构化模型状态接口；sessions.preview 只能返回会话预览文本，不能作为 /model 的真实结果。",
            formatModelCommandMessage()
        )
    }

    @Test
    fun `model command shows backend reply text`() {
        assertEquals(
            "当前模型\n- Current: openai/gpt-5.4\n- Default: openai/gpt-4.1",
            formatModelCommandMessage(
                "Current: openai/gpt-5.4\nDefault: openai/gpt-4.1\nAgent: main\nAuth file: ~/.openclaw/..."
            )
        )
    }

    @Test
    fun `compact model status keeps current only when default missing`() {
        assertEquals(
            "当前模型\n- Current: custom-12newapi/gemini-3-flash-preview",
            compactModelStatusText(
                "Current: custom-12newapi/gemini-3-flash-preview\nAgent: main\n[custom-12newapi] endpoint: https://..."
            )
        )
    }

    @Test
    fun `compact model status falls back to raw text when current missing`() {
        assertEquals(
            "Agent: main\nAuth file: ~/.openclaw/...",
            compactModelStatusText("Agent: main\nAuth file: ~/.openclaw/...")
        )
    }

    @Test
    fun `model command shows failure with concrete error`() {
        assertEquals(
            "/model 查询失败：gateway timeout",
            formatModelCommandFailureMessage("gateway timeout")
        )
    }

    @Test
    fun `models command displays preview text when preview exists`() {
        val result = ModelsCatalogQueryResult(
            ok = true,
            supported = true,
            providerGroups = listOf(
                OpenClawProviderModelsGroup("custom-12newapi", listOf("custom-12newapi/gemini-3-flash-preview")),
                OpenClawProviderModelsGroup("grok-api", listOf("grok-api/grok-4"))
            )
        )

        assertEquals(
            "可用模型提供商\n- custom-12newapi (1)\n- grok-api (1)",
            formatModelsCommandMessage(result)
        )
    }

    @Test
    fun `models command can display provider models`() {
        val result = ModelsCatalogQueryResult(
            ok = true,
            supported = true,
            providerGroups = listOf(
                OpenClawProviderModelsGroup(
                    provider = "openai",
                    models = listOf("openai/gpt-5.4", "openai/gpt-4.1")
                )
            )
        )

        assertEquals(
            "openai 可用模型\n- openai/gpt-5.4\n- openai/gpt-4.1",
            formatModelsCommandMessage(result, "openai")
        )
    }
}
