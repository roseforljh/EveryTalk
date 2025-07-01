package com.example.everytalk.ui.screens.MainScreen.chat

import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.util.MarkdownBlock


sealed interface ChatListItem {
    val stableId: String

    data class UserMessage(
        val messageId: String,
        val text: String,
        val attachments: List<com.example.everytalk.model.SelectedMediaItem>
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class AiMessageBlock(
        val messageId: String,
        val block: MarkdownBlock,
        val blockIndex: Int,
        val isFirstBlock: Boolean,
        val isLastBlock: Boolean,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val stableId: String = "${messageId}_block_$blockIndex"
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