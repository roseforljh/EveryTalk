package com.android.everytalk.ui.screens.BubbleMain.Main

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.screens.ImageGeneration.ImageGenerationLoadingView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ThinkingUiScrollComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `图像生成空列表加载态也使用执行抽屉`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ImageGenerationLoadingView()
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("正在执行").fetchSemanticsNode("")
        assertTrue(composeRule.onAllNodesWithText("等待首个响应").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithContentDescription("正在执行").performClick()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("执行过程加载中").fetchSemanticsNode("")
        composeRule.onNodeWithText("等待首个响应").fetchSemanticsNode("")
    }

    @Test
    fun `执行期间主界面只显示单行执行状态`() {
        composeRule.mainClock.autoAdvance = false
        val reasoning = "第一行推理\n第二行推理"
        var pixelsPerDp = 1f

        composeRule.setContent {
            pixelsPerDp = LocalDensity.current.density
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "inline-status",
                    displayedReasoningText = reasoning,
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    modifier = Modifier.testTag("reasoning-root"),
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("正在执行").fetchSemanticsNode("")
        assertTrue(composeRule.onAllNodesWithText(reasoning).fetchSemanticsNodes().isEmpty())

        val rootHeight = composeRule
            .onNodeWithTag("reasoning-root")
            .fetchSemanticsNode("")
            .boundsInRoot
            .height
        assertTrue("执行状态超过单行高度", rootHeight < 56f * pixelsPerDp)
    }

    @Test
    fun `工具状态在抽屉内使用统一状态且不显示计时`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "sheet-loader",
                    displayedReasoningText = "",
                    activityStatusText = "调用工具 · search_docs",
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        assertTrue(
            composeRule.onAllNodesWithContentDescription("执行过程加载中")
                .fetchSemanticsNodes()
                .isEmpty()
        )
        assertTrue(composeRule.onAllNodesWithText("调用工具 · search_docs").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithContentDescription("正在执行").performClick()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("执行过程加载中").fetchSemanticsNode("")
        composeRule.onNodeWithText("调用工具 · search_docs").fetchSemanticsNode("")
        composeRule.mainClock.advanceTimeBy(2_100L)
        composeRule.waitForIdle()
        listOf("0s", "1s", "2s", "3s").forEach { elapsedText ->
            assertTrue(composeRule.onAllNodesWithText(elapsedText).fetchSemanticsNodes().isEmpty())
        }
        val sheetBounds = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
        val statusBounds = composeRule
            .onNodeWithTag("reasoning-sheet-activity-status")
            .fetchSemanticsNode("")
            .boundsInRoot
        assertTrue("工具状态超出抽屉", statusBounds.left >= sheetBounds.left && statusBounds.right <= sheetBounds.right)
    }

    @Test
    fun `打开抽屉后实时显示新增思考内容`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var appendReasoning: () -> Unit

        composeRule.setContent {
            var reasoning by remember { mutableStateOf("第一段思考") }
            appendReasoning = { reasoning += "\n第二段思考" }
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "live-sheet",
                    displayedReasoningText = reasoning,
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("正在执行").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第一段思考").fetchSemanticsNode("")

        composeRule.runOnIdle { appendReasoning() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第一段思考\n第二段思考").fetchSemanticsNode("")
    }

    @Test
    fun `短思考内容使用固定加高抽屉且内部不可滚动`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var scrollState: ScrollState
        var windowHeightPx = 1f
        var pixelsPerDp = 1f

        composeRule.setContent {
            scrollState = rememberScrollState()
            windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
            pixelsPerDp = LocalDensity.current.density
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "short-sheet",
                    displayedReasoningText = "简短思考内容",
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    streamingScrollState = scrollState,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("正在执行").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        val sheetHeight = composeRule
            .onNodeWithTag("reasoning-sheet-surface")
            .fetchSemanticsNode("")
            .boundsInRoot
            .height
        assertEquals(
            windowHeightPx * reasoningSheetTallHeightFraction(),
            sheetHeight,
            2f * pixelsPerDp,
        )
        assertTrue("抽屉触发了全屏高度", sheetHeight < windowHeightPx)
        composeRule.onNodeWithTag("reasoning-sheet-header").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-sheet-bottom-indicator").fetchSemanticsNode("")
        assertEquals(0, scrollState.maxValue)
        val lockedTop = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        composeRule
            .onNodeWithTag("reasoning-sheet-drag-handle-disabled", useUnmergedTree = true)
            .performTouchInput {
            val start = center
            swipe(start, start.copy(y = start.y - 600f), durationMillis = 500L)
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        val topAfterLockedSwipe = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertEquals(lockedTop, topAfterLockedSwipe, 1f)
    }

    @Test
    fun `长思考内容实时增长并保留用户阅读位置`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var scrollState: ScrollState
        lateinit var appendReasoning: () -> Unit

        composeRule.setContent {
            var reasoning by remember {
                mutableStateOf((1..60).joinToString("\n") { "初始推理第${it}行" })
            }
            scrollState = rememberScrollState()
            appendReasoning = {
                reasoning += (1..20).joinToString(
                    separator = "\n",
                    prefix = "\n",
                ) { "新增推理第${it}行" }
            }
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "long-sheet",
                    displayedReasoningText = reasoning,
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    streamingScrollState = scrollState,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("正在执行").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        val initialMaxValue = scrollState.maxValue
        assertTrue(initialMaxValue > 0)
        assertEquals(0, scrollState.value)

        composeRule.runOnIdle { appendReasoning() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue(scrollState.maxValue > initialMaxValue)
            assertEquals(0, scrollState.value)
        }
    }

    @Test
    fun `圆点打开已有长内容时默认展开抽屉`() {
        composeRule.mainClock.autoAdvance = false
        var windowHeightPx = 1f

        composeRule.setContent {
            windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "completed-long-sheet",
                    displayedReasoningText = (1..80).joinToString("\n") { "已完成推理第${it}行" },
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    messageIsError = false,
                    mainContentHasStarted = true,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-sheet-review-toggle").performClick()
        composeRule.mainClock.advanceTimeBy(2_000L)
        composeRule.waitForIdle()

        val expandedTop = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertTrue(
            "圆点打开长内容后抽屉未默认展开：top=$expandedTop, window=$windowHeightPx",
            expandedTop < windowHeightPx * 0.35f,
        )
    }

    @Test
    fun `圆点打开短内容时保持默认高度且可下拉关闭`() {
        composeRule.mainClock.autoAdvance = false
        var windowHeightPx = 1f

        composeRule.setContent {
            windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "completed-short-sheet",
                    displayedReasoningText = "简短的已完成推理",
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    messageIsError = false,
                    mainContentHasStarted = true,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-sheet-review-toggle").performClick()
        composeRule.mainClock.advanceTimeBy(2_000L)
        composeRule.waitForIdle()

        val defaultTop = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertTrue("短内容错误触发展开", defaultTop > windowHeightPx * 0.35f)

        composeRule.onNodeWithTag("reasoning-sheet-scroll").performTouchInput {
            val start = center.copy(y = top + 40f)
            swipe(start, start.copy(y = start.y + 520f), durationMillis = 500L)
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        assertTrue(
            "默认高度抽屉下拉后仍未关闭",
            composeRule.onAllNodesWithTag("reasoning-sheet-content").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `抽屉内容由短变长时不程序化跳动到末尾`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var scrollState: ScrollState
        lateinit var appendLongReasoning: () -> Unit

        composeRule.setContent {
            var reasoning by remember { mutableStateOf("第一行思考") }
            scrollState = rememberScrollState()
            appendLongReasoning = {
                reasoning += (1..80).joinToString(
                    separator = "\n",
                    prefix = "\n",
                ) { "持续推理第${it}行" }
            }
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "growing-sheet",
                    displayedReasoningText = reasoning,
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    streamingScrollState = scrollState,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("正在执行").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        assertEquals(0, scrollState.maxValue)
        val initialSheetBounds = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot

        composeRule.runOnIdle { appendLongReasoning() }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            assertTrue(scrollState.maxValue > 0)
            assertEquals(0, scrollState.value)
        }
        val grownSheetBounds = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
        assertEquals(initialSheetBounds.top, grownSheetBounds.top, 1f)
        assertEquals(initialSheetBounds.height, grownSheetBounds.height, 1f)
        composeRule
            .onNodeWithTag("reasoning-sheet-drag-handle-enabled", useUnmergedTree = true)
            .performTouchInput {
            val start = center
            swipe(start, start.copy(y = start.y - 600f), durationMillis = 500L)
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        val manuallyExpandedTop = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertTrue(
            "内容溢出后手动上拉未展开抽屉：before=${grownSheetBounds.top}, after=$manuallyExpandedTop",
            manuallyExpandedTop < grownSheetBounds.top,
        )
        composeRule.onNodeWithTag(
            "reasoning-sheet-drag-handle-enabled",
            useUnmergedTree = true,
        ).fetchSemanticsNode("")

        composeRule.onNodeWithTag("reasoning-sheet-scroll").performTouchInput {
            val start = center
            swipe(start, start.copy(y = start.y - 240f), durationMillis = 500L)
        }
        composeRule.waitForIdle()
        val topAfterContentScroll = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertEquals(manuallyExpandedTop, topAfterContentScroll, 1f)
        assertTrue("加高抽屉展开后正文未接管滚动", scrollState.value > 0)
        composeRule.onNodeWithTag(
            "reasoning-sheet-drag-handle-disabled",
            useUnmergedTree = true,
        ).fetchSemanticsNode("")

        composeRule.runOnIdle {
            scrollState.dispatchRawDelta(-scrollState.value.toFloat())
            assertEquals(0, scrollState.value)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(
            "reasoning-sheet-drag-handle-enabled",
            useUnmergedTree = true,
        ).fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-sheet-scroll").performTouchInput {
            val start = center.copy(y = top + 48f)
            swipe(start, start.copy(y = start.y + 180f), durationMillis = 500L)
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        val collapsedTop = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertTrue("正文位于顶部时再次下拉未收起抽屉", collapsedTop > manuallyExpandedTop)
    }
}
