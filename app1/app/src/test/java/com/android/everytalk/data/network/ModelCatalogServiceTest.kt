package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.resolveModelCapability
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelCatalogServiceTest {

    @Test
    fun `批量与单模型按名称获取pi参数且高于models dev`() = runTest {
        var piRequests = 0
        val engine = testEngine { request ->
            when (request.url.host) {
                "pi.dev" -> {
                    piRequests++
                    assertEquals("/api/models", request.url.encodedPath)
                    assertEquals(null, request.headers[HttpHeaders.Authorization])
                    assertEquals(null, request.headers["x-api-key"])
                    assertTrue(request.url.parameters.isEmpty())
                    jsonResponse("""{"official":{"model-a":{"id":"model-a","baseUrl":"https://unrelated.example","api":"anthropic-messages","contextWindow":200000,"maxTokens":32000,"reasoning":true,"input":["text","image"]}}}""")
                }
                "models.dev" -> jsonResponse("""{"proxy":{"models":{"model-a":{"id":"model-a","limit":{"context":128000,"input":100000,"output":8000},"reasoning":false}}}}""")
                else -> if (request.url.encodedPath == "/v1/models") {
                    jsonResponse("""{"data":[{"id":"model-a"}]}""")
                } else respond("unsupported", HttpStatusCode.NotFound)
            }
        }
        withService(engine) { service ->
            val batch = service.getCatalogWithCapabilities("https://proxy-a.example/v1", "secret", "OpenAI兼容")
            val single = service.getCapabilities("https://proxy-b.example/v1", "secret", "Codex", "model-a", "different-provider")
            for ((candidates, protocol) in listOf(batch to ModelParameterProtocol.OPENAI_COMPATIBLE, single to ModelParameterProtocol.CODEX)) {
                val resolved = resolveModelCapability("model-a", protocol, "https://proxy-b.example/v1", candidates)
                assertEquals(200_000, resolved.contextWindowTokens)
                assertEquals(32_000, resolved.maxOutputTokens)
                assertEquals(ModelCapabilitySource.PI_CATALOG, resolved.contextWindowSource)
                assertEquals(ModelCapabilitySource.PI_CATALOG, resolved.maxOutputSource)
                assertEquals(true, resolved.supportsReasoning)
                // pi 没有提供的输入限制才由 models.dev 补齐。
                assertEquals(100_000, resolved.maxInputTokens)
                assertEquals(ModelCapabilitySource.COMMUNITY_CATALOG, resolved.maxInputSource)
            }
            assertEquals(1, piRequests)
        }
    }

    @Test
    fun `pi超时自动回退社区目录`() = runTest {
        val engine = testEngine { request ->
            when (request.url.host) {
                "pi.dev" -> awaitCancellation()
                "models.dev" -> jsonResponse("""{"custom":{"models":{"model-a":{"limit":{"context":128000,"output":16000}}}}}""")
                else -> if (request.url.encodedPath == "/v1/models") jsonResponse("""{"data":[{"id":"model-a"}]}""")
                    else respond("unsupported", HttpStatusCode.NotFound)
            }
        }
        withService(engine) { service ->
            val candidates = service.getCatalogWithCapabilities("https://proxy.example/v1", "secret", "OpenAI兼容")
            val resolved = resolveModelCapability("model-a", ModelParameterProtocol.OPENAI_COMPATIBLE, "https://proxy.example/v1", candidates)
            assertEquals(128_000, resolved.contextWindowTokens)
            assertEquals(ModelCapabilitySource.COMMUNITY_CATALOG, resolved.contextWindowSource)
        }
    }

    @Test
    fun `获取整批模型同时获取详情和社区参数但列表只请求一次`() = runTest {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val engine = testEngine { request ->
            requests += request.url.host + request.url.encodedPath
            when (request.url.host + request.url.encodedPath) {
                "api.example/v1/models" -> jsonResponse("""{"data":[{"id":"model-a"},{"id":"model-b"}]}""")
                "api.example/v1/models/model-a" -> jsonResponse("""{"id":"model-a","context_window":256000,"reasoning_efforts":["high"]}""")
                "api.example/v1/models/model-b" -> respond("unsupported", HttpStatusCode.NotFound)
                "pi.dev/api/models" -> jsonResponse("{}")
                "models.dev/api.json" -> jsonResponse("""{"custom":{"models":{"model-a":{"limit":{"context":128000,"output":16000}},"model-b":{"limit":{"context":64000,"output":8000}}}}}""")
                else -> error("未预期的请求：${request.url}")
            }
        }
        withService(engine) { service ->
            val catalog = service.getCatalogWithCapabilities("https://api.example/v1", "key", "OpenAI兼容")
            val first = resolveModelCapability("model-a", ModelParameterProtocol.OPENAI_COMPATIBLE, "https://api.example/v1", catalog)
            val second = resolveModelCapability("model-b", ModelParameterProtocol.OPENAI_COMPATIBLE, "https://api.example/v1", catalog)
            assertEquals(256_000, first.contextWindowTokens)
            assertEquals(ModelCapabilitySource.LIVE_ENDPOINT, first.contextWindowSource)
            assertEquals(16_000, first.maxOutputTokens)
            assertEquals(ModelCapabilitySource.COMMUNITY_CATALOG, first.maxOutputSource)
            assertEquals(setOf("high"), first.reasoningEfforts)
            assertEquals(64_000, second.contextWindowTokens)
            assertEquals(8_000, second.maxOutputTokens)
            assertEquals(1, requests.count { it == "api.example/v1/models" })
            assertEquals(1, requests.count { it == "models.dev/api.json" })
            assertEquals(5, requests.size)
        }
    }

    @Test
    fun `社区不可用只尝试一次且不会丢失列表参数`() = runTest {
        var communityRequests = 0
        val engine = testEngine { request ->
            when {
                request.url.host == "models.dev" -> {
                    communityRequests++
                    respond("offline", HttpStatusCode.ServiceUnavailable)
                }
                request.url.encodedPath == "/v1/models" -> jsonResponse("""{"data":[{"id":"model-a","context_window":128000,"max_output_tokens":16000},{"id":"model-b"}]}""")
                else -> respond("unsupported", HttpStatusCode.NotFound)
            }
        }
        withService(engine) { service ->
            val catalog = service.getCatalogWithCapabilities("https://api.example/v1", "key", "OpenAI兼容")
            assertEquals(setOf("model-a", "model-b"), catalog.map { it.modelId }.toSet())
            assertEquals(128_000, catalog.first().contextWindowTokens)
            assertEquals(1, communityRequests)
        }
    }

    @Test
    fun `详情超时保留已完成参数并继续社区补齐`() = runTest {
        val engine = testEngine { request ->
            when {
                request.url.host in setOf("models.dev", "pi.dev") -> jsonResponse("{}")
                request.url.encodedPath == "/v1/models" -> jsonResponse("""{"data":[{"id":"fast"},{"id":"slow"}]}""")
                request.url.encodedPath.endsWith("/fast") -> jsonResponse("""{"id":"fast","context_window":128000,"max_output_tokens":16000}""")
                else -> awaitCancellation()
            }
        }
        withService(engine) { service ->
            val catalog = service.getCatalogWithCapabilities("https://api.example/v1", "key", "OpenAI兼容")
            assertEquals(128_000, catalog.first { it.modelId == "fast" }.contextWindowTokens)
            assertTrue(catalog.any { it.modelId == "slow" })
        }
    }

    @Test
    fun `取消获取会取消进行中的参数请求`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val engine = testEngine { request ->
            if (request.url.encodedPath == "/v1/models") {
                jsonResponse("""{"data":[{"id":"model-a"}]}""")
            } else {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        withService(engine) { service ->
            val job = launch { service.getCatalogWithCapabilities("https://api.example/v1", "key", "OpenAI兼容") }
            started.await()
            job.cancelAndJoin()
            cancelled.await()
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun `Codex优先请求单模型详情并使用Bearer认证`() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            when (request.url.host) {
                "models.dev", "pi.dev" -> jsonResponse("{}")
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
                "models.dev", "pi.dev" -> jsonResponse("{}")
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
                "models.dev", "pi.dev" -> jsonResponse("{}")
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
                "models.dev", "pi.dev" -> jsonResponse("{}")
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

    @Test
    fun `强制获取绕过端点和远程目录缓存`() = runTest {
        var fresh = false
        val requests = mutableListOf<String>()
        val engine = testEngine { request ->
            requests += request.url.host + request.url.encodedPath
            val output = if (fresh) 64_000 else 4_000
            when (request.url.host) {
                "pi.dev" -> jsonResponse(
                    """{"google":{"model-a":{"id":"model-a","contextWindow":1000000,"maxTokens":$output}}}"""
                )
                "models.dev" -> jsonResponse(
                    """{"google":{"models":{"model-a":{"limit":{"context":1000000,"output":$output}}}}}"""
                )
                else -> when (request.url.encodedPath) {
                    "/v1/models/model-a" -> respond("unsupported", HttpStatusCode.NotFound)
                    "/v1/models" -> jsonResponse("""{"data":[{"id":"model-a"}]}""")
                    else -> error("未预期的请求：${request.url}")
                }
            }
        }
        withService(engine) { service ->
            val old = service.getCapabilities("https://api.example/v1", "key", "OpenAI兼容", "model-a", "Google")
            assertEquals(4_000, resolveModelCapability(
                "model-a", ModelParameterProtocol.OPENAI_COMPATIBLE, "https://api.example/v1", old,
            ).maxOutputTokens)

            fresh = true
            val current = service.getCapabilities(
                "https://api.example/v1", "key", "OpenAI兼容", "model-a", "Google", allowCache = false,
            )
            val resolved = resolveModelCapability(
                "model-a", ModelParameterProtocol.OPENAI_COMPATIBLE, "https://api.example/v1", current,
            )
            assertEquals(64_000, resolved.maxOutputTokens)
            assertEquals(ModelCapabilitySource.PI_CATALOG, resolved.maxOutputSource)
            assertEquals(2, requests.count { it == "api.example/v1/models" })
            assertEquals(2, requests.count { it == "pi.dev/api/models" })
            assertEquals(2, requests.count { it == "models.dev/api.json" })
        }
    }

    @Test
    fun `强制获取网络失败时不返回已保存的端点和目录缓存`() = runTest {
        var online = true
        val engine = testEngine { request ->
            if (!online) return@testEngine respond("offline", HttpStatusCode.ServiceUnavailable)
            when (request.url.host) {
                "pi.dev" -> jsonResponse(
                    """{"google":{"model-a":{"id":"model-a","contextWindow":1000000,"maxTokens":4000}}}"""
                )
                "models.dev" -> jsonResponse(
                    """{"google":{"models":{"model-a":{"limit":{"context":1000000,"output":4000}}}}}"""
                )
                else -> when (request.url.encodedPath) {
                    "/v1/models/model-a" -> respond("unsupported", HttpStatusCode.NotFound)
                    "/v1/models" -> jsonResponse("""{"data":[{"id":"model-a","max_output_tokens":4000}]}""")
                    else -> error("未预期的请求：${request.url}")
                }
            }
        }
        withService(engine) { service ->
            val old = service.getCapabilities("https://api.example/v1", "key", "OpenAI兼容", "model-a", "Google")
            assertTrue(old.isNotEmpty())

            online = false
            val current = service.getCapabilities(
                "https://api.example/v1", "key", "OpenAI兼容", "model-a", "Google", allowCache = false,
            )
            assertTrue(current.isEmpty())
        }
    }

    /** 让模拟网络与超时共享测试时钟，避免虚拟时间先于真实 IO 线程跳到截止时间。 */
    private fun TestScope.testEngine(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): MockEngine = MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler(handler)
    })

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
                    piModelCatalog = PiModelCatalog(directory.resolve("pi.json")),
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
