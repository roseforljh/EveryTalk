package com.android.everytalk.data.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelStreamRelayTest {

    private fun envelope(target: String, build: HttpRequestBuilder.() -> Unit) =
        ModelStreamRelay.envelope(HttpRequestBuilder().also { it.url(target); build(it) })
            ?.let { Json.parseToJsonElement(it.json).jsonObject }

    @Test
    fun `按鉴权方式识别协议并原样拼接请求体`() {
        val openai = envelope("https://api.example.com/v1/chat/completions") {
            header("Authorization", "Bearer sk-1")
            header("Accept", "text/event-stream")
            setBody("""{"model":"m","stream":true}""")
        }!!
        assertEquals("openai", openai["protocol"]!!.jsonPrimitive.content)
        assertEquals("sk-1", openai["apiKey"]!!.jsonPrimitive.content)
        assertEquals("m", openai["body"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals("text/event-stream", openai["headers"]!!.jsonObject["accept"]!!.jsonPrimitive.content)

        val anthropic = envelope("https://api.anthropic.com/v1/messages") {
            header("x-api-key", "ak")
            header("anthropic-beta", "compact")
            setBody("{}")
        }!!
        assertEquals("anthropic", anthropic["protocol"]!!.jsonPrimitive.content)
        assertEquals("compact", anthropic["headers"]!!.jsonObject["anthropic-beta"]!!.jsonPrimitive.content)

        val gemini = envelope("https://generativelanguage.googleapis.com/v1beta/models/g:streamGenerateContent?alt=sse&key=gk") {
            setBody("{}")
        }!!
        assertEquals("gemini", gemini["protocol"]!!.jsonPrimitive.content)
        assertEquals("gk", gemini["apiKey"]!!.jsonPrimitive.content)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/g:streamGenerateContent?alt=sse",
            gemini["target"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `非 HTTPS 或无法识别鉴权时回退直连`() {
        assertNull(envelope("http://192.168.1.2:8080/v1/chat/completions") { header("Authorization", "Bearer k"); setBody("{}") })
        assertNull(envelope("https://api.example.com/v1/chat/completions") { setBody("{}") })
    }
}
