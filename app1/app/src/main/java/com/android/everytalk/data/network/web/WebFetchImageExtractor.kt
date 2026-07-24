package com.android.everytalk.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.core.graphics.scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.android.everytalk.util.storage.CappedByteArrayOutputStream

data class ExtractedImage(
    val url: String,
    val base64Data: String,
    val mimeType: String,
)

object WebFetchImageExtractor {
    private const val TAG = "WebFetchImageExtractor"
    private const val MAX_TOTAL_BASE64_BYTES = 10 * 1024 * 1024 // 10MB
    private const val MAX_COMPRESSED_IMAGE_BYTES = MAX_TOTAL_BASE64_BYTES * 3L / 4L
    internal const val MAX_IMAGE_URLS = 8
    internal const val MAX_IMAGE_DOWNLOAD_BYTES = 8L * 1024L * 1024L
    private const val MAX_SOURCE_PIXELS = 40_000_000L
    private const val IMAGE_DOWNLOAD_TIMEOUT_MS = 10_000L
    private const val MAX_DIMENSION = 512
    private const val JPEG_QUALITY = 75

    private val MARKDOWN_IMAGE_REGEX = Regex("""!\[.*?]\((https?://[^)]+)\)""")

    fun extractImageUrls(markdown: String): List<String> {
        return MARKDOWN_IMAGE_REGEX.findAll(markdown)
            .map { it.groupValues[1] }
            .filter { url -> isLikelyContentImage(url) }
            .distinct()
            .take(MAX_IMAGE_URLS)
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
        var originalBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        return try {
            val bytes = SafeHttpDownloader.download(
                url = url,
                maxBytes = MAX_IMAGE_DOWNLOAD_BYTES,
                timeoutMillis = IMAGE_DOWNLOAD_TIMEOUT_MS.toInt(),
                accept = "image/*",
            ).bytes

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_SOURCE_PIXELS
            ) return null

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
            scaledBitmap = scaleBitmap(originalBitmap)
            val outputStream = CappedByteArrayOutputStream(MAX_COMPRESSED_IMAGE_BYTES)
            if (!scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)) return null

            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            Log.d(TAG, "图片处理完成: $url, base64 size=${base64.length}")

            ExtractedImage(
                url = url,
                base64Data = base64,
                mimeType = "image/jpeg",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            Log.d(TAG, "图片处理失败: $url, 内存不足")
            null
        } catch (e: Exception) {
            Log.d(TAG, "图片处理失败: $url, error=${e.message}")
            null
        } finally {
            if (scaledBitmap !== originalBitmap) scaledBitmap?.recycle()
            originalBitmap?.recycle()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > MAX_DIMENSION * 2 || height / sampleSize > MAX_DIMENSION * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) return bitmap

        val scale = MAX_DIMENSION.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return bitmap.scale(newWidth, newHeight)
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
