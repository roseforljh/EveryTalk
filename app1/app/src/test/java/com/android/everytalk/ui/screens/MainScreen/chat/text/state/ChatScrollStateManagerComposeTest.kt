package com.android.everytalk.ui.screens.MainScreen.chat.text.state

import android.app.Application
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ChatScrollStateManagerComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `超高末项内部离开底部后返回按钮仍会出现`() {
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        lateinit var scrollStateManager: ChatScrollStateManager
        lateinit var coroutineScope: CoroutineScope

        composeRule.setContent {
            coroutineScope = rememberCoroutineScope()
            listState = rememberLazyListState()
            scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)

            LazyColumn(
                state = listState,
                modifier = Modifier.height(320.dp),
            ) {
                item(key = "tall-last-message") {
                    Spacer(Modifier.height(1200.dp))
                }
            }
        }

        composeRule.runOnIdle {
            coroutineScope.launch {
                listState.scrollToItem(0)
                listState.scrollBy(10_000f)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            !listState.canScrollForward
        }
        composeRule.runOnIdle {
            assertFalse(scrollStateManager.showScrollToBottomButton.value)
            coroutineScope.launch { listState.scrollBy(-180f) }
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            listState.canScrollForward
        }

        composeRule.runOnIdle {
            assertEquals(0, listState.layoutInfo.visibleItemsInfo.last().index)
            assertTrue(scrollStateManager.showScrollToBottomButton.value)
        }
    }
}
