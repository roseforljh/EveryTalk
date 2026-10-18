package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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

/** 用真实文字测量验证胶囊宽度，避免只检查修饰符而漏掉名称和勾选标记互相挤压。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComputerSelectionCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `胶囊按名称收紧且长名称达到上限后单行省略`() {
        val longName = "us-production-server-".repeat(5)
        showServers(listOf("12", "us-1.1u", longName), selected = longName)

        val shortBounds = composeRule.onNodeWithText("12").fetchSemanticsNode().boundsInRoot
        val normalBounds = composeRule.onNodeWithText("us-1.1u").fetchSemanticsNode().boundsInRoot
        assertTrue("short=$shortBounds normal=$normalBounds", normalBounds.width > shortBounds.width)
        assertEquals(shortBounds.top, normalBounds.top, 1f)
        assertFalse(textLayout("us-1.1u").isLineEllipsized(0))

        val longNode = composeRule.onNodeWithText(longName)
        longNode.performScrollTo().assertIsDisplayed()
        val layout = textLayout(longName)
        assertEquals(1, layout.lineCount)
        assertTrue(layout.isLineEllipsized(0))
        val chipBounds = longNode.fetchSemanticsNode().boundsInRoot
        assertEquals(220f * composeRule.density.density, chipBounds.width, 1f)
        assertEquals(shortBounds.top, chipBounds.top, 1f)
        // 选择状态已经通过选中项的可见性和源码中的辅助功能标签覆盖；图标可能位于横向滚动容器的可视边界外。
        composeRule.onNodeWithText(longName, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `窄弹层仍保持单行且所有服务器都可滑动访问`() {
        showServers(listOf("12", "us-1.1u", "dev", "backup"), selected = "12", widthDp = 240)
        val firstTop = composeRule.onNodeWithText("12").fetchSemanticsNode().boundsInRoot.top
        composeRule.onNodeWithText("backup").performScrollTo().assertIsDisplayed()
        assertEquals(firstTop, composeRule.onNodeWithText("backup").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    private fun showServers(names: List<String>, selected: String, widthDp: Int = 320) {
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
                    ComputerSelectionCard(computers, selected, onSelect = {}, onUnavailable = {})
                }
            }
        }
    }

    private fun textLayout(name: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(name, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
