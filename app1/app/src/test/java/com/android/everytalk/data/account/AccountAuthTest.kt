package com.android.everytalk.data.account

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** 不依赖真实账号，直接验证出站请求、回调边界和加密存储边界的状态变化。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountAuthTest {
    private val config = AccountAuthConfig("https://example.supabase.co", "sb_publishable_test-public-key", "everytalk://oauth/account")
    private val user = AccountUser("user-1", "hello@example.com", "2026-10-09T00:00:00Z")
    private val sessionResponse = """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600,"token_type":"bearer","user":{"id":"user-1","email":"hello@example.com"}}"""

    private class MemorySecrets : AccountSecrets {
        val values = mutableMapOf<String, String>()
        override suspend fun read(reference: String): String? = values[reference]
        override suspend fun write(reference: String, value: String): Unit { values[reference] = value }
        override suspend fun remove(reference: String): Unit { values.remove(reference) }
    }

    private fun TestScope.http(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler(handler)
    })) { followRedirects = false; expectSuccess = false }

    private fun body(request: HttpRequestData) = accountJson.parseToJsonElement((request.body as TextContent).text).jsonObject
    private fun query(raw: String): Map<String, String> = URI(raw).rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }

    @Test fun `客户端拒绝管理员密钥和不安全项目地址`() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"role":"service_role"}""".toByteArray())
        for (key in listOf("sb_secret_not-for-an-apk", "header.$payload.signature")) {
            assertTrue(runCatching { config.copy(publicKey = key) }.isFailure)
        }
        for (url in listOf("http://example.supabase.co", "https://user:pass@example.supabase.co", "https://example.supabase.co/path")) {
            assertTrue(runCatching { config.copy(projectUrl = url) }.isFailure)
        }
        val anon = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"role":"anon"}""".toByteArray())
        assertNotNull(config.copy(publicKey = "header.$anon.signature"))
        assertEquals("user+tag@example.com", normalizeAccountEmail(" User+Tag@Example.com "))
        assertTrue(runCatching { normalizeAccountEmail("a@example.com\r\nb@example.com") }.isFailure)
    }

    @Test fun `邮箱验证才建立账号会话并且首次允许注册`() = runTest {
        val paths = mutableListOf<String>()
        val http = http { request ->
            paths += request.url.encodedPath
            assertEquals(config.publicKey, request.headers["apikey"])
            assertNull(request.headers["Authorization"])
            val payload = body(request)
            when (request.url.encodedPath) {
                "/auth/v1/otp" -> {
                    assertEquals("hello@example.com", payload["email"]?.jsonPrimitive?.content)
                    assertEquals("true", payload["create_user"]?.jsonPrimitive?.content)
                    respond("{}", HttpStatusCode.OK)
                }
                "/auth/v1/verify" -> {
                    assertEquals("email", payload["type"]?.jsonPrimitive?.content)
                    assertEquals("123456", payload["token"]?.jsonPrimitive?.content)
                    respond(sessionResponse, HttpStatusCode.OK)
                }
                else -> error("不能调用未知认证接口")
            }
        }
        val secrets = MemorySecrets()
        val manager = AccountManager(AccountAuthClient(config, http) { 1_000_000 }, secrets, backgroundScope, { 1_000_000 })
        runCurrent()
        manager.sendEmailCode(" HELLO@example.com ").join()
        assertNull(manager.state.value.user)
        assertEquals("hello@example.com", manager.state.value.pendingEmail)
        manager.sendEmailCode("hello@example.com").join()
        assertEquals(1, paths.size) // 冷却期间不能重复发邮件。
        manager.verifyEmailCode("123456").join()
        assertEquals(user.id, manager.state.value.user?.id)
        assertTrue(secrets.values.containsKey(config.storageReference))
        assertNull(manager.state.value.pendingEmail)
        assertEquals(listOf("/auth/v1/otp", "/auth/v1/verify"), paths)
        http.close()
    }

    @Test fun `无效验证码和上游错误不泄露凭据也不建立会话`() = runTest {
        var calls = 0
        val http = http { request ->
            calls++
            if (request.url.encodedPath.endsWith("/otp")) respond("{}", HttpStatusCode.OK)
            else respond("""{"error_code":"otp_expired","msg":"secret-token-and-server-details"}""", HttpStatusCode.Forbidden)
        }
        val secrets = MemorySecrets()
        val manager = AccountManager(AccountAuthClient(config, http), secrets, backgroundScope)
        runCurrent()
        manager.sendEmailCode("hello@example.com").join()
        manager.verifyEmailCode("abc123").join()
        assertEquals(1, calls)
        manager.verifyEmailCode("123456").join()
        assertNull(manager.state.value.user)
        assertTrue(secrets.values.isEmpty())
        assertFalse(manager.state.value.message.orEmpty().contains("secret-token"))
        http.close()
    }

    @Test fun `Google只申请身份权限且恶意回调不会消费合法草稿`() = runTest {
        var exchanges = 0
        val http = http { request ->
            exchanges++
            assertEquals("pkce", request.url.parameters["grant_type"])
            assertEquals("valid-code", body(request)["auth_code"]?.jsonPrimitive?.content)
            assertNotNull(body(request)["code_verifier"])
            respond(sessionResponse, HttpStatusCode.OK)
        }
        val secrets = MemorySecrets()
        val manager = AccountManager(AccountAuthClient(config, http), secrets, backgroundScope)
        runCurrent()
        var authorizationUrl = ""
        manager.startGoogleLogin { authorizationUrl = it }.join()
        val params = query(authorizationUrl)
        assertEquals("openid email profile", params["scopes"])
        assertEquals("s256", params["code_challenge_method"])
        val pending = accountJson.decodeFromString<PendingAccountOAuth>(secrets.values.getValue(config.storageReference + ":pending"))
        assertEquals(pending.challenge, params["code_challenge"])
        val callback = config.redirectUri + "?login_state=${pending.state}&code=valid-code"
        for (bad in listOf(callback.replace(pending.state, "stolen-state"), callback + "&code=other", callback + "#access_token=leak")) {
            manager.handleGoogleCallback(bad).join()
            assertTrue(secrets.values.containsKey(config.storageReference + ":pending"))
            assertEquals(0, exchanges)
        }
        manager.handleGoogleCallback(callback).join()
        assertEquals(user.id, manager.state.value.user?.id)
        manager.handleGoogleCallback(callback).join()
        assertEquals(1, exchanges)
        http.close()
    }

    @Test fun `过期授权和直接传Token的回调都不能建立会话`() {
        val pending = PendingAccountOAuth.create(1_000)
        val callback = config.redirectUri + "?login_state=${pending.state}&code=c"
        for (raw in listOf(callback + "&access_token=token", callback.replace("everytalk://", "everytalk-debug://"))) {
            assertTrue(runCatching { pending.consumeCode(raw, config.redirectUri, 1_001) }.isFailure)
        }
        assertTrue(runCatching { pending.consumeCode(callback, config.redirectUri, 1_000 + ACCOUNT_OAUTH_TTL_MILLIS) }.isFailure)
        assertTrue(runCatching { pending.consumeCode(callback, config.redirectUri, 999) }.isFailure)
    }

    @Test fun `刷新轮换Token先保存且临时网络失败保留会话`() = runTest {
        val secrets = MemorySecrets()
        secrets.values[config.storageReference] = accountJson.encodeToString(AccountSession("old-access", "old-refresh", 1, user))
        var unavailable = true
        var refreshCount = 0
        val http = http { request ->
            if (request.url.encodedPath.endsWith("/token")) {
                refreshCount++
                assertEquals("old-refresh", body(request)["refresh_token"]?.jsonPrimitive?.content)
                respond(sessionResponse, HttpStatusCode.OK)
            } else {
                val saved = accountJson.decodeFromString<AccountSession>(secrets.values.getValue(config.storageReference))
                assertEquals("refresh-1", saved.refreshToken)
                assertEquals("Bearer access-1", request.headers["Authorization"])
                if (unavailable) respond("{}", HttpStatusCode.ServiceUnavailable)
                else respond(accountJson.encodeToString(user), HttpStatusCode.OK)
            }
        }
        val manager = AccountManager(AccountAuthClient(config, http) { 1_000_000 }, secrets, backgroundScope, { 1_000_000 })
        runCurrent()
        assertTrue(secrets.values.containsKey(config.storageReference))
        assertNull(manager.state.value.user)
        assertFalse(manager.state.value.busy)
        unavailable = false
        manager.refreshAccount().join()
        assertEquals(user.id, manager.state.value.user?.id)
        assertEquals(1, refreshCount)
        http.close()
    }

    @Test fun `服务端拒绝已有会话后删除本地凭据`() = runTest {
        val secrets = MemorySecrets()
        secrets.values[config.storageReference] = accountJson.encodeToString(AccountSession("access", "refresh", Long.MAX_VALUE, user))
        val http = http { respond("{}", HttpStatusCode.Unauthorized) }
        val manager = AccountManager(AccountAuthClient(config, http), secrets, backgroundScope)
        runCurrent()
        assertNull(manager.state.value.user)
        assertFalse(secrets.values.containsKey(config.storageReference))
        http.close()
    }

    @Test fun `退出网络失败也清除本地账号且迟到Google回调不能重新登录`() = runTest {
        val secrets = MemorySecrets()
        secrets.values[config.storageReference] = accountJson.encodeToString(AccountSession("access", "refresh", Long.MAX_VALUE, user))
        val http = http { request ->
            if (request.url.encodedPath.endsWith("/user")) respond(accountJson.encodeToString(user), HttpStatusCode.OK)
            else {
                assertEquals("/auth/v1/logout", request.url.encodedPath)
                assertEquals("local", request.url.parameters["scope"])
                respond("{}", HttpStatusCode.ServiceUnavailable)
            }
        }
        val manager = AccountManager(AccountAuthClient(config, http), secrets, backgroundScope)
        runCurrent()
        assertEquals(user.id, manager.state.value.user?.id)
        manager.startGoogleLogin { }.join()
        val pending = accountJson.decodeFromString<PendingAccountOAuth>(secrets.values.getValue(config.storageReference + ":pending"))
        manager.logout().join()
        assertNull(manager.state.value.user)
        assertTrue(secrets.values.isEmpty())
        assertTrue(manager.state.value.message.orEmpty().contains("撤销失败"))
        manager.handleGoogleCallback(config.redirectUri + "?login_state=${pending.state}&code=c").join()
        assertNull(manager.state.value.user)
        http.close()
    }
}
