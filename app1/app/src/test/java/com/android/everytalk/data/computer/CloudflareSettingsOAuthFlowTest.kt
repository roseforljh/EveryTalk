package com.android.everytalk.data.computer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudflareSettingsOAuthFlowTest {
    @Test fun `取消回调消费草稿且重试获得新 state`() = runTest {
        val store = CloudflareSettingsOAuthStore()
        val client = HttpClient(MockEngine { error("取消回调不能发送 Token 请求") })
        val flow = CloudflareSettingsOAuthFlow(CloudflareOAuthConfig("client", "everytalk://oauth/cloudflare"), store, client)
        val first = flow.start("draft")
        val failed = runCatching { flow.consumeRaw("everytalk://oauth/cloudflare?error=access_denied&state=${first.request.state}", "draft") }
        assertEquals(true, failed.isFailure)
        assertEquals(false, store.owns(first.request.state, "draft"))
        val retry = flow.start("draft")
        assertEquals(true, store.owns(retry.request.state, "draft"))
        client.close()
    }

    @Test fun `共享 Store 不让设置页消费 Agent 的授权状态`() = runTest {
        val store = CloudflareSettingsOAuthStore()
        val client = HttpClient(MockEngine { error("错误归属不能交换 Token") })
        val flow = CloudflareSettingsOAuthFlow(CloudflareOAuthConfig("client", "everytalk://oauth/cloudflare"), store, client)
        val start = flow.start("agent:suspension")
        assertEquals(true, runCatching { flow.consumeRaw("everytalk://oauth/cloudflare?code=c&state=${start.request.state}", "settings:cf") }.isFailure)
        assertEquals(true, store.owns(start.request.state, "agent:suspension"))
        client.close()
    }
    @Test
    fun `设置 OAuth 成功交换并且回调只能消费一次`() = runTest {
        val flow = CloudflareSettingsOAuthFlow(
            CloudflareOAuthConfig("client", "everytalk://oauth/cloudflare"),
            CloudflareSettingsOAuthStore(),
            HttpClient(MockEngine(MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { respond("{\"access_token\":\"access\",\"scope\":\"account:read\"}", HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
            })),
        )
        val start = flow.start("draft")
        val callback = "everytalk://oauth/cloudflare?code=code&state=${start.request.state}"
        val result = flow.consumeRaw(callback, "draft")
        assertEquals("access", result.accessToken.concatToString())
        var failed = false
        try { flow.consumeRaw(callback, "draft") }
        catch (_: CloudflareOAuthException) { failed = true }
        assertEquals(true, failed)
        result.accessToken.fill('\u0000')
    }
}
