package com.android.everytalk.ui.screens.MainScreen

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// 只验证侧栏界面，不启动真实应用的后台同步任务。
@Config(sdk = [34], application = android.app.Application::class)
class AppDrawerContentScrollTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `重新打开侧栏保留选中会话的位置和像素偏移`() {
        stopKoin()
        val conversations = List(40) { index ->
            listOf(Message(id = "message_$index", text = "Message $index", sender = Sender.User))
        }
        var drawerSessionKey by mutableIntStateOf(0)
        var loadedHistoryIndex by mutableStateOf<Int?>(null)
        lateinit var drawerListState: LazyListState
        lateinit var scope: CoroutineScope
        var appInfoClickCount = 0
        var searchClickCount = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appInfoContentDescription = context.getString(R.string.drawer_app_info_content_description)
        val searchContentDescription = context.getString(R.string.drawer_conversation_search_content_description)

        composeRule.setContent {
            MaterialTheme {
                // 与主界面一致：只重建抽屉内容，列表状态保留在 key 外。
                drawerListState = rememberLazyListState()
                scope = rememberCoroutineScope()
                key(drawerSessionKey) {
                    AppDrawerContent(
                        listState = drawerListState,
                        historicalConversations = conversations,
                        loadedHistoryIndex = loadedHistoryIndex,
                        onConversationSearchClick = { searchClickCount++ },
                        onConversationClick = { loadedHistoryIndex = it },
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
        composeRule.runOnIdle { scope.launch { drawerListState.scrollToItem(33, 17) } }
        composeRule.onNodeWithText("Conversation 30").assertIsDisplayed()
        composeRule.onNodeWithText("新建会话").assertIsNotDisplayed()
        composeRule.onNodeWithContentDescription(searchContentDescription).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(appInfoContentDescription).assertIsDisplayed()

        composeRule.onNodeWithText("Conversation 30").performTouchInput { click() }
        composeRule.waitForIdle()
        val selectedPosition = composeRule.runOnIdle {
            assertEquals(30, loadedHistoryIndex)
            assertEquals(17, drawerListState.firstVisibleItemScrollOffset)
            drawerListState.firstVisibleItemIndex to drawerListState.firstVisibleItemScrollOffset
        }

        repeat(3) {
            composeRule.runOnUiThread { drawerSessionKey++ }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Conversation 30").assertIsDisplayed()
            composeRule.onNodeWithText("新建会话").assertIsNotDisplayed()
            composeRule.runOnIdle {
                assertEquals(selectedPosition.first, drawerListState.firstVisibleItemIndex)
                assertEquals(selectedPosition.second, drawerListState.firstVisibleItemScrollOffset)
            }
        }
    }
}
