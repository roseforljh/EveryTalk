package com.android.everytalk.ui.topanchor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.rememberChatScrollStateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TopAnchorReserveEngineComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private data class HarnessItem(
        val id: String,
        val heightDp: Dp
    )

    @org.junit.After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `initial snap suppresses bottom scroll before reserve is positive`() {
        val state = TopAnchorReserveEngineState()
        state.updateRuntime(
            TopAnchorRuntimeState(
                phase = TopAnchorPhase.InitialSnap,
                activeTurn = TopAnchorTurn("u2", "a2", "s1", 2L),
                reservePx = 0
            )
        )

        assertTrue(state.suppressesBottomScroll)
        assertFalse(state.reservePx > 0)
    }

    @Test
    fun `stream end moves active turn into retained without losing current turn`() {
        composeRule.mainClock.autoAdvance = false
        val turn = TopAnchorTurn("u2", "a2", "s1", 2L)
        val initialItems = listOf(
            HarnessItem("u1", 80.dp),
            HarnessItem("a1", 180.dp),
            HarnessItem("u2", 80.dp),
            HarnessItem("a2", 360.dp)
        )
        lateinit var engineState: TopAnchorReserveEngineState
        lateinit var stopRunning: () -> Unit

        composeRule.setContent {
            val density = LocalDensity.current
            var isRunning by remember { mutableStateOf(true) }
            val listState = rememberLazyListState()
            val state = remember {
                TopAnchorReserveEngineState().also {
                    it.updateRuntime(
                        TopAnchorRuntimeState(
                            phase = TopAnchorPhase.InitialSnap,
                            activeTurn = turn
                        )
                    )
                }
            }
            engineState = state
            stopRunning = { isRunning = false }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .height(640.dp)
                    .testTag("top_anchor_list")
            ) {
                itemsIndexed(initialItems, key = { _, item -> item.id }) { _, item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(item.heightDp)
                            .testTag(item.id)
                    )
                }
                if (state.reservePx > 0) {
                    item(key = "top_anchor_reserve") {
                        Spacer(
                            modifier = Modifier
                                .height(with(density) { state.reservePx.toDp() })
                                .testTag("top_anchor_reserve")
                        )
                    }
                }
            }

            RunTopAnchorReserveEngine(
                state = state,
                listState = listState,
                anchorIndex = 2,
                anchorKey = "u2",
                targetAnchorY = with(density) { 96.dp.toPx().toInt() },
                trailingRealItemIndex = initialItems.lastIndex,
                isRunning = isRunning,
                config = TopAnchorConfig(
                    tallAnchorThresholdPx = with(density) { 240.dp.toPx().toInt() },
                    tallAnchorVisibleHeightPx = with(density) { 96.dp.toPx().toInt() },
                    topInsetPx = with(density) { 96.dp.toPx().toInt() },
                    stableWindowNanos = 1_000_000L
                ),
                enabled = state.runtime.hasRuntime
            )
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitUntil { engineState.runtime.phase == TopAnchorPhase.AnchoredRunning }

        composeRule.runOnUiThread { stopRunning() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitUntil { engineState.runtime.phase == TopAnchorPhase.Retained }

        composeRule.runOnIdle {
            assertEquals(TopAnchorPhase.Retained, engineState.runtime.phase)
            assertEquals(turn, engineState.runtime.retainedTurn)
            assertSame(turn, engineState.runtime.currentTurn)
            assertTrue(engineState.runtime.suppressesBottomScroll)
        }
    }

    @Test
    fun `user bottom action clears runtime through scroll manager clearer`() {
        val turn = TopAnchorTurn("u2", "a2", "s1", 2L)
        lateinit var engineState: TopAnchorReserveEngineState
        lateinit var scrollStateManager: ChatScrollStateManager

        composeRule.setContent {
            val listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()
            engineState = remember {
                TopAnchorReserveEngineState().also {
                    it.updateRuntime(
                        TopAnchorRuntimeState(
                            phase = TopAnchorPhase.Retained,
                            retainedTurn = turn
                        )
                    )
                }
            }
            scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)
            DisposableEffect(scrollStateManager, engineState) {
                scrollStateManager.setTopAnchorRuntimeClearer(engineState::clearRuntime)
                scrollStateManager.updateTopAnchorBottomScrollSuppression(true)
                onDispose { scrollStateManager.setTopAnchorRuntimeClearer(null) }
            }
            LazyColumn(state = listState, modifier = Modifier.height(200.dp)) {
                item { Box(Modifier.height(80.dp)) }
            }
        }

        composeRule.runOnIdle {
            assertTrue(engineState.runtime.hasRuntime)
            scrollStateManager.jumpToBottom(isUserAction = true)
            assertFalse(engineState.runtime.hasRuntime)
        }
    }

    @Test
    fun `manual user drag clears runtime through scroll manager clearer`() {
        val turn = TopAnchorTurn("u2", "a2", "s1", 2L)
        lateinit var engineState: TopAnchorReserveEngineState
        lateinit var scrollStateManager: ChatScrollStateManager

        composeRule.setContent {
            val listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()
            engineState = remember {
                TopAnchorReserveEngineState().also {
                    it.updateRuntime(
                        TopAnchorRuntimeState(
                            phase = TopAnchorPhase.Retained,
                            retainedTurn = turn
                        )
                    )
                }
            }
            scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)
            DisposableEffect(scrollStateManager, engineState) {
                scrollStateManager.setTopAnchorRuntimeClearer(engineState::clearRuntime)
                scrollStateManager.updateTopAnchorBottomScrollSuppression(true)
                onDispose { scrollStateManager.setTopAnchorRuntimeClearer(null) }
            }
            LazyColumn(state = listState, modifier = Modifier.height(200.dp)) {
                item { Box(Modifier.height(80.dp)) }
            }
        }

        composeRule.runOnIdle {
            assertTrue(engineState.runtime.hasRuntime)
            scrollStateManager.nestedScrollConnection.onPreScroll(
                available = Offset(0f, -24f),
                source = NestedScrollSource.UserInput
            )
            assertFalse(engineState.runtime.hasRuntime)
        }
    }
}
