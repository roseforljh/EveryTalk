package com.android.everytalk.ui.topanchor

import androidx.compose.foundation.gestures.scrollBy
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
import org.junit.Assert.assertSame
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
    fun `last user message reaches top target before assistant item exists`() {
        composeRule.mainClock.autoAdvance = false
        val turn = TopAnchorTurn("u2", null, "s1", 3L)
        val initialItems = listOf(
            HarnessItem("u1", 280.dp),
            HarnessItem("a1", 720.dp),
            HarnessItem("u2", 80.dp),
        )
        lateinit var engineState: TopAnchorReserveEngineState
        lateinit var listState: LazyListState
        var targetAnchorYPx = 0

        composeRule.setContent {
            val density = LocalDensity.current
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

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .height(640.dp)
                    .testTag("last_user_anchor_list"),
            ) {
                itemsIndexed(initialItems, key = { _, item -> item.id }) { _, item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(item.heightDp)
                            .testTag(item.id)
                    )
                }
                if (engineState.reservePx > 0) {
                    item(key = "top_anchor_reserve") {
                        Spacer(modifier = Modifier.height(with(density) { engineState.reservePx.toDp() }))
                    }
                }
            }

            RunTopAnchorReserveEngine(
                state = engineState,
                listState = listState,
                anchorIndex = initialItems.lastIndex,
                anchorKey = "u2",
                targetAnchorY = targetAnchorYPx,
                trailingRealItemIndex = initialItems.lastIndex,
                isRunning = true,
                config = TopAnchorConfig(
                    tallAnchorThresholdPx = with(density) { 240.dp.toPx().toInt() },
                    tallAnchorVisibleHeightPx = with(density) { 96.dp.toPx().toInt() },
                    topInsetPx = targetAnchorYPx,
                    stableWindowNanos = 1_000_000L,
                ),
                enabled = engineState.runtime.hasRuntime,
            )
        }

        repeat(8) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            val anchor = listState.layoutInfo.visibleItemsInfo.first { it.key == "u2" }
            val actualAnchorY = anchor.offset - listState.layoutInfo.viewportStartOffset
            val layoutSummary = listState.layoutInfo.visibleItemsInfo.joinToString { item ->
                "${item.key}@${item.offset}:${item.size}"
            }
            assertTrue(
                "actual=$actualAnchorY target=$targetAnchorYPx reserve=${engineState.reservePx} " +
                    "phase=${engineState.runtime.phase} viewport=${listState.layoutInfo.viewportStartOffset}.." +
                    "${listState.layoutInfo.viewportEndOffset} total=${listState.layoutInfo.totalItemsCount} " +
                    "items=[$layoutSummary]",
                abs(actualAnchorY - targetAnchorYPx) <= 1,
            )
        }
    }

    @Test
    fun `assistant insertion and viewport expansion do not cancel initial top anchor`() {
        composeRule.mainClock.autoAdvance = false
        val turn = TopAnchorTurn("u2", null, "s1", 3L)
        val initialItems = listOf(
            HarnessItem("u1", 280.dp),
            HarnessItem("a1", 720.dp),
            HarnessItem("u2", 80.dp),
        )
        lateinit var engineState: TopAnchorReserveEngineState
        lateinit var listState: LazyListState
        lateinit var insertAssistantAndExpandViewport: () -> Unit
        var targetAnchorYPx = 0

        composeRule.setContent {
            val density = LocalDensity.current
            var items by remember { mutableStateOf(initialItems) }
            var viewportHeight by remember { mutableStateOf(420.dp) }
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
            insertAssistantAndExpandViewport = {
                items = items + HarnessItem("loading-a2", 48.dp)
                viewportHeight = 640.dp
            }

            val anchorIndex = items.indexOfFirst { it.id == turn.anchorMessageId }
            RunTopAnchorReserveEngine(
                state = engineState,
                listState = listState,
                anchorIndex = anchorIndex,
                anchorKey = turn.anchorMessageId,
                targetAnchorY = targetAnchorYPx,
                trailingRealItemIndex = items.lastIndex,
                isRunning = true,
                config = TopAnchorConfig(
                    tallAnchorThresholdPx = with(density) { 240.dp.toPx().toInt() },
                    tallAnchorVisibleHeightPx = with(density) { 96.dp.toPx().toInt() },
                    topInsetPx = targetAnchorYPx,
                    stableWindowNanos = 1_000_000L,
                ),
                enabled = engineState.runtime.hasRuntime,
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .height(viewportHeight)
                    .testTag("dynamic_top_anchor_list"),
                contentPadding = PaddingValues(top = 96.dp, bottom = 80.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(item.heightDp)
                            .testTag(item.id)
                    )
                }
                if (engineState.reservePx > 0) {
                    item(key = "top_anchor_reserve") {
                        Spacer(modifier = Modifier.height(with(density) { engineState.reservePx.toDp() }))
                    }
                }
            }
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnUiThread { insertAssistantAndExpandViewport() }
        repeat(12) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            val anchor = listState.layoutInfo.visibleItemsInfo.first { it.key == "u2" }
            val actualAnchorY = anchor.offset - listState.layoutInfo.viewportStartOffset
            val layoutSummary = listState.layoutInfo.visibleItemsInfo.joinToString { item ->
                "${item.key}@${item.offset}:${item.size}"
            }
            assertTrue(
                "actual=$actualAnchorY target=$targetAnchorYPx reserve=${engineState.reservePx} " +
                    "phase=${engineState.runtime.phase} viewportStart=${listState.layoutInfo.viewportStartOffset} " +
                    "viewportEnd=${listState.layoutInfo.viewportEndOffset} before=${listState.layoutInfo.beforeContentPadding} " +
                    "after=${listState.layoutInfo.afterContentPadding} canForward=${listState.canScrollForward} " +
                    "total=${listState.layoutInfo.totalItemsCount} items=[$layoutSummary]",
                abs(actualAnchorY - targetAnchorYPx) <= 1,
            )
        }
    }

    @Test
    fun `direct send anchors before assistant pair exists`() {
        composeRule.mainClock.autoAdvance = false
        val historyItems = listOf(
            HarnessItem("u1", 280.dp),
            HarnessItem("a1", 720.dp),
        )
        lateinit var engineState: TopAnchorReserveEngineState
        lateinit var listState: LazyListState
        lateinit var appendUserAndStartRun: () -> Unit
        lateinit var resendSameUser: () -> Unit
        lateinit var appendAssistantTarget: () -> Unit
        lateinit var pendingSentId: () -> String?
        lateinit var activationCount: () -> Int
        var targetAnchorYPx = 0

        composeRule.setContent {
            val density = LocalDensity.current
            var items by remember { mutableStateOf(historyItems) }
            var sentUserMessageId by remember { mutableStateOf<String?>(null) }
            var isRunning by remember { mutableStateOf(false) }
            var activations by remember { mutableStateOf(0) }
            listState = rememberLazyListState()
            targetAnchorYPx = with(density) { 96.dp.toPx().toInt() }
            engineState = remember { TopAnchorReserveEngineState() }
            pendingSentId = { sentUserMessageId }
            appendUserAndStartRun = {
                items = items + HarnessItem("u2", 80.dp)
                sentUserMessageId = "u2"
            }
            resendSameUser = { sentUserMessageId = "u2" }
            appendAssistantTarget = {
                items = items + HarnessItem("loading-a2", 48.dp)
                isRunning = true
            }
            activationCount = { activations }

            val topAnchorItems = items.map { item ->
                TopAnchorItem(
                    id = item.id,
                    role = when {
                        item.id.startsWith("u") -> TopAnchorItemRole.User
                        item.id.startsWith("loading") -> TopAnchorItemRole.LoadingTarget
                        else -> TopAnchorItemRole.AssistantTarget
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
                engineState.updateRuntime(
                    engineState.runtime.copy(
                        phase = TopAnchorPhase.InitialSnap,
                        activeTurn = turn,
                        retainedTurn = null,
                    )
                )
                sentUserMessageId = null
            }

            val engineTurn = engineState.runtime.currentTurn
            val engineAnchorInfo = remember(items, engineTurn) {
                val currentTurn = engineTurn ?: return@remember null
                items.mapIndexedNotNull { index, item ->
                    if (item.id == currentTurn.anchorMessageId) index to item.id else null
                }.firstOrNull()
            }
            LaunchedEffect(engineTurn, engineAnchorInfo) {
                if (
                    engineTurn != null &&
                    engineAnchorInfo == null &&
                    engineState.runtime.currentTurn == engineTurn
                ) {
                    engineState.clearRuntime()
                }
            }
            engineAnchorInfo?.let { (anchorIndex, anchorKey) ->
                RunTopAnchorReserveEngine(
                    state = engineState,
                    listState = listState,
                    anchorIndex = anchorIndex,
                    anchorKey = anchorKey,
                    targetAnchorY = targetAnchorYPx,
                    trailingRealItemIndex = items.lastIndex,
                    isRunning = isRunning,
                    config = TopAnchorConfig(
                        tallAnchorThresholdPx = with(density) { 240.dp.toPx().toInt() },
                        tallAnchorVisibleHeightPx = with(density) { 96.dp.toPx().toInt() },
                        topInsetPx = targetAnchorYPx,
                        stableWindowNanos = 1_000_000L,
                        keepReserveAfterRunEnd = false,
                    ),
                    enabled = engineState.runtime.hasRuntime,
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.height(420.dp),
                contentPadding = PaddingValues(top = 96.dp, bottom = 80.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    Box(Modifier.height(item.heightDp))
                }
                if (engineState.reservePx > 0) {
                    item(key = "top_anchor_reserve") {
                        Spacer(Modifier.height(with(density) { engineState.reservePx.toDp() }))
                    }
                }
            }
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnUiThread { appendUserAndStartRun() }
        repeat(12) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertEquals(null, pendingSentId())
            assertEquals("u2", engineState.runtime.currentTurn?.anchorMessageId)
            assertEquals(null, engineState.runtime.currentTurn?.targetItemId)
            val anchor = listState.layoutInfo.visibleItemsInfo.first { it.key == "u2" }
            val actualAnchorY = anchor.offset - listState.layoutInfo.viewportStartOffset
            assertTrue(abs(actualAnchorY - targetAnchorYPx) <= 1)
            assertEquals(1, activationCount())
        }

        composeRule.runOnUiThread { resendSameUser() }
        repeat(12) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertEquals(null, pendingSentId())
            assertEquals(2, activationCount())
            assertEquals("u2", engineState.runtime.currentTurn?.anchorMessageId)
        }

        composeRule.runOnUiThread { appendAssistantTarget() }
        repeat(12) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            assertEquals(null, pendingSentId())
            assertEquals("u2", engineState.runtime.currentTurn?.anchorMessageId)
            assertEquals(null, engineState.runtime.currentTurn?.targetItemId)
            val anchor = listState.layoutInfo.visibleItemsInfo.first { it.key == "u2" }
            val actualAnchorY = anchor.offset - listState.layoutInfo.viewportStartOffset
            assertTrue(abs(actualAnchorY - targetAnchorYPx) <= 1)
            assertEquals(2, activationCount())
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
        lateinit var stopAnswer: () -> Unit
        var targetAnchorYPx = 0
        var consumedScrollPx = 0f

        composeRule.setContent {
            val density = LocalDensity.current
            var isRunning by remember { mutableStateOf(true) }
            listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()
            targetAnchorYPx = with(density) { 96.dp.toPx().toInt() }
            scrollBackward = {
                coroutineScope.launch {
                    consumedScrollPx = listState.scrollBy(-24f)
                }
            }
            stopAnswer = { isRunning = false }
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
                trailingRealItemIndex = items.lastIndex,
                isRunning = isRunning,
                config = TopAnchorConfig(
                    tallAnchorThresholdPx = with(density) { 240.dp.toPx().toInt() },
                    tallAnchorVisibleHeightPx = with(density) { 96.dp.toPx().toInt() },
                    topInsetPx = targetAnchorYPx,
                    stableWindowNanos = 1_000_000L,
                    keepReserveAfterRunEnd = false,
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
            scrollStateManager.nestedScrollConnection.onPreScroll(
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
            stopAnswer()
        }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            assertEquals(0, engineState.reservePx)
            assertFalse(engineState.runtime.hasRuntime)
        }
    }
}
