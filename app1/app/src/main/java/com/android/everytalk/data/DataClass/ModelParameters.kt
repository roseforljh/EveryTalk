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
    // 只覆盖当前模型发请求时使用的 API 协议，不参与配置分组。
    val apiProtocolOverride: ModelParameterProtocol? = null,
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
        "codex" in normalized || "responses" in normalized -> ModelParameterProtocol.CODEX
        "anthropic" in normalized -> ModelParameterProtocol.ANTHROPIC
        "gemini" in normalized -> ModelParameterProtocol.GEMINI
        else -> ModelParameterProtocol.OPENAI_COMPATIBLE
    }
}

/**
 * 返回模型配置中持久化的协议名称。
 *
 * `ApiConfig.channel` 是历史字段，实际承担 API 协议的职责。所有协议切换统一经过
 * 这个函数，避免界面各自保存不同文案，导致请求路由识别失败。
 */
fun modelParameterChannel(protocol: ModelParameterProtocol): String = when (protocol) {
    ModelParameterProtocol.CODEX -> "Codex"
    ModelParameterProtocol.ANTHROPIC -> "Anthropic"
    ModelParameterProtocol.GEMINI -> "Gemini"
    ModelParameterProtocol.OPENAI_COMPATIBLE -> "OpenAI兼容"
}

fun reasoningBudgetForEffort(effort: String): Int = when (effort.trim().lowercase()) {
    "none", "minimal" -> 0
    "low" -> 1024
    "medium" -> 8192
    "high" -> 24576
    "xhigh", "max" -> 32768
    else -> 8192
}

/**
 * 思考选项属于所选 API 协议，界面与请求转换共用这份选项。
 * 这里只定义协议参数，不维护模型规格，也不让远程规格目录裁剪用户可选的等级。
 */
fun thinkingLevelOptions(protocol: ModelParameterProtocol): List<String> = when (protocol) {
    ModelParameterProtocol.CODEX -> listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
    ModelParameterProtocol.ANTHROPIC -> listOf("none", "low", "medium", "high", "max")
    ModelParameterProtocol.GEMINI -> listOf("none", "minimal", "low", "medium", "high")
    ModelParameterProtocol.OPENAI_COMPATIBLE -> listOf("none", "low", "medium", "high", "xhigh", "max")
}

/** 按当前接口协议转换用户设置；目录能力缺失或变更不能吞掉已选的思考参数。 */
fun ModelParameters.toThinkingConfig(channel: String, model: String): ThinkingConfig? {
    val protocol = modelParameterProtocol(channel)
    val normalizedEffort = reasoningEffort.trim().lowercase().takeIf { it in thinkingLevelOptions(protocol) }
        ?: DEFAULT_REASONING_EFFORT
    return when (protocol) {
        ModelParameterProtocol.OPENAI_COMPATIBLE -> null
        ModelParameterProtocol.CODEX -> ThinkingConfig(
            includeThoughts = reasoningMode != ReasoningMode.DISABLED && normalizedEffort != "none",
            reasoningMode = ReasoningMode.EFFORT,
            reasoningEffort = if (reasoningMode == ReasoningMode.DISABLED) "none" else normalizedEffort,
        )
        ModelParameterProtocol.ANTHROPIC -> when {
            reasoningMode == ReasoningMode.DISABLED || normalizedEffort == "none" -> null
            else -> ThinkingConfig(
                includeThoughts = true,
                thinkingBudget = thinkingBudget.takeIf { reasoningMode == ReasoningMode.BUDGET },
                reasoningMode = reasoningMode,
                reasoningEffort = normalizedEffort,
            )
        }
        ModelParameterProtocol.GEMINI -> {
            // 保留 Gemini 不同版本的字段兼容：3 使用 level，旧版本使用 budget。
            // 这里只转换接口字段，不据此推断或限制思考选项。
            val usesThinkingLevel = "gemini-3" in model.lowercase()
            when (if (normalizedEffort == "none") ReasoningMode.DISABLED else reasoningMode) {
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
    // 与兼容接口的界面默认值一致，不读取模型目录。显式空列表和用户私有参数原样保留。
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
