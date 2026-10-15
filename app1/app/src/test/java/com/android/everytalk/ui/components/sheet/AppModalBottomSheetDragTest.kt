package com.android.everytalk.ui.components.sheet

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** 注入真实触摸事件，验证把手留白和正文滚动区拥有不同的收起规则。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class AppModalBottomSheetDragTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var scroll: ScrollState

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `顶部实体底色且只有上滚正文在标题下边缘渐隐`() {
        lateinit var hostView: View
        rule.setContent {
            scroll = rememberScrollState()
            MaterialTheme(colorScheme = lightColorScheme(surface = Color.Black)) {
                AppModalBottomSheet(
                    onDismissRequest = {},
                    scrollState = scroll,
                    topGradient = true,
                    dragHandleModifier = Modifier.testTag("handle"),
                    scrollModifier = Modifier.testTag("body"),
                    header = {
                        val view = LocalView.current
                        SideEffect { hostView = view }
                        Box(Modifier.fillMaxWidth().height(40.dp).testTag("header"))
                    },
                ) {
                    Box(Modifier.fillMaxWidth().height(3000.dp).background(Color.Red))
                }
            }
        }
        fun capture(tag: String): Bitmap {
            val bounds = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            rule.runOnIdle {
                val canvas = Canvas(bitmap)
                canvas.translate(-bounds.left, -bounds.top)
                hostView.draw(canvas)
            }
            return bitmap
        }
        val handle = capture("handle")
        assertTrue("把手区域必须有实体底色", Color(handle.getPixel(2, handle.height / 2)).red < 0.15f)
        handle.recycle()
        val header = capture("header")
        assertTrue(Color(header.getPixel(header.width / 2, header.height / 2)).red < 0.15f)
        header.recycle()
        val initial = capture("body")
        assertTrue("正文尚未滚动时不应淡化第一行", Color(initial.getPixel(initial.width / 2, 1)).red > 0.95f)
        initial.recycle()

        rule.runOnIdle { scroll.dispatchRawDelta(100f) }
        val scrolled = capture("body")
        val x = scrolled.width / 2
        val fadeHeight = (24 * rule.density.density).toInt()
        assertTrue(Color(scrolled.getPixel(x, 1)).red < 0.15f)
        assertTrue(Color(scrolled.getPixel(x, fadeHeight / 2)).red in 0.3f..0.7f)
        assertTrue("渐变之外正文保持清晰", Color(scrolled.getPixel(x, fadeHeight + 2)).red > 0.95f)
        scrolled.recycle()
    }

    private fun open(longContent: Boolean = true) {
        rule.setContent {
            var visible by remember { mutableStateOf(true) }
            scroll = rememberScrollState()
            MaterialTheme {
                if (visible) AppModalBottomSheet(
                    onDismissRequest = { visible = false },
                    scrollState = scroll,
                    sheetContentModifier = Modifier.testTag("sheet"),
                    dragHandleModifier = Modifier.testTag("handle"),
                    scrollModifier = Modifier.testTag("body"),
                    stateModifier = { state, _ -> Modifier.testTag("state-$state") },
                    header = {},
                ) {
                    Box(Modifier.fillMaxWidth().height(if (longContent) 3000.dp else 80.dp))
                }
            }
        }
        rule.waitForIdle()
    }

    /** 返回必须发给抽屉自己的窗口，才能覆盖手机边缘返回的实际分发路径。 */
    private fun back(edge: Int = BackEventCompat.EDGE_LEFT, repeat: Boolean = false) {
        rule.runOnIdle {
            val dispatcher = (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 0f, 0.8f, edge))
            dispatcher.onBackPressed()
            if (repeat) dispatcher.onBackPressed()
        }
        rule.waitForIdle()
    }

    private fun handleTop(): Float =
        rule.onNodeWithTag("handle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun expandFromHandle() {
        rule.onNodeWithTag("handle", useUnmergedTree = true).performTouchInput {
            swipe(center, center.copy(y = center.y - 1200f), durationMillis = 500L)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("state-Expanded").assertExists()
    }

    @Test
    fun `左右侧滑从中档依次降到低档和关闭且保留正文位置`() {
        open()
        rule.runOnIdle { scroll.dispatchRawDelta(600f) }
        val previousScroll = scroll.value
        val middleTop = handleTop()
        back(repeat = true)
        val lowTop = handleTop()
        assertTrue("一次返回只降到低档，连发不能直接关闭", lowTop > middleTop + 100f)
        assertEquals(previousScroll, scroll.value)
        back(edge = BackEventCompat.EDGE_RIGHT)
        assertTrue(rule.onAllNodesWithTag("sheet").fetchSemanticsNodes().isEmpty())
        assertEquals(previousScroll, scroll.value)
    }

    @Test
    fun `全屏返回先到中档再到低档且重新全屏后恢复顺序`() {
        open()
        val middleTop = handleTop()
        expandFromHandle()
        assertTrue(handleTop() < middleTop - 10f)
        rule.runOnIdle { scroll.dispatchRawDelta(600f) }
        val previousScroll = scroll.value
        back()
        assertEquals(middleTop, handleTop(), 2f)
        assertEquals(previousScroll, scroll.value)
        back()
        assertTrue(handleTop() > middleTop + 100f)
        expandFromHandle()
        back()
        assertEquals("低档重新展开后也应先回到中档", middleTop, handleTop(), 2f)
        back()
        back()
        assertTrue(rule.onAllNodesWithTag("sheet").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `取消左右侧滑不会下降档位或改变正文位置`() {
        open()
        rule.runOnIdle { scroll.dispatchRawDelta(600f) }
        val previousScroll = scroll.value
        val top = handleTop()
        for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            rule.runOnIdle {
                val dispatcher = (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 0f, 0.8f, edge))
                dispatcher.dispatchOnBackCancelled()
            }
            rule.waitForIdle()
            assertEquals(top, handleTop(), 1f)
            assertEquals(previousScroll, scroll.value)
        }
    }

    @Test
    fun `正文在中间时拖动把手留白可以完全关闭`() {
        open()
        rule.runOnIdle { scroll.dispatchRawDelta(600f); assertTrue(scroll.value > 0) }
        val previousScroll = scroll.value
        rule.onNodeWithTag("handle", useUnmergedTree = true).performTouchInput {
            val start = center.copy(x = left + 16f)
            swipe(start, start.copy(y = start.y + 1200f), durationMillis = 500L)
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("sheet").fetchSemanticsNodes().isEmpty())
        assertEquals("把手拖动不能先把正文滚回顶部", previousScroll, scroll.value)
    }

    @Test
    fun `把手手势结束后正文下拉仍然只滚动正文`() {
        open()
        rule.runOnIdle { scroll.dispatchRawDelta(600f) }
        rule.onNodeWithTag("handle", useUnmergedTree = true).performTouchInput {
            swipe(center, center.copy(y = center.y - 30f), durationMillis = 500L)
        }
        rule.waitForIdle()
        val before = scroll.value
        val top = rule.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithTag("body").performTouchInput {
            swipe(center, center.copy(y = center.y + 80f), durationMillis = 500L)
        }
        rule.waitForIdle()
        assertTrue(scroll.value in 1 until before)
        assertEquals(top, rule.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test
    fun `短内容把手下拉同样彻底关闭`() {
        open(longContent = false)
        rule.onNodeWithTag("handle", useUnmergedTree = true).performTouchInput {
            swipe(center, center.copy(y = center.y + 700f), durationMillis = 500L)
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("sheet").fetchSemanticsNodes().isEmpty())
    }
}
