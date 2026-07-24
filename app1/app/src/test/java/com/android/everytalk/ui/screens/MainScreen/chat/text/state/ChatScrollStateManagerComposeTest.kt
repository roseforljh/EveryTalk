package com.android.everytalk.ui.screens.MainScreen.chat.text.state

import android.app.Application
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
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
    fun `首次显示长列表时第一帧直接位于末尾`() {
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        val itemCount = 20

        composeRule.setContent {
            listState = rememberLazyListState(
                initialFirstVisibleItemIndex = itemCount - 1,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.height(320.dp),
            ) {
                items(itemCount, key = { "message_$it" }) {
                    Spacer(Modifier.height(180.dp))
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            listState.layoutInfo.totalItemsCount == itemCount
        }

        composeRule.runOnIdle {
            assertTrue(listState.firstVisibleItemIndex > 0)
            assertFalse(listState.canScrollForward)
            assertEquals(itemCount - 1, listState.layoutInfo.visibleItemsInfo.last().index)
        }
    }

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

    @Test
    fun `单次返回底部会跟随多轮延迟重组抵达真实底部`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        lateinit var scrollStateManager: ChatScrollStateManager
        lateinit var appendDelayedTurns: () -> Unit
        var currentItemCount = 0

        composeRule.setContent {
            val coroutineScope = rememberCoroutineScope()
            var itemCount by remember { mutableIntStateOf(12) }
            currentItemCount = itemCount
            listState = rememberLazyListState()
            scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)
            appendDelayedTurns = {
                coroutineScope.launch {
                    repeat(3) {
                        repeat(2) { withFrameNanos { } }
                        itemCount += 4
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.height(320.dp),
            ) {
                items(itemCount, key = { "message_$it" }) {
                    Spacer(Modifier.height(180.dp))
                }
            }
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            scrollStateManager.jumpToBottom(isUserAction = true)
        }
        repeat(20) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertFalse(listState.canScrollForward)
            appendDelayedTurns()
        }
        repeat(20) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            assertEquals(24, currentItemCount)
            assertFalse(listState.canScrollForward)
            assertEquals(
                currentItemCount - 1,
                listState.layoutInfo.visibleItemsInfo.last().index,
            )
        }
    }

    @Test
    fun `底部守护会修正项目尺寸不变的位置漂移`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        lateinit var scrollStateManager: ChatScrollStateManager
        lateinit var coroutineScope: CoroutineScope
        var displacedPx = 0f

        composeRule.setContent {
            coroutineScope = rememberCoroutineScope()
            listState = rememberLazyListState()
            scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)

            LazyColumn(
                state = listState,
                modifier = Modifier.height(320.dp),
            ) {
                items(12, key = { "position_shift_message_$it" }) {
                    Spacer(Modifier.height(180.dp))
                }
            }
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            scrollStateManager.jumpToBottom(isUserAction = true)
        }
        repeat(10) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertFalse(listState.canScrollForward)
            coroutineScope.launch {
                displacedPx = listState.scrollBy(-40f)
            }
        }
        repeat(10) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            assertTrue(displacedPx < 0f)
            assertFalse(listState.canScrollForward)
        }
    }

    @Test
    fun `用户打断底部守护后程序滚动标记会立即释放`() {
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        lateinit var scrollStateManager: ChatScrollStateManager

        composeRule.setContent {
            val coroutineScope = rememberCoroutineScope()
            listState = rememberLazyListState()
            scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)

            LazyColumn(
                state = listState,
                modifier = Modifier.height(320.dp),
            ) {
                items(12, key = { "interrupt_message_$it" }) {
                    Spacer(Modifier.height(180.dp))
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            listState.layoutInfo.totalItemsCount == 12
        }
        composeRule.runOnIdle {
            scrollStateManager.jumpToBottom(isUserAction = true)
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            !listState.canScrollForward
        }

        composeRule.runOnIdle {
            scrollStateManager.nestedScrollConnection.onPreScroll(
                available = Offset(0f, -1f),
                source = NestedScrollSource.UserInput,
            )

            val field = ChatScrollStateManager::class.java.getDeclaredField("isProgrammaticScroll")
            field.isAccessible = true
            assertFalse(field.getBoolean(scrollStateManager))
        }
    }

    @Test
    fun `释放滚动管理器后延迟扩高不再劫持列表`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        lateinit var scrollStateManager: ChatScrollStateManager
        lateinit var appendItems: () -> Unit

        composeRule.setContent {
            val coroutineScope = rememberCoroutineScope()
            var itemCount by remember { mutableIntStateOf(12) }
            listState = rememberLazyListState()
            scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)
            appendItems = { itemCount += 4 }

            LazyColumn(
                state = listState,
                modifier = Modifier.height(320.dp),
            ) {
                items(itemCount, key = { "dispose_message_$it" }) {
                    Spacer(Modifier.height(180.dp))
                }
            }
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            scrollStateManager.jumpToBottom(isUserAction = true)
        }
        repeat(6) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertFalse(listState.canScrollForward)
            scrollStateManager.dispose()
            appendItems()
        }
        repeat(10) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            assertTrue(listState.canScrollForward)
        }
    }
}
