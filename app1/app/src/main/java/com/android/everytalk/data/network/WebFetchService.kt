package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WebFetchService {
    private const val TAG = "WebFetchService"
    private const val JINA_TIMEOUT_MS = 30_000L
    private const val DEFAULT_MAX_CONTENT_CHARS = 24_000

    private val readerBaseUrl: String = BuildConfig.JINA_READER_BASE_URL.trimEnd('/')

    private val jinaClient by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = JINA_TIMEOUT_MS
                connectTimeoutMillis = JINA_TIMEOUT_MS
                socketTimeoutMillis = JINA_TIMEOUT_MS
            }
            install(HttpRedirect)
        }
    }

    suspend fun fetch(
        url: String,
        maxContentChars: Int = DEFAULT_MAX_CONTENT_CHARS,
    ): WebFetchResult = withContext(Dispatchers.IO) {
        if (readerBaseUrl.isBlank()) {
            return@withContext WebFetchResult(
                success = false,
                requestedUrl = url,
                error = "未配置 JINA_READER_BASE_URL",
            )
        }

        val normalizedUrl = url.trim()
        val validatedUrl = validateUrl(normalizedUrl)
            ?: return@withContext WebFetchResult(
                success = false,
                requestedUrl = normalizedUrl,
                error = "URL 无效，仅支持 http/https 网页地址",
            )

        fetchViaReader(validatedUrl, maxContentChars)
    }

    private suspend fun fetchViaReader(
        url: String,
        maxContentChars: Int,
    ): WebFetchResult {
        return try {
            val fetchUrl = "$readerBaseUrl/$url"
            Log.d(TAG, "通过 Reader API 抓取: $fetchUrl")

            val response = jinaClient.get(fetchUrl) {
                header(HttpHeaders.Accept, "text/markdown")
                header("X-Return-Format", "markdown")
                header("X-No-Cache", "true")
            }

            if (!response.status.isSuccess()) {
                Log.w(TAG, "Reader API 返回非成功状态: ${response.status.value}")
                return WebFetchResult(
                    success = false,
                    requestedUrl = url,
                    statusCode = response.status.value,
                    error = "Reader API 返回 HTTP ${response.status.value}",
                )
            }

            val content = response.bodyAsText()
            if (content.isBlank()) {
                return WebFetchResult(
                    success = false,
                    requestedUrl = url,
                    error = "Reader API 返回空内容",
                )
            }

            val truncated = content.length > maxContentChars
            val finalContent = if (truncated) content.take(maxContentChars).trimEnd() else content

            WebFetchResult(
                success = true,
                requestedUrl = url,
                finalUrl = url,
                title = extractTitleFromMarkdown(finalContent),
                content = finalContent,
                truncated = truncated,
                truncationReason = if (truncated) "content_truncated" else null,
                statusCode = response.status.value,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Reader API 请求异常", e)
            WebFetchResult(
                success = false,
                requestedUrl = url,
                error = "Reader API 请求失败: ${e.message ?: "未知错误"}",
            )
        }
    }

    private fun extractTitleFromMarkdown(markdown: String): String? {
        val firstLine = markdown.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        if (firstLine.startsWith("# ")) {
            return firstLine.removePrefix("# ").trim().takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun validateUrl(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val uri = java.net.URI(raw)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) raw else null
        } catch (_: Exception) {
            null
        }
    }
}
