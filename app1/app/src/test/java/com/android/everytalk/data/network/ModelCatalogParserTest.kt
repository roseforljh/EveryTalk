package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogParserTest {

    @Test
    fun `Gemini 模型列表保留输入与输出限制`() {
        val catalog = parseModelCatalog(
            responseBody =
                """
                {
                  "models": [
                    {
                      "name": "models/gemini-example",
                      "inputTokenLimit": 1048576,
                      "outputTokenLimit": 65536,
                      "supportedGenerationMethods": ["generateContent"]
                    }
                  ]
                }
                """.trimIndent(),
            protocol = ModelParameterProtocol.GEMINI,
            apiAddress = "https://generativelanguage.googleapis.com/v1beta",
            fetchedAtEpochMillis = 1234L,
        )

        val model = catalog.single()
        assertEquals("gemini-example", model.modelId)
        assertEquals(1_048_576, model.contextWindowTokens)
        assertEquals(65_536, model.maxOutputTokens)
        assertEquals(ModelCapabilitySource.LIVE_ENDPOINT, model.source)
        assertEquals(1234L, model.sourceUpdatedAt)
    }

    @Test
    fun `OpenAI 风格模型列表保留能力字段和模态`() {
        val catalog = parseModelCatalog(
            responseBody =
                """
                {
                  "data": [
                    {
                      "id": "example-model",
                      "context_length": 262144,
                      "max_output_tokens": 32768,
                      "architecture": {
                        "input_modalities": ["text", "image"],
                        "output_modalities": ["text"]
                      },
                      "supported_parameters": ["reasoning_effort"]
                    }
                  ]
                }
                """.trimIndent(),
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            apiAddress = "https://api.example.com/v1",
        )

        val model = catalog.single()
        assertEquals("example-model", model.modelId)
        assertEquals(262_144, model.contextWindowTokens)
        assertEquals(32_768, model.maxOutputTokens)
        assertEquals(setOf("text", "image"), model.inputModalities)
        assertEquals(setOf("text"), model.outputModalities)
        assertTrue(model.supportsReasoning == true)
    }

    @Test
    fun `字符串模型数组保持旧接口兼容`() {
        val catalog = parseModelCatalog(
            responseBody = "[\"model-a\", \"models/model-b\"]",
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            apiAddress = "api.example.com/v1/",
        )

        assertEquals(listOf("model-a", "model-b"), catalog.map { it.modelId })
        assertEquals(
            setOf("https://api.example.com/v1"),
            catalog.map { it.endpointIdentity }.toSet(),
        )
    }

    @Test
    fun `模型名称按大小写不敏感去重`() {
        val catalog = parseModelCatalog(
            responseBody = """{"data":[{"id":"Model-A"},{"id":"model-a"}]}""",
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            apiAddress = "https://api.example.com/v1",
        )

        assertEquals(listOf("Model-A"), catalog.map { it.modelId })
    }

    @Test
    fun `OpenAI兼容目录识别max tokens并保留缺失能力的模型`() {
        val catalog = parseModelCatalog(
            responseBody =
                """
                {
                  "data": [
                    {"id":"model-with-limit","max_tokens":16384},
                    {"id":"model-without-capabilities"}
                  ]
                }
                """.trimIndent(),
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            apiAddress = "https://api.example.com/v1",
        )

        assertEquals(16_384, catalog.first().maxOutputTokens)
        assertEquals("model-without-capabilities", catalog.last().modelId)
    }

    @Test
    fun `Anthropic模型目录保留嵌套视觉与思考能力`() {
        val catalog = parseModelCatalog(
            responseBody =
                """
                {
                  "data": [{
                    "id": "claude-example",
                    "max_input_tokens": 1000000,
                    "max_tokens": 128000,
                    "capabilities": {
                      "image_input": {"supported": true},
                      "thinking": {"supported": true}
                    }
                  }]
                }
                """.trimIndent(),
            protocol = ModelParameterProtocol.ANTHROPIC,
            apiAddress = "https://api.anthropic.com",
        )

        val model = catalog.single()
        assertEquals(1_000_000, model.contextWindowTokens)
        assertEquals(128_000, model.maxOutputTokens)
        assertEquals(setOf("text", "image"), model.inputModalities)
        assertEquals(setOf("text"), model.outputModalities)
        assertTrue(model.supportsReasoning == true)
    }
}
