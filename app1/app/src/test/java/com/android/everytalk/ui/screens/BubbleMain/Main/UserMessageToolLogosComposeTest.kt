package com.android.everytalk.ui.screens.BubbleMain.Main

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.MessageToolIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class UserMessageToolLogosComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `user message displays tool logos without labels or close icon`() {
        composeRule.setContent {
            MaterialTheme {
                UserMessageToolLogos(
                    enabledToolIds = listOf(MessageToolIds.WEB_SEARCH, MessageToolIds.MCP),
                )
            }
        }

        composeRule.onNodeWithTag("user-message-tool-logos").fetchSemanticsNode("")
        composeRule.onNodeWithTag("user-message-tool-logo-web-search").fetchSemanticsNode("")
        composeRule.onNodeWithTag("user-message-tool-logo-mcp").fetchSemanticsNode("")
        assertTrue(composeRule.onAllNodesWithText("搜索").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("MCP").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `unknown and duplicate tools do not create extra logos`() {
        assertEquals(
            listOf(MessageToolIds.MCP),
            supportedUserMessageToolIds(listOf("unknown", MessageToolIds.MCP, MessageToolIds.MCP)),
        )
    }

    @Test
    fun `tool logos reserve horizontal space without adding a bottom row`() {
        assertEquals(10f, resolveUserMessageContentEndPaddingDp(0), 0.0001f)
        assertEquals(32f, resolveUserMessageContentEndPaddingDp(1), 0.0001f)
        assertEquals(53f, resolveUserMessageContentEndPaddingDp(2), 0.0001f)
    }
}
