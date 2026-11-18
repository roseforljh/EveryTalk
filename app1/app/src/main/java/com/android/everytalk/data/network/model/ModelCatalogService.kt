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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_MODEL_PAGE_RESPONSE_BYTES = 4L * 1024L * 1024L
private const val MODEL_DETAIL_CONCURRENCY = 8
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
     * 列表只下载一次；单模型详情、pi 目录和社区目录三路并发，不再依次排队。
     * 多数服务的详情与列表是同一份字段：先用一个模型试探，详情没带来列表之外的参数就不再逐个请求，
     * 否则只给列表缺参数的模型补详情，滑动并发、单个慢请求不拖住整批，超时保留已完成结果。
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
        val (pi, community) = coroutineScope {
            val pi = async { fetchPiCapabilities(catalog.map { it.modelId }, endpoint) }
            val community = async {
                withTimeoutOrNull(COMMUNITY_CATALOG_TIMEOUT_MS) {
                    modelsDevCatalog.findCatalogCapabilities(
                        modelIds = catalog.map { it.modelId },
                        providerHint = channel.orEmpty(),
                        apiAddress = endpoint.normalizedBase,
                        protocol = endpoint.protocol,
                        fetchRemote = ::fetchCommunityCatalog,
                    )
                }.orEmpty()
            }
            withTimeoutOrNull(MODEL_DETAIL_BATCH_TIMEOUT_MS) {
                val missing = catalog.filterNot(::hasListedLimits)
                val probe = missing.firstOrNull()
                val probed = probe?.let { fetchDetail(endpoint, cleanedApiKey, it.modelId) }
                if (probe != null && probed != null) {
                    details[probe.modelId] = probed
                    if (addsCapabilities(probe, probed)) {
                        val gate = Semaphore(MODEL_DETAIL_CONCURRENCY)
                        missing.drop(1).map { candidate ->
                            async {
                                gate.withPermit {
                                    fetchDetail(endpoint, cleanedApiKey, candidate.modelId)?.let { details[candidate.modelId] = it }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
            pi.await() to community.await()
        }
        // 缓存落盘失败不应丢弃已经获取成功的模型和参数。
        if (details.isNotEmpty()) runCatching { endpointCache.put(details.values.toList()) }
        // 详情在前，使相同 LIVE 来源的字段优先使用单模型响应；模型展示顺序仍以列表为准。
        val byModel = (details.values + catalog + pi + cached + community).groupBy { it.modelId.lowercase() }
        return catalog.flatMap { byModel[it.modelId.lowercase()].orEmpty() }
    }

    /** 列表条目已带上下文和输出上限，详情不会更全。 */
    private fun hasListedLimits(candidate: ModelCapabilityCandidate) =
        (candidate.contextWindowTokens != null || candidate.maxInputTokens != null) && candidate.maxOutputTokens != null

    /** 详情是否给出了列表里没有的参数；没有就说明这家详情接口只是重复列表。 */
    private fun addsCapabilities(listed: ModelCapabilityCandidate, detail: ModelCapabilityCandidate) =
        listed.contextWindowTokens == null && detail.contextWindowTokens != null ||
            listed.maxInputTokens == null && detail.maxInputTokens != null ||
            listed.maxOutputTokens == null && detail.maxOutputTokens != null ||
            listed.supportsReasoning == null && detail.supportsReasoning != null ||
            listed.inputModalities.isEmpty() && detail.inputModalities.isNotEmpty() ||
            listed.reasoningEfforts.isEmpty() && detail.reasoningEfforts.isNotEmpty() ||
            listed.supportsToolCalls == null && detail.supportsToolCalls != null

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
