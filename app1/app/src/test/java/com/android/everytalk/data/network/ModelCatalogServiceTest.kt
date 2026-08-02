package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ModelCapabilitySource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogServiceTest {

    @Test
    fun `Codex优先请求单模型详情并使用Bearer认证`() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            when (request.url.host) {
                "models.dev" -> jsonResponse("{}")
                else -> {
                    assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
                    when (request.url.encodedPath) {
                        "/v1/models/gpt-test" -> jsonResponse(
                            """{"id":"gpt-test","context_window":200000,"max_output_tokens":32000,"reasoning_efforts":["high","max"]}"""
                        )
                        "/v1/models" -> jsonResponse("""{"data":[{"id":"gpt-test"}]}""")
                        else -> error("未预期的请求：${request.url}")
                    }
                }
            }
        }

        withService(engine) { service ->
            val capabilities = service.getCapabilities(
                apiUrl = "https://api.openai.com/v1/responses",
                apiKey = "secret",
                channel = "Codex",
                modelId = "gpt-test",
                providerHint = "OpenAI",
            )

            assertEquals(200_000, capabilities.first().contextWindowTokens)
            assertEquals(setOf("high", "max"), capabilities.first().reasoningEfforts)
            assertTrue("/v1/models/gpt-test" in requestedPaths)
        }
    }

    @Test
    fun `Anthropic详情和分页均使用专用认证`() = runTest {
        var listPageCount = 0
        val engine = MockEngine { request ->
            when (request.url.host) {
                "models.dev" -> jsonResponse("{}")
                else -> {
                    assertEquals("secret", request.headers["x-api-key"])
                    assertEquals("2023-06-01", request.headers["anthropic-version"])
                    if (request.url.encodedPath.endsWith("/claude-test")) {
                        jsonResponse("""{"id":"claude-test","max_input_tokens":200000,"max_tokens":64000}""")
                    } else {
                        listPageCount++
                        if (request.url.parameters["after_id"] == null) {
                            jsonResponse(
                                """{"data":[{"id":"claude-test"}],"has_more":true,"last_id":"claude-test"}"""
                            )
                        } else {
                            jsonResponse(
                                """{"data":[{"id":"claude-other"}],"has_more":false,"last_id":"claude-other"}"""
                            )
                        }
                    }
                }
            }
        }

        withService(engine) { service ->
            val capabilities = service.getCapabilities(
                apiUrl = "https://api.anthropic.com",
                apiKey = "secret",
                channel = "Anthropic",
                modelId = "claude-test",
                providerHint = "Anthropic",
            )

            assertEquals(200_000, capabilities.first().maxInputTokens)
            assertEquals(2, listPageCount)
        }
    }

    @Test
    fun `Gemini详情和nextPageToken分页均携带查询密钥`() = runTest {
        var listPageCount = 0
        val engine = MockEngine { request ->
            when (request.url.host) {
                "models.dev" -> jsonResponse("{}")
                else -> {
                    assertEquals("secret", request.headers["x-goog-api-key"])
                    if (request.url.encodedPath.endsWith("/gemini-test")) {
                        jsonResponse(
                            """{"name":"models/gemini-test","inputTokenLimit":1000000,"outputTokenLimit":64000}"""
                        )
                    } else {
                        listPageCount++
                        if (request.url.parameters["pageToken"] == null) {
                            jsonResponse(
                                """{"models":[{"name":"models/gemini-test"}],"nextPageToken":"page-2"}"""
                            )
                        } else {
                            jsonResponse("""{"models":[{"name":"models/gemini-other"}]}""")
                        }
                    }
                }
            }
        }

        withService(engine) { service ->
            val capabilities = service.getCapabilities(
                apiUrl = "https://generativelanguage.googleapis.com",
                apiKey = "secret",
                channel = "Gemini",
                modelId = "gemini-test",
                providerHint = "Google",
            )

            assertEquals(1_000_000, capabilities.first().contextWindowTokens)
            assertEquals(2, listPageCount)
        }
    }

    @Test
    fun `OpenAI兼容详情不支持时继续读取分页列表`() = runTest {
        var listPageCount = 0
        val engine = MockEngine { request ->
            when (request.url.host) {
                "models.dev" -> jsonResponse("{}")
                else -> {
                    assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
                    if (request.url.encodedPath.endsWith("/compatible-test")) {
                        respond("unsupported", HttpStatusCode.NotFound)
                    } else {
                        listPageCount++
                        if (request.url.parameters["after"] == null) {
                            jsonResponse(
                                """{"data":[{"id":"compatible-test","context_length":128000,"max_output_tokens":16000}],"has_more":true,"last_id":"compatible-test"}"""
                            )
                        } else {
                            jsonResponse("""{"data":[{"id":"compatible-other"}],"has_more":false}""")
                        }
                    }
                }
            }
        }

        withService(engine) { service ->
            val capabilities = service.getCapabilities(
                apiUrl = "https://compatible.example/v1",
                apiKey = "secret",
                channel = "OpenAI兼容",
                modelId = "compatible-test",
                providerHint = "自定义",
            )

            assertEquals(128_000, capabilities.first().contextWindowTokens)
            assertEquals(16_000, capabilities.first().maxOutputTokens)
            assertEquals(ModelCapabilitySource.LIVE_ENDPOINT, capabilities.first().source)
            assertEquals(2, listPageCount)
        }
    }

    private suspend fun withService(
        engine: MockEngine,
        block: suspend (ModelCatalogService) -> Unit,
    ) {
        val directory = Files.createTempDirectory("model-catalog-service").toFile()
        val client = HttpClient(engine)
        try {
            block(
                ModelCatalogService(
                    client = client,
                    endpointCache = ModelCapabilityCache(directory.resolve("endpoint.json")),
                    modelsDevCatalog = ModelsDevCatalog(directory.resolve("models-dev.json")),
                )
            )
        } finally {
            client.close()
            directory.deleteRecursively()
        }
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.jsonResponse(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
