package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.effectiveModelChannel
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.data.network.SystemPromptModules
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.coroutines.flow.collect

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
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                maxOutputTokens = 32,
            ),
        )

        val streamed = StringBuilder()
        var finalText: String? = null
        ApiClient.streamModelTurn(request).collect { event ->
            when (event) {
                is AppStreamEvent.Text -> if (streamed.isEmpty()) streamed.append(event.text)
                is AppStreamEvent.Content -> streamed.append(event.text)
                is AppStreamEvent.ContentFinal -> finalText = event.text
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
