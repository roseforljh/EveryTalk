package com.android.everytalk.data.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CloudAgentClientTest {
    private val id = "a1ec0101-29dd-4b90-8b64-192f2f037777"
    private fun view(cursor: Long, status: String = "RUNNING") = CloudRunView(1, id, id, id, id, status, 1, cursor,
        CloudRunSnapshot("回复$cursor", "", emptyList(), null, 1))
    private fun raw(cursor: Long, status: String = "RUNNING") = cloudAgentJson.encodeToString(CloudRunView.serializer(), view(cursor, status))

    @Test fun `事件连接携带已落盘游标且取消只退订不停止`() = runBlocking {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requests += request.method.value + " " + request.url.encodedPath
            assertEquals("17", request.url.parameters["after"])
            assertEquals("Bearer access", request.headers["Authorization"])
            respond("event: snapshot\ndata: ${raw(19)}\n\nevent: ping\ndata: \n\n", headers = headersOf("Content-Type", "text/event-stream"))
        })
        try {
            val api = CloudAgentClient("https://example.com", { "access" }, { true }, http)
            val received = mutableListOf<Long>()
            val failure = runCatching { api.events(id, 17) { received += it.cursor; throw CancellationException("页面离开") } }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(listOf(19L), received)
            assertEquals(listOf("GET /v1/agent/runs/$id/events"), requests)
        } finally { http.close() }
    }

    @Test fun `401只刷新一次且终态快照结束事件读取`() = runBlocking {
        val refreshes = mutableListOf<Boolean>()
        var calls = 0
        val http = HttpClient(MockEngine {
            calls++
            if (calls == 1) respond("{}", HttpStatusCode.Unauthorized)
            else respond("event: snapshot\ndata: ${raw(21, "COMPLETED")}\n\nevent: run\ndata: ${raw(22)}\n\n",
                headers = headersOf("Content-Type", "text/event-stream"))
        })
        try {
            val api = CloudAgentClient("https://example.com", { refreshes += it; "token" }, { true }, http)
            val received = mutableListOf<Long>()
            api.events(id, 0) { received += it.cursor }
            assertEquals(listOf(false, true), refreshes)
            assertEquals(listOf(21L), received)
            assertEquals(2, calls)
        } finally { http.close() }
    }

    @Test fun `创建响应丢失向外抛出不悄悄新建或切本地`() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; throw java.io.IOException("connection reset") })
        try {
            val api = CloudAgentClient("https://example.com", { "access" }, { true }, http)
            assertTrue(runCatching { api.create(buildJsonObject { put("id", id) }) }.exceptionOrNull() is java.io.IOException)
            assertEquals(1, calls)
        } finally { http.close() }
    }

    @Test fun `返回期间账号变化拒绝应用旧响应且错误正文不泄漏`() = runBlocking {
        var permitted = true
        val http = HttpClient(MockEngine { permitted = false; respond(raw(2)) })
        try {
            val api = CloudAgentClient("https://example.com", { "access" }, { permitted }, http)
            assertTrue(runCatching { api.get(id) }.exceptionOrNull() is IllegalStateException)
        } finally { http.close() }
        val errorHttp = HttpClient(MockEngine { respond("""{"error":{"code":"AGENT_UNAVAILABLE","message":"secret-token"}}""", HttpStatusCode.ServiceUnavailable) })
        try {
            val error = runCatching { CloudAgentClient("https://example.com", { "access" }, { true }, errorHttp).get(id) }.exceptionOrNull()
            assertTrue(error is CloudAgentHttpException)
            assertFalse(error.toString().contains("secret-token"))
            assertEquals("AGENT_UNAVAILABLE", (error as CloudAgentHttpException).code)
        } finally { errorHttp.close() }
    }
}
