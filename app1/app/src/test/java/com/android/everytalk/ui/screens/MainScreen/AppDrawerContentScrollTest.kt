package com.android.everytalk.ui.screens.MainScreen

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppDrawerContentScrollTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `history list state resets when drawer content is recreated`() {
        stopKoin()
        val conversations = List(40) { index ->
            listOf(Message(id = "message_$index", text = "Message $index", sender = Sender.User))
        }
        var drawerSessionKey by mutableIntStateOf(0)

        composeRule.setContent {
            MaterialTheme {
                key(drawerSessionKey) {
                    AppDrawerContent(
                        historicalConversations = conversations,
                        loadedHistoryIndex = null,
                        isSearchActive = false,
                        currentSearchQuery = "",
                        onSearchActiveChange = {},
                        onSearchQueryChange = {},
                        onConversationClick = {},
                        onImageGenerationConversationClick = {},
                        onNewChatClick = {},
                        onRenameRequest = { _, _ -> },
                        onDeleteRequest = {},
                        onClearAllConversationsRequest = {},
                        onClearAllImageGenerationConversationsRequest = {},
                        showClearImageHistoryDialog = false,
                        onShowClearImageHistoryDialog = {},
                        onDismissClearImageHistoryDialog = {},
                        getPreviewForIndex = { index -> "Conversation $index" },
                        getFullTextForIndex = { index -> "Conversation $index" },
                        onAboutClick = {},
                        onImageGenerationClick = {},
                        isLoadingHistoryData = false,
                        isImageGenerationMode = false,
                        expandedItemIndex = null,
                        onExpandItem = {},
                        pinnedIds = emptySet(),
                        onTogglePin = {},
                        conversationGroups = emptyMap(),
                        onCreateGroup = {},
                        onRenameGroup = { _, _ -> },
                        onDeleteGroup = {},
                        onMoveConversationToGroup = { _, _, _ -> },
                        expandedGroups = emptySet(),
                        onToggleGroup = {},
                        modifier = Modifier.height(640.dp),
                    )
                }
            }
        }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        composeRule.onNodeWithText("Conversation 30").assertIsDisplayed()

        composeRule.runOnUiThread { drawerSessionKey++ }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Conversation 0").assertIsDisplayed()
    }
}
