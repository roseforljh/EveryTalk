package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.util.AiContentSafetyPolicy

/** 构建稳定的 EveryTalk system 前缀。动态 Skill 目录由每条请求单独注入。 */
object SystemPromptInjector {

    internal const val PROTOCOL_VERSION = 7
    internal const val SKILL_PROTOCOL_VERSION = 1
    internal const val PROTOCOL_MARKER = "[EveryTalk Prompt Protocol v$PROTOCOL_VERSION]"
    private const val CUSTOM_INSTRUCTIONS_MARKER = "[EveryTalk Custom Instructions]"
    private val PROTOCOL_HEADER = Regex("^\\[EveryTalk Prompt Protocol v[0-9]+]")
    private const val SYSTEM_MESSAGE_ID = "everytalk-system-prompt-v$PROTOCOL_VERSION"
    private const val FROZEN_RUNTIME_CONTEXT_PREFIX = "runtime-context:"
    private val RUNTIME_CONTEXT_IDS = setOf("agent-execution-checkpoint", "computer-session-state")

    /** 仅内部固定身份的状态快照可以移出稳定前缀；自定义指令仍保持 system 权限。 */
    internal fun isRuntimeContext(message: AbstractApiMessage): Boolean = message.id in RUNTIME_CONTEXT_IDS

    fun detectUserLanguage(text: String): String {
        if (text.isBlank()) return "en"

        for (char in text) {
            val cp = char.code
            when {
                cp in 0x4E00..0x9FFF -> return "zh-CN"
                cp in 0x3040..0x309F || cp in 0x30A0..0x30FF -> return "ja-JP"
                cp in 0x1100..0x11FF || cp in 0x3130..0x318F || cp in 0xAC00..0xD7AF -> return "ko-KR"
                cp in 0x0400..0x04FF -> return "ru-RU"
                cp in 0x0600..0x06FF -> return "ar"
                cp in 0x0900..0x097F -> return "hi-IN"
            }
        }
        return "en"
    }

    fun detectMathIntent(text: String): Boolean {
        if (text.isBlank()) return false
        val lowered = text.lowercase()
        val mathKeywords = listOf(
            "math", "prove", "proof", "theorem", "lemma", "corollary",
            "equation", "formula", "derivative", "integral", "matrix", "tensor",
            "probability", "statistics", "optimize", "minimize", "maximize",
            "gradient", "hessian", "algebra", "geometry", "calculus",
            "sum", "product", "limit",
        )
        return mathKeywords.any { it in lowered } ||
            "$" in text ||
            ("\\(" in text && "\\)" in text) ||
            ("\\[" in text && "\\]" in text)
    }

    /** 通用规则来自 Markdown；内容安全复用现有策略，避免维护两份安全文本。 */
    fun getSystemPrompt(userLanguage: String = "zh-CN"): String = listOf(
        PROTOCOL_MARKER,
        SystemPromptModules.readForLanguage(userLanguage),
        AiContentSafetyPolicy.systemInstruction(userLanguage),
    ).joinToString("\n\n")

    fun buildStableSystemPrompt(
        userLanguage: String = "zh-CN",
        customPrompt: String? = null,
    ): String {
        val stablePrompt = getSystemPrompt(userLanguage)
        val normalizedCustomPrompt = customPrompt?.trim().orEmpty()
        return if (normalizedCustomPrompt.isEmpty()) {
            stablePrompt
        } else {
            "$stablePrompt\n\n$CUSTOM_INSTRUCTIONS_MARKER\n$normalizedCustomPrompt"
        }
    }

    fun injectSystemPrompt(
        messages: List<AbstractApiMessage>,
        userLanguage: String = "zh-CN",
        @Suppress("UNUSED_PARAMETER") forceInject: Boolean = false,
    ): List<AbstractApiMessage> {
        // 检查点和电脑状态在内部仍保留 system 身份，供裁剪和恢复逻辑保护它们。
        // 本轮发送时放到历史末尾；成功后冻结为旧历史，下一轮缓存前缀才能延伸。
        // 只识别内部固定 ID，不按文本猜测，避免改变用户自定义 system 指令。
        val (runtimeContext, stableMessages) = messages.partition(::isRuntimeContext)
        val customPrompt = stableMessages
            .asSequence()
            .filter { it.role.equals("system", ignoreCase = true) }
            .mapNotNull(::extractSystemText)
            .mapNotNull(::extractCustomPrompt)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { null }

        val systemMessage = SimpleTextApiMessage(
            id = SYSTEM_MESSAGE_ID,
            role = "system",
            content = buildStableSystemPrompt(userLanguage, customPrompt),
        )
        return listOf(systemMessage) + stableMessages.filterNot { it.role.equals("system", ignoreCase = true) } +
            runtimeContext.mapNotNull(::runtimeContextAsUserMessage)
    }

