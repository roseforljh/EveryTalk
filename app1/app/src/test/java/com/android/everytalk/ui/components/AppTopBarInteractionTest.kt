package com.android.everytalk.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.ui.screens.settings.ModelParametersDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AppTopBarInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `模型列表打开首帧将选中模型置于七项窗口中央`() {
        val models = (0 until 10).map { index ->
            ApiConfig(
                address = "https://example.com",
                key = "test-key",
                model = "model-${index.toString().padStart(2, '0')}",
                provider = "OpenAI",
                id = "model-$index",
                name = "模型 $index",
            )
        }

        composeRule.setContent {
            MaterialTheme {
                AppTopBar(
                    selectedConfigName = models[5].name,
                    onMenuClick = {},
                    onSettingsClick = {},
                    onTitleClick = {},
                    onSystemPromptClick = {},
                    systemPrompt = "",
                    isSystemPromptExpanded = false,
                    showModelSelection = true,
                    modelList = models,
                    selectedApiConfig = models[5],
                    onDismissModelSelection = {},
                )
            }
        }

        composeRule.onNodeWithText("模型 2").assertIsDisplayed()
        composeRule.onNodeWithText("模型 5").assertIsDisplayed()
        composeRule.onNodeWithText("模型 8").assertIsDisplayed()

        val selectedCenter = composeRule.onNodeWithText("模型 5").fetchSemanticsNode().boundsInRoot.center.y
        val dropdownCenter = composeRule.onNodeWithTag("model_selection_dropdown")
            .fetchSemanticsNode().boundsInRoot.center.y
        assertEquals(dropdownCenter, selectedCenter, 0.5f)
    }

    @Test
    fun `长按模型列表中的具体模型触发参数对话框回调`() {
        val model = ApiConfig(
            address = "https://example.com",
            key = "test-key",
            model = "gpt-5.6",
            provider = "OpenAI",
            id = "model-id",
            name = "具体模型",
        )
        var longClickedModelId: String? = null
        var dismissCount = 0
        val dialogTarget = mutableStateOf<ApiConfig?>(null)

        composeRule.setContent {
            MaterialTheme {
                AppTopBar(
                    selectedConfigName = "gpt-5.6",
                    onMenuClick = {},
                    onSettingsClick = {},
                    onTitleClick = {},
                    onSystemPromptClick = {},
                    systemPrompt = "",
                    isSystemPromptExpanded = false,
                    showModelSelection = true,
                    modelList = listOf(model),
                    selectedApiConfig = model,
                    onModelLongClick = {
                        longClickedModelId = it.id
                        dialogTarget.value = it
                    },
                    onDismissModelSelection = { dismissCount++ },
                )
                dialogTarget.value?.let { target ->
                    ModelParametersDialog(
                        config = target,
                        onDismissRequest = { dialogTarget.value = null },
                        onConfirm = { dialogTarget.value = null },
                    )
                }
            }
        }

        composeRule.onNodeWithText("具体模型").performTouchInput { longClick() }
        composeRule.onNodeWithText("模型参数").assertIsDisplayed()
        val visibleModelLabels = composeRule.onAllNodesWithText("具体模型").fetchSemanticsNodes().size

        composeRule.runOnIdle {
            assertEquals("model-id", longClickedModelId)
            assertEquals(0, dismissCount)
            assertTrue(visibleModelLabels >= 2)
        }
    }
}
