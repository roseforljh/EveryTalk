package com.android.everytalk.ui.screens.MainScreen

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.After
import org.junit.Assert.assertEquals
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
        var appInfoClickCount = 0
        var searchClickCount = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appInfoContentDescription = context.getString(R.string.drawer_app_info_content_description)
        val searchContentDescription = context.getString(R.string.drawer_conversation_search_content_description)

        composeRule.setContent {
            MaterialTheme {
                key(drawerSessionKey) {
                    AppDrawerContent(
                        historicalConversations = conversations,
                        loadedHistoryIndex = null,
                        onConversationSearchClick = { searchClickCount++ },
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
                        onAppInfoClick = { appInfoClickCount++ },
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

        composeRule.onNodeWithContentDescription(searchContentDescription).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, searchClickCount) }
        composeRule.onNodeWithContentDescription(appInfoContentDescription).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, appInfoClickCount) }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(33)
        composeRule.onNodeWithText("Conversation 30").assertIsDisplayed()
        composeRule.onNodeWithText("新建会话").assertIsNotDisplayed()
        composeRule.onNodeWithContentDescription(searchContentDescription).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(appInfoContentDescription).assertIsDisplayed()

        composeRule.runOnUiThread { drawerSessionKey++ }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Conversation 0").assertIsDisplayed()
    }
}
