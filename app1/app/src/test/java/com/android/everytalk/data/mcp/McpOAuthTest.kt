package com.android.everytalk.data.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpOAuthTest {
    @Test fun `Gmail 使用离线授权且通过 Worker 交换 PKCE 和刷新令牌`() = runTest {
        val store = MemorySecrets()
        val provider = McpOAuthProvider.GMAIL
        var time = 100_000L
        var exchanges = 0
        var challenge = ""
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                assertEquals("https://oauth.everytalk.cc/oauth/mcp/gmail/token", request.url.toString())
                val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                assertEquals("google-client", form["client_id"])
                assertNull(form["client_secret"])
                assertNull(form["resource"])
                exchanges++
                if (exchanges == 1) {
                    assertEquals("authorization_code", form["grant_type"])
                    assertEquals(provider.redirectUri, form["redirect_uri"])
                    assertEquals(challenge, mcpPkceChallenge(form["code_verifier"]!!))
                } else {
                    assertEquals("refresh_token", form["grant_type"])
                    // Google 续期通常不返回 refresh_token，下一轮仍须保留旧值。
                    assertEquals("google-refresh", form["refresh_token"])
                }
                val refresh = if (exchanges == 1) ",\"refresh_token\":\"google-refresh\"" else ""
                respond("""{"access_token":"google-$exchanges","expires_in":120,"token_type":"Bearer"$refresh}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }))
        try {
            val missing = McpOAuthManager(store, client, "", gmailClientId = "") { time }
            assertTrue(runCatching { missing.start(provider) }.isFailure)
            assertTrue(store.values.isEmpty())
            val flow = McpOAuthManager(store, client, "", gmailClientId = "google-client") { time }
            val url = Url(flow.start(provider))
            assertEquals("accounts.google.com", url.host)
            assertEquals("/o/oauth2/v2/auth", url.encodedPath)
            assertEquals("offline", url.parameters["access_type"])
            assertEquals("consent", url.parameters["prompt"])
            assertEquals("https://www.googleapis.com/auth/gmail.modify", url.parameters["scope"])
            assertEquals(provider.redirectUri, url.parameters["redirect_uri"])
            assertEquals("S256", url.parameters["code_challenge_method"])
            challenge = url.parameters["code_challenge"]!!
            val callback = "${provider.appRedirectUri}?code=c&state=${url.parameters["state"]}"
            val restored = McpOAuthManager(store, client, "", gmailClientId = "google-client") { time }
            assertTrue(runCatching { restored.consume("$callback&iss=https%3A%2F%2Fevil.example") }.isFailure)
            assertEquals(provider, restored.consume(callback))
            assertTrue(runCatching { restored.consume(callback) }.isFailure)
            val config = provider.defaultConfig()
            assertTrue(config.headers.isEmpty())
            assertEquals("google-1", restored.accessTokenFor(config))
            time += 70_000
            assertEquals(listOf("google-2", "google-2"), listOf(
                async { restored.accessTokenFor(config) }, async { restored.accessTokenFor(config) },
            ).awaitAll())
            time += 70_000
            assertEquals("google-3", restored.accessTokenFor(config))
            val malicious = (config as McpServerConfig.StreamableHTTPServer).copy(url = "https://evil.example/mcp")
            assertTrue(runCatching { restored.accessTokenFor(malicious) }.isFailure)
            restored.logout(provider)
            assertTrue(store.values.isEmpty())
        } finally { client.close() }
    }

    @Test fun `Notion 注册登录跨进程回调续期和删除复用安全存储`() = runTest {
        val store = MemorySecrets()
        val provider = McpOAuthProvider.NOTION
        var time = 100_000L
        var exchanges = 0
        var challenge = ""
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                assertEquals("https", request.url.protocol.name)
                assertEquals("mcp.notion.com", request.url.host)
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                when (request.url.encodedPath) {
                    "/register" -> {
                        val registration = Json.parseToJsonElement(body).jsonObject
                        assertEquals("none", registration["token_endpoint_auth_method"]!!.jsonPrimitive.content)
                        assertEquals(listOf("everytalk://oauth/mcp/notion"),
                            registration["redirect_uris"]!!.jsonArray.map { it.jsonPrimitive.content })
                        respond("""{"client_id":"notion-client"}""", HttpStatusCode.Created, headersOf("Content-Type", "application/json"))
                    }
                    "/token" -> {
                        val form = parseQueryString(body)
                        assertEquals("notion-client", form["client_id"])
                        assertEquals(provider.endpoint, form["resource"])
                        assertNull(form["client_secret"])
                        exchanges++
                        if (exchanges == 1) {
                            assertEquals("authorization_code", form["grant_type"])
                            assertEquals(provider.appRedirectUri, form["redirect_uri"])
                            assertEquals(challenge, mcpPkceChallenge(form["code_verifier"]!!))
                        } else {
                            assertEquals("refresh_token", form["grant_type"])
                            assertEquals("refresh-1", form["refresh_token"])
                        }
                        respond("""{"access_token":"notion-$exchanges","refresh_token":"refresh-$exchanges","expires_in":120,"token_type":"Bearer"}""",
                            HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    }
                    else -> error("不应调用其他端点")
                }
            }
        }))
        try {
            val initial = McpOAuthManager(store, client, "") { time }
            val url = Url(initial.start(provider))
            assertEquals("https://mcp.notion.com/authorize", url.toString().substringBefore('?'))
            assertEquals("default", url.parameters["scope"])
            assertEquals(provider.endpoint, url.parameters["resource"])
            assertEquals(provider.appRedirectUri, url.parameters["redirect_uri"])
            assertEquals("S256", url.parameters["code_challenge_method"])
            challenge = url.parameters["code_challenge"]!!
            val callback = "${provider.appRedirectUri}?code=notion-code&state=${url.parameters["state"]}"
            assertEquals(provider, McpOAuthCallbackBus.providerFor(callback))
            val restored = McpOAuthManager(store, client, "") { time }
            assertTrue(runCatching { restored.consume("$callback&iss=https%3A%2F%2Fevil.example") }.isFailure)
            assertTrue(runCatching { restored.consume("$callback&state=duplicate") }.isFailure)
            assertEquals(provider, restored.consume(callback))
            assertTrue(runCatching { restored.consume(callback) }.isFailure)
            val config = provider.defaultConfig()
            assertTrue(config.headers.isEmpty())
            assertEquals("notion-1", restored.accessTokenFor(config))
            time += 70_000
            assertEquals(listOf("notion-2", "notion-2"), listOf(
                async { restored.accessTokenFor(config) }, async { restored.accessTokenFor(config) },
            ).awaitAll())
            assertEquals(2, exchanges)
            val malicious = (config as McpServerConfig.StreamableHTTPServer).copy(url = "https://evil.example/mcp")
            assertTrue(runCatching { restored.accessTokenFor(malicious) }.isFailure)
            restored.logout(provider)
            assertTrue(store.values.isEmpty())
            assertTrue(runCatching { restored.accessTokenFor(config) }.isFailure)
            val cancelled = Url(restored.start(provider))
            assertTrue(runCatching { restored.consume("${provider.appRedirectUri}?error=access_denied&state=${cancelled.parameters["state"]}") }.isFailure)
            assertTrue(store.values.isEmpty())
        } finally { client.close() }
    }

    private class MemorySecrets : McpOAuthSecrets {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    @Test fun `PKCE 符合 RFC7636 官方向量`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            mcpPkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `回调拒绝重复参数片段和跨提供方 issuer`() {
        val p = McpOAuthProvider.CLOUDFLARE
        listOf("${p.redirectUri}?state=a&state=b", "${p.redirectUri}?state=a#x",
            "${p.redirectUri}?state=a&iss=https%3A%2F%2Fevil.example", "${p.redirectUri}/extra?state=a")
            .forEach { assertTrue(runCatching { parseMcpCallback(it, p) }.isFailure) }
        assertEquals("a", parseMcpCallback("${p.appRedirectUri}?state=a&code=c", p)["state"])
    }

    @Test fun `GitHub 登录跨进程恢复 PKCE 一次性消费且刷新轮换只执行一次`() = runTest {
        val store = MemorySecrets()
        var time = 100_000L
        var exchanges = 0
        var challenge = ""
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                assertEquals("oauth.everytalk.cc", request.url.host)
                val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                assertEquals("client", form["client_id"])
                assertNull(form["client_secret"])
                exchanges++
                if (exchanges == 1) {
                    assertEquals(challenge, mcpPkceChallenge(form["code_verifier"]!!))
                    assertEquals("authorization_code", form["grant_type"])
                } else {
                    assertEquals("refresh_token", form["grant_type"])
                    assertEquals("refresh-${exchanges - 1}", form["refresh_token"])
                }
                respond("""{"access_token":"access-$exchanges","refresh_token":"refresh-$exchanges","expires_in":120,"token_type":"bearer"}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }))
        try {
            val provider = McpOAuthProvider.GITHUB
            val initial = McpOAuthManager(store, client, "client") { time }
            val url = Url(initial.start(provider))
            challenge = url.parameters["code_challenge"]!!
            val callback = "${provider.redirectUri}?code=code&state=${url.parameters["state"]}"
            val restored = McpOAuthManager(store, client, "client") { time }
            assertTrue(runCatching { restored.consume("${provider.redirectUri}?code=c&state=forged") }.isFailure)
            assertEquals(provider, restored.consume(callback))
            assertTrue(runCatching { restored.consume(callback) }.isFailure)
            assertEquals("access-1", restored.accessTokenFor(provider.defaultConfig()))
            time += 70_000
            assertEquals(listOf("access-2", "access-2"), listOf(
                async { restored.accessTokenFor(provider.defaultConfig()) },
                async { restored.accessTokenFor(provider.defaultConfig()) },
            ).awaitAll())
            time += 70_000
            assertEquals("access-3", restored.accessTokenFor(provider.defaultConfig()))
            assertEquals(3, exchanges)
            val malicious = (provider.defaultConfig() as McpServerConfig.StreamableHTTPServer).copy(url = "https://evil.example/mcp")
            assertTrue(runCatching { restored.accessTokenFor(malicious) }.isFailure)
            restored.logout(provider)
            assertTrue(store.values.isEmpty())
        } finally { client.close() }
    }

    @Test fun `Cloudflare 动态注册后拒绝取消过期及旧登录回调`() = runTest {
        val store = MemorySecrets()
        var time = 100_000L
        val provider = McpOAuthProvider.CLOUDFLARE
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                assertEquals("/register", it.url.encodedPath)
                respond("""{"client_id":"registered"}""", HttpStatusCode.Created, headersOf("Content-Type", "application/json"))
            }
        }))
        try {
            val flow = McpOAuthManager(store, client, "client") { time }
            val first = Url(flow.start(provider))
            val second = Url(flow.start(provider))
            assertEquals("registered", second.parameters["client_id"])
            assertEquals(provider.endpoint, second.parameters["resource"])
            assertTrue(runCatching { flow.consume("${provider.redirectUri}?code=c&state=${first.parameters["state"]}") }.isFailure)
            assertTrue(runCatching { flow.consume("${provider.redirectUri}?error=access_denied&state=${second.parameters["state"]}") }.isFailure)
            assertTrue(store.values.isEmpty())
            val third = Url(flow.start(provider))
            time += 600_001
            assertTrue(runCatching { flow.consume("${provider.redirectUri}?code=c&state=${third.parameters["state"]}") }.isFailure)
            assertTrue(store.values.isEmpty())
        } finally { client.close() }
    }
}
