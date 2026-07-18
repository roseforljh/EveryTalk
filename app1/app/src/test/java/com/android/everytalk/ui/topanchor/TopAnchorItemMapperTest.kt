package com.android.everytalk.ui.topanchor

import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.PlaceholderRole
import org.junit.Assert.assertEquals
import org.junit.Test

class TopAnchorItemMapperTest {
    @Test
    fun `maps assistant loading and status as targets`() {
        val items = mapChatItemsToTopAnchorItems(
            items = listOf(
                ChatListItem.UserMessage("u1", "hi", emptyList()),
                ChatListItem.LoadingIndicator("a1"),
                ChatListItem.StatusIndicator("a1", "working")
            ),
            resolveErrorSender = { null }
        )

        assertEquals(TopAnchorItemRole.User, items[0].role)
        assertEquals(TopAnchorItemRole.LoadingTarget, items[1].role)
        assertEquals(TopAnchorItemRole.StatusTarget, items[2].role)
    }

    @Test
    fun `maps assistant placeholder as loading target and user placeholder as user`() {
        val items = mapChatItemsToTopAnchorItems(
            items = listOf(
                ChatListItem.LoadingBubblePlaceholder("u", PlaceholderRole.User, 0.5f, 80),
                ChatListItem.LoadingBubblePlaceholder("a", PlaceholderRole.Assistant, 0.5f, 80)
            ),
            resolveErrorSender = { null }
        )

        assertEquals(TopAnchorItemRole.User, items[0].role)
        assertEquals(TopAnchorItemRole.LoadingTarget, items[1].role)
    }

    @Test
    fun `maps error by sender`() {
        val items = mapChatItemsToTopAnchorItems(
            items = listOf(
                ChatListItem.ErrorMessage("u1", "bad"),
                ChatListItem.ErrorMessage("a1", "bad")
            ),
            resolveErrorSender = {
                if (it == "u1") Sender.User else Sender.AI
            }
        )

        assertEquals(TopAnchorItemRole.User, items[0].role)
        assertEquals(TopAnchorItemRole.AssistantTarget, items[1].role)
    }

    @Test
    fun `maps system message as non target`() {
        val items = mapChatItemsToTopAnchorItems(
            items = listOf(ChatListItem.SystemMessage("sys", "notice")),
            resolveErrorSender = { null }
        )

        assertEquals(TopAnchorItemRole.NonTarget, items.single().role)
    }
}
