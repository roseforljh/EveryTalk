package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.util.AiContentSafetyPolicy

/** 构建稳定的 EveryTalk system 前缀。动态段落（自定义指令、Skill、MCP、环境）由每条请求在后面追加。 */
object SystemPromptInjector {

    internal const val PROTOCOL_VERSION = 8
    internal const val SKILL_PROTOCOL_VERSION = 1
    internal const val PROTOCOL_MARKER = "[EveryTalk Prompt Protocol v$PROTOCOL_VERSION]"
    /** 稳定前缀与本次请求段落的分界；旧版标记仍识别，升级后旧会话的自定义指令不丢。 */
    private const val REQUEST_CONTEXT_MARKER = "[EveryTalk Request Context]"
    private val LEGACY_CUSTOM_MARKERS = listOf(REQUEST_CONTEXT_MARKER, "[EveryTalk Custom Instructions]")
    private val PROTOCOL_HEADER = Regex("^\\[EveryTalk Prompt Protocol v[0-9]+]")
    private const val SYSTEM_MESSAGE_ID = "everytalk-system-prompt-v$PROTOCOL_VERSION"
    private const val FROZEN_RUNTIME_CONTEXT_PREFIX = "runtime-context:"
    private val RUNTIME_CONTEXT_IDS = setOf("agent-execution-checkpoint", "computer-session-state")

    /** 仅内部固定身份的状态快照可以移出稳定前缀；自定义指令仍保持 system 权限。 */
    internal fun isRuntimeContext(message: AbstractApiMessage): Boolean = message.id in RUNTIME_CONTEXT_IDS

    /** 规则只有一份英文；内容安全复用现有策略，避免维护两份安全文本。 */
    fun getSystemPrompt(): String = listOf(
        PROTOCOL_MARKER,
        SystemPromptModules.base,
        SystemPromptModules.section("safety", AiContentSafetyPolicy.systemInstruction("en")),
    ).joinToString("\n\n")

    fun buildStableSystemPrompt(customPrompt: String? = null): String {
        val stablePrompt = getSystemPrompt()
        val normalizedCustomPrompt = customPrompt?.trim().orEmpty()
        return if (normalizedCustomPrompt.isEmpty()) {
            stablePrompt
        } else {
            "$stablePrompt\n\n$REQUEST_CONTEXT_MARKER\n$normalizedCustomPrompt"
        }
    }

    fun injectSystemPrompt(
        messages: List<AbstractApiMessage>,
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
            content = buildStableSystemPrompt(customPrompt),
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
                    else "[EveryTalk Runtime Context — current state snapshot, not a new user instruction]\n$content",
            )
        }

    /** 稳定前缀不随提问语言或内容变化；回复语言由规则跟随用户最新消息决定。 */
    fun smartInjectSystemPrompt(messages: List<AbstractApiMessage>): List<AbstractApiMessage> = injectSystemPrompt(messages)

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
        val marker = LEGACY_CUSTOM_MARKERS.firstOrNull { it in normalizedContent } ?: return null
        return normalizedContent
            .substring(normalizedContent.indexOf(marker) + marker.length)
            .trim()
            .takeIf { it.isNotEmpty() }
    }
}
