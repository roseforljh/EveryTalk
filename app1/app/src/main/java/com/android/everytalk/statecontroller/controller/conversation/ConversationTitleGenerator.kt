package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.effectiveModelChannel
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.data.network.SystemPromptModules
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

/**
 * 把自动生成的标题写进内存历史，列表随之刷新；会话已有标题（用户手动改过）或已被删除时不写，返回 null。
 * 返回写入的会话 ID，调用方再落库。
 */
internal fun applyGeneratedConversationTitle(
    history: MutableStateFlow<List<List<Message>>>,
    conversationId: String,
    title: String,
): String? {
    val list = history.value
    val index = list.indexOfFirst { ConversationNameHelper.resolveStableId(it) == conversationId }
    val conversation = list.getOrNull(index) ?: return null
    if (ConversationNameHelper.getStoredConversationTitle(conversation) != null) return null
    val titleMessage = Message(
        id = "title_$conversationId",
        text = title,
        sender = Sender.System,
        timestamp = (conversation.firstOrNull()?.timestamp ?: System.currentTimeMillis()) - 1,
        contentStarted = true,
        isPlaceholderName = true,
        conversationId = conversationId,
    )
    history.value = list.toMutableList().apply { this[index] = listOf(titleMessage) + conversation }
    return conversationId
}

/** 负责把首条用户问题转换为短会话标题；网络失败时由调用方保留原问题作为标题。 */
internal object ConversationTitleGenerator {
    suspend fun generate(prompt: String, config: ApiConfig): String? {
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(
                    id = "conversation-title-system",
                    role = "system",
                    content = SystemPromptModules.conversationTitle,
                ),
                SimpleTextApiMessage(
                    id = "conversation-title-user",
                    role = "user",
                    content = prompt,
                ),
            ),
            provider = config.provider,
            channel = config.effectiveModelChannel(),
            apiAddress = config.address,
            apiKey = config.key,
            model = config.model,
            // 不限输出长度也不改温度：推理模型的思考会吃掉 32 token 的额度导致正文为空，
            // 部分推理模型还会拒绝自定义温度。标题长度由提示词和 normalize 控制。
        )

        val streamed = StringBuilder()
        var finalText: String? = null
        ApiClient.streamModelTurn(request).collect { event ->
            when (event) {
                is AppStreamEvent.Text -> streamed.append(event.text)
                is AppStreamEvent.Content -> streamed.append(event.text)
                is AppStreamEvent.ContentFinal -> finalText = event.text.takeIf(String::isNotBlank)
                is AppStreamEvent.Error -> error(event.message)
                else -> Unit
            }
        }
        return normalize(finalText ?: streamed.toString())
    }

    /** 去掉模型偶尔返回的引号、Markdown 和多余空白，空结果视为生成失败。 */
    internal fun normalize(raw: String): String? {
        val cleaned = raw
            .replace("```", "")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .trim('"', '\'', '“', '”', '‘', '’', '`', '*', '#')
            .trim()
        return cleaned.takeIf(String::isNotBlank)?.let {
            ConversationNameHelper.cleanAndTruncateText(it, 40)
        }
    }
}
