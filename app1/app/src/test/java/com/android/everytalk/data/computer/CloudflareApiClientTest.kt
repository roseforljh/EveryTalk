package com.android.everytalk.data.computer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CloudflareApiClientTest {
    @Test
    fun `Worker 列表解析 Cloudflare 数组格式`() = runTest {
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                respond("{\"success\":true,\"result\":[{\"id\":\"hello\",\"etag\":\"tag\"}]}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }))
        val result = CloudflareApiClient(client, tokenProvider = { "token" }).listWorkers("account")
        assertEquals("hello", result.workers.single().id)
    }

    @Test
    fun `HTTP 200 但 success false 仍视为失败`() = runTest {
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond("{\"success\":false,\"result\":[]}", HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        }))
        var code: String? = null
        try { CloudflareApiClient(client, tokenProvider = { "token" }).listAccounts() }
        catch (error: CloudflareApiException) { code = error.code }
        assertEquals("CLOUDFLARE_API_ERROR", code)
    }
    @Test
    fun `listAccounts 注入 Bearer Token 并解析结果`() = runTest {
        var authorization = ""
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                authorization = request.headers[HttpHeaders.Authorization].orEmpty()
                respond("{\"success\":true,\"result\":[{\"id\":\"a1\",\"name\":\"个人\"}]}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }))
        val accounts = CloudflareApiClient(client, tokenProvider = { "token" }).listAccounts()
        assertEquals("Bearer token", authorization)
        assertEquals("a1", accounts.single().id)
    }

    @Test
    fun `缺少 Token 时拒绝请求`() = runTest {
        val client = HttpClient(MockEngine(MockEngineConfig().apply { addHandler { respond("", HttpStatusCode.OK) } }))
        var thrown = false
        try {
            CloudflareApiClient(client, tokenProvider = { "" }).listAccounts()
        } catch (_: CloudflareApiException) {
            thrown = true
        }
        assertEquals(true, thrown)
    }

    @Test
    fun `Worker health 只返回状态和延迟且拒绝不可信主机`() = runTest {
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond("worker response should not be exposed", HttpStatusCode.OK) }
        }))
        val health = CloudflareApiClient(client, tokenProvider = { "token" }).probeWorker("https://demo.workers.dev/health")
        assertEquals(CloudflareWorkerRuntimeStatus.HEALTHY, health.status)
        assertEquals(200, health.httpStatus)
        assertThrows(IllegalArgumentException::class.java) {
            // 这里只验证 URL 主机边界；真实请求不会发出。
            kotlinx.coroutines.runBlocking { CloudflareApiClient(client, tokenProvider = { "token" }).probeWorker("https://example.com") }
        }
        client.close()
    }

    @Test
    fun `Worker 部署先上传版本再创建正式 deployment`() = runTest {
        val paths = CopyOnWriteArrayList<String>()
        val methods = CopyOnWriteArrayList<String>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                paths += request.url.encodedPath
                methods += request.method.value
                when {
                    request.url.encodedPath.endsWith("/versions") -> respond(
                        "{\"success\":true,\"result\":{\"id\":\"11111111-1111-1111-1111-111111111111\"}}",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"),
                    )
                    request.url.encodedPath.endsWith("/deployments") -> respond(
                        "{\"success\":true,\"result\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"versions\":[{\"version_id\":\"11111111-1111-1111-1111-111111111111\"}]}}",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"),
                    )
                    else -> error("unexpected path ${request.url.encodedPath}")
                }
            }
        }))
        val result = CloudflareApiClient(client, tokenProvider = { "token" }).deployModuleWorker(
            "account",
            "hello",
            WorkerPackage(listOf(WorkerPackageFile("worker.js", "export default {}".toByteArray(), "hash")), "hash", 18),
        )
        assertEquals("22222222-2222-2222-2222-222222222222", result.deploymentId)
        assertEquals("11111111-1111-1111-1111-111111111111", result.versionId)
        assertEquals(listOf("/client/v4/accounts/account/workers/scripts/hello/versions", "/client/v4/accounts/account/workers/scripts/hello/deployments"), paths)
        assertEquals(listOf("POST", "POST"), methods)
    }

    @Test
    fun `UNKNOWN 对账查询真实 deployment`() = runTest {
        var requestedPath = ""
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requestedPath = request.url.encodedPath
                respond(
                    "{\"success\":true,\"result\":{\"id\":\"22222222-2222-2222-2222-222222222222\",\"versions\":[]}}",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
        }))
        val status = CloudflareApiClient(client, tokenProvider = { "token" }).workerDeploymentStatus(
            "account", "hello", "22222222-2222-2222-2222-222222222222",
        )
        assertEquals(CloudflareDeploymentStatus.DEPLOYMENT_SUCCEEDED, status)
        assertEquals("/client/v4/accounts/account/workers/scripts/hello/deployments/22222222-2222-2222-2222-222222222222", requestedPath)
    }

    @Test
    fun `Queue Peek 使用官方 endpoint`() = runTest {
        var requestedPath = ""
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requestedPath = request.url.encodedPath
                respond(
                    "{\"success\":true,\"result\":{\"messages\":[{\"id\":\"m1\",\"body\":\"secret\"}]}}",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
        }))
        val result = CloudflareResourceClient(client, tokenProvider = { "token" }).peekQueue("account", "queue", 3)
        assertEquals("/client/v4/accounts/account/queues/queue/messages/peek", requestedPath)
        assertTrue(result.toString().contains("secret"))
        client.close()
    }

    @Test
    fun `UNKNOWN 且只有 versionId 时可从部署历史反查 deployment`() = runTest {
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                respond(
                    "{\"success\":true,\"result\":[{\"id\":\"deployment-1\",\"versions\":[{\"version_id\":\"version-1\"}]}]}",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
        }))
        val lookup = CloudflareApiClient(client, tokenProvider = { "token" })
            .findDeploymentByVersion("account", "hello", "version-1")
        assertEquals("deployment-1", lookup?.deploymentId)
        assertEquals(CloudflareDeploymentStatus.DEPLOYMENT_SUCCEEDED, lookup?.status)
        client.close()
    }

    @Test
    fun `D1 写查询响应中的单条失败不会被外层 success 掩盖`() = runTest {
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                respond(
                    "{\"success\":true,\"result\":[{\"success\":false,\"results\":[]}]}",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
        }))
        var code: String? = null
        try {
            CloudflareResourceClient(client, tokenProvider = { "token" }).queryD1("account", "db", "UPDATE users SET x=1")
        } catch (error: CloudflareApiException) {
            code = error.code
        } finally {
            client.close()
        }
        assertEquals("D1_QUERY_FAILED", code)
    }
}
