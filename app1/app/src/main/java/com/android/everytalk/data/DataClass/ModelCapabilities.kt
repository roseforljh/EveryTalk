package com.android.everytalk.data.DataClass

import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
enum class ModelCapabilitySource {
    USER_OVERRIDE,
    LIVE_ENDPOINT,
    // 仅兼容旧配置的序列化标记，已移除内置规格表，不再参与参数选择。
    OFFICIAL_CATALOG,
    PI_CATALOG,
    LOCAL_CACHE,
    COMMUNITY_CATALOG,
    FAMILY_FALLBACK,
    CONSERVATIVE_DEFAULT,
}

@Serializable
data class ModelCapabilityCandidate(
    val modelId: String,
    val protocol: ModelParameterProtocol,
    val providerId: String? = null,
    val family: String? = null,
    val endpointIdentity: String? = null,
    val contextWindowTokens: Int? = null,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val inputModalities: Set<String> = emptySet(),
    val outputModalities: Set<String> = emptySet(),
    val supportsReasoning: Boolean? = null,
    val reasoningEfforts: Set<String> = emptySet(),
    val source: ModelCapabilitySource,
    val cachedSource: ModelCapabilitySource? = null,
    val sourceUpdatedAt: Long? = null,
    val supportsTemperature: Boolean? = null,
    val supportsToolCalls: Boolean? = null,
)

@Serializable
data class ResolvedModelCapability(
    val modelId: String,
    val endpointIdentity: String,
    val family: String? = null,
    val familySource: ModelCapabilitySource? = null,
    val familyUpdatedAt: Long? = null,
    val contextWindowTokens: Int,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int,
    val contextWindowSource: ModelCapabilitySource,
    val contextWindowUpdatedAt: Long? = null,
    val maxInputSource: ModelCapabilitySource? = null,
    val maxInputUpdatedAt: Long? = null,
    val maxOutputSource: ModelCapabilitySource,
    val maxOutputUpdatedAt: Long? = null,
    val inputModalities: Set<String>,
    val outputModalities: Set<String>,
    val modalitiesSource: ModelCapabilitySource? = null,
    val modalitiesUpdatedAt: Long? = null,
    val supportsReasoning: Boolean?,
    val reasoningEfforts: Set<String> = emptySet(),
    val reasoningSource: ModelCapabilitySource? = null,
    val reasoningUpdatedAt: Long? = null,
    val supportsTemperature: Boolean? = null,
    val supportsToolCalls: Boolean? = null,
)

fun normalizeModelEndpointIdentity(apiAddress: String): String {
    val trimmed = apiAddress.trim().removeSuffix("#").trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return withScheme.lowercase()
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.lowercase().orEmpty()
    if (scheme.isEmpty() || host.isEmpty()) return withScheme.lowercase()
    val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
    val path = uri.path.orEmpty().trimEnd('/')
    return "$scheme://$host$port$path"
}

