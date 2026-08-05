package com.android.everytalk.ui.screens.settings.dialogs

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AutoFetchModelsConfirmDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `自动获取只继续流程不会触发取消`() {
        var autoFetchCount = 0
        var dismissCount = 0

        composeRule.setContent {
            MaterialTheme {
                AutoFetchModelsConfirmDialog(
                    showDialog = true,
                    onDismiss = { dismissCount++ },
                    onConfirmAutoFetch = { autoFetchCount++ },
                    onManualInput = {},
                )
            }
        }

        composeRule.onNodeWithText("自动获取").performClick()

        composeRule.runOnIdle {
            assertEquals(1, autoFetchCount)
            assertEquals(0, dismissCount)
        }
    }

    @Test
    fun `手动输入只继续流程不会触发取消`() {
        var manualInputCount = 0
        var dismissCount = 0

        composeRule.setContent {
            MaterialTheme {
                AutoFetchModelsConfirmDialog(
                    showDialog = true,
                    onDismiss = { dismissCount++ },
                    onConfirmAutoFetch = {},
                    onManualInput = { manualInputCount++ },
                )
            }
        }

        composeRule.onNodeWithText("手动输入").performClick()

        composeRule.runOnIdle {
            assertEquals(1, manualInputCount)
            assertEquals(0, dismissCount)
        }
    }
}
