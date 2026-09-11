package com.android.everytalk.data.computer

import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareOAuthClientTest {
    @Test
    fun `续期用 refresh_token 且响应缺新值時沿用旧值`() = runTest {
        var requestBody = ""
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requestBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respond(
                    "{\"access_token\":\"new-access\",\"expires_in\":3600}",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
        }))
        val config = CloudflareOAuthConfig("client", "everytalk://oauth/cloudflare")

        val result = exchangeCloudflareRefreshToken(client, config, "old-refresh".toCharArray(), Json)

        assertEquals("new-access", result.accessToken.concatToString())
        assertEquals(3600L, result.expiresInSeconds)
        assertEquals("old-refresh", result.refreshToken?.concatToString())
        assertTrue(requestBody, requestBody.contains("grant_type=refresh_token"))
        assertTrue(requestBody, requestBody.contains("refresh_token=old-refresh"))
        client.close()
    }

    @Test
    fun `续期失败报 TOKEN_REFRESH_FAILED`() = runTest {
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond("nope", HttpStatusCode.Unauthorized) }
        }))
        val config = CloudflareOAuthConfig("client", "everytalk://oauth/cloudflare")

        val error = runCatching {
            exchangeCloudflareRefreshToken(client, config, "r".toCharArray(), Json)
        }.exceptionOrNull()

        assertTrue(error is CloudflareOAuthException)
        client.close()
    }

    @Test fun `拒绝重复 state 片段和额外尾斜线`() {
        listOf("?code=c&state=a&state=b", "?code=c&state=a#fragment", "/?code=c&state=a").forEach { suffix ->
            assertThrows(IllegalArgumentException::class.java) {
                parseCloudflareOAuthCallback("everytalk://oauth/cloudflare$suffix", "everytalk://oauth/cloudflare")
            }
        }
    }
    private val config = CloudflareOAuthConfig("client", "everytalk://oauth/cloudflare")

    @Test
    fun `回调只接受配置的 redirect uri 和 code state`() {
        val callback = parseCloudflareOAuthCallback("everytalk://oauth/cloudflare?code=c1&state=s1", config.redirectUri)
        assertEquals("c1", callback.code)
        assertEquals("s1", callback.state)
    }

    @Test
    fun `回调拒绝错误或错误地址`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseCloudflareOAuthCallback("everytalk://other?code=c1&state=s1", config.redirectUri)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCloudflareOAuthCallback("everytalk://oauth/cloudflare?error=access_denied", config.redirectUri)
        }
    }

    @Test
    fun `回调携带 nonce 时保留 nonce`() {
        val callback = parseCloudflareOAuthCallback("everytalk://oauth/cloudflare?code=c1&state=s1&nonce=n1", config.redirectUri)
        assertEquals("n1", callback.nonce)
    }
}
