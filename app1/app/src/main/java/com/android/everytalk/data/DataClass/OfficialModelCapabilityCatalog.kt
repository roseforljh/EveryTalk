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
    val limits = when (protocol) {
        ModelParameterProtocol.CODEX,
        ModelParameterProtocol.OPENAI_COMPATIBLE -> when (normalizedModelId) {
            "gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna" ->
                1_050_000 to 128_000
            else -> null
        }
        ModelParameterProtocol.ANTHROPIC -> when (normalizedModelId) {
            "claude-fable-5",
            "claude-mythos-5",
            "claude-opus-5",
            "claude-opus-4-8",
            "claude-opus-4-7",
            "claude-opus-4-6",
            "claude-sonnet-5",
            "claude-sonnet-4-6" -> 1_000_000 to 128_000
            "claude-haiku-4-5",
            "claude-haiku-4-5-20251001" -> 200_000 to 64_000
            else -> null
        }
        ModelParameterProtocol.GEMINI -> null
    }
    limits ?: return null
    return ModelCapabilityCandidate(
        modelId = modelId.trim(),
        protocol = protocol,
        contextWindowTokens = limits.first,
        maxOutputTokens = limits.second,
        inputModalities = setOf("text", "image"),
        outputModalities = setOf("text"),
        supportsReasoning = true,
        source = ModelCapabilitySource.OFFICIAL_CATALOG,
        sourceUpdatedAt = OFFICIAL_CATALOG_REVIEWED_AT,
    )
}
