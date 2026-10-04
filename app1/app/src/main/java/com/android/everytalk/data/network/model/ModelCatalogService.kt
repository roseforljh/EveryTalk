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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_MODEL_PAGE_RESPONSE_BYTES = 4L * 1024L * 1024L
private const val MODEL_DETAIL_CONCURRENCY = 6
private const val MODEL_DETAIL_BATCH_TIMEOUT_MS = 20_000L
private const val COMMUNITY_CATALOG_TIMEOUT_MS = 10_000L

internal class ModelCatalogService(
    private val client: HttpClient,
    private val endpointCache: ModelCapabilityCache,
    private val modelsDevCatalog: ModelsDevCatalog,
    private val piModelCatalog: PiModelCatalog,
) {
    /**
     * 获取列表时同步补齐参数，返回各来源候选而不是混成一个来源。
     * 列表只下载一次；详情最多并发六个，超时保留已完成结果，避免大目录长时间阻塞界面。
     * 未提供的字段继续由 pi、社区目录以及配置层的家族和默认值补齐。
     */
    suspend fun getCatalogWithCapabilities(
        apiUrl: String,
        apiKey: String,
        channel: String?,
    ): List<ModelCapabilityCandidate> {
        val catalog = getCatalog(apiUrl, apiKey, channel)
        val endpoint = resolveModelCatalogEndpoint(apiUrl, channel)
        val cleanedApiKey = apiKey.filterNot(Char::isWhitespace)
        val cached = endpointCache.get(endpoint.protocol, endpoint.normalizedBase)
        val details = ConcurrentHashMap<String, ModelCapabilityCandidate>()
        withTimeoutOrNull(MODEL_DETAIL_BATCH_TIMEOUT_MS) {
            coroutineScope {
                catalog.chunked(MODEL_DETAIL_CONCURRENCY).forEach { batch ->
                    batch.map { candidate ->
                        async {
                            fetchDetail(endpoint, cleanedApiKey, candidate.modelId)?.let {
                                details[candidate.modelId] = it
                            }
                        }
                    }.awaitAll()
                }
            }
        }
        // 缓存落盘失败不应丢弃已经获取成功的模型和参数。
        if (details.isNotEmpty()) runCatching { endpointCache.put(details.values.toList()) }
        val pi = fetchPiCapabilities(catalog.map { it.modelId }, endpoint)
        val community = withTimeoutOrNull(COMMUNITY_CATALOG_TIMEOUT_MS) {
            modelsDevCatalog.findCatalogCapabilities(
                modelIds = catalog.map { it.modelId },
                providerHint = channel.orEmpty(),
                apiAddress = endpoint.normalizedBase,
                protocol = endpoint.protocol,
                fetchRemote = ::fetchCommunityCatalog,
            )
        }.orEmpty()
        // 详情在前，使相同 LIVE 来源的字段优先使用单模型响应；模型展示顺序仍以列表为准。
        val byModel = (details.values + catalog + pi + cached + community).groupBy { it.modelId.lowercase() }
        return catalog.flatMap { byModel[it.modelId.lowercase()].orEmpty() }
    }

    suspend fun getCatalog(
        apiUrl: String,
        apiKey: String,
        channel: String?,
        allowEndpointCache: Boolean = true,
    ): List<ModelCapabilityCandidate> {
        val endpoint = resolveModelCatalogEndpoint(apiUrl, channel)
        val cleanedApiKey = apiKey.filterNot(Char::isWhitespace)
        return try {
            val catalog = fetchAllPages(endpoint, cleanedApiKey)
            if (allowEndpointCache) endpointCache.put(catalog)
            catalog
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (allowEndpointCache) {
                val cached = endpointCache.get(endpoint.protocol, endpoint.normalizedBase)
                if (cached.isNotEmpty()) {
                    cached
                } else {
                    throw IOException("获取模型列表失败: ${error.message}", error)
                }
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
        allowCache: Boolean = true,
    ): List<ModelCapabilityCandidate> {
        val endpoint = resolveModelCatalogEndpoint(apiUrl, channel)
        val cleanedApiKey = apiKey.filterNot(Char::isWhitespace)
        val normalizedModelId = modelId.removePrefix("models/").trim()
        val candidates = mutableListOf<ModelCapabilityCandidate>()

        fetchDetail(endpoint, cleanedApiKey, normalizedModelId)?.let { detail ->
            candidates += detail
            if (allowCache) endpointCache.put(listOf(detail))
        }

        try {
            getCatalog(apiUrl, cleanedApiKey, channel, allowEndpointCache = allowCache)
                .firstOrNull { it.modelId.equals(normalizedModelId, ignoreCase = true) }
                ?.let(candidates::add)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }

        candidates += fetchPiCapabilities(listOf(normalizedModelId), endpoint, allowCache)
        val community = modelsDevCatalog.findCapabilities(
            modelId = normalizedModelId,
            providerHint = providerHint,
            apiAddress = endpoint.normalizedBase,
            protocol = endpoint.protocol,
            fetchRemote = ::fetchCommunityCatalog,
            allowCache = allowCache,
        )
        candidates += community
        return candidates
    }

    /** 详情接口不受支持或单个模型请求失败时保留目录结果；取消必须向上传播。 */
    private suspend fun fetchDetail(
        endpoint: ModelCatalogEndpoint,
        apiKey: String,
        modelId: String,
    ): ModelCapabilityCandidate? = try {
        parseModelCatalog(
            responseBody = fetchJson(buildModelDetailUrl(endpoint, modelId), endpoint, apiKey),
            protocol = endpoint.protocol,
            apiAddress = endpoint.normalizedBase,
        ).firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /** 整个目录只下载一次，查询不带用户地址或密钥；超时作为获取失败处理，允许使用 pi 旧缓存。 */
    private suspend fun fetchPiCapabilities(
        modelIds: List<String>,
        endpoint: ModelCatalogEndpoint,
        allowCache: Boolean = true,
    ): List<ModelCapabilityCandidate> = piModelCatalog.findCapabilities(
        modelIds = modelIds,
        protocol = endpoint.protocol,
        fetchRemote = {
            withTimeoutOrNull(COMMUNITY_CATALOG_TIMEOUT_MS) {
                val response = client.get(PI_MODEL_CATALOG_URL) {
                    header(HttpHeaders.Accept, "application/json")
                    header(HttpHeaders.UserAgent, "EveryTalk/1.0 (Android)")
                }
                if (!response.status.isSuccess()) throw IOException("pi 目录返回 HTTP ${response.status.value}")
                response.readTextAtMost(MAX_PI_MODEL_CATALOG_BYTES)
            } ?: throw IOException("pi 目录请求超时")
        },
        allowCache = allowCache,
    )

    private suspend fun fetchCommunityCatalog(): String {
        val response = client.get(MODELS_DEV_URL) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "EveryTalk/1.0 (Android)")
        }
        if (!response.status.isSuccess()) {
            throw IOException("models.dev 返回 HTTP ${response.status.value}")
        }
        return response.readTextAtMost(MAX_MODELS_DEV_RESPONSE_BYTES)
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
