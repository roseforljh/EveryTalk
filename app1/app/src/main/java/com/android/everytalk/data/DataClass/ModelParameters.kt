package com.android.everytalk.data.DataClass

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

const val DEFAULT_REASONING_EFFORT = "medium"
const val DEFAULT_THINKING_BUDGET = 2048
const val DEFAULT_MAX_OUTPUT_TOKENS = 4096
const val DEFAULT_MAX_CONTEXT_TOKENS = 128_000
const val MAX_MODEL_TOKEN_LIMIT = 10_000_000
const val DEFAULT_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT = 80
const val MIN_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT = 50
const val MAX_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT = 90

@Serializable
enum class ReasoningMode {
    EFFORT,
    BUDGET,
    DISABLED,
}

@Serializable
enum class CustomParameterType {
    STRING,
    NUMBER,
    BOOLEAN,
    JSON,
}

@Serializable
data class CustomModelParameter(
    val name: String = "",
    val value: String = "",
    val type: CustomParameterType = CustomParameterType.STRING,
    val enabled: Boolean = true,
)

@Serializable
data class ModelParameters(
    val reasoningMode: ReasoningMode = ReasoningMode.EFFORT,
    val reasoningEffort: String = DEFAULT_REASONING_EFFORT,
    val thinkingBudget: Int = DEFAULT_THINKING_BUDGET,
    // null 表示旧配置尚未初始化，OpenAI 兼容接口会使用默认的 medium 预置项。
    val customParameters: List<CustomModelParameter>? = null,
    // OpenAI 兼容渠道保存用户添加的思考程度，供下次直接选择。
    val customReasoningEfforts: List<String> = emptyList(),
    // 上下文窗口是模型能力限制，只用于发送前裁剪本地历史，不作为请求字段发送。
    val maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
    // 自动压缩按模型保存，默认关闭，避免旧配置升级后改变现有会话行为。
    val autoContextCompressionEnabled: Boolean = false,
    val autoContextCompressionThresholdPercent: Int = DEFAULT_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT,
    // 记录自动解析后的能力及来源；旧配置缺少该字段时按用户现有值处理。
    val resolvedCapability: ResolvedModelCapability? = null,
)

fun validateAutoContextCompressionThreshold(percent: Int): Int {
    require(percent in MIN_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT..MAX_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT) {
        "自动压缩触发值需在 $MIN_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT% 到 " +
            "$MAX_AUTO_CONTEXT_COMPRESSION_THRESHOLD_PERCENT% 之间"
    }
    return percent
}

data class ModelTokenLimits(
    val maxOutputTokens: Int,
    val maxContextTokens: Int,
)

fun validateModelTokenLimits(
    maxOutputTokens: Int,
    maxContextTokens: Int,
): ModelTokenLimits {
    require(maxOutputTokens in 1 until MAX_MODEL_TOKEN_LIMIT) {
        "最大输出需在 1 到 ${MAX_MODEL_TOKEN_LIMIT - 1} tokens 之间"
    }
    require(maxContextTokens in 2..MAX_MODEL_TOKEN_LIMIT) {
        "上下文窗口需在 2 到 $MAX_MODEL_TOKEN_LIMIT tokens 之间"
    }
    require(maxOutputTokens < maxContextTokens) {
        "最大输出必须小于上下文窗口"
    }
    return ModelTokenLimits(maxOutputTokens, maxContextTokens)
}

fun resolvedModelTokenLimits(
    maxOutputTokens: Int?,
    maxContextTokens: Int,
): ModelTokenLimits = runCatching {
    validateModelTokenLimits(
        maxOutputTokens = maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS,
        maxContextTokens = maxContextTokens,
    )
}.getOrElse {
    ModelTokenLimits(
        maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
        maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS,
    )
}

@Serializable
enum class ModelParameterProtocol {
    CODEX,
    ANTHROPIC,
    GEMINI,
    OPENAI_COMPATIBLE,
}

fun modelParameterProtocol(channel: String): ModelParameterProtocol {
    val normalized = channel.trim().lowercase()
    return when {
        "codex" in normalized -> ModelParameterProtocol.CODEX
        "anthropic" in normalized -> ModelParameterProtocol.ANTHROPIC
        "gemini" in normalized -> ModelParameterProtocol.GEMINI
        else -> ModelParameterProtocol.OPENAI_COMPATIBLE
    }
}

fun reasoningBudgetForEffort(effort: String): Int = when (effort.trim().lowercase()) {
    "none", "minimal" -> 0
    "low" -> 1024
    "medium" -> 8192
    "high" -> 24576
    "xhigh", "max" -> 32768
    else -> 8192
}

