package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ModelParameterProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogProtocolTest {

    @Test
    fun `四协议生成各自的模型目录与认证方式`() {
        val codex = resolveModelCatalogEndpoint(
            "https://api.openai.com/v1/responses",
            "Codex",
        )
        assertEquals(ModelParameterProtocol.CODEX, codex.protocol)
        assertEquals("https://api.openai.com/v1/models", codex.listUrl)
        assertEquals(ModelCatalogAuthMode.BEARER, codex.authMode)

        val anthropic = resolveModelCatalogEndpoint("https://api.anthropic.com", "Anthropic")
        assertEquals("https://api.anthropic.com/v1/models?limit=1000", anthropic.listUrl)
        assertEquals(ModelCatalogAuthMode.ANTHROPIC, anthropic.authMode)

        val gemini = resolveModelCatalogEndpoint(
            "https://generativelanguage.googleapis.com",
            "Gemini",
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
            gemini.listUrl,
        )
        assertEquals(ModelCatalogAuthMode.GOOGLE_API_KEY_HEADER, gemini.authMode)

        val compatible = resolveModelCatalogEndpoint(
            "https://proxy.example/v1/chat/completions",
            "OpenAI兼容",
        )
        assertEquals("https://proxy.example/v1/models", compatible.listUrl)
        assertEquals(ModelCatalogAuthMode.BEARER, compatible.authMode)
    }

    @Test
    fun `Gemini反代避免重复拼接v1beta路径`() {
        val endpoint = resolveModelCatalogEndpoint(
            "https://proxy.example/v1beta",
            "Gemini",
        )

        assertEquals("https://proxy.example/v1beta/models?pageSize=1000", endpoint.listUrl)
        assertEquals(ModelCatalogAuthMode.BEARER, endpoint.authMode)
    }

    @Test
    fun `详情URL对模型ID执行路径段编码`() {
        val endpoint = resolveModelCatalogEndpoint("https://api.example/v1", "Codex")

        assertEquals(
            "https://api.example/v1/models/provider%2Fmodel%20name",
            buildModelDetailUrl(endpoint, "provider/model name"),
        )
    }

    @Test
    fun `Anthropic按last id继续分页`() {
        val cursor = parseModelPageCursor(
            """{"has_more":true,"last_id":"model-last"}""",
            ModelParameterProtocol.ANTHROPIC,
        )

        requireNotNull(cursor)
        val next = applyModelPageCursor("https://api.anthropic.com/v1/models?limit=1000", cursor)
        assertTrue(next.orEmpty().contains("after_id=model-last"))
    }

    @Test
    fun `Gemini按nextPageToken继续分页`() {
        val cursor = parseModelPageCursor(
            """{"nextPageToken":"next-token"}""",
            ModelParameterProtocol.GEMINI,
        )

        requireNotNull(cursor)
        val next = applyModelPageCursor(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
            cursor,
        )
        assertTrue(next.orEmpty().contains("pageToken=next-token"))
    }

    @Test
    fun `兼容端点接受同源next并拒绝跨源next`() {
        val sameOrigin = parseModelPageCursor(
            """{"next":"/v1/models?page=2"}""",
            ModelParameterProtocol.OPENAI_COMPATIBLE,
        )
        val crossOrigin = ModelPageCursor(directUrl = "https://attacker.example/models")

        assertEquals(
            "https://api.example/v1/models?page=2",
            applyModelPageCursor("https://api.example/v1/models", requireNotNull(sameOrigin)),
        )
        assertNull(applyModelPageCursor("https://api.example/v1/models", crossOrigin))
    }
}
