package com.android.everytalk.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI

class OwnedServerRequestInterceptorTest {
    @Test
    fun `LocalOnly 拒绝自有服务器商业请求`() = runBlocking {
        val client = testClient(isLocalOnly = true)
        assertThrows(LocalOnlyServerRequestException::class.java) {
            runBlocking { client.get("https://api.everytalk.test/sync") }
        }
        client.close()
    }

    @Test
    fun `LocalOnly 允许第三方模型请求`() = runBlocking {
        val client = testClient(isLocalOnly = true)
        val response = client.get("https://api.openai.com/v1/chat/completions")
        assertEquals("ok", response.bodyAsText())
        client.close()
    }

    @Test
    fun `用户主动登录允许认证请求`() = runBlocking {
        val client = HttpClient(MockEngine {
            respond("ok", HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
        }) {
            install(
                ownedServerRequestGuard(
                    isLocalOnly = { true },
                    isOwnedServer = { it.host == "api.everytalk.test" },
                    purpose = { OwnedServerRequestPurpose.AUTH },
                ),
            )
        }
        assertEquals("ok", client.get("https://api.everytalk.test/auth").bodyAsText())
        client.close()
    }

    private fun testClient(isLocalOnly: Boolean): HttpClient = HttpClient(MockEngine {
        respond("ok", HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
    }) {
        install(
            ownedServerRequestGuard(
                isLocalOnly = { isLocalOnly },
                isOwnedServer = { uri: URI -> uri.host == "api.everytalk.test" },
                purpose = { OwnedServerRequestPurpose.SYNC },
            ),
        )
    }
}
