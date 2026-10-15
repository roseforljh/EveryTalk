package com.android.everytalk.data.sync

import com.android.everytalk.data.account.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** 假 HTTP 服务验证 401/429、刷新故障、本地模式和缺失地址的实际请求边界。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncApiTest {
    private val config = AccountAuthConfig("https://example.supabase.co", "sb_publishable_test-key", "everytalk://oauth/account")
    private val user = AccountUser("user-1", "hello@example.com", "2026-10-09T00:00:00Z")
    private class Secrets : AccountSecrets {
        val values = mutableMapOf<String, String>()
        override suspend fun read(reference: String) = values[reference]
        override suspend fun write(reference: String, value: String) { values[reference] = value }
        override suspend fun remove(reference: String) { values.remove(reference) }
    }
    private fun TestScope.http(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler)
        })) { expectSuccess = false; followRedirects = false }
    private fun TestScope.account(http: HttpClient): AccountManager {
        val secrets = Secrets().apply { values[config.storageReference] = accountJson.encodeToString(
            AccountSession("old-access", "refresh-1", 9999999999999, user)) }
        return AccountManager(AccountAuthClient(config, http), secrets, backgroundScope, restoreOnCreate = false)
    }
    private val emptyPull = """{"changes":[],"next_cursor":0,"has_more":false,"epoch":0}"""
    private val refresh = """{"access_token":"new-access","refresh_token":"refresh-2","expires_in":3600,"token_type":"bearer","user":{"id":"user-1","email":"hello@example.com"}}"""

    @Test fun `401仅刷新一次成功恢复原请求`() = runTest {
        var syncCalls = 0; var refreshCalls = 0
        val http = http { request ->
            if (request.url.encodedPath == "/auth/v1/token") { refreshCalls++; respond(refresh, HttpStatusCode.OK) }
            else {
                assertEquals("/sync/pull", request.url.encodedPath)
                assertEquals(SYNC_VERSION, request.headers["X-EveryTalk-Sync-Version"])
                assertEquals("0", request.url.parameters["since"])
                syncCalls++
                assertEquals(if (syncCalls == 1) "Bearer old-access" else "Bearer new-access", request.headers["Authorization"])
                respond(if (syncCalls == 1) "{}" else emptyPull, if (syncCalls == 1) HttpStatusCode.Unauthorized else HttpStatusCode.OK)
            }
        }
        val manager = account(http)
        val api = KtorSyncApi("https://sync.example", manager, { false }, httpClient = http)
        assertEquals(0L, api.pull(0).epoch)
        assertEquals(2, syncCalls); assertEquals(1, refreshCalls)
        assertFalse(manager.state.value.sessionExpired)
        http.close()
    }

    @Test fun `刷新明确拒绝过期断网超时和5xx保留会话`() = runTest {
        for (failure in listOf("reject", "offline", "timeout", "server")) {
            val http = http { request ->
                if (request.url.encodedPath != "/auth/v1/token") respond("{}", HttpStatusCode.Unauthorized)
                else when (failure) {
                    "reject" -> respond("""{"error_code":"refresh_token_not_found","msg":"invalid"}""", HttpStatusCode.BadRequest)
                    "offline" -> throw IOException("断网")
                    "timeout" -> throw java.net.SocketTimeoutException("超时")
                    else -> respond("{}", HttpStatusCode.ServiceUnavailable)
                }
            }
            val manager = account(http)
            val error = runCatching { KtorSyncApi("https://sync.example", manager, { false }, httpClient = http).pull(0) }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(failure == "reject", manager.state.value.sessionExpired)
            if (failure == "reject") assertTrue(error is SyncRefreshRejectedException)
            else assertNotNull(manager.accessTokenForOwnedServer())
            http.close()
        }
    }

    @Test fun `429保留RetryAfter不立即重试第二次401也不伪造过期`() = runTest {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.Unauthorized)) {
            var calls = 0
            val http = http { request ->
                if (request.url.encodedPath == "/auth/v1/token") respond(refresh, HttpStatusCode.OK)
                else { calls++; respond("{}", status, headersOf("Retry-After", "120")) }
            }
            val manager = account(http)
            val error = runCatching { KtorSyncApi("https://sync.example", manager, { false }, httpClient = http).pull(0) }.exceptionOrNull() as SyncHttpException
            assertEquals(status.value, error.statusCode)
            assertEquals(120L, error.retryAfterSeconds)
            assertEquals(if (status == HttpStatusCode.Unauthorized) 2 else 1, calls)
            assertFalse(manager.state.value.sessionExpired)
            http.close()
        }
    }

    @Test fun `本地模式撤回权限和缺地址都不访问token或HTTP`() = runTest {
        val http = http { error("不应请求认证或同步") }; val manager = account(http)
        for (url in listOf("", "broken", "https://u:p@example.com", "https://example.com?secret=x")) {
            assertFalse(syncServerConfigured(url))
            assertTrue(runCatching { KtorSyncApi(url, manager, { false }, httpClient = http).pull(0) }.exceptionOrNull() is SyncNotConfiguredException)
        }
        assertTrue(runCatching { KtorSyncApi("https://sync.example", manager, { true }, httpClient = http).pull(0) }.isFailure)
        assertTrue(runCatching { KtorSyncApi("https://sync.example", manager, { false }, httpClient = http, canRequest = { false }).pull(0) }.isFailure)
        http.close()
    }

    @Test fun `消息墓碑保留合同required的空payload`() {
        val json = syncJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(PushChange("messages", "uuid", true))))
        assertTrue(json.contains("\"payload\":{}"))
    }

    @Test fun `只有密文或crypto_keys请求携带密钥代次`() = runTest {
        val http = http { request ->
            assertEquals(SYNC_VERSION, request.headers["X-EveryTalk-Sync-Version"])
            if (request.url.encodedPath == "/sync/secrets") {
                assertNull(request.headers["X-EveryTalk-Secrets-Epoch"]); respond("", HttpStatusCode.NoContent)
            } else {
                val body = request.body.toByteArray().toString(Charsets.UTF_8)
                if (body.contains("memory_switches")) assertNull(request.headers["X-EveryTalk-Secrets-Epoch"])
                else assertEquals("7", request.headers["X-EveryTalk-Secrets-Epoch"])
                respond("{\"results\":[]}", HttpStatusCode.OK)
            }
        }
        val manager = account(http)
        val api = KtorSyncApi("https://sync.example", manager, { false }, httpClient = http,
            readSecretsEpoch = { 7L })
        api.push(listOf(PushChange("user_settings", "00000000-0000-4000-8000-000000000001", false,
            buildJsonObject { put("kind", "memory_switches") })))
        api.push(listOf(PushChange("user_settings", "00000000-0000-4000-8000-000000000002", false,
            buildJsonObject { put("kind", "crypto_keys"); put("value", buildJsonObject {
                put("wrappedDek", buildJsonObject { put("\$enc", 1); put("alg", "AES-256-GCM"); put("nonce", "AAAAAAAAAAAAAAAA"); put("ct", "AAAAAAAAAAAAAAAAAAAAAAAA"); put("keyVersion", 1) })
            }) })))
        api.deleteSecrets()
        http.close()
    }
}
