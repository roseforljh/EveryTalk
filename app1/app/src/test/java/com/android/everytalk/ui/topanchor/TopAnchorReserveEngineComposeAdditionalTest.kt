package com.android.everytalk.ui.topanchor

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TopAnchorReserveEngineComposeAdditionalTest {
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
    fun `historical regeneration waits for committed move before pinning distant user`() {
        composeRule.mainClock.autoAdvance = false
        val initialItems = buildList {
            repeat(8) { turnIndex ->
                add(HarnessItem("u$turnIndex", 80.dp))
                add(HarnessItem("a$turnIndex", 520.dp))
            }
        }
        lateinit var engineState: TopAnchorReserveEngineState
        lateinit var listState: LazyListState
        lateinit var publishPendingRegeneration: () -> Unit
        lateinit var commitRegeneratedUserMove: () -> Unit
        lateinit var pendingSentId: () -> String?
        lateinit var activationCount: () -> Int
        var targetAnchorYPx = 0

        composeRule.setContent {
            val density = LocalDensity.current
            var items by remember { mutableStateOf(initialItems) }
            var sentUserMessageId by remember { mutableStateOf<String?>(null) }
            var activations by remember { mutableStateOf(0) }
            listState = rememberLazyListState()
            engineState = remember { TopAnchorReserveEngineState() }
            targetAnchorYPx = with(density) { 96.dp.toPx().toInt() }
            publishPendingRegeneration = { sentUserMessageId = "u0" }
            commitRegeneratedUserMove = {
                items = items.filterNot { it.id == "u0" || it.id == "a0" } +
                    HarnessItem("u0", 80.dp)
            }
            pendingSentId = { sentUserMessageId }
            activationCount = { activations }

            val topAnchorItems = items.map { item ->
                TopAnchorItem(
                    id = item.id,
                    role = if (item.id.startsWith("u")) {
                        TopAnchorItemRole.User
                    } else {
                        TopAnchorItemRole.AssistantTarget
                    },
                )
            }
            val activeTurn = resolveActiveTopAnchorTurn(
                items = topAnchorItems,
                sentUserMessageId = sentUserMessageId,
                sessionKey = "s1",
                generation = items.size.toLong(),
            )
            LaunchedEffect(
                activeTurn?.anchorMessageId,
                activeTurn?.targetItemId,
                activeTurn?.generation,
            ) {
                val turn = activeTurn ?: return@LaunchedEffect
                activations += 1
                engineState.activateTurn(turn)
                sentUserMessageId = null
            }

            val engineTurn = engineState.runtime.currentTurn
            val engineAnchorInfo = remember(items, engineTurn) {
                val turn = engineTurn ?: return@remember null
                items.mapIndexedNotNull { index, item ->
                    if (item.id == turn.anchorMessageId) index to item.id else null
                }.firstOrNull()
            }
            engineAnchorInfo?.let { (anchorIndex, anchorKey) ->
                RunTopAnchorReserveEngine(
                    state = engineState,
                    listState = listState,
                    anchorIndex = anchorIndex,
                    anchorKey = anchorKey,
                    targetAnchorY = targetAnchorYPx,
                    trailingRealItemIndex = items.lastIndex,
                    isRunning = true,
                    config = TopAnchorConfig(
                        tallAnchorThresholdPx = with(density) { 240.dp.toPx().toInt() },
                        tallAnchorVisibleHeightPx = with(density) { 96.dp.toPx().toInt() },
                        topInsetPx = targetAnchorYPx,
                        stableWindowNanos = 1_000_000L,
                        keepReserveAfterRunEnd = true,
                        reserveInsideTrailingItem = true,
                    ),
                    enabled = engineState.runtime.hasRuntime,
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.height(420.dp),
                contentPadding = PaddingValues(top = 96.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    Box(
                        Modifier
                            .appendTopAnchorReserve(
                                if (index == items.lastIndex) engineState.reservePx else 0
                            )
                            .height(item.heightDp)
                    )
                }
            }
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { publishPendingRegeneration() }
        repeat(6) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertEquals("u0", pendingSentId())
            assertEquals(0, activationCount())
            assertFalse(engineState.runtime.hasRuntime)
            commitRegeneratedUserMove()
        }

        repeat(20) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertEquals(null, pendingSentId())
            assertEquals(1, activationCount())
            val anchor = listState.layoutInfo.visibleItemsInfo.first { it.key == "u0" }
            val actualAnchorY = anchor.offset - listState.layoutInfo.viewportStartOffset
            assertTrue(
                "远距离重答未置顶：actual=$actualAnchorY target=$targetAnchorYPx",
                abs(actualAnchorY - targetAnchorYPx) <= 1,
            )
        }
    }

    @Test
    fun `locking auto scroll cancels a previously scheduled bottom animation`() {
        composeRule.mainClock.autoAdvance = false
        val items = List(80) { index -> HarnessItem("item-$index", 80.dp) }
        lateinit var listState: LazyListState
        lateinit var scrollStateManager: ChatScrollStateManager

        composeRule.setContent {
            listState = rememberLazyListState()
            scrollStateManager = rememberChatScrollStateManager(
                listState = listState,
                coroutineScope = rememberCoroutineScope(),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.height(320.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    Box(Modifier.height(item.heightDp))
                }
            }
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            scrollStateManager.smoothScrollToBottom(isUserAction = true)
            scrollStateManager.lockAutoScroll()
        }
        repeat(30) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            assertTrue(
                "lock 后旧动画仍滚到了底部，first=${listState.firstVisibleItemIndex}",
                listState.canScrollForward,
            )
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
                scrollStateManager.setTopAnchorUserScrollReleaser(engineState::releaseForUserScroll)
                scrollStateManager.updateTopAnchorBottomScrollSuppression(true)
                onDispose {
                    scrollStateManager.setTopAnchorRuntimeClearer(null)
                    scrollStateManager.setTopAnchorUserScrollReleaser(null)
                }
            }
            LazyColumn(state = listState, modifier = Modifier.height(200.dp)) {
                item { Box(Modifier.height(80.dp)) }
            }
        }

        composeRule.runOnIdle {
            assertTrue(engineState.runtime.hasRuntime)
            scrollStateManager.jumpToBottom(isUserAction = true)
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            !engineState.runtime.hasRuntime
        }
        composeRule.runOnIdle {
            assertFalse(engineState.runtime.hasRuntime)
        }
    }

    @Test
    fun `stopping stream removes reserve and reaches real bottom`() {
        composeRule.mainClock.autoAdvance = false
        val turn = TopAnchorTurn("u2", "a2", "s1", 2L)
        val items = List(5) { index -> HarnessItem("item_$index", 120.dp) } +
            HarnessItem("item_5", 900.dp)
        lateinit var engineState: TopAnchorReserveEngineState
        lateinit var listState: LazyListState
        lateinit var scrollStateManager: ChatScrollStateManager
        lateinit var appendDelayedTerminalLayout: () -> Unit
        lateinit var releasePinAndScrollUp: () -> Unit
        lateinit var expandAfterRelease: () -> Unit
        var wasAtVirtualBottomWhenReserveCleared = false
        var lastVisibleKeyWhenReserveCleared: Any? = null

        composeRule.setContent {
            val density = LocalDensity.current
            val coroutineScope = rememberCoroutineScope()
            var showFooter by remember { mutableStateOf(false) }
            var answerHeight by remember { mutableStateOf(900.dp) }
            listState = rememberLazyListState()
            appendDelayedTerminalLayout = {
                coroutineScope.launch {
                    repeat(12) { frame ->
                        withFrameNanos { }
                        if (frame == 2) showFooter = true
                    }
                    answerHeight = 1200.dp
                }
            }
            releasePinAndScrollUp = {
                scrollStateManager.nestedScrollConnection.onPreScroll(
                    available = Offset(0f, 1f),
                    source = NestedScrollSource.UserInput,
                )
                coroutineScope.launch { listState.scrollBy(-120f) }
            }
            expandAfterRelease = { answerHeight = 1500.dp }
            engineState = remember {
                TopAnchorReserveEngineState().also {
                    it.updateRuntime(
                        TopAnchorRuntimeState(
                            phase = TopAnchorPhase.Retained,
                            retainedTurn = turn,
                            reservePx = with(density) { 480.dp.toPx().toInt() },
                        )
                    )
                }
            }
            scrollStateManager = rememberChatScrollStateManager(
                listState = listState,
                coroutineScope = coroutineScope,
            )
            DisposableEffect(scrollStateManager, engineState) {
                scrollStateManager.setTopAnchorRuntimeClearer {
                    wasAtVirtualBottomWhenReserveCleared = !listState.canScrollForward
                    lastVisibleKeyWhenReserveCleared =
                        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key
                    engineState.clearRuntime()
                }
                onDispose { scrollStateManager.setTopAnchorRuntimeClearer(null) }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.height(320.dp),
                contentPadding = PaddingValues(top = 96.dp, bottom = 100.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    Box(Modifier.height(if (item.id == "item_5") answerHeight else item.heightDp))
                }
                if (showFooter) {
                    item(key = "answer_footer") { Box(Modifier.height(36.dp)) }
                }
                if (engineState.reservePx > 0) {
                    item(key = "top_anchor_reserve") {
                        Spacer(Modifier.height(with(density) { engineState.reservePx.toDp() }))
                    }
                }
            }
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(engineState.runtime.hasRuntime)
            assertTrue(engineState.reservePx > 0)
            appendDelayedTerminalLayout()
            scrollStateManager.stopStreamingAndJumpToRealBottom()
        }
        repeat(28) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            assertTrue(wasAtVirtualBottomWhenReserveCleared)
            assertEquals("top_anchor_reserve", lastVisibleKeyWhenReserveCleared)
            assertFalse(engineState.runtime.hasRuntime)
            assertEquals(0, engineState.reservePx)
            assertFalse(listState.canScrollForward)
            assertEquals("answer_footer", listState.layoutInfo.visibleItemsInfo.last().key)
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.last()
            val expectedContentBottom = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
            assertTrue(
                abs(lastVisibleItem.offset + lastVisibleItem.size - expectedContentBottom) <= 1
            )
        }

        composeRule.runOnIdle { releasePinAndScrollUp() }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertTrue(listState.canScrollForward)
            expandAfterRelease()
        }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle { assertTrue(listState.canScrollForward) }
    }

    @Test
    fun `late bottom event after stopping still reaches bottom of a tall final item`() {
        composeRule.mainClock.autoAdvance = false
        val turn = TopAnchorTurn("u2", "a2", "s1", 2L)
        val items = List(5) { index -> HarnessItem("item_$index", 120.dp) } +
            HarnessItem("tall_answer", 1200.dp)
        lateinit var engineState: TopAnchorReserveEngineState
        lateinit var listState: LazyListState
        lateinit var scrollStateManager: ChatScrollStateManager
        lateinit var expandAfterLateBottomEvent: () -> Unit

        composeRule.setContent {
            val density = LocalDensity.current
            val coroutineScope = rememberCoroutineScope()
            var answerHeight by remember { mutableStateOf(1200.dp) }
            expandAfterLateBottomEvent = { answerHeight = 1600.dp }
            listState = rememberLazyListState()
            engineState = remember {
                TopAnchorReserveEngineState().also {
                    it.updateRuntime(
                        TopAnchorRuntimeState(
                            phase = TopAnchorPhase.Retained,
                            retainedTurn = turn,
                            reservePx = with(density) { 480.dp.toPx().toInt() },
                        )
                    )
                }
            }
            scrollStateManager = rememberChatScrollStateManager(
                listState = listState,
                coroutineScope = coroutineScope,
            )
            DisposableEffect(scrollStateManager, engineState) {
                scrollStateManager.setTopAnchorRuntimeClearer(engineState::clearRuntime)
                onDispose { scrollStateManager.setTopAnchorRuntimeClearer(null) }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.height(320.dp),
                contentPadding = PaddingValues(top = 96.dp, bottom = 100.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    Box(
                        Modifier.height(
                            if (item.id == "tall_answer") answerHeight else item.heightDp
                        )
                    )
                }
                if (engineState.reservePx > 0) {
                    item(key = "top_anchor_reserve") {
                        Spacer(Modifier.height(with(density) { engineState.reservePx.toDp() }))
                    }
                }
            }
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            scrollStateManager.stopStreamingAndJumpToRealBottom()
        }
        repeat(12) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            // 模拟停止后图片、公式或终态重排触发的普通落底事件。
            scrollStateManager.jumpToBottom()
        }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle { expandAfterLateBottomEvent() }
        repeat(8) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            assertFalse(
                "延迟落底事件把超高末项对齐到了顶部，仍可继续向下滚动",
                listState.canScrollForward,
            )
        }
    }

    @Test
    fun `user controlled reserve is replaced by growing response content`() {
        composeRule.mainClock.autoAdvance = false
        val turn = TopAnchorTurn("u2", "a2", "s1", 2L)
        val historyItems = listOf(
            HarnessItem("u1", 280.dp),
            HarnessItem("a1", 720.dp),
            HarnessItem("u2", 80.dp),
        )
        lateinit var engineState: TopAnchorReserveEngineState
        lateinit var listState: LazyListState
        lateinit var takeUserControl: () -> Unit
        lateinit var growResponseAndAppendFooter: () -> Unit
        lateinit var finishAnswer: () -> Unit
        var targetAnchorYPx = 0

        composeRule.setContent {
            val density = LocalDensity.current
            var answerHeight by remember { mutableStateOf(48.dp) }
            var showFooter by remember { mutableStateOf(false) }
            var isRunning by remember { mutableStateOf(true) }
            listState = rememberLazyListState()
            targetAnchorYPx = with(density) { 96.dp.toPx().toInt() }
            engineState = remember {
                TopAnchorReserveEngineState().also {
                    it.updateRuntime(
                        TopAnchorRuntimeState(
                            phase = TopAnchorPhase.InitialSnap,
                            activeTurn = turn,
                        )
                    )
                }
            }
            takeUserControl = engineState::releaseForUserScroll
            growResponseAndAppendFooter = {
                answerHeight = 900.dp
                showFooter = true
            }
            finishAnswer = { isRunning = false }

            val trailingRealItemIndex = historyItems.size + if (showFooter) 1 else 0
            RunTopAnchorReserveEngine(
                state = engineState,
                listState = listState,
                anchorIndex = 2,
                anchorKey = "u2",
                targetAnchorY = targetAnchorYPx,
                trailingRealItemIndex = trailingRealItemIndex,
                isRunning = isRunning,
                config = TopAnchorConfig(
                    tallAnchorThresholdPx = with(density) { 240.dp.toPx().toInt() },
                    tallAnchorVisibleHeightPx = with(density) { 96.dp.toPx().toInt() },
                    topInsetPx = targetAnchorYPx,
                    stableWindowNanos = 1_000_000L,
                    keepReserveAfterRunEnd = true,
                    reserveInsideTrailingItem = true,
                ),
                enabled = engineState.runtime.hasRuntime,
                hasResponseTarget = true,
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.height(420.dp),
                contentPadding = PaddingValues(top = 96.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(historyItems, key = { _, item -> item.id }) { _, item ->
                    Box(Modifier.height(item.heightDp))
                }
                item(key = "a2") {
                    Box(
                        Modifier
                            .appendTopAnchorReserve(
                                if (!showFooter) engineState.reservePx else 0
                            )
                            .height(answerHeight)
                    )
                }
                if (showFooter) {
                    item(key = "a2_footer") {
                        Box(
                            Modifier
                                .appendTopAnchorReserve(engineState.reservePx)
                                .height(36.dp)
                        )
                    }
                }
            }
        }

        repeat(16) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        var anchorYBeforeGrowth = 0
        composeRule.runOnIdle {
            assertTrue(engineState.reservePx > 0)
            anchorYBeforeGrowth = listState.layoutInfo.visibleItemsInfo
                .first { it.key == "u2" }
                .offset - listState.layoutInfo.viewportStartOffset
            takeUserControl()
        }

        composeRule.runOnIdle { growResponseAndAppendFooter() }
        repeat(16) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            val anchorYAfterGrowth = listState.layoutInfo.visibleItemsInfo
                .first { it.key == "u2" }
                .offset - listState.layoutInfo.viewportStartOffset
            assertEquals(anchorYBeforeGrowth, anchorYAfterGrowth)
            assertEquals(0, engineState.reservePx)
            finishAnswer()
        }

        repeat(8) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertEquals(0, engineState.reservePx)
            assertFalse(engineState.runtime.hasRuntime)
        }
    }

    @Test
    fun `manual user drag keeps anchored item stable while releasing automatic correction`() {
        composeRule.mainClock.autoAdvance = false
        val turn = TopAnchorTurn("u2", "a2", "s1", 2L)
        val items = listOf(
            HarnessItem("u1", 280.dp),
            HarnessItem("a1", 720.dp),
            HarnessItem("u2", 80.dp),
            HarnessItem("a2", 48.dp),
        )
        lateinit var engineState: TopAnchorReserveEngineState
        lateinit var listState: LazyListState
        lateinit var scrollStateManager: ChatScrollStateManager
        lateinit var scrollBackward: () -> Unit
        lateinit var finishAnswer: () -> Unit
        var targetAnchorYPx = 0
        var consumedScrollPx = 0f
        var consumedPreScroll = Offset.Zero
        var firstVisibleKeyBeforeFinish: Any? = null
        var firstVisibleOffsetBeforeFinish = 0

        composeRule.setContent {
            val density = LocalDensity.current
            var isRunning by remember { mutableStateOf(true) }
            var showFooter by remember { mutableStateOf(false) }
            listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()
            targetAnchorYPx = with(density) { 96.dp.toPx().toInt() }
            scrollBackward = {
                coroutineScope.launch {
                    consumedScrollPx = listState.scrollBy(-24f)
                }
            }
            finishAnswer = {
                showFooter = true
                isRunning = false
            }
            engineState = remember {
                TopAnchorReserveEngineState().also {
                    it.updateRuntime(
                        TopAnchorRuntimeState(
                            phase = TopAnchorPhase.InitialSnap,
                            activeTurn = turn,
                        )
                    )
                }
            }
            scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)
            DisposableEffect(scrollStateManager, engineState) {
                scrollStateManager.setTopAnchorRuntimeClearer(engineState::clearRuntime)
                scrollStateManager.setTopAnchorUserScrollReleaser(engineState::releaseForUserScroll)
                scrollStateManager.updateTopAnchorBottomScrollSuppression(true)
                onDispose {
                    scrollStateManager.setTopAnchorRuntimeClearer(null)
                    scrollStateManager.setTopAnchorUserScrollReleaser(null)
                }
            }

            RunTopAnchorReserveEngine(
                state = engineState,
                listState = listState,
                anchorIndex = 2,
                anchorKey = "u2",
                targetAnchorY = targetAnchorYPx,
                trailingRealItemIndex = items.lastIndex + if (showFooter) 1 else 0,
                isRunning = isRunning,
                config = TopAnchorConfig(
                    tallAnchorThresholdPx = with(density) { 240.dp.toPx().toInt() },
                    tallAnchorVisibleHeightPx = with(density) { 96.dp.toPx().toInt() },
                    topInsetPx = targetAnchorYPx,
                    stableWindowNanos = 1_000_000L,
                    keepReserveAfterRunEnd = true,
                ),
                enabled = engineState.runtime.hasRuntime,
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.height(640.dp),
                contentPadding = PaddingValues(top = 96.dp, bottom = 80.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    Box(Modifier.height(item.heightDp))
                }
                if (showFooter) {
                    item(key = "answer_footer") { Box(Modifier.height(36.dp)) }
                }
                if (engineState.reservePx > 0) {
                    item(key = "top_anchor_reserve") {
                        Spacer(Modifier.height(with(density) { engineState.reservePx.toDp() }))
                    }
                }
            }
        }

        repeat(12) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        var anchorYBeforeDrag = 0
        var reserveBeforeDrag = 0
        composeRule.runOnIdle {
            assertTrue(engineState.runtime.hasRuntime)
            anchorYBeforeDrag = listState.layoutInfo.visibleItemsInfo
                .first { it.key == "u2" }
                .offset - listState.layoutInfo.viewportStartOffset
            reserveBeforeDrag = engineState.reservePx
            assertTrue(reserveBeforeDrag > 0)
            consumedPreScroll = scrollStateManager.nestedScrollConnection.onPreScroll(
                available = Offset(0f, -24f),
                source = NestedScrollSource.UserInput
            )
        }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            val anchorYAfterDrag = listState.layoutInfo.visibleItemsInfo
                .first { it.key == "u2" }
                .offset - listState.layoutInfo.viewportStartOffset
            assertEquals(anchorYBeforeDrag, anchorYAfterDrag)
            assertEquals(reserveBeforeDrag, engineState.reservePx)
            assertTrue(engineState.runtime.hasRuntime)
            assertEquals(TopAnchorPhase.UserControlled, engineState.runtime.phase)
            assertEquals(Offset.Zero, consumedPreScroll)
            scrollBackward()
        }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            val anchorYAfterScroll = listState.layoutInfo.visibleItemsInfo
                .first { it.key == "u2" }
                .offset - listState.layoutInfo.viewportStartOffset
            assertTrue(consumedScrollPx < 0f)
            assertTrue(
                abs(anchorYAfterScroll - (anchorYBeforeDrag - consumedScrollPx.roundToInt())) <= 1
            )
            assertEquals(reserveBeforeDrag, engineState.reservePx)
            assertEquals(TopAnchorPhase.UserControlled, engineState.runtime.phase)
            val firstVisible = listState.layoutInfo.visibleItemsInfo.first()
            firstVisibleKeyBeforeFinish = firstVisible.key
            firstVisibleOffsetBeforeFinish = firstVisible.offset
            finishAnswer()
        }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            assertTrue(engineState.reservePx in 1..reserveBeforeDrag)
            assertTrue(engineState.runtime.hasRuntime)
            assertEquals(TopAnchorPhase.UserControlled, engineState.runtime.phase)
            val firstVisible = listState.layoutInfo.visibleItemsInfo.first()
            assertEquals(firstVisibleKeyBeforeFinish, firstVisible.key)
            assertTrue(abs(firstVisibleOffsetBeforeFinish - firstVisible.offset) <= 1)
            assertEquals(items.size + 2, listState.layoutInfo.totalItemsCount)
        }
    }
}
