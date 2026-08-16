package com.android.everytalk.ui.screens.settings

import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.network.LlmEndpointResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsEndpointRulesTest {
    @Test
    fun `anthropic root address previews messages endpoint`() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            SettingsEndpointRules.buildFullEndpointPreview(
                base = "https://api.anthropic.com",
                provider = "Anthropic",
                channel = "Anthropic",
            ),
        )
    }

    @Test
    fun `anthropic full messages address is not duplicated`() {
        assertEquals(
            "https://proxy.example/v1/messages",
            SettingsEndpointRules.buildFullEndpointPreview(
                base = "https://proxy.example/v1/messages",
                provider = "custom",
                channel = "Anthropic",
            ),
        )
    }

    @Test
    fun `responses address ending with v1 is not duplicated`() {
        assertEquals(
            "https://proxy.example/v1/responses",
            LlmEndpointResolver.resolve(
                protocol = ModelParameterProtocol.CODEX,
                apiAddress = "https://proxy.example/v1",
                model = "gpt-5.6",
            ),
        )
    }

    @Test
    fun `switching protocol replaces known endpoint and keeps proxy prefix`() {
        assertEquals(
            "https://proxy.example/cpa/v1/responses",
            LlmEndpointResolver.resolve(
                protocol = ModelParameterProtocol.CODEX,
                apiAddress = "https://proxy.example/cpa/v1/chat/completions",
                model = "gpt-5.6",
            ),
        )
    }

    @Test
    fun `all protocols accept a prefixed version base`() {
        val base = "https://proxy.example/gateway/v1"

        assertEquals(
            "$base/chat/completions",
            LlmEndpointResolver.resolve(ModelParameterProtocol.OPENAI_COMPATIBLE, base, "model-a"),
        )
        assertEquals(
            "$base/responses",
            LlmEndpointResolver.resolve(ModelParameterProtocol.CODEX, base, "model-a"),
        )
        assertEquals(
            "$base/messages",
            LlmEndpointResolver.resolve(ModelParameterProtocol.ANTHROPIC, base, "model-a"),
        )
    }

    @Test
    fun `provider specific version base does not receive an extra v1`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            LlmEndpointResolver.resolve(
                ModelParameterProtocol.OPENAI_COMPATIBLE,
                "https://open.bigmodel.cn/api/paas/v4",
                "glm-5",
            ),
        )
        assertEquals(
            "https://ark.example/api/v3/responses",
            LlmEndpointResolver.resolve(
                ModelParameterProtocol.CODEX,
                "https://ark.example/api/v3",
                "model-a",
            ),
        )
    }

    @Test
    fun `official bigmodel root receives its required base path in preview and request`() {
        val expected = "https://open.bigmodel.cn/api/paas/v4/chat/completions"

        assertEquals(
            expected,
            LlmEndpointResolver.resolve(
                ModelParameterProtocol.OPENAI_COMPATIBLE,
                "https://open.bigmodel.cn",
                "glm-5",
            ),
        )
        assertEquals(
            expected,
            SettingsEndpointRules.buildFullEndpointPreview(
                base = "https://open.bigmodel.cn",
                provider = "智谱",
                channel = "OpenAI兼容",
                model = "glm-5",
            ),
        )
    }

    @Test
    fun `custom proxy containing provider name is never rewritten`() {
        assertEquals(
            "https://bigmodel.example/custom/v1/chat/completions",
            LlmEndpointResolver.resolve(
                ModelParameterProtocol.OPENAI_COMPATIBLE,
                "https://bigmodel.example/custom/v1",
                "glm-5",
            ),
        )
    }

    @Test
    fun `gemini full endpoint is replaced with current model without duplication`() {
        assertEquals(
            "https://proxy.example/google/v1beta/models/gemini-3:streamGenerateContent",
            LlmEndpointResolver.resolve(
                protocol = ModelParameterProtocol.GEMINI,
                apiAddress = "https://proxy.example/google/v1beta/models/old:generateContent",
                model = "models/gemini-3",
            ),
        )
    }

    @Test
    fun `hash suffix keeps a private full endpoint unchanged`() {
        assertEquals(
            "https://proxy.example/private/inference?tenant=one",
            LlmEndpointResolver.resolve(
                protocol = ModelParameterProtocol.CODEX,
                apiAddress = "https://proxy.example/private/inference?tenant=one#",
                model = "gpt-5.6",
            ),
        )
    }

    @Test
    fun `settings preview uses the same resolver as the request`() {
        val preview = SettingsEndpointRules.buildFullEndpointPreview(
            base = "https://proxy.example/v1",
            provider = "custom",
            channel = "Codex",
            model = "gpt-5.6",
        )

        assertEquals("https://proxy.example/v1/responses", preview)
    }

    @Test
    fun `predefined provider protection remains generic`() {
        assertFalse(SettingsEndpointRules.canDeleteProvider("Anthropic"))
        assertTrue(SettingsEndpointRules.canDeleteProvider("自定义平台"))
    }
}
