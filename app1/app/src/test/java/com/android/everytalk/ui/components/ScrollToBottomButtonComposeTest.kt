package com.android.everytalk.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.rememberChatScrollStateManager
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ScrollToBottomButtonComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `权限卡退场屏蔽期间按钮立即移出组合`() {
        lateinit var setSuppressed: (Boolean) -> Unit
        lateinit var scrollToBottomLabel: String

        composeRule.setContent {
            scrollToBottomLabel = stringResource(R.string.chat_scroll_to_bottom)
            var suppressed by remember { mutableStateOf(false) }
            setSuppressed = { suppressed = it }
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val manager = rememberChatScrollStateManager(listState, scope)

            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.height(240.dp),
                ) {
                    items(20) { Spacer(Modifier.height(100.dp)) }
                }
                ScrollToBottomButton(
                    scrollStateManager = manager,
                    suppressed = suppressed,
                )
            }

            androidx.compose.runtime.LaunchedEffect(Unit) {
                listState.scrollToItem(8)
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule
                .onAllNodesWithContentDescription(scrollToBottomLabel)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.runOnIdle { setSuppressed(true) }
        composeRule.runOnIdle {
            assertTrue(
                composeRule
                    .onAllNodesWithContentDescription(scrollToBottomLabel)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
        }
    }
}