fun ModelParameters.toThinkingConfig(channel: String, model: String): ThinkingConfig? {
    val protocol = modelParameterProtocol(channel)
    val allowedEfforts = when (protocol) {
        ModelParameterProtocol.CODEX -> setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        ModelParameterProtocol.ANTHROPIC -> setOf("low", "medium", "high", "max")
        ModelParameterProtocol.GEMINI -> setOf("minimal", "low", "medium", "high")
        ModelParameterProtocol.OPENAI_COMPATIBLE -> emptySet()
    }
    val normalizedEffort = reasoningEffort.trim().lowercase().takeIf { it in allowedEfforts }
        ?: DEFAULT_REASONING_EFFORT
    return when (protocol) {
        ModelParameterProtocol.OPENAI_COMPATIBLE -> null
        ModelParameterProtocol.CODEX -> ThinkingConfig(
            includeThoughts = !normalizedEffort.equals("none", ignoreCase = true),
            reasoningMode = ReasoningMode.EFFORT,
            reasoningEffort = normalizedEffort,
        )
        ModelParameterProtocol.ANTHROPIC -> ThinkingConfig(
            includeThoughts = reasoningMode != ReasoningMode.DISABLED,
            thinkingBudget = thinkingBudget.takeIf { reasoningMode == ReasoningMode.BUDGET },
            reasoningMode = reasoningMode,
            reasoningEffort = normalizedEffort,
        )
        ModelParameterProtocol.GEMINI -> {
            val usesThinkingLevel = "gemini-3" in model.lowercase()
            when (reasoningMode) {
                ReasoningMode.DISABLED -> ThinkingConfig(
                    includeThoughts = false,
                    thinkingBudget = 0.takeUnless { usesThinkingLevel },
                    thinkingLevel = "minimal".takeIf { usesThinkingLevel },
                    reasoningMode = reasoningMode,
                    reasoningEffort = normalizedEffort,
                )
                ReasoningMode.BUDGET -> ThinkingConfig(
                    includeThoughts = true,
                    thinkingBudget = thinkingBudget,
                    reasoningMode = reasoningMode,
                    reasoningEffort = normalizedEffort,
                )
                ReasoningMode.EFFORT -> ThinkingConfig(
                    includeThoughts = true,
                    thinkingBudget = reasoningBudgetForEffort(normalizedEffort).takeUnless { usesThinkingLevel },
                    thinkingLevel = normalizedEffort.takeIf { usesThinkingLevel },
                    reasoningMode = reasoningMode,
                    reasoningEffort = normalizedEffort,
                )
            }
        }
    }
}

val defaultOpenAICompatibleParameters: List<CustomModelParameter>
    get() = listOf(
        CustomModelParameter(
            name = "reasoning_effort",
            value = DEFAULT_REASONING_EFFORT,
            type = CustomParameterType.STRING,
        )
    )

val reservedModelParameterNames: Set<String> = setOf(
    "model",
    "messages",
    "input",
    "stream",
    "tools",
    "tool_choice",
    "previous_response_id",
    "max_tokens",
    "max_completion_tokens",
    "max_output_tokens",
)

fun CustomModelParameter.toJsonElement(): JsonElement = when (type) {
    CustomParameterType.STRING -> JsonPrimitive(value)
    CustomParameterType.NUMBER -> {
        val parsed = Json.parseToJsonElement(value.trim())
        require(parsed is JsonPrimitive && !parsed.isString && parsed.content.toDoubleOrNull() != null) {
            "参数 $name 需要填写有效数字"
        }
        parsed
    }
    CustomParameterType.BOOLEAN -> JsonPrimitive(
        value.trim().lowercase().let {
            require(it == "true" || it == "false") { "参数 $name 需要填写 true 或 false" }
            it == "true"
        }
    )
    CustomParameterType.JSON -> Json.parseToJsonElement(value)
}

fun ModelParameters.openAICompatibleRequestParameters(): Map<String, JsonElement> {
    val parameters = customParameters ?: defaultOpenAICompatibleParameters
    val enabledParameters = parameters.filter(CustomModelParameter::enabled)
    val duplicateName = enabledParameters
        .groupBy { it.name.trim().lowercase() }
        .entries
        .firstOrNull { (name, values) -> name.isNotEmpty() && values.size > 1 }
        ?.key
    require(duplicateName == null) { "参数名不能重复：$duplicateName" }

    return buildMap {
        enabledParameters.forEach { parameter ->
            val name = parameter.name.trim()
            require(name.isNotEmpty()) { "参数名不能为空" }
            require(name.lowercase() !in reservedModelParameterNames) { "参数 $name 由应用管理，不能覆盖" }
            put(name, parameter.toJsonElement())
        }
    }
}
