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
import org.junit.Assert.*
import org.junit.Test

class McpOAuthTest {
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
