package com.android.everytalk.ui.screens.BubbleMain.Main

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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
    fun `tool logos follow text in the same content layout`() {
        var pixelsPerDp = 1f
        composeRule.setContent {
            pixelsPerDp = LocalDensity.current.density
            MaterialTheme {
                Box(modifier = Modifier.width(200.dp)) {
                    UserMessageInlineContent(
                        enabledToolIds = listOf(MessageToolIds.WEB_SEARCH, MessageToolIds.MCP),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 80.dp, height = 40.dp)
                                .testTag("user-message-inline-text-test"),
                        )
                    }
                }
            }
        }

        val contentBounds = composeRule
            .onNodeWithTag("user-message-inline-content")
            .fetchSemanticsNode("")
            .boundsInRoot
        val textBounds = composeRule
            .onNodeWithTag("user-message-inline-text-test")
            .fetchSemanticsNode("")
            .boundsInRoot
        val logoBounds = composeRule
            .onNodeWithTag("user-message-tool-logos")
            .fetchSemanticsNode("")
            .boundsInRoot

        assertEquals(textBounds.height, contentBounds.height, pixelsPerDp)
        assertTrue("Logo 没有排在正文后方", logoBounds.left > textBounds.right)
        assertTrue(
            "Logo 没有与正文底部对齐",
            kotlin.math.abs(contentBounds.bottom - logoBounds.bottom) <= 3f * pixelsPerDp,
        )
    }
}
