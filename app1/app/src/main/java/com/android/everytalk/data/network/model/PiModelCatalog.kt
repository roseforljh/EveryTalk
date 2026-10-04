package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.MAX_MODEL_TOKEN_LIMIT
import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal const val PI_MODEL_CATALOG_URL = "https://pi.dev/api/models"
internal const val MAX_PI_MODEL_CATALOG_BYTES = 6L * 1024L * 1024L
private const val PI_MODEL_CATALOG_TTL_MS = 4L * 60L * 60L * 1_000L

/**
 * 读取 pi 官方发布的 JSON 目录。它包含 pi 对多个上游目录的修正与补充。
 * 匹配只使用模型 ID，忽略用户平台名称、地址及 pi 的 baseUrl/api，
 * 因此同一模型经自定义代理访问时也能获得相同参数，且不会改变请求协议。
 */
internal class PiModelCatalog(
    cacheFile: File,
    nowEpochMillis: () -> Long = System::currentTimeMillis,
    ttlMillis: Long = PI_MODEL_CATALOG_TTL_MS,
) {
    private val cache = RemoteModelCatalogCache(cacheFile, ttlMillis, nowEpochMillis, ::parsePiModelCatalog)

    suspend fun findCapabilities(
        modelIds: List<String>,
        protocol: ModelParameterProtocol,
        allowCache: Boolean = true,
        fetchRemote: suspend () -> String,
    ): List<ModelCapabilityCandidate> {
        if (modelIds.isEmpty()) return emptyList()
        val index = cache.load(fetchRemote, allowCache) ?: return emptyList()
        return modelIds.mapNotNull { requested ->
            val id = normalizePiModelId(requested)
            // 精确 ID 优先。只有未命中时才移除目录/代理附加的命名空间，不模糊匹配版本号。
            val candidates = index[id] ?: index[id.substringAfterLast('/')] ?: return@mapNotNull null
            mergePiCapabilities(requested, protocol, candidates)
        }
    }
}

/** 解析官方 provider -> model ID -> 参数结构；只接受有限的能力字段，不采用远端地址和认证信息。 */
internal fun parsePiModelCatalog(body: String, fetchedAt: Long): Map<String, List<ModelCapabilityCandidate>>? = runCatching {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
    val entries = root.values.flatMap { provider ->
        (provider as? JsonObject)?.values.orEmpty().mapNotNull { value ->
            val model = value as? JsonObject ?: return@mapNotNull null
            val id = (model["id"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return@mapNotNull null
            val context = model.tokenLimit("contextWindow")?.takeIf { it >= 2 }
            val output = model.tokenLimit("maxTokens")
            val reasoning = (model["reasoning"] as? JsonPrimitive)?.booleanOrNull
            val input = (model["input"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("text", "image", "audio", "video") }
            }.toSet()
            if (context == null && output == null && reasoning == null && input.isEmpty()) return@mapNotNull null
            // thinkingLevelMap 是 pi 的等级映射；这里只读取明确的字符串值，
            // 不把 null（禁用）、数值预算或缺省映射猜成端点支持的 reasoning_effort。
            val efforts = (model["thinkingLevelMap"] as? JsonObject)?.values.orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?.takeIf { it in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max") }
            }.toSet().takeIf { reasoning == true }.orEmpty()
            ModelCapabilityCandidate(
                modelId = id,
                protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                contextWindowTokens = context,
                maxOutputTokens = output,
                inputModalities = input,
                supportsReasoning = reasoning,
                reasoningEfforts = efforts,
                source = ModelCapabilitySource.PI_CATALOG,
                sourceUpdatedAt = fetchedAt,
            )
        }
    }
    if (entries.isEmpty()) return@runCatching null
    entries.groupBy { normalizePiModelId(it.modelId) }
}.getOrNull()

/** 同名模型可能在多个 pi provider 下有不同限制；无地址匹配时取较小限制和共同能力，结果不依赖目录顺序。 */
private fun mergePiCapabilities(
    requested: String,
    protocol: ModelParameterProtocol,
    candidates: List<ModelCapabilityCandidate>,
): ModelCapabilityCandidate = ModelCapabilityCandidate(
    modelId = requested.trim().removePrefix("models/"),
    protocol = protocol,
    contextWindowTokens = candidates.mapNotNull { it.contextWindowTokens }.minOrNull(),
    maxOutputTokens = candidates.mapNotNull { it.maxOutputTokens }.minOrNull(),
    inputModalities = candidates.map { it.inputModalities }.piCommonValues(),
    supportsReasoning = when {
        candidates.any { it.supportsReasoning == false } -> false
        candidates.any { it.supportsReasoning == true } -> true
        else -> null
    },
    reasoningEfforts = candidates.map { it.reasoningEfforts }.piCommonValues()
        .takeUnless { candidates.any { it.supportsReasoning == false } }.orEmpty(),
    source = ModelCapabilitySource.PI_CATALOG,
    sourceUpdatedAt = candidates.mapNotNull { it.sourceUpdatedAt }.maxOrNull(),
)

private fun normalizePiModelId(id: String): String = id.trim().lowercase(Locale.ROOT).removePrefix("models/")

private fun JsonObject.tokenLimit(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..MAX_MODEL_TOKEN_LIMIT }

private fun List<Set<String>>.piCommonValues(): Set<String> =
    filter { it.isNotEmpty() }.reduceOrNull { common, values -> common intersect values }.orEmpty()
