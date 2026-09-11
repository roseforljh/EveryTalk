package com.android.everytalk.data.computer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** 只验证临时网关协议边界，不代表真实 Cloudflare 网关已经联调。 */
class TemporaryWorkerHttpGatewayTest {
    @Test
    fun `创建请求保留二进制内容并校验返回地址`() = runBlocking {
        val binary = byteArrayOf(0, 1, 2, 255.toByte())
        var requestBody = ""
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            addHandler { request ->
                requestBody = (request.body as TextContent).text
                respond(
                    """{"ok":true,"result":{"temporaryDeploymentId":"tmp-1","workerUrl":"https://worker.example","claimUrl":"https://claim.example","expiresAt":4102444800000}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }))
        val gateway = TemporaryWorkerHttpGateway(client, "https://gateway.example")
        val result = gateway.create("workspace-1", WorkerPackage(
            listOf(WorkerPackageFile("worker.js", binary, "hash")), "request-hash", binary.size.toLong(),
        ))
        assertEquals("tmp-1", result.temporaryDeploymentId)
        assertTrue(requestBody.contains(Base64.getEncoder().encodeToString(binary)))
        client.close()
    }

    @Test
    fun `非 HTTPS 网关和非 HTTPS Claim 地址被拒绝`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            TemporaryWorkerHttpGateway(HttpClient(MockEngine(MockEngineConfig().apply {
                addHandler { respond("") }
            })), "http://gateway.example")
        }
    }
}
