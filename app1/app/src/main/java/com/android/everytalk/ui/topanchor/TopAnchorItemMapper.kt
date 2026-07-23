package com.android.everytalk.ui.topanchor

import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.PlaceholderRole

fun mapChatItemsToTopAnchorItems(
    items: List<ChatListItem>,
    resolveErrorSender: (messageId: String) -> Sender?
): List<TopAnchorItem> {
    return items.map { item ->
        TopAnchorItem(
            id = item.stableId,
            role = when (item) {
                is ChatListItem.UserMessage -> TopAnchorItemRole.User
                is ChatListItem.AiMessage,
                is ChatListItem.AiMessageStreaming,
                is ChatListItem.AiMessageCode,
                is ChatListItem.AiMessageCodeStreaming,
                is ChatListItem.AiMessageSources,
                is ChatListItem.AiMarkdownNode,
                is ChatListItem.AiMessageReasoning,
                is ChatListItem.AiMessageFooter -> TopAnchorItemRole.AssistantTarget
                is ChatListItem.LoadingIndicator -> TopAnchorItemRole.LoadingTarget
                is ChatListItem.StatusIndicator -> TopAnchorItemRole.StatusTarget
                is ChatListItem.LoadingBubblePlaceholder -> {
                    if (item.role == PlaceholderRole.User) {
                        TopAnchorItemRole.User
                    } else {
                        TopAnchorItemRole.LoadingTarget
                    }
                }
                is ChatListItem.ErrorMessage -> {
                    if (resolveErrorSender(item.messageId) == Sender.User) {
                        TopAnchorItemRole.User
                    } else {
                        TopAnchorItemRole.AssistantTarget
                    }
                }
                is ChatListItem.SystemMessage -> TopAnchorItemRole.NonTarget
            }
        )
    }
}
