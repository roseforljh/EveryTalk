package com.android.everytalk.data.DataClass

import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
enum class ModelCapabilitySource {
    USER_OVERRIDE,
    LIVE_ENDPOINT,
    OFFICIAL_CATALOG,
    LOCAL_CACHE,
    COMMUNITY_CATALOG,
    FAMILY_FALLBACK,
    CONSERVATIVE_DEFAULT,
}

@Serializable
data class ModelCapabilityCandidate(
    val modelId: String,
    val protocol: ModelParameterProtocol,
    val endpointIdentity: String? = null,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val inputModalities: Set<String> = emptySet(),
    val outputModalities: Set<String> = emptySet(),
    val supportsReasoning: Boolean? = null,
    val source: ModelCapabilitySource,
    val cachedSource: ModelCapabilitySource? = null,
    val sourceUpdatedAt: Long? = null,
)

@Serializable
data class ResolvedModelCapability(
    val modelId: String,
    val endpointIdentity: String,
    val contextWindowTokens: Int,
    val maxOutputTokens: Int,
    val contextWindowSource: ModelCapabilitySource,
    val maxOutputSource: ModelCapabilitySource,
    val inputModalities: Set<String>,
    val outputModalities: Set<String>,
    val supportsReasoning: Boolean?,
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
        .filter { it.protocol == protocol }
        .filter { it.modelId.removePrefix("models/").trim().equals(normalizedModelId, ignoreCase = true) }
        .filter { candidate ->
            candidate.endpointIdentity == null ||
                normalizeModelEndpointIdentity(candidate.endpointIdentity) == endpointIdentity
        }
        .sortedBy { sourcePriority(it.source) }
        .toList()

    val contextCandidate = applicableCandidates.firstOrNull {
        it.contextWindowTokens != null && it.contextWindowTokens >= 2
    }
    val contextWindowTokens = contextCandidate?.contextWindowTokens ?: DEFAULT_MAX_CONTEXT_TOKENS
    val maxOutputCandidate = applicableCandidates.firstOrNull {
        it.maxOutputTokens != null && it.maxOutputTokens > 0 && it.maxOutputTokens < contextWindowTokens
    }
    val maxOutputTokens = maxOutputCandidate?.maxOutputTokens
        ?: DEFAULT_MAX_OUTPUT_TOKENS.coerceAtMost(contextWindowTokens - 1)
    val modalityCandidate = applicableCandidates.firstOrNull {
        it.inputModalities.isNotEmpty() || it.outputModalities.isNotEmpty()
    }
    val reasoningCandidate = applicableCandidates.firstOrNull { it.supportsReasoning != null }

    return ResolvedModelCapability(
        modelId = normalizedModelId,
        endpointIdentity = endpointIdentity,
        contextWindowTokens = contextWindowTokens,
        maxOutputTokens = maxOutputTokens,
        contextWindowSource = contextCandidate?.source ?: ModelCapabilitySource.CONSERVATIVE_DEFAULT,
        maxOutputSource = maxOutputCandidate?.source ?: ModelCapabilitySource.CONSERVATIVE_DEFAULT,
        inputModalities = modalityCandidate?.inputModalities.orEmpty(),
        outputModalities = modalityCandidate?.outputModalities.orEmpty(),
        supportsReasoning = reasoningCandidate?.supportsReasoning,
    )
}

private fun sourcePriority(source: ModelCapabilitySource): Int = when (source) {
    ModelCapabilitySource.USER_OVERRIDE -> 0
    ModelCapabilitySource.LIVE_ENDPOINT -> 1
    ModelCapabilitySource.OFFICIAL_CATALOG -> 2
    ModelCapabilitySource.LOCAL_CACHE -> 3
    ModelCapabilitySource.COMMUNITY_CATALOG -> 4
    ModelCapabilitySource.FAMILY_FALLBACK -> 5
    ModelCapabilitySource.CONSERVATIVE_DEFAULT -> 6
}

fun ApiConfig.withModelCapabilityDefaults(
    candidates: List<ModelCapabilityCandidate>,
): ApiConfig {
    val protocol = modelParameterProtocol(channel)
    val resolved = resolveModelCapability(
        modelId = model,
        protocol = protocol,
        apiAddress = address,
        candidates = candidates + listOfNotNull(officialModelCapability(model, protocol)),
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
        contextWindowTokens = currentContextTokens,
        maxOutputTokens = currentOutputTokens,
        contextWindowSource = ModelCapabilitySource.USER_OVERRIDE,
        maxOutputSource = ModelCapabilitySource.USER_OVERRIDE,
        inputModalities = emptySet(),
        outputModalities = emptySet(),
        supportsReasoning = null,
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
