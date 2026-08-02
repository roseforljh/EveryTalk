package com.android.everytalk.data.DataClass

private const val OFFICIAL_CATALOG_REVIEWED_AT = 1_785_628_800_000L

/**
 * 只保存本轮已从官方文档核对过的精确模型规格。
 *
 * Google 官方要求从 Models API 实时读取能力，因此不维护 Gemini 硬编码表。
 */
fun officialModelCapability(
    modelId: String,
    protocol: ModelParameterProtocol,
): ModelCapabilityCandidate? {
    val normalizedModelId = modelId.trim().lowercase()
    val specification = when (protocol) {
        ModelParameterProtocol.CODEX,
        ModelParameterProtocol.OPENAI_COMPATIBLE -> when {
            normalizedModelId == "gpt-5.6" || normalizedModelId.startsWith("gpt-5.6-") ->
                OfficialModelSpecification(
                    contextWindowTokens = 1_050_000,
                    maxInputTokens = 922_000,
                    maxOutputTokens = 128_000,
                    reasoningEfforts = setOf("none", "low", "medium", "high", "xhigh", "max"),
                )
            else -> null
        }
        ModelParameterProtocol.ANTHROPIC -> when {
            normalizedModelId.matchesOfficialFamily(
                "claude-fable-5",
                "claude-mythos-5",
                "claude-opus-5",
                "claude-opus-4-8",
                "claude-opus-4-7",
                "claude-opus-4-6",
                "claude-sonnet-5",
                "claude-sonnet-4-6",
            ) -> OfficialModelSpecification(
                contextWindowTokens = 1_000_000,
                maxInputTokens = 1_000_000,
                maxOutputTokens = 128_000,
                reasoningEfforts = setOf("low", "medium", "high", "max"),
            )
            normalizedModelId.matchesOfficialFamily("claude-haiku-4-5") -> OfficialModelSpecification(
                contextWindowTokens = 200_000,
                maxInputTokens = 200_000,
                maxOutputTokens = 64_000,
            )
            else -> null
        }
        ModelParameterProtocol.GEMINI -> null
    }
    specification ?: return null
    return ModelCapabilityCandidate(
        modelId = modelId.trim(),
        protocol = protocol,
        contextWindowTokens = specification.contextWindowTokens,
        maxInputTokens = specification.maxInputTokens,
        maxOutputTokens = specification.maxOutputTokens,
        inputModalities = setOf("text", "image"),
        outputModalities = setOf("text"),
        supportsReasoning = specification.supportsReasoning,
        reasoningEfforts = specification.reasoningEfforts,
        source = ModelCapabilitySource.OFFICIAL_CATALOG,
        sourceUpdatedAt = OFFICIAL_CATALOG_REVIEWED_AT,
    )
}

private data class OfficialModelSpecification(
    val contextWindowTokens: Int,
    val maxInputTokens: Int?,
    val maxOutputTokens: Int,
    val supportsReasoning: Boolean = true,
    val reasoningEfforts: Set<String> = emptySet(),
)

private fun String.matchesOfficialFamily(vararg baseIds: String): Boolean =
    baseIds.any { baseId -> this == baseId || startsWith("$baseId-") }
