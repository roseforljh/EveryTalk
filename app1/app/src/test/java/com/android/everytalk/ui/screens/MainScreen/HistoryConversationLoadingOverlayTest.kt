package com.android.everytalk.ui.screens.MainScreen

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HistoryConversationLoadingOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `history loading overlay displays full page progress indicator`() {
        composeRule.setContent {
            MaterialTheme {
                HistoryConversationLoadingOverlay()
            }
        }

        composeRule.onNodeWithContentDescription("正在加载会话").assertIsDisplayed()
    }
}
