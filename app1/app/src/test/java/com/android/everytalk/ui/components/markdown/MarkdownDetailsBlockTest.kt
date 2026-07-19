package com.android.everytalk.ui.components.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.streaming.DetailsRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkdownDetailsBlockTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Before
    fun setUp() {
        stopKoin()
    }

    @Test
    fun `details defaults collapsed and toggles its markdown content`() {
        val request = DetailsRequest(
            id = "a".repeat(64),
            summary = "折叠标题",
            markdown = "折叠正文",
            contentVersion = 1L,
        )
        composeRule.setContent {
            MaterialTheme {
                MarkdownDetailsBlock(request = request) {
                    Text("折叠正文")
                }
            }
        }

        composeRule.onNodeWithText("折叠正文").assertIsNotDisplayed()
        composeRule.onNodeWithText("折叠标题")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("折叠正文").assertIsDisplayed()

        composeRule.onNodeWithText("折叠标题").performClick()
        composeRule.onNodeWithText("折叠正文").assertIsNotDisplayed()
    }
}
