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