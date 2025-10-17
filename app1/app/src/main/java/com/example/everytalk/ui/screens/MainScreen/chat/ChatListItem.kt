package com.example.everytalk.ui.screens.MainScreen.chat

import com.example.everytalk.data.DataClass.Message

sealed interface ChatListItem {
    val stableId: String

    data class UserMessage(
        val messageId: String,
        val text: String,
        val attachments: List<com.example.everytalk.models.SelectedMediaItem>
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    // 简化的 AI 消息项，直接使用文本内容而不是复杂的 Markdown 块
    data class AiMessage(
        val messageId: String,
        val text: String,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class AiMessageMath(
        val messageId: String,
        val text: String,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val stableId: String = "${messageId}_math"
    }

    data class AiMessageCode(
        val messageId: String,
        val text: String,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val stableId: String = "${messageId}_code"
    }

    // —— 新增：流式渲染专用项（文本/数学/代码）——
    // 仅携带 messageId（在 UI 层通过 StateFlow 收集流式文本），避免在数据层频繁变更 text
    data class AiMessageStreaming(
        val messageId: String,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val stableId: String = "${messageId}_stream"
    }

    data class AiMessageMathStreaming(
        val messageId: String,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val stableId: String = "${messageId}_math_stream"
    }

    data class AiMessageCodeStreaming(
        val messageId: String,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val stableId: String = "${messageId}_code_stream"
    }
    // —— 新增结束 ——


    data class AiMessageReasoning(val message: Message) : ChatListItem {
        override val stableId: String = "${message.id}_reasoning"
    }

    data class AiMessageFooter(val message: Message) : ChatListItem {
        override val stableId: String = "${message.id}_footer"
    }

    data class ErrorMessage(
        val messageId: String,
        val text: String
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class LoadingIndicator(val messageId: String) : ChatListItem {
        override val stableId: String = "${messageId}_loading"
    }
}