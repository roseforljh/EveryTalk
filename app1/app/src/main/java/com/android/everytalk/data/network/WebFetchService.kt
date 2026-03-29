package com.android.everytalk.data.network

import android.util.Log
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
import org.jsoup.Jsoup

object WebFetchService {
    private const val TAG = "WebFetchService"
    private const val DEFAULT_TIMEOUT_MS = 15_000L
    private const val MAX_RESPONSE_BYTES = 1_000_000
    private const val DEFAULT_MAX_CONTENT_CHARS = 12_000

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = DEFAULT_TIMEOUT_MS
                connectTimeoutMillis = DEFAULT_TIMEOUT_MS
                socketTimeoutMillis = DEFAULT_TIMEOUT_MS
            }
            install(HttpRedirect)
        }
    }

    suspend fun fetch(
        url: String,
        maxContentChars: Int = DEFAULT_MAX_CONTENT_CHARS,
    ): WebFetchResult = withContext(Dispatchers.IO) {
        val normalizedUrl = url.trim()
        val validatedUrl = validateUrl(normalizedUrl)
            ?: return@withContext WebFetchResult(
                success = false,
                requestedUrl = normalizedUrl,
                error = "URL 无效，仅支持 http/https 网页地址",
            )

        try {
            val response = client.get(validatedUrl) {
                header(HttpHeaders.UserAgent, ANDROID_WEB_USER_AGENT)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
                header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
            }

            val finalUrl = response.call.request.url.toString()

            if (!response.status.isSuccess()) {
                return@withContext WebFetchResult(
                    success = false,
                    requestedUrl = normalizedUrl,
                    finalUrl = finalUrl,
                    statusCode = response.status.value,
                    error = "网页抓取失败: HTTP ${response.status.value}",
                )
            }

            val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val rawBytes = response.bodyAsText().toByteArray(Charsets.UTF_8)
            val responseTruncated = rawBytes.size > MAX_RESPONSE_BYTES
            val safeBytes = if (responseTruncated) {
                rawBytes.copyOf(MAX_RESPONSE_BYTES)
            } else {
                rawBytes
            }
            val html = safeBytes.toString(Charsets.UTF_8)
            val extraction = extractReadableText(html, finalUrl, maxContentChars)

            if (extraction.content.isBlank()) {
                return@withContext WebFetchResult(
                    success = false,
                    requestedUrl = normalizedUrl,
                    finalUrl = finalUrl,
                    title = extraction.title,
                    truncated = responseTruncated || extraction.truncated,
                    truncationReason = mergeTruncationReasons(
                        if (responseTruncated || (contentLength != null && contentLength > MAX_RESPONSE_BYTES)) {
                            "response_too_large"
                        } else {
                            null
                        },
                        extraction.truncationReason,
                    ),
                    error = "网页抓取成功，但未提取到可用正文",
                )
            }

            WebFetchResult(
                success = true,
                requestedUrl = normalizedUrl,
                finalUrl = finalUrl,
                title = extraction.title,
                content = extraction.content,
                truncated = responseTruncated || extraction.truncated || (contentLength != null && contentLength > MAX_RESPONSE_BYTES),
                truncationReason = mergeTruncationReasons(
                    if (responseTruncated || (contentLength != null && contentLength > MAX_RESPONSE_BYTES)) {
                        "response_too_large"
                    } else {
                        null
                    },
                    extraction.truncationReason,
                ),
                statusCode = response.status.value,
            )
        } catch (e: Exception) {
            Log.e(TAG, "网页抓取失败: $normalizedUrl", e)
            WebFetchResult(
                success = false,
                requestedUrl = normalizedUrl,
                error = "网页抓取失败: ${e.message ?: "未知错误"}",
            )
        }
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

    private fun extractReadableText(
        html: String,
        baseUrl: String,
        maxContentChars: Int,
    ): ExtractedContent {
        val document = Jsoup.parse(html, baseUrl)
        document.select("script, style, noscript, svg, canvas, iframe, form, button, input, footer, nav, aside").remove()

        val title = sequenceOf(
            document.title().takeIf { it.isNotBlank() },
            document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() },
            document.selectFirst("meta[name=twitter:title]")?.attr("content")?.takeIf { it.isNotBlank() },
        ).filterNotNull().firstOrNull()

        val candidate = (document.selectFirst("article")
            ?: document.selectFirst("main")
            ?: document.body())?.clone()
        candidate?.select("header, footer, nav, aside, form, button, .nav, .footer, .sidebar, .ads, .advertisement, .cookie, .menu, .comment, .comments, .share")
            ?.remove()

        val normalizedText = normalizePlainText(candidate?.text().orEmpty())
        if (normalizedText.isBlank()) {
            return ExtractedContent(title = title, content = "")
        }

        return if (normalizedText.length > maxContentChars) {
            ExtractedContent(
                title = title,
                content = normalizedText.take(maxContentChars).trimEnd(),
                truncated = true,
                truncationReason = "content_truncated",
            )
        } else {
            ExtractedContent(title = title, content = normalizedText)
        }
    }

    private fun normalizePlainText(text: String): String {
        return text
            .replace('\u0000', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun mergeTruncationReasons(
        first: String?,
        second: String?,
    ): String? {
        val reasons = listOfNotNull(first, second).distinct()
        return reasons.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private data class ExtractedContent(
        val title: String?,
        val content: String,
        val truncated: Boolean = false,
        val truncationReason: String? = null,
    )

    private const val ANDROID_WEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}
