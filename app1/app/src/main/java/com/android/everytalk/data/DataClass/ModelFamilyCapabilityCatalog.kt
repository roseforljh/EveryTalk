package com.android.everytalk.data.DataClass

/**
 * 最后一级安全兜底，只保存少量能够明确识别的模型家族。
 */
fun familyModelCapability(
    modelId: String,
    protocol: ModelParameterProtocol,
): ModelCapabilityCandidate? {
    val normalized = modelId.removePrefix("models/").trim().lowercase()
    val specification = when {
        protocol == ModelParameterProtocol.ANTHROPIC && normalized.startsWith("claude-") ->
            FamilySpecification(128_000, 128_000, 8_192, setOf("text", "image"))
        protocol == ModelParameterProtocol.GEMINI && normalized.startsWith("gemini-") ->
            FamilySpecification(
                contextWindowTokens = 32_768,
                maxInputTokens = 32_768,
                maxOutputTokens = 8_192,
                inputModalities = setOf("text", "image"),
                supportsReasoning = true.takeIf {
                    normalized.startsWith("gemini-2.5") || normalized.startsWith("gemini-3")
                },
                reasoningEfforts = setOf("minimal", "low", "medium", "high")
                    .takeIf { normalized.startsWith("gemini-3") }
                    .orEmpty(),
            )
        protocol in setOf(ModelParameterProtocol.CODEX, ModelParameterProtocol.OPENAI_COMPATIBLE) &&
            (normalized.startsWith("gpt-5") || normalized.startsWith("o1") ||
                normalized.startsWith("o3") || normalized.startsWith("o4")) ->
            FamilySpecification(
                contextWindowTokens = 128_000,
                maxInputTokens = null,
                maxOutputTokens = 8_192,
                inputModalities = setOf("text", "image"),
                supportsReasoning = true,
                reasoningEfforts = setOf("none", "low", "medium", "high"),
            )
        protocol == ModelParameterProtocol.OPENAI_COMPATIBLE && normalized.startsWith("deepseek-") ->
            FamilySpecification(
                contextWindowTokens = 32_768,
                maxInputTokens = null,
                maxOutputTokens = 8_192,
                inputModalities = setOf("text"),
                supportsReasoning = true.takeIf { "reason" in normalized || "r1" in normalized },
            )
        protocol == ModelParameterProtocol.OPENAI_COMPATIBLE &&
            listOf("qwen", "glm-", "mistral").any(normalized::startsWith) ->
            FamilySpecification(32_768, null, 8_192, setOf("text"))
        else -> null
    } ?: return null

    return ModelCapabilityCandidate(
        modelId = modelId.removePrefix("models/").trim(),
        protocol = protocol,
        contextWindowTokens = specification.contextWindowTokens,
        maxInputTokens = specification.maxInputTokens,
        maxOutputTokens = specification.maxOutputTokens,
        inputModalities = specification.inputModalities,
        outputModalities = setOf("text"),
        supportsReasoning = specification.supportsReasoning,
        reasoningEfforts = specification.reasoningEfforts,
        source = ModelCapabilitySource.FAMILY_FALLBACK,
    )
}

private data class FamilySpecification(
    val contextWindowTokens: Int,
    val maxInputTokens: Int?,
    val maxOutputTokens: Int,
    val inputModalities: Set<String>,
    val supportsReasoning: Boolean? = null,
    val reasoningEfforts: Set<String> = emptySet(),
)
