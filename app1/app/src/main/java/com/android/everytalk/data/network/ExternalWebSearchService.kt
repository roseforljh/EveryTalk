package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.WebSearchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.android.everytalk.util.text.TextSanitizer

data class ExternalWebSearchResponse(
    val provider: ExternalWebSearchProvider,
    val results: List<WebSearchResult>,
)

object ExternalWebSearchService {
    private const val TAG = "ExternalWebSearchService"
    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val DEFAULT_RESULT_LIMIT = 5
    private const val MAX_SEARCH_RESPONSE_BYTES = 2L * 1024L * 1024L

    private val json = Json { ignoreUnknownKeys = true }

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

    suspend fun search(
        provider: ExternalWebSearchProvider,
        apiKey: String,
        query: String,
    ): Result<ExternalWebSearchResponse> = withContext(Dispatchers.IO) {
        try {
            val trimmedQuery = query.trim()
            require(trimmedQuery.isNotBlank()) { "搜索内容不能为空" }
            require(apiKey.isNotBlank()) { "${provider.displayName} API Key 未配置" }

            val searchResponse = client.preparePost(provider.baseUrl) {
                contentType(ContentType.Application.Json)
                when (provider) {
                    ExternalWebSearchProvider.TAVILY -> {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                        setBody(
                            buildJsonObject {
                                put("query", JsonPrimitive(trimmedQuery))
                                put("search_depth", JsonPrimitive("basic"))
                                put("max_results", JsonPrimitive(DEFAULT_RESULT_LIMIT))
                                put("include_answer", JsonPrimitive(false))
                                put("include_images", JsonPrimitive(false))
                            }.toString()
                        )
                    }

                    ExternalWebSearchProvider.EXA -> {
                        header("x-api-key", apiKey)
                        setBody(
                            buildJsonObject {
                                put("query", JsonPrimitive(trimmedQuery))
                                put("type", JsonPrimitive("auto"))
                                put("numResults", JsonPrimitive(DEFAULT_RESULT_LIMIT))
                                put(
                                    "contents",
                                    buildJsonObject {
                                        put(
                                            "highlights",
                                            buildJsonObject {
                                                put("maxCharacters", JsonPrimitive(1200))
                                            }
                                        )
                                    }
                                )
                            }.toString()
                        )
                    }

                    ExternalWebSearchProvider.BOCHA -> {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                        setBody(
                            buildJsonObject {
                                put("query", JsonPrimitive(trimmedQuery))
                                put("freshness", JsonPrimitive("oneYear"))
                                put("summary", JsonPrimitive(true))
                                put("count", JsonPrimitive(DEFAULT_RESULT_LIMIT))
                            }.toString()
                        )
                    }

                    ExternalWebSearchProvider.SERPAPI -> {
                        setBody(
                            buildJsonObject {
                                put("q", JsonPrimitive(trimmedQuery))
                                put("api_key", JsonPrimitive(apiKey))
                                put("engine", JsonPrimitive("google"))
                                put("num", JsonPrimitive(DEFAULT_RESULT_LIMIT))
                                put("hl", JsonPrimitive("zh-cn"))
                                put("google_domain", JsonPrimitive("google.com"))
                            }.toString()
                        )
                    }
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("${provider.displayName} 搜索失败: HTTP ${response.status.value}")
                }

                val body = response.readTextAtMost(MAX_SEARCH_RESPONSE_BYTES)
                val results = parseResults(provider, body)
                if (results.isEmpty()) {
                    Log.w(TAG, "provider=${provider.providerId} empty results bodyChars=${body.length}")
                    throw IllegalStateException("${provider.displayName} 未返回可用搜索结果")
                }
                ExternalWebSearchResponse(provider = provider, results = results)
            }

            Result.success(searchResponse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "搜索失败 provider=${provider.providerId}", e)
            Result.failure(e)
        }
    }

    private fun parseResults(
        provider: ExternalWebSearchProvider,
        body: String,
    ): List<WebSearchResult> {
        val root = json.parseToJsonElement(body).jsonObject
        return when (provider) {
            ExternalWebSearchProvider.TAVILY -> root["results"]?.jsonArray.orEmpty().mapIndexedNotNull { index, element ->
                val item = element.jsonObject
                val href = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (href.isBlank()) return@mapIndexedNotNull null
                WebSearchResult(
                    index = index,
                    title = cleanSearchText(item["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { href }),
                    href = href,
                    snippet = cleanSearchText(item["content"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                )
            }

            ExternalWebSearchProvider.EXA -> root["results"]?.jsonArray.orEmpty().mapIndexedNotNull { index, element ->
                val item = element.asJsonObjectOrNull() ?: return@mapIndexedNotNull null
                val href = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (href.isBlank()) return@mapIndexedNotNull null
                WebSearchResult(
                    index = index,
                    title = cleanSearchText(item["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { href }),
                    href = href,
                    snippet = cleanSearchText(item["text"]?.jsonPrimitive?.contentOrNull
                        ?: extractExaHighlightText(item["highlights"])
                        ?: item["summary"]?.jsonPrimitive?.contentOrNull
                        ?: ""),
                )
            }

            ExternalWebSearchProvider.BOCHA -> root["data"]?.jsonObject?.get("webPages")?.jsonObject
                ?.get("value")?.jsonArray.orEmpty().mapIndexedNotNull { index, element ->
                    val item = element.jsonObject
                    val href = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (href.isBlank()) return@mapIndexedNotNull null
                WebSearchResult(
                    index = index,
                    title = cleanSearchText(item["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { href }),
                    href = href,
                    snippet = cleanSearchText(item["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                )
            }

            ExternalWebSearchProvider.SERPAPI -> root["organic_results"]?.jsonArray.orEmpty().mapIndexedNotNull { index, element ->
                val item = element.jsonObject
                val href = item["link"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (href.isBlank()) return@mapIndexedNotNull null
                WebSearchResult(
                    index = index,
                    title = cleanSearchText(item["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { href }),
                    href = href,
                    snippet = cleanSearchText(item["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                )
            }
        }
    }

    private fun cleanSearchText(text: String): String {
        return TextSanitizer.removeUnicodeReplacementCharacters(text)
    }

    private fun JsonElement.asJsonObjectOrNull() = runCatching { jsonObject }.getOrNull()

    private fun extractExaHighlightText(highlightsElement: JsonElement?): String? {
        val highlights = runCatching { highlightsElement?.jsonArray }.getOrNull().orEmpty()
        if (highlights.isEmpty()) return null

        return highlights.firstNotNullOfOrNull { highlight ->
            highlight.jsonPrimitive.contentOrNull
                ?: highlight.asJsonObjectOrNull()
                    ?.get("text")?.jsonPrimitive?.contentOrNull
        }
    }
}
