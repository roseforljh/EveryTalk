package com.android.everytalk.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** 网页只自动附带少量图片，避免长文章导致网络请求和视觉 token 无上限增长。 */
internal object WebFetchImages {
    private const val MAX_IMAGES = 3
    private const val MAX_DOWNLOAD_BYTES = 5L * 1024 * 1024
    private const val MAX_EDGE = 1536
    private const val MAX_ENCODED_BYTES = 2 * 1024 * 1024

    /** 常见图片直链直接下载；无扩展名的地址由 webfetch 的 image 模式显式指定。 */
    fun isImageUrl(raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        val formats = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        return url.pathSegments.lastOrNull()?.substringAfterLast('.', "")?.lowercase() in formats ||
            url.queryParameter("format")?.lowercase() in formats
    }

    /** 图片直链复用同一下载和编码限制，失败不得回退到网页文本解析。 */
    suspend fun fetch(url: String): JsonObject {
        val response = attach(buildJsonObject { put("requestedUrl", url) }, listOf(url))
        val loaded = (response["_images"] as JsonArray).isNotEmpty()
        return JsonObject(response + buildJsonObject {
            put("ok", loaded)
            if (loaded) put("content", "图片已作为视觉输入附带，请根据实际图片回答。")
            else put("error", "图片未读取成功，不能推测图像内容。")
        })
    }

    /** 从 Markdown 图片和 HTML img 中提取地址；普通超链接不作为图片下载。 */
    fun extractUrls(content: String, pageUrl: String): List<String> {
        val base = pageUrl.toHttpUrlOrNull() ?: return emptyList()
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        fun ASTNode.nodes(): Sequence<ASTNode> = sequence {
            yield(this@nodes)
            children.forEach { yieldAll(it.nodes()) }
        }
        fun ASTNode.text(): String = content.substring(startOffset, endOffset)
        val nodes = root.nodes().toList()
        val definitions = nodes.filter { it.type == MarkdownElementTypes.LINK_DEFINITION }
            .mapNotNull { node ->
                val label = node.nodes().firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }?.text()
                val destination = node.nodes().firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }?.text()
                if (label == null || destination == null) null else label.lowercase() to destination
            }.toMap()
        val urls = nodes.filter { it.type == MarkdownElementTypes.IMAGE }.mapNotNull { node ->
            node.nodes().firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }?.text()
                ?: node.nodes().lastOrNull { it.type == MarkdownElementTypes.LINK_LABEL }?.text()
                    ?.lowercase()?.let(definitions::get)
        }.toMutableList()
        // Html 的解析器只收集 src，不加载资源；不把整段 HTML 当正则表达式解析。
        if (content.contains("<img", ignoreCase = true)) {
            Html.fromHtml(content, Html.FROM_HTML_MODE_LEGACY, { source -> urls.add(source); null }, null)
        }
        return urls.mapNotNull { raw ->
            base.resolve(raw.trim().removeSurrounding("<", ">"))
                ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
                ?.newBuilder()?.fragment(null)?.build()?.toString()
        }.distinct().take(MAX_IMAGES)
    }

    /**
     * 正文已成功时，图片失败只增加逐图状态，不改变正文的成功状态。
     * 下载器校验公网地址和每次重定向；每张最多等 6 秒，用户取消继续向上传播。
     * 成功图片顺序与 imageResults 中 loaded=true 的顺序一致。
     */
    suspend fun attach(
        result: JsonObject,
        urls: List<String>,
        download: suspend (String) -> ByteArray = { url ->
            SafeHttpDownloader.download(url, MAX_DOWNLOAD_BYTES, 6_000, accept = "image/*").bytes
        },
    ): JsonObject = withContext(Dispatchers.IO) {
        val images = mutableListOf<JsonObject>()
        val statuses = mutableListOf<JsonObject>()
        urls.take(MAX_IMAGES).forEach { url ->
            var loaded = false
            withTimeoutOrNull(6_000) {
                try {
                    val image = encode(download(url))
                    images.add(image)
                    loaded = true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // 防盗链、超限或非图片响应均只跳过当前图片，继续保留网页正文。
                }
            }
            statuses.add(buildJsonObject {
                put("url", url)
                put("loaded", loaded)
                if (!loaded) put("error", "图片未读取：下载失败、超时、格式不支持或超过大小限制。不要推测图片内容。")
            })
        }
        JsonObject(result + mapOf(
            "_images" to JsonArray(images),
            "imageResults" to JsonArray(statuses),
        ))
    }

    /** 先读尺寸并采样再解码，避免小压缩文件解码成超大位图；动图仅取第一帧。 */
    internal fun encode(bytes: ByteArray): JsonObject {
        require(bytes.size <= MAX_DOWNLOAD_BYTES) { "图片文件过大" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth >= 64 && bounds.outHeight >= 64) { "非图片或尺寸过小" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > MAX_EDGE * 2) {
                inSampleSize *= 2
            }
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        try {
            val ratio = minOf(1.0, MAX_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height))
            val scaled = Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            try {
                val output = ByteArrayOutputStream()
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, output))
                require(output.size() <= MAX_ENCODED_BYTES) { "图片编码结果过大" }
                return buildJsonObject {
                    put("base64", Base64.getEncoder().encodeToString(output.toByteArray()))
                    put("mimeType", "image/jpeg")
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