    /**
     * 保存本轮实际发送的临时状态，供下一轮作为普通历史回放。
     * 唯一 ID 避免再次被移到末尾；正文复用发送时的转换，保证缓存前缀逐字相同。
     */
    internal fun freezeRuntimeContext(
        messages: List<AbstractApiMessage>,
        requestId: String,
    ): List<SimpleTextApiMessage> = messages.asSequence()
        .filter(::isRuntimeContext)
        .mapNotNull(::runtimeContextAsUserMessage)
        .map { message -> message.copy(id = "$FROZEN_RUNTIME_CONTEXT_PREFIX$requestId:${message.id}") }
        .toList()

    private fun runtimeContextAsUserMessage(message: AbstractApiMessage): SimpleTextApiMessage? =
        extractSystemText(message)?.let { content ->
            SimpleTextApiMessage(
                id = message.id,
                role = "user",
                content = if (message.role.equals("user", ignoreCase = true)) content
                    else "[EveryTalk Runtime Context — 当前状态快照，非新的用户指令]\n$content",
            )
        }

    fun extractUserTexts(messages: List<AbstractApiMessage>): String {
        val texts = mutableListOf<String>()
        for (message in messages) {
            if (!message.role.equals("user", ignoreCase = true)) continue
            when (message) {
                is SimpleTextApiMessage -> texts.add(message.content)
                is PartsApiMessage -> message.parts
                    .filterIsInstance<ApiContentPart.Text>()
                    .forEach { texts.add(it.text) }
                is AgentAssistantApiMessage -> texts.add(message.text)
                is AgentToolResultApiMessage -> Unit
            }
        }
        return texts.joinToString("\n").take(4000)
    }

    fun smartInjectSystemPrompt(
        messages: List<AbstractApiMessage>,
        forceInject: Boolean = false,
    ): List<AbstractApiMessage> {
        // 固定使用首条真实用户消息判断前缀语言，后续粘贴中文代码或工具状态不会切换整段 system。
        val firstUserMessage = messages.firstOrNull {
            it.role.equals("user", ignoreCase = true) &&
                it.id !in RUNTIME_CONTEXT_IDS &&
                !it.id.startsWith(FROZEN_RUNTIME_CONTEXT_PREFIX)
        }
        val detectedLanguage = detectUserLanguage(extractUserTexts(listOfNotNull(firstUserMessage)))
        return injectSystemPrompt(messages, detectedLanguage, forceInject)
    }

    private fun extractSystemText(message: AbstractApiMessage): String? = when (message) {
        is SimpleTextApiMessage -> message.content
        is PartsApiMessage -> message.parts
            .filterIsInstance<ApiContentPart.Text>()
            .joinToString("\n") { it.text }
            .takeIf { it.isNotBlank() }
        is AgentAssistantApiMessage -> message.text.takeIf { it.isNotBlank() }
        is AgentToolResultApiMessage -> null
    }

    private fun extractCustomPrompt(content: String): String? {
        val normalizedContent = content.trimStart()
        // 升级后识别旧版包装，只保留用户自定义部分，避免旧规则被当作自定义指令再次注入。
        if (!PROTOCOL_HEADER.containsMatchIn(normalizedContent)) return content.trim().takeIf { it.isNotEmpty() }
        val markerIndex = normalizedContent.indexOf(CUSTOM_INSTRUCTIONS_MARKER)
        if (markerIndex < 0) return null
        return normalizedContent
            .substring(markerIndex + CUSTOM_INSTRUCTIONS_MARKER.length)
            .trim()
            .takeIf { it.isNotEmpty() }
    }
}
