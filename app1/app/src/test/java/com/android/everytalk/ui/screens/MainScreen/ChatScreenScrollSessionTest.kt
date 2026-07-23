package com.android.everytalk.ui.screens.MainScreen

import androidx.compose.ui.graphics.Color
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.everyTalkLoadingIndicatorColor
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.viewmodel.resolveHistoryExpectedStableConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatScreenScrollSessionTest {

    @Test
    fun `shared loading indicator uses strict black and white theme colors`() {
        assertEquals(Color.Black, everyTalkLoadingIndicatorColor(isDarkTheme = false))
        assertEquals(Color.White, everyTalkLoadingIndicatorColor(isDarkTheme = true))
    }

    @Test
    fun `history loading overlay remains until content layout is ready`() {
        assertTrue(
            shouldShowHistoryConversationLoadingOverlay(
                isLoadingHistory = true,
                overlayKey = "conversation-a",
                observedLoadGeneration = 1L,
                currentLoadGeneration = 1L,
            )
        )
        assertTrue(
            shouldShowHistoryConversationLoadingOverlay(
                isLoadingHistory = false,
                overlayKey = "conversation-a",
                observedLoadGeneration = 1L,
                currentLoadGeneration = 1L,
            )
        )
        assertFalse(
            shouldShowHistoryConversationLoadingOverlay(
                isLoadingHistory = false,
                overlayKey = null,
                observedLoadGeneration = 1L,
                currentLoadGeneration = 1L,
            )
        )
    }

    @Test
    fun `initial conversation loading overlay remains until the first real bottom is shown`() {
        assertTrue(
            shouldShowInitialConversationLoadingOverlay(
                isHistoryLoadingOverlayVisible = false,
                initialScrollHandled = false,
            )
        )
        assertTrue(
            shouldShowInitialConversationLoadingOverlay(
                isHistoryLoadingOverlayVisible = true,
                initialScrollHandled = true,
            )
        )
        assertFalse(
            shouldShowInitialConversationLoadingOverlay(
                isHistoryLoadingOverlayVisible = false,
                initialScrollHandled = true,
            )
        )
    }

    @Test
    fun `persistent load generation catches a fast loading pulse`() {
        assertTrue(
            shouldStartHistoryConversationLoadingOverlay(
                observedLoadGeneration = 7L,
                currentLoadGeneration = 8L,
            )
        )
        assertTrue(
            shouldShowHistoryConversationLoadingOverlay(
                isLoadingHistory = false,
                overlayKey = null,
                observedLoadGeneration = 7L,
                currentLoadGeneration = 8L,
            )
        )
    }

    @Test
    fun `history initial bottom waits for current conversation items instead of stale equal sized list`() {
        val currentMessages = listOf(
            Message(id = "new-user", text = "新会话", sender = Sender.User),
            Message(id = "new-ai", text = "新回复", sender = Sender.AI, contentStarted = true),
        )
        val staleItems = listOf(
            ChatListItem.UserMessage("old-user", "旧会话", emptyList()),
            ChatListItem.AiMessageFooter(
                Message(id = "old-ai", text = "旧回复", sender = Sender.AI, contentStarted = true)
            ),
        )

        assertFalse(
            isHistoryConversationReadyForInitialBottom(
                currentConversationId = "new-user",
                scrollSessionKey = "new-user",
                isLoadingHistory = false,
                messages = currentMessages,
                chatItems = staleItems,
                laidOutItemCount = staleItems.size,
            )
        )
    }

    @Test
    fun `history initial bottom waits for new lazy list session then accepts synchronized items`() {
        val currentMessages = listOf(
            Message(id = "new-user", text = "新会话", sender = Sender.User),
            Message(id = "new-ai", text = "新回复", sender = Sender.AI, contentStarted = true),
        )
        val currentItems = listOf(
            ChatListItem.UserMessage("new-user", "新会话", emptyList()),
            ChatListItem.AiMessageFooter(currentMessages.last()),
        )

        assertFalse(
            isHistoryConversationReadyForInitialBottom(
                currentConversationId = "new-user",
                scrollSessionKey = "old-user",
                isLoadingHistory = false,
                messages = currentMessages,
                chatItems = currentItems,
                laidOutItemCount = currentItems.size,
            )
        )
        assertTrue(
            isHistoryConversationReadyForInitialBottom(
                currentConversationId = "new-user",
                scrollSessionKey = "new-user",
                isLoadingHistory = false,
                messages = currentMessages,
                chatItems = currentItems,
                laidOutItemCount = currentItems.size,
            )
        )
    }

    @Test
    fun `conversation content can be ready before the message list is composed`() {
        val messages = listOf(
            Message(id = "ready-user", text = "已准备", sender = Sender.User),
        )
        val items = listOf(
            ChatListItem.UserMessage("ready-user", "已准备", emptyList()),
        )

        assertTrue(
            isHistoryConversationReadyForInitialBottom(
                currentConversationId = "ready-user",
                scrollSessionKey = "ready-user",
                isLoadingHistory = false,
                messages = messages,
                chatItems = items,
                laidOutItemCount = 0,
                requireLaidOutItemCount = false,
            )
        )
    }

    @Test
    fun `startup hydration cannot finish initial bottom on the temporary empty conversation`() {
        assertFalse(
            isHistoryConversationReadyForInitialBottom(
                currentConversationId = "new_chat_startup",
                scrollSessionKey = "new_chat_startup",
                isLoadingHistory = false,
                isLoadingHistoryData = true,
                requireMatchingScrollSession = false,
                messages = emptyList(),
                chatItems = emptyList(),
                laidOutItemCount = 0,
            )
        )
    }

    @Test
    fun `startup last open conversation waits for rendered items then reaches bottom once`() {
        val messages = listOf(
            Message(id = "restored-user", text = "恢复会话", sender = Sender.User),
            Message(id = "restored-ai", text = "恢复回复", sender = Sender.AI, contentStarted = true),
        )
        val items = listOf(
            ChatListItem.UserMessage("restored-user", "恢复会话", emptyList()),
            ChatListItem.AiMessageFooter(messages.last()),
        )

        assertFalse(
            isHistoryConversationReadyForInitialBottom(
                currentConversationId = "restored-user",
                scrollSessionKey = "new_chat_startup",
                isLoadingHistory = false,
                isLoadingHistoryData = false,
                requireMatchingScrollSession = false,
                messages = messages,
                chatItems = emptyList(),
                laidOutItemCount = 0,
            )
        )
        assertTrue(
            isHistoryConversationReadyForInitialBottom(
                currentConversationId = "restored-user",
                scrollSessionKey = "new_chat_startup",
                isLoadingHistory = false,
                isLoadingHistoryData = false,
                requireMatchingScrollSession = false,
                messages = messages,
                chatItems = items,
                laidOutItemCount = items.size,
            )
        )
    }

    @Test
    fun `completed history load cannot clear a newer loading overlay`() {
        assertFalse(
            shouldClearHistoryLoadingOverlay(
                completedGeneration = 8L,
                currentGeneration = 9L,
                completedOverlayKey = "history-load-8",
                currentOverlayKey = "history-load-9",
            )
        )
        assertTrue(
            shouldClearHistoryLoadingOverlay(
                completedGeneration = 9L,
                currentGeneration = 9L,
                completedOverlayKey = "history-load-9",
                currentOverlayKey = "history-load-9",
            )
        )
    }

    @Test
    fun `preserves scroll session only when temporary conversation id migrates to first user message id`() {
        val stableConversationId = "user_message_1"
        val messages = listOf(
            Message(
                id = stableConversationId,
                text = "hello",
                sender = Sender.User
            )
        )

        listOf("new_chat_123", "chat_123", "user_temp_123").forEach { temporaryId ->
            val result = shouldPreserveScrollSessionOnConversationIdChange(
                previousConversationId = temporaryId,
                newConversationId = stableConversationId,
                messages = messages
            )

            assertTrue("临时会话 ID $temporaryId 应保留滚动会话", result)
        }
    }

    @Test
    fun `history conversation switch never preserves previous lazy list session`() {
        val stableConversationId = "history-user-message"
        val messages = listOf(
            Message(
                id = stableConversationId,
                text = "历史会话",
                sender = Sender.User
            )
        )

        val result = shouldPreserveScrollSessionOnConversationIdChange(
            previousConversationId = "previous-user-message",
            newConversationId = stableConversationId,
            messages = messages,
        )

        assertFalse(result)
    }

    @Test
    fun `history load from temporary empty conversation still creates a new scroll session`() {
        val stableConversationId = "history-user-message"
        val messages = listOf(
            Message(
                id = stableConversationId,
                text = "历史会话",
                sender = Sender.User,
            )
        )

        listOf("new_chat_123", "chat_123", "user_temp_123").forEach { temporaryId ->
            assertFalse(
                shouldPreserveScrollSessionOnConversationIdChange(
                    previousConversationId = temporaryId,
                    newConversationId = stableConversationId,
                    messages = messages,
                    isHistoryConversationLoad = true,
                )
            )
        }
    }

    @Test
    fun `initial bottom handling follows scroll session instead of mutable conversation id`() {
        val source = chatScreenSource()

        assertTrue(source.contains("var initialScrollHandled by remember(scrollSessionKey)"))
        assertTrue(source.contains("LaunchedEffect(scrollSessionKey, historyLoadGeneration)"))
        assertTrue(source.contains("LaunchedEffect(scrollSessionKey, listState, initialReadyToken)"))
        assertTrue(source.contains("val isLoadingHistoryDataState = rememberUpdatedState(isLoadingHistoryData)"))
        assertTrue(source.contains("scrollStateManager.pinToRealBottomUntilUserScroll()"))
        assertFalse(source.contains("scrollStateManager.settleToRealBottomForInitialLoad()"))
        assertTrue(source.contains("isInitialConversationLoading = shouldShowInitialConversationLoadingOverlay("))
        assertTrue(source.contains("val initialContentReady = initialListIndex != null"))
        assertTrue(source.contains("LazyListState(firstVisibleItemIndex = initialListIndex ?: 0)"))
        assertTrue(source.contains("requireLaidOutItemCount = false"))
        assertTrue(source.contains("!initialContentReady ->"))
        assertFalse(source.contains("val restoredScrollState = remember(scrollSessionKey)"))
        assertFalse(source.contains("if (savedState != null)"))
        assertFalse(source.contains("var initialScrollHandled by remember(conversationId)"))
        assertFalse(source.contains("LaunchedEffect(conversationId, scrollSessionKey, listState)"))
        assertFalse(source.contains("lastSendAt"))
    }

    @Test
    fun `startup history loading flag spans last open restoration`() {
        val source = dataPersistenceSource()
        val loadingStartedAt = source.indexOf("stateHolder._isLoadingHistoryData.value = true")
        val outerLaunchAt = source.indexOf("viewModelScope.launch(Dispatchers.IO)", loadingStartedAt)
        val historyJobAt = source.indexOf("historyLoadingJob = launch", outerLaunchAt)
        val historyJoinAt = source.indexOf("historyLoadingJob.join()", historyJobAt)
        val loadingFinishedAt = source.lastIndexOf("stateHolder._isLoadingHistoryData.value = false")

        assertTrue(loadingStartedAt >= 0)
        assertTrue(loadingStartedAt < outerLaunchAt)
        assertTrue(historyJobAt > outerLaunchAt)
        assertTrue(historyJoinAt > historyJobAt)
        assertTrue(loadingFinishedAt > historyJoinAt)
        assertTrue(source.contains("historyLoadingJob?.cancelAndJoin()"))
        assertTrue(source.contains("ConversationNameHelper.resolveStableId(loadedMessages)"))
        assertEquals(loadingStartedAt, source.lastIndexOf("stateHolder._isLoadingHistoryData.value = true"))
        assertEquals(loadingFinishedAt, source.indexOf("stateHolder._isLoadingHistoryData.value = false"))
    }

    @Test
    fun `does not preserve scroll session for unrelated conversation switches`() {
        val messages = listOf(
            Message(
                id = "user_message_1",
                text = "hello",
                sender = Sender.User
            )
        )

        val result = shouldPreserveScrollSessionOnConversationIdChange(
            previousConversationId = "conversation_old",
            newConversationId = "conversation_new",
            messages = messages
        )

        assertFalse(result)
    }

    @Test
    fun `image regeneration keeps loaded conversation stable id instead of new first message id`() {
        val stableId = resolveHistoryExpectedStableConversationId(
            isImageGeneration = true,
            loadedHistoryIndex = 2,
            currentConversationId = "original_user_id",
            stableIdFromMessages = "new_regenerated_user_id"
        )

        assertEquals("original_user_id", stableId)
    }

    @Test
    fun `new image conversation still migrates to first message stable id`() {
        val stableId = resolveHistoryExpectedStableConversationId(
            isImageGeneration = true,
            loadedHistoryIndex = null,
            currentConversationId = "new_image_generation_123",
            stableIdFromMessages = "first_user_id"
        )

        assertEquals("first_user_id", stableId)
    }

    @Test
    fun `explicit stop cancels api and scrolls conversation to real bottom`() {
        val source = chatScreenSource()
        val stopHandler = source
            .substringAfter("onStopApiCall = {")
            .substringBefore("},")

        assertTrue(stopHandler.contains("viewModel.onCancelAPICall()"))
        assertTrue(stopHandler.contains("scrollStateManager.stopStreamingAndJumpToRealBottom()"))
    }

    private fun chatScreenSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/MainScreen/ChatScreen.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/MainScreen/ChatScreen.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/MainScreen/ChatScreen.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 ChatScreen.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }

    private fun dataPersistenceSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/viewmodel/DataPersistenceManager.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/viewmodel/DataPersistenceManager.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/viewmodel/DataPersistenceManager.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 DataPersistenceManager.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }
}