fun resolveModelCapability(
    modelId: String,
    protocol: ModelParameterProtocol,
    apiAddress: String,
    candidates: List<ModelCapabilityCandidate>,
): ResolvedModelCapability {
    val normalizedModelId = modelId.removePrefix("models/").trim()
    val endpointIdentity = normalizeModelEndpointIdentity(apiAddress)
    val applicableCandidates = candidates
        .asSequence()
        .filter { it.source != ModelCapabilitySource.OFFICIAL_CATALOG && it.cachedSource != ModelCapabilitySource.OFFICIAL_CATALOG }
        .filter { it.protocol == protocol }
        .filter { it.modelId.removePrefix("models/").trim().equals(normalizedModelId, ignoreCase = true) }
        .filter { candidate ->
            candidate.endpointIdentity == null ||
                normalizeModelEndpointIdentity(candidate.endpointIdentity) == endpointIdentity
        }
        .sortedBy { sourcePriority(it.cachedSource ?: it.source) }
        .toList()

    val contextCandidate = applicableCandidates.firstOrNull {
        it.contextWindowTokens != null && it.contextWindowTokens >= 2
    }
    val contextWindowTokens = contextCandidate?.contextWindowTokens ?: DEFAULT_MAX_CONTEXT_TOKENS
    val maxInputCandidate = applicableCandidates.firstOrNull {
        it.maxInputTokens != null && it.maxInputTokens > 0 && it.maxInputTokens <= contextWindowTokens
    }
    val maxOutputCandidate = applicableCandidates.firstOrNull {
        it.maxOutputTokens != null && it.maxOutputTokens > 0 && it.maxOutputTokens < contextWindowTokens
    }
    val maxOutputTokens = maxOutputCandidate?.maxOutputTokens
        ?: DEFAULT_MAX_OUTPUT_TOKENS.coerceAtMost(contextWindowTokens - 1)
    val modalityCandidate = applicableCandidates.firstOrNull {
        it.inputModalities.isNotEmpty() || it.outputModalities.isNotEmpty()
    }
    val familyCandidate = applicableCandidates.firstOrNull { !it.family.isNullOrBlank() }
    val reasoningCandidate = applicableCandidates.firstOrNull {
        it.supportsReasoning != null || it.reasoningEfforts.isNotEmpty()
    }

    return ResolvedModelCapability(
        modelId = normalizedModelId,
        endpointIdentity = endpointIdentity,
        family = familyCandidate?.family,
        familySource = familyCandidate?.source,
        familyUpdatedAt = familyCandidate?.sourceUpdatedAt,
        contextWindowTokens = contextWindowTokens,
        maxInputTokens = maxInputCandidate?.maxInputTokens,
        maxOutputTokens = maxOutputTokens,
        contextWindowSource = contextCandidate?.source ?: ModelCapabilitySource.CONSERVATIVE_DEFAULT,
        contextWindowUpdatedAt = contextCandidate?.sourceUpdatedAt,
        maxInputSource = maxInputCandidate?.source,
        maxInputUpdatedAt = maxInputCandidate?.sourceUpdatedAt,
        maxOutputSource = maxOutputCandidate?.source ?: ModelCapabilitySource.CONSERVATIVE_DEFAULT,
        maxOutputUpdatedAt = maxOutputCandidate?.sourceUpdatedAt,
        inputModalities = applicableCandidates.firstOrNull { it.inputModalities.isNotEmpty() }?.inputModalities.orEmpty(),
        outputModalities = applicableCandidates.firstOrNull { it.outputModalities.isNotEmpty() }?.outputModalities.orEmpty(),
        modalitiesSource = modalityCandidate?.source,
        modalitiesUpdatedAt = modalityCandidate?.sourceUpdatedAt,
        supportsReasoning = reasoningCandidate?.supportsReasoning
            ?: true.takeIf { reasoningCandidate?.reasoningEfforts?.isNotEmpty() == true },
        reasoningEfforts = reasoningCandidate?.reasoningEfforts.orEmpty(),
        reasoningSource = reasoningCandidate?.source,
        reasoningUpdatedAt = reasoningCandidate?.sourceUpdatedAt,
        supportsTemperature = applicableCandidates.firstOrNull { it.supportsTemperature != null }?.supportsTemperature,
        supportsToolCalls = applicableCandidates.firstOrNull { it.supportsToolCalls != null }?.supportsToolCalls,
    )
}

private fun sourcePriority(source: ModelCapabilitySource): Int = when (source) {
    ModelCapabilitySource.USER_OVERRIDE -> 0
    ModelCapabilitySource.COMMUNITY_CATALOG -> 1
    ModelCapabilitySource.LIVE_ENDPOINT -> 2
    ModelCapabilitySource.PI_CATALOG -> 3
    ModelCapabilitySource.LOCAL_CACHE -> 4
    ModelCapabilitySource.FAMILY_FALLBACK -> 5
    ModelCapabilitySource.CONSERVATIVE_DEFAULT -> 6
    ModelCapabilitySource.OFFICIAL_CATALOG -> Int.MAX_VALUE
}

/** 与 models.dev 一致区分输入和输出；旧配置在无目录数据时保持原本的文本/图片用途。 */
fun ApiConfig.inputModalities(): Set<String> = normalizeModelModalities(
    modelParameters.inputModalities ?: modelParameters.resolvedCapability?.inputModalities.orEmpty()
).ifEmpty { setOf("text") }

fun ApiConfig.outputModalities(): Set<String> = normalizeModelModalities(
    modelParameters.outputModalities ?: modelParameters.resolvedCapability?.outputModalities.orEmpty()
).ifEmpty { setOf(if (modalityType == ModalityType.IMAGE) "image" else "text") }

fun normalizeModelModalities(values: Set<String>): Set<String> = values.mapTo(linkedSetOf()) {
    val value = it.trim().lowercase()
    when {
        value == "application/pdf" -> "pdf"
        value.startsWith("image/") || value.startsWith("audio/") || value.startsWith("video/") -> value.substringBefore('/')
        else -> value
    }
}.filterTo(linkedSetOf()) { it.isNotEmpty() }

/**
 * 图片复用 API 地址和协议，不再读取独立的图片协议覆盖。
 * 只有内置服务和明确的 Modal 地址使用专用传输；代理中的模型名不能改变 API 协议。
 */
