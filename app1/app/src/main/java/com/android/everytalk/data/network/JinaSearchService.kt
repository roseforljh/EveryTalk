package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.BuildConfig
import com.android.everytalk.data.DataClass.WebSearchResult
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
import java.net.URLEncoder

object JinaSearchService {
    private const val TAG = "JinaSearchService"
    private const val SEARCH_TIMEOUT_MS = 30_000L
    private const val DEFAULT_MAX_CONTENT_CHARS = 20_000

    private val searchBaseUrl: String = BuildConfig.JINA_SEARCH_BASE_URL.trimEnd('/')

    val isAvailable: Boolean
        get() = searchBaseUrl.isNotBlank()

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = SEARCH_TIMEOUT_MS
                connectTimeoutMillis = SEARCH_TIMEOUT_MS
                socketTimeoutMillis = SEARCH_TIMEOUT_MS
            }
            install(HttpRedirect)
        }
    }

    suspend fun search(
        query: String,
        maxContentChars: Int = DEFAULT_MAX_CONTENT_CHARS,
    ): Result<ExternalWebSearchResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedQuery = query.trim()
            require(trimmedQuery.isNotBlank()) { "搜索内容不能为空" }
            require(searchBaseUrl.isNotBlank()) { "未配置 JINA_SEARCH_BASE_URL" }

            val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
            val searchUrl = "$searchBaseUrl/search?q=$encodedQuery"
            Log.d(TAG, "Jina Search: $searchUrl")

            val response = client.get(searchUrl) {
                header(HttpHeaders.Accept, "application/json")
                header("X-Return-Format", "markdown")
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText().take(500)
                Log.e(TAG, "Jina Search HTTP ${response.status.value}: $errorBody")
                throw IllegalStateException("Jina Search 返回 HTTP ${response.status.value}: $errorBody")
            }

            val content = response.bodyAsText()
            if (content.isBlank()) {
                throw IllegalStateException("Jina Search 返回空内容")
            }

            val truncatedContent = if (content.length > maxContentChars) {
                content.take(maxContentChars).trimEnd()
            } else {
                content
            }

            val results = parseMarkdownResults(truncatedContent)
            ExternalWebSearchResponse(
                provider = ExternalWebSearchProvider.TAVILY,
                results = results,
            )
        }.onFailure {
            Log.e(TAG, "Jina Search 失败", it)
        }
    }

    private fun parseMarkdownResults(markdown: String): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        val sections = markdown.split(Regex("(?=^#{1,2}\\s)", RegexOption.MULTILINE))

        for ((index, section) in sections.withIndex()) {
            if (section.isBlank()) continue
            val lines = section.lines()
            val titleLine = lines.firstOrNull { it.isNotBlank() } ?: continue
            val title = titleLine.replace(Regex("^#+\\s*"), "").trim()
            if (title.isBlank()) continue

            val urlMatch = Regex("https?://[^\\s)>]+").find(section)
            val href = urlMatch?.value ?: ""

            val snippetLines = lines.drop(1)
                .filter { it.isNotBlank() && !it.startsWith("http") && !it.startsWith("[") }
                .take(3)
            val snippet = snippetLines.joinToString(" ").take(500)

            results.add(
                WebSearchResult(
                    index = index,
                    title = title,
                    href = href,
                    snippet = snippet,
                )
            )
            if (results.size >= 5) break
        }

        if (results.isEmpty()) {
            results.add(
                WebSearchResult(
                    index = 0,
                    title = "搜索结果",
                    href = "",
                    snippet = markdown.take(1000),
                )
            )
        }

        return results
    }
}
