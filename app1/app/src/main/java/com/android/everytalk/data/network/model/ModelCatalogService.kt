package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.coroutines.CancellationException

private const val MAX_MODEL_PAGE_RESPONSE_BYTES = 4L * 1024L * 1024L

internal class ModelCatalogService(
    private val client: HttpClient,
    private val endpointCache: ModelCapabilityCache,
    private val modelsDevCatalog: ModelsDevCatalog,
) {
    suspend fun getCatalog(
        apiUrl: String,
        apiKey: String,
        channel: String?,
    ): List<ModelCapabilityCandidate> {
        val endpoint = resolveModelCatalogEndpoint(apiUrl, channel)
        val cleanedApiKey = apiKey.filterNot(Char::isWhitespace)
        return try {
            val catalog = fetchAllPages(endpoint, cleanedApiKey)
            endpointCache.put(catalog)
            catalog
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val cached = endpointCache.get(endpoint.protocol, endpoint.normalizedBase)
            if (cached.isNotEmpty()) {
                cached
            } else {
                throw IOException("获取模型列表失败: ${error.message}", error)
            }
        }
    }

    suspend fun getCapabilities(
        apiUrl: String,
        apiKey: String,
        channel: String?,
        modelId: String,
        providerHint: String,
    ): List<ModelCapabilityCandidate> {
        val endpoint = resolveModelCatalogEndpoint(apiUrl, channel)
        val cleanedApiKey = apiKey.filterNot(Char::isWhitespace)
        val normalizedModelId = modelId.removePrefix("models/").trim()
        val candidates = mutableListOf<ModelCapabilityCandidate>()

        try {
            val detailBody = fetchJson(
                url = buildModelDetailUrl(endpoint, normalizedModelId),
                endpoint = endpoint,
                apiKey = cleanedApiKey,
            )
            parseModelCatalog(
                responseBody = detailBody,
                protocol = endpoint.protocol,
                apiAddress = endpoint.normalizedBase,
            ).firstOrNull { it.modelId.equals(normalizedModelId, ignoreCase = true) }
                ?.let { detail ->
                    candidates += detail
                    endpointCache.put(listOf(detail))
                }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }

        try {
            getCatalog(apiUrl, cleanedApiKey, channel)
                .firstOrNull { it.modelId.equals(normalizedModelId, ignoreCase = true) }
                ?.let(candidates::add)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }

        val community = modelsDevCatalog.findCapabilities(
            modelId = normalizedModelId,
            providerHint = providerHint,
            apiAddress = endpoint.normalizedBase,
            protocol = endpoint.protocol,
        ) {
            val response = client.get(MODELS_DEV_URL) {
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "EveryTalk/1.0 (Android)")
            }
            if (!response.status.isSuccess()) {
                throw IOException("models.dev 返回 HTTP ${response.status.value}")
            }
            response.readTextAtMost(MAX_MODELS_DEV_RESPONSE_BYTES)
        }
        candidates += community
        return candidates
    }

    private suspend fun fetchAllPages(
        endpoint: ModelCatalogEndpoint,
        apiKey: String,
    ): List<ModelCapabilityCandidate> {
        val catalog = linkedMapOf<String, ModelCapabilityCandidate>()
        val visitedUrls = mutableSetOf<String>()
        var pageUrl: String? = endpoint.listUrl
        for (pageIndex in 0 until MAX_MODEL_CATALOG_PAGES) {
            val currentUrl = pageUrl?.takeIf(visitedUrls::add) ?: break
            val body = try {
                fetchJson(currentUrl, endpoint, apiKey)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (catalog.isEmpty()) throw error
                break
            }
            val page = parseModelCatalog(
                responseBody = body,
                protocol = endpoint.protocol,
                apiAddress = endpoint.normalizedBase,
            )
            if (page.isEmpty() && catalog.isEmpty()) {
                throw IOException("模型目录响应中没有可识别的模型")
            }
            page.forEach { candidate ->
                if (catalog.size < MAX_MODEL_CATALOG_ENTRIES) {
                    catalog.putIfAbsent(candidate.modelId.lowercase(), candidate)
                }
            }
            if (catalog.size >= MAX_MODEL_CATALOG_ENTRIES) break
            pageUrl = parseModelPageCursor(body, endpoint.protocol)
                ?.let { applyModelPageCursor(currentUrl, it) }
        }
        if (catalog.isEmpty()) throw IOException("模型目录为空")
        return catalog.values.toList()
    }

    private suspend fun fetchJson(
        url: String,
        endpoint: ModelCatalogEndpoint,
        apiKey: String,
    ): String {
        val response = client.get {
            url(url)
            when (endpoint.authMode) {
                ModelCatalogAuthMode.ANTHROPIC -> {
                    header("x-api-key", apiKey)
                    header("anthropic-version", "2023-06-01")
                }
                ModelCatalogAuthMode.GOOGLE_API_KEY_HEADER -> header("x-goog-api-key", apiKey)
                ModelCatalogAuthMode.BEARER -> header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "EveryTalk/1.0 (Android)")
        }
        return response.requireModelCatalogBody()
    }

    private suspend fun HttpResponse.requireModelCatalogBody(): String {
        if (!status.isSuccess()) {
            val errorBody = readErrorTextAtMost().orEmpty().take(500)
            throw IOException("HTTP ${status.value} $errorBody".trim())
        }
        return readTextAtMost(MAX_MODEL_PAGE_RESPONSE_BYTES)
    }
}