fun ApiConfig.imageApiFormat(): String {
    val qwenEdit = model.contains("qwen", true) && model.contains("edit", true)
    if (isBuiltinModel()) return when (model) {
        "qwen-image-edit-modal" -> "qwen"
        "z-image-turbo-modal" -> "modal"
        else -> "openai"
    }
    val endpoint = address.trim().removeSuffix("#").lowercase()
    if ("modal.run" in endpoint) return if (qwenEdit) "qwen" else "modal"
    if (Regex("/images/(generations|edits)(?:[/?]|$)").containsMatchIn(endpoint)) return "openai"
    if (Regex("/v1beta(?:[/?]|$)").containsMatchIn(endpoint) || "generativelanguage.googleapis.com" in endpoint) return "gemini"
    return when (modelParameterProtocol(effectiveModelChannel())) {
        ModelParameterProtocol.GEMINI -> "gemini"
        ModelParameterProtocol.OPENAI_COMPATIBLE -> "openai"
        // 这两个原生接口目前没有接入图片生成，不能偷偷改用 OpenAI Images。
        ModelParameterProtocol.ANTHROPIC, ModelParameterProtocol.CODEX -> "unsupported"
    }
}

fun imageParameterKeys(format: String): Set<String> = when (format) {
    "gemini" -> setOf("imageSize", "aspectRatio")
    "qwen" -> setOf("inferenceSteps", "guidanceScale")
    "modal" -> setOf("imageSize", "aspectRatio", "inferenceSteps", "guidanceScale")
    "unsupported" -> emptySet()
    else -> setOf("imageSize", "quality", "batchSize")
}

fun ApiConfig.withModelCapabilityDefaults(
    candidates: List<ModelCapabilityCandidate>,
): ApiConfig {
    val protocol = modelParameterProtocol(effectiveModelChannel())
    val currentCapability = modelParameters.resolvedCapability
    val userOverride = currentCapability?.let { current ->
        ModelCapabilityCandidate(
            modelId = model,
            protocol = protocol,
            endpointIdentity = address,
            contextWindowTokens = modelParameters.maxContextTokens.takeIf {
                current.contextWindowSource == ModelCapabilitySource.USER_OVERRIDE
            },
            maxOutputTokens = maxTokens.takeIf {
                current.maxOutputSource == ModelCapabilitySource.USER_OVERRIDE
            },
            source = ModelCapabilitySource.USER_OVERRIDE,
        ).takeIf { it.contextWindowTokens != null || it.maxOutputTokens != null }
    }
    val resolved = resolveModelCapability(
        modelId = model,
        protocol = protocol,
        apiAddress = address,
        candidates = candidates + listOfNotNull(
            userOverride,
            familyModelCapability(model, protocol),
        ),
    )
    return copy(
        maxTokens = resolved.maxOutputTokens,
        modelParameters = modelParameters.copy(
            maxContextTokens = resolved.contextWindowTokens,
            resolvedCapability = resolved,
        ),
    )
}

fun ApiConfig.withUserTokenLimits(limits: ModelTokenLimits): ApiConfig {
    val currentOutputTokens = maxTokens ?: DEFAULT_MAX_OUTPUT_TOKENS
    val currentContextTokens = modelParameters.maxContextTokens
    val currentCapability = modelParameters.resolvedCapability ?: ResolvedModelCapability(
        modelId = model,
        endpointIdentity = normalizeModelEndpointIdentity(address),
        family = null,
        familySource = null,
        familyUpdatedAt = null,
        contextWindowTokens = currentContextTokens,
        maxInputTokens = null,
        maxOutputTokens = currentOutputTokens,
        contextWindowSource = ModelCapabilitySource.USER_OVERRIDE,
        contextWindowUpdatedAt = null,
        maxInputSource = null,
        maxInputUpdatedAt = null,
        maxOutputSource = ModelCapabilitySource.USER_OVERRIDE,
        maxOutputUpdatedAt = null,
        inputModalities = emptySet(),
        outputModalities = emptySet(),
        modalitiesSource = null,
        modalitiesUpdatedAt = null,
        supportsReasoning = null,
        reasoningEfforts = emptySet(),
        reasoningSource = null,
        reasoningUpdatedAt = null,
    )
    val updatedCapability = currentCapability.copy(
        contextWindowTokens = limits.maxContextTokens,
        maxOutputTokens = limits.maxOutputTokens,
        contextWindowSource = if (limits.maxContextTokens != currentContextTokens) {
            ModelCapabilitySource.USER_OVERRIDE
        } else {
            currentCapability.contextWindowSource
        },
        maxOutputSource = if (limits.maxOutputTokens != currentOutputTokens) {
            ModelCapabilitySource.USER_OVERRIDE
        } else {
            currentCapability.maxOutputSource
        },
    )
    return copy(
        maxTokens = limits.maxOutputTokens,
        modelParameters = modelParameters.copy(
            maxContextTokens = limits.maxContextTokens,
            resolvedCapability = updatedCapability,
        ),
    )
}
