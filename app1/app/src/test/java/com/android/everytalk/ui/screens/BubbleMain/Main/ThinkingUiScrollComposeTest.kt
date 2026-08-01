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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun `推理期间主界面只显示单行思考状态`() {
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

        composeRule.onNodeWithContentDescription("思考中").fetchSemanticsNode("")
        assertTrue(composeRule.onAllNodesWithText(reasoning).fetchSemanticsNodes().isEmpty())

        val rootHeight = composeRule
            .onNodeWithTag("reasoning-root")
            .fetchSemanticsNode("")
            .boundsInRoot
            .height
        assertTrue("思考状态超过单行高度", rootHeight < 56f * pixelsPerDp)
    }

    @Test
    fun `底部加载动画只在打开思考抽屉后组成`() {
        composeRule.mainClock.autoAdvance = false
        var pixelsPerDp = 1f
        composeRule.setContent {
            pixelsPerDp = LocalDensity.current.density
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "sheet-loader",
                    displayedReasoningText = "",
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
            composeRule.onAllNodesWithContentDescription("思考内容加载中")
                .fetchSemanticsNodes()
                .isEmpty()
        )

        composeRule.onNodeWithContentDescription("思考中").performClick()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("思考内容加载中").fetchSemanticsNode("")
        assertTrue(
            composeRule.onAllNodesWithText("正在等待思考内容...")
                .fetchSemanticsNodes()
                .isEmpty()
        )
        val sheetBounds = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
        val loaderBounds = composeRule
            .onNodeWithTag("reasoning-sheet-loader-dots")
            .fetchSemanticsNode("")
            .boundsInRoot
        assertTrue(
            "加载圆点未与正文左边距对齐",
            kotlin.math.abs(loaderBounds.left - sheetBounds.left - 20f * pixelsPerDp) <=
                2f * pixelsPerDp,
        )
        assertTrue(
            "加载圆点尺寸过大",
            loaderBounds.width <= 24f * pixelsPerDp,
        )
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
        composeRule.onNodeWithContentDescription("思考中").performClick()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第一段思考").fetchSemanticsNode("")

        composeRule.runOnIdle { appendReasoning() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第一段思考\n第二段思考").fetchSemanticsNode("")
    }

    @Test
    fun `短思考内容保持自然抽屉高度且内部不可滚动`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var scrollState: ScrollState
        var windowHeightPx = 1f

        composeRule.setContent {
            scrollState = rememberScrollState()
            windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
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
        composeRule.onNodeWithContentDescription("思考中").performClick()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        val sheetContentHeight = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .height
        assertTrue("短内容抽屉被撑高", sheetContentHeight < windowHeightPx * 0.5f)
        assertEquals(0, scrollState.maxValue)
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
        composeRule.onNodeWithContentDescription("思考中").performClick()
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
        composeRule.onNodeWithContentDescription("思考中").performClick()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        assertEquals(0, scrollState.maxValue)

        composeRule.runOnIdle { appendLongReasoning() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue(scrollState.maxValue > 0)
            assertEquals(0, scrollState.value)
        }
    }
}
