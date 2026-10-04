package com.android.everytalk.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebFetchImagesTest {
    @Test
    fun `图片直链和显式图片模式不经过网页解析包括失败结果`() = runTest {
        val failed = buildJsonObject { put("ok", false); put("error", "图片下载失败") }
        for ((url, format) in listOf(
            "https://pbs.twimg.com/media/HTZ6atXbgAAfX6W.png" to "auto",
            "https://pbs.twimg.com/media/HTZ6atXbgAAfX6W?format=png&name=large" to "auto",
            "https://example.com/image/123" to "image",
        )) {
            val result = WebFetchToolExecutor.execute(
                buildJsonObject { put("url", url); put("format", format) },
                fetchPage = { _, _ -> error("图片不得交给网页解析") },
                fetchImage = { actual -> assertEquals(url, actual); failed },
            )
            assertSame(failed, result)
        }
    }

    @Test
    fun `普通网页继续走正文解析`() = runTest {
        val url = "https://example.com/article?next=photo.png"
        val result = WebFetchToolExecutor.execute(buildJsonObject { put("url", url) },
            fetchPage = { actual, _ -> WebFetchResult(true, actual, content = "正文") },
            fetchImage = { error("普通网页不应走图片下载") })
        assertEquals("正文", result["content"]!!.jsonPrimitive.content)
        assertFalse(WebFetchImages.isImageUrl("file:///photo.png"))
    }

    @Test
    fun `提取相对图片和引用图片并去重过滤普通链接和非HTTP地址`() {
        val content = """
            ![正文](/photo.jpg)
            ![重复](https://example.com/photo.jpg)
            ![图表][chart]

            [chart]: /chart.png

            [普通链接](https://example.com/link)
            ![无效](data:image/png;base64,AAAA)
            <img src="//cdn.example.com/picture.png">
        """.trimIndent()
        assertEquals(listOf("https://example.com/photo.jpg", "https://example.com/chart.png",
            "https://cdn.example.com/picture.png"), WebFetchImages.extractUrls(content, "https://example.com/article"))
    }

    @Test
    fun `正文截断不丢失后面的图片且最多提取三张`() {
        val result = WebFetchService.parseDefuddleWebFetchResponse("https://example.com/article",
            buildJsonObject { put("result", buildJsonObject {
                put("contentMarkdown", "正文\n" + (1..5).joinToString("\n") { "![图](/$it.png)" })
            }) }.toString(), 2, 200)
        assertEquals("正文", result.content)
        assertEquals(3, result.imageUrls.size)
    }

    @Test
    fun `成功图片可解码并缩小失败图片不影响正文`() = runTest {
        val bitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        val result = WebFetchImages.attach(buildJsonObject { put("ok", true); put("content", "正文") },
            listOf("good", "bad")) { url ->
            if (url == "bad") error("下载失败") else output.toByteArray()
        }
        assertEquals("true", result["ok"]?.jsonPrimitive?.content)
        assertEquals("正文", result["content"]?.jsonPrimitive?.content)
        val images = result["_images"] as JsonArray
        assertEquals(1, images.size)
        val bytes = Base64.getDecoder().decode(images.single().jsonObject["base64"]!!.jsonPrimitive.content)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(1536, decoded.width)
        assertEquals(768, decoded.height)
        decoded.recycle()
        val statuses = result["imageResults"] as JsonArray
        assertEquals("true", statuses[0].jsonObject["loaded"]!!.jsonPrimitive.content)
        assertEquals("false", statuses[1].jsonObject["loaded"]!!.jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝把HTML响应当成图片`() {
        WebFetchImages.encode("<html>access denied</html>".toByteArray())
    }

    @Test(expected = CancellationException::class)
    fun `用户取消不能被吞掉`() = runTest {
        WebFetchImages.attach(buildJsonObject { put("ok", true) }, listOf("image")) {
            throw CancellationException("用户停止")
        }
    }
}
