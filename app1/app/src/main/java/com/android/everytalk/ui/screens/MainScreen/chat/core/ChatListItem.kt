package com.android.everytalk.ui.screens.MainScreen.chat.core

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.ui.components.streaming.PreparedMarkdownDocument
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.android.everytalk.ui.components.streaming.StreamBlock
import org.intellij.markdown.ast.ASTNode

enum class PlaceholderRole {
    User,
    Assistant,
}

sealed interface ChatListItem {
    val stableId: String

    data class UserMessage(
        val messageId: String,
        val text: String,
        val attachments: List<com.android.everytalk.models.SelectedMediaItem>
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class SystemMessage(
        val messageId: String,
        val text: String
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    // 完成态消息携带后台准备的渲染数据，流式消息继续由独立状态实时驱动。
    data class AiMessage(
        val message: Message,
        val messageId: String,
        val text: String,
        val hasReasoning: Boolean,
        val blocksHash: String = "",
        val hasPendingMath: Boolean = false,
        val blocks: List<StreamBlock> = emptyList(),
        val displayText: String = text,
        val pageSources: List<WebSearchResult> = emptyList(),
        val preparedMessage: PreparedMessage? = null,
        val preparedMarkdownDocument: PreparedMarkdownDocument? = null,
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class AiMessageCode(
        val message: Message,
        val messageId: String,
        val text: String,
        val hasReasoning: Boolean,
        val blocks: List<StreamBlock> = emptyList(),
        val displayText: String = text,
        val pageSources: List<WebSearchResult> = emptyList(),
        val preparedMessage: PreparedMessage? = null,
        val preparedMarkdownDocument: PreparedMarkdownDocument? = null,
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class AiMessageSources(
        val message: Message,
        val messageId: String,
        val pageSources: List<WebSearchResult>,
    ) : ChatListItem {
        override val stableId: String = "${messageId}_sources"
    }

    data class AiMarkdownNode(
        val message: Message,
        val messageId: String,
        val preparedMessage: PreparedMessage,
        val preparedMarkdownDocument: PreparedMarkdownDocument,
        val node: ASTNode,
        val nodeIndex: Int,
        val nodeCount: Int,
        val hasSourcesBefore: Boolean,
    ) : ChatListItem {
        override val stableId: String = "${messageId}_markdown_${node.startOffset}_${node.type}"

        val isFirstNode: Boolean
            get() = nodeIndex == 0

        val isLastNode: Boolean
            get() = nodeIndex == nodeCount - 1
    }

    // 新增：流式渲染专用项（文本/数学/代码）
    // 仅携带 messageId（在 UI 层通过 StateFlow 收集流式文本），避免在数据层频繁变更 text
    data class AiMessageStreaming(
        val messageId: String,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val stableId: String = messageId
    }

    data class AiMessageCodeStreaming(
        val messageId: String,
        val hasReasoning: Boolean
    ) : ChatListItem {
        override val stableId: String = messageId
    }
    // 新增结束


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

    data class LoadingIndicator(
        val messageId: String, 
        val text: String? = null,
        val textResId: Int? = null
    ) : ChatListItem {
        override val stableId: String = "${messageId}_loading"
    }

    data class StatusIndicator(
        val messageId: String,
        val text: String
    ) : ChatListItem {
        override val stableId: String = "${messageId}_status"
    }

    data class LoadingBubblePlaceholder(
        val id: String,
        val role: PlaceholderRole,
        val widthFraction: Float,
        val estimatedHeightDp: Int,
    ) : ChatListItem {
        override val stableId: String = id
    }
}

internal fun expandStaticAiMessageItem(item: ChatListItem): List<ChatListItem> {
    val message: Message
    val messageId: String
    val pageSources: List<WebSearchResult>
    val preparedMessage: PreparedMessage
    val preparedMarkdownDocument: PreparedMarkdownDocument

    when (item) {
        is ChatListItem.AiMessage -> {
            message = item.message
            messageId = item.messageId
            pageSources = item.pageSources
            preparedMessage = item.preparedMessage ?: return listOf(item)
            preparedMarkdownDocument = item.preparedMarkdownDocument ?: return listOf(item)
        }

        is ChatListItem.AiMessageCode -> {
            message = item.message
            messageId = item.messageId
            pageSources = item.pageSources
            preparedMessage = item.preparedMessage ?: return listOf(item)
            preparedMarkdownDocument = item.preparedMarkdownDocument ?: return listOf(item)
        }

        else -> return listOf(item)
    }

    if (preparedMarkdownDocument.nodes.isEmpty()) return listOf(item)

    return buildList {
        if (pageSources.isNotEmpty()) {
            add(
                ChatListItem.AiMessageSources(
                    message = message,
                    messageId = messageId,
                    pageSources = pageSources,
                )
            )
        }
        preparedMarkdownDocument.nodes.forEachIndexed { index, node ->
            add(
                ChatListItem.AiMarkdownNode(
                    message = message,
                    messageId = messageId,
                    preparedMessage = preparedMessage,
                    preparedMarkdownDocument = preparedMarkdownDocument,
                    node = node,
                    nodeIndex = index,
                    nodeCount = preparedMarkdownDocument.nodes.size,
                    hasSourcesBefore = pageSources.isNotEmpty(),
                )
            )
        }
    }
}
