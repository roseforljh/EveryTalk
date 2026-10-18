package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 用真实布局验证名称省略、自动换行和限高滚动，避免浮层遮住过多聊天内容。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComputerSelectionCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `服务器选项固定宽度且长名称单行省略`() {
        val longName = "us-production-server-".repeat(5)
        showServers(listOf("12", "us-1.1u", longName), selected = longName)

        val shortBounds = composeRule.onNodeWithText("12").fetchSemanticsNode().boundsInRoot
        val normalBounds = composeRule.onNodeWithText("us-1.1u").fetchSemanticsNode().boundsInRoot
        assertEquals(shortBounds.width, normalBounds.width, 1f)
        assertEquals(shortBounds.top, normalBounds.top, 1f)
        assertTrue(textLayout("us-1.1u").isLineEllipsized(0))

        val longNode = composeRule.onNodeWithText(longName)
        longNode.performScrollTo().assertIsDisplayed()
        val layout = textLayout(longName)
        assertEquals(1, layout.lineCount)
        assertTrue(layout.isLineEllipsized(0))
        val chipBounds = longNode.fetchSemanticsNode().boundsInRoot
        assertEquals(92f * composeRule.density.density, chipBounds.width, 1f)
        assertEquals(shortBounds.top, chipBounds.top, 1f)
        composeRule.onNodeWithText(longName, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `窄弹层自动换行且未达到上限时按内容增高`() {
        showServers(listOf("12", "us-1.1u", "dev", "backup"), selected = "12", widthDp = 240)
        val firstTop = composeRule.onNodeWithText("12").fetchSemanticsNode().boundsInRoot.top
        val lastNode = composeRule.onNodeWithText("backup").assertIsDisplayed()
        assertTrue(lastNode.fetchSemanticsNode().boundsInRoot.top > firstTop)
        assertEquals(104f * composeRule.density.density, scrollHeight(), 1f)
    }

    @Test
    fun `只有一行服务器时卡片不占满最大高度`() {
        showServers(listOf("dev"), selected = "dev")
        assertEquals(48f * composeRule.density.density, scrollHeight(), 1f)
    }

    @Test
    fun `超过三行后高度保持上限且能滚动选择末尾服务器`() {
        val names = (1..8).map { "production-server-$it" }
        var selectedId: String? = null
        showServers(names, selected = names.first(), widthDp = 240, onSelect = { selectedId = it.id })

        // 可滚动区域为三行的 160dp；外侧上下各有 10dp 留白，总高度不超过 180dp。
        val maxHeight = 160f * composeRule.density.density
        assertEquals(maxHeight, scrollHeight(), 1f)
        val firstTop = composeRule.onNodeWithText(names.first()).fetchSemanticsNode().boundsInRoot.top
        composeRule.onNodeWithText(names.last()).performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(names.last(), selectedId) }
        assertEquals(maxHeight, scrollHeight(), 1f)
        assertTrue(composeRule.onNodeWithText(names.first()).fetchSemanticsNode().boundsInRoot.top < firstTop)
    }

    @Test
    fun `多选点击立即提交且选项宽度保持固定`() {
        var changed: List<String>? = null
        var immediateSelection = false
        showServers(listOf("dev", "backup"), "dev", onSelect = { immediateSelection = true },
            onSelectionChanged = { changed = it })
        composeRule.onNodeWithText("backup").performClick()
        composeRule.runOnIdle { assertEquals(listOf("dev", "backup"), changed); assertFalse(immediateSelection) }
        val dev = composeRule.onNodeWithText("dev").fetchSemanticsNode().boundsInRoot
        val backup = composeRule.onNodeWithText("backup").fetchSemanticsNode().boundsInRoot
        assertEquals(dev.width, backup.width, 1f)
    }

    private fun showServers(
        names: List<String>,
        selected: String,
        widthDp: Int = 320,
        onSelect: (Computer) -> Unit = {},
        onSelectionChanged: ((List<String>) -> Unit)? = null,
    ) {
        val computers = names.map { name ->
            Computer(
                id = name,
                displayName = name,
                host = "example.invalid",
                port = 22,
                username = "test",
                authKind = ComputerAuthKind.PRIVATE_KEY,
                runMode = ComputerRunMode.DIRECT,
                status = ComputerStatus.READY,
            )
        }
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(widthDp.dp)) {
                    ComputerSelectionCard(computers, selected, onSelect = onSelect, onUnavailable = {},
                        onSelectionChanged = onSelectionChanged)
                }
            }
        }
    }

    private fun scrollHeight(): Float =
        composeRule.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot.height

    private fun textLayout(name: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(name, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
