package io.github.roseforljh.kuntalk.ui.screens.MainScreen.chat

import io.github.roseforljh.kuntalk.data.DataClass.Message
import io.github.roseforljh.kuntalk.util.MarkdownBlock

/**
 * A sealed interface representing all possible item types in the chat list.
 * This allows LazyColumn to handle different UI components for different parts of a message,
 * enabling virtualization for long messages.
 */
sealed interface ChatListItem {
    /**
     * A unique identifier for each item, used as a key in LazyColumn.
     */
    val key: String

    /**
     * Represents a complete message from a user.
     */
    data class UserMessage(val message: Message) : ChatListItem {
        override val key: String = message.id
    }

    /**
     * Represents a block of content within an AI message (e.g., a paragraph, a code block).
     * Each block is a separate item in the list.
     */
    data class AiMessageBlock(
        val messageId: String,
        val block: MarkdownBlock,
        val blockIndex: Int,
        val isFirstBlock: Boolean,
        val isLastBlock: Boolean,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val key: String = "${messageId}_block_$blockIndex"
    }

    /**
     * Represents the reasoning/thought process part of an AI message.
     */
    data class AiMessageReasoning(val message: Message) : ChatListItem {
        override val key: String = "${message.id}_reasoning"
    }

    /**
     * Represents the footer of an AI message, which might contain actions like "view sources".
     */
    data class AiMessageFooter(val message: Message) : ChatListItem {
        override val key: String = "${message.id}_footer"
    }

    /**
     * Represents a message that resulted in an error.
     */
    data class ErrorMessage(val message: Message) : ChatListItem {
        override val key: String = message.id
    }

    /**
     * Represents a loading indicator, for example, when waiting for an AI response.
     */
    data class LoadingIndicator(val messageId: String) : ChatListItem {
        override val key: String = "${messageId}_loading"
    }
}
