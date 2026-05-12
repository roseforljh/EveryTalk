package com.android.everytalk.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class ExtractedImage(
    val url: String,
    val base64Data: String,
    val mimeType: String,
)

object WebFetchImageExtractor {
    private const val TAG = "WebFetchImageExtractor"
    private const val MAX_TOTAL_BASE64_BYTES = 10 * 1024 * 1024 // 10MB
    private const val IMAGE_DOWNLOAD_TIMEOUT_MS = 10_000L
    private const val MAX_DIMENSION = 512
    private const val JPEG_QUALITY = 75

    private val MARKDOWN_IMAGE_REGEX = Regex("""!\[.*?]\((https?://[^)]+)\)""")

    private val imageClient by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = IMAGE_DOWNLOAD_TIMEOUT_MS
                connectTimeoutMillis = IMAGE_DOWNLOAD_TIMEOUT_MS
                socketTimeoutMillis = IMAGE_DOWNLOAD_TIMEOUT_MS
            }
        }
    }

    fun extractImageUrls(markdown: String): List<String> {
        return MARKDOWN_IMAGE_REGEX.findAll(markdown)
            .map { it.groupValues[1] }
            .filter { url -> isLikelyContentImage(url) }
            .distinct()
            .toList()
    }

    suspend fun extractAndDownloadImages(markdown: String): List<ExtractedImage> =
        withContext(Dispatchers.IO) {
            val urls = extractImageUrls(markdown)
            if (urls.isEmpty()) return@withContext emptyList()

            Log.d(TAG, "提取到 ${urls.size} 张图片URL，开始下载")

            val results = mutableListOf<ExtractedImage>()
            var totalBytes = 0L

            for (url in urls) {
                val image = downloadAndCompress(url) ?: continue
                val imageBytes = image.base64Data.length
                if (totalBytes + imageBytes > MAX_TOTAL_BASE64_BYTES) {
                    Log.d(TAG, "已达到总大小上限 ${MAX_TOTAL_BASE64_BYTES / 1024 / 1024}MB，停止下载")
                    break
                }
                totalBytes += imageBytes
                results.add(image)
            }

            Log.d(TAG, "共下载 ${results.size}/${urls.size} 张图片，总大小 ${totalBytes / 1024}KB")
            results
        }

    private suspend fun downloadAndCompress(url: String): ExtractedImage? {
        return try {
            val response = imageClient.get(url)
            if (!response.status.isSuccess()) {
                Log.d(TAG, "图片下载失败: $url, status=${response.status.value}")
                return null
            }

            val inputStream = response.bodyAsChannel().toInputStream()
            val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return null

            val scaled = scaleBitmap(originalBitmap)
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)

            if (scaled !== originalBitmap) scaled.recycle()
            originalBitmap.recycle()

            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            Log.d(TAG, "图片处理完成: $url, base64 size=${base64.length}")

            ExtractedImage(
                url = url,
                base64Data = base64,
                mimeType = "image/jpeg",
            )
        } catch (e: Exception) {
            Log.d(TAG, "图片处理失败: $url, error=${e.message}")
            null
        }
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) return bitmap

        val scale = MAX_DIMENSION.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun isLikelyContentImage(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("avatar") || lower.contains("icon") || lower.contains("logo")
            || lower.contains("favicon") || lower.contains("emoji")
            || lower.contains("badge") || lower.contains("button")
        ) return false
        if (lower.endsWith(".svg") || lower.endsWith(".gif")) return false
        return true
    }
}
