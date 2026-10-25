package com.android.everytalk.data.network

import android.util.Log
import android.util.Base64
import com.android.everytalk.data.DataClass.ImageGenRequest
import com.android.everytalk.data.DataClass.ImageRatio
import com.android.everytalk.ui.components.ImageGenCapabilities
import com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageGenerationAspectRatioTest {
    @Before
    fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    @After
    fun restoreAndroidLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `Qwen official aligned sizes do not fall back to square`() {
        val sizes = mapOf(
            "1:1" to "1328x1328", "16:9" to "1664x928", "9:16" to "928x1664",
            "4:3" to "1472x1140", "3:4" to "1140x1472", "3:2" to "1584x1056", "2:3" to "1056x1584",
        )
        for ((name, expectedSize) in sizes) {
            val ratio = ImageRatio.DEFAULT_RATIOS.first { it.displayName == name }
            val size = ImageGenCapabilities.resolveRequestImageSize(ModelFamily.QWEN, "Qwen/Qwen-Image", ratio, "1024x1024")
            assertEquals(name, expectedSize, size)
        }
        assertNull(ImageGenCapabilities.resolveRequestImageSize(ModelFamily.QWEN, "qwen_edit", ImageRatio.AUTO, "1024x1024"))
    }

    @Test
    fun `exact Kolors sizes and unknown model ratios and GPT auto are retained`() {
        for (size in listOf(960 to 1280, 768 to 1024)) {
            val ratio = ImageRatio("3:4", size.first, size.second)
            assertEquals("${size.first}x${size.second}", ImageGenCapabilities.resolveRequestImageSize(ModelFamily.KOLORS, "Kolors", ratio, null))
        }
        val ratio = ImageRatio("16:9", 1536, 864)
        assertEquals("1536x864", ImageGenCapabilities.resolveRequestImageSize(ModelFamily.UNKNOWN, "custom-image", ratio, "1024x1024"))
        assertEquals("auto", ImageGenCapabilities.resolveRequestImageSize(ModelFamily.GPT_IMAGE, "gpt-image-2", ImageRatio.AUTO, "1024x1024"))
        assertEquals("2048x1152", ImageGenCapabilities.resolveRequestImageSize(ModelFamily.GPT_IMAGE, "gpt-image-2", ratio, "1024x1024"))
        assertEquals(ImageGenCapabilities.QualityTier.Q4K, ImageGenCapabilities.getSeedreamQuality("4096x4096"))
        assertEquals(ImageGenCapabilities.QualityTier.Q2K, ImageGenCapabilities.getSeedreamQuality("2048x2048"))
    }

    @Test
    fun `model name takes precedence over a multi model provider`() {
        assertEquals(ModelFamily.GPT_IMAGE, ImageGenCapabilities.detectFamily("gpt-image-2", "Google", "https://api.siliconflow.cn"))
        assertEquals(ModelFamily.QWEN, ImageGenCapabilities.detectFamily("Qwen/Qwen-Image", "Google", null))
        assertEquals(ModelFamily.GEMINI, ImageGenCapabilities.detectFamily("gemini-2.5-flash-image", "OpenAI", null))
        assertEquals(ModelFamily.KOLORS, ImageGenCapabilities.detectFamily("custom-image", "SiliconFlow", null))
    }

    @Test
    fun `Modal sends dimensions for every ratio offered by the selector`() = runBlocking {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any<ByteArray>(), Base64.NO_WRAP) } returns "QUJD"
        try {
            val cases = mapOf(
                "2K 1:1" to (2048 to 2048), "2K 16:9" to (2048 to 1152),
                "2K 9:16" to (1152 to 2048), "2K 4:3" to (2048 to 1536),
                "HD 1:1" to (1024 to 1024), "HD 16:9" to (1024 to 576), "HD 9:16" to (576 to 1024),
            )
            for ((ratio, dimensions) in cases) {
                val client = HttpClient(MockEngine { request ->
                    assertEquals(dimensions.first.toString(), request.url.parameters["width"])
                    assertEquals(dimensions.second.toString(), request.url.parameters["height"])
                    respond("image-bytes", headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
                })
                try {
                    ImageGenerationDirectClient.generateImageModal(
                        client,
                        ImageGenRequest("z-image-turbo", "测试", aspectRatio = ratio, apiAddress = "", apiKey = ""),
                        listOf("https://modal.invalid"),
                    )
                } finally {
                    client.close()
                }
            }
        } finally {
            unmockkStatic(Base64::class)
        }
    }

    @Test
    fun `Seedream respects new ratios and automatic mode with and without reference images`() = runBlocking {
        val cases = listOf(
            Triple("2048x2048", "16:9", "2560x1440"),
            Triple("4096x4096", "9:16", "2880x5120"),
            Triple("2K", "3:4", "1728x2304"),
            Triple("4K", "4:3", "4608x3456"),
            Triple("2K", null, "2K"),
            Triple("4K", null, "4K"),
            Triple("2304x1728", null, "2304x1728"),
        )
        for (withReference in listOf(false, true)) {
            for ((size, ratio, expectedSize) in cases) {
                val client = HttpClient(MockEngine { request ->
                    val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals(expectedSize, payload["size"]?.jsonPrimitive?.content)
                    assertEquals(withReference, payload.containsKey("image"))
                    respond("""{"data":[{"url":"https://images.invalid/image.png"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                })
                try {
                    val request = ImageGenRequest("doubao-seedream-4-5", "测试", imageSize = size, aspectRatio = ratio, apiAddress = "https://test.invalid/images/generations", apiKey = "test-key")
                    val response = if (withReference) {
                        ImageGenerationDirectClient.generateImageSeedreamWithReference(client, request, listOf("https://images.invalid/reference.png"))
                    } else {
                        ImageGenerationDirectClient.generateImageSeedream(client, request)
                    }
                    assertEquals(1, response.images.size)
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `Gemini sends every supported ratio in generation and reference image requests`() = runBlocking {
        for (withReference in listOf(false, true)) {
            for (model in listOf("gemini-2.5-flash-image", "gemini-3-pro-image-preview")) {
                for (ratio in listOf<String?>(null) + ImageGenCapabilities.getCapabilities(ModelFamily.GEMINI).ratios.map { it.ratio }) {
                    val client = HttpClient(MockEngine { request ->
                        val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val imageConfig = payload["generationConfig"]?.jsonObject?.get("imageConfig")?.jsonObject
                        assertEquals(ratio, imageConfig?.get("aspectRatio")?.jsonPrimitive?.content)
                        assertEquals(if (model.contains("3-pro")) "4K" else null, imageConfig?.get("imageSize")?.jsonPrimitive?.content)
                        respond("""{"candidates":[]} """, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    })
                    try {
                        val request = ImageGenRequest(model, "测试", aspectRatio = ratio, geminiImageSize = "4K", apiAddress = "https://test.invalid", apiKey = "test-key")
                        if (withReference) {
                            ImageGenerationDirectClient.generateImageGeminiWithReference(client, request, "QUJD")
                        } else {
                            ImageGenerationDirectClient.generateImageGemini(client, request)
                        }
                    } finally {
                        client.close()
                    }
                }
            }
        }
    }

    @Test
    fun `GPT reference image editing keeps explicit and automatic dimensions`() = runBlocking {
        mockkStatic(Base64::class)
        every { Base64.decode("QUJD", Base64.DEFAULT) } returns byteArrayOf(65, 66, 67)
        try {
            for (size in listOf("auto", "1536x1024", "1024x1536")) {
                val client = HttpClient(MockEngine { request ->
                    assertTrue(request.url.encodedPath.endsWith("/images/edits"))
                    val body = request.body.toByteArray().toString(Charsets.UTF_8)
                    // multipart 可在字段名和正文间带 Content-Length 等头，按字段分段检查实际值。
                    val sizePart = body.split("\r\n--").first { Regex("""name="?size"?(?:;|\r?\n)""").containsMatchIn(it) }
                    assertEquals(size, sizePart.substringAfter("\r\n\r\n").substringBefore("\r\n"))
                    respond("""{"data":[{"url":"https://images.invalid/image.png"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                })
                try {
                    ImageGenerationDirectClient.generateImageOpenAIWithReference(
                        client,
                        ImageGenRequest("gpt-image-2", "测试", imageSize = size, apiAddress = "https://test.invalid", apiKey = "test-key"),
                        listOf("QUJD" to "image/png"),
                    )
                } finally {
                    client.close()
                }
            }
        } finally {
            unmockkStatic(Base64::class)
        }
    }

    @Test
    fun `selected dimensions reach SiliconFlow and OpenAI in their own protocol`() = runBlocking {
        // 覆盖默认平台的实际域名、显式平台代理，以及同名模型走 OpenAI 代理的情况。
        val targets = listOf(
            Triple("https://api.siliconflow.cn", "默认", true),
            Triple("https://api.siliconflow.com/v1/images/generations", "OpenAI", true),
            Triple("https://relay.invalid", "SiliconFlow", true),
            Triple("https://relay.invalid", "硅基流动", true),
            Triple("https://relay.invalid", "OpenAI", false),
        )
        for ((address, provider, isSiliconFlow) in targets) {
            for (size in listOf("720x1280", "1664x928", "1024x1024")) {
                val imageUrl = "https://images.invalid/$size.png"
                val engine = MockEngine { request ->
                    val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals(size, payload[if (isSiliconFlow) "image_size" else "size"]?.jsonPrimitive?.content)
                    assertEquals("1", payload[if (isSiliconFlow) "batch_size" else "n"]?.jsonPrimitive?.content)
                    assertFalse(payload.containsKey(if (isSiliconFlow) "size" else "image_size"))
                    assertFalse(payload.containsKey(if (isSiliconFlow) "n" else "batch_size"))
                    assertEquals("high", payload["quality"]?.jsonPrimitive?.content)
                    assertEquals(isSiliconFlow, payload.containsKey("num_inference_steps"))
                    assertEquals(isSiliconFlow, payload.containsKey("guidance_scale"))
                    val imagesKey = if (isSiliconFlow) "images" else "data"
                    respond(
                        content = """{"$imagesKey":[{"url":"$imageUrl"}]}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                val client = HttpClient(engine)
                try {
                    val response = ImageGenerationDirectClient.generateImageOpenAI(
                        client,
                        ImageGenRequest(
                            model = "Kwai-Kolors/Kolors",
                            prompt = "测试图片",
                            apiAddress = address,
                            apiKey = "test-key",
                            provider = provider,
                            imageSize = size,
                            batchSize = 1,
                            quality = "high",
                            numInferenceSteps = 30,
                            guidanceScale = 7.5f,
                        ),
                    )
                    assertEquals(imageUrl, response.images.single().url)
                } finally {
                    client.close()
                }
            }
        }
    }
}
