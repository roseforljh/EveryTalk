package com.android.everytalk.ui.screens.ImageGeneration

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ImageRatio
import com.android.everytalk.data.DataClass.imageParameterKeys
import com.android.everytalk.ui.components.ImageGenCapabilities
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.FunctionPanelContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
class ImageFunctionPanelComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `菜单随图片接口切换且点击进入对应操作`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val format = mutableStateOf("qwen")
        var action = ""
        composeRule.setContent {
            MaterialTheme {
                if (format.value == "text") {
                    FunctionPanelContent(
                        isWebSearchEnabled = false, isWebSearchAvailable = true, onToggleWebSearch = {},
                        isCodeExecutionEnabled = false, onToggleCodeExecution = {}, isGeminiChannel = false,
                        onToggleImagePanel = {}, onToggleMoreOptionsPanel = {}, hasContent = false,
                        onClearContent = {}, onDismiss = {},
                    )
                } else ImageFunctionPanelContent(
                    supportsImageEditing = format.value != "modal", hasContent = true,
                    isQwenEdit = format.value == "qwen",
                    detectedFamily = if (format.value == "modal") ImageGenCapabilities.ModelFamily.MODAL_Z_IMAGE else ImageGenCapabilities.ModelFamily.GPT_IMAGE,
                    onChangeImageSteps = {}, onChangeImageParams = { _, _ -> },
                    onOpenGallery = { action = "gallery" }, onOpenCamera = { action = "camera" },
                    onShowRatioDialog = { action = "ratio" }, onShowStepsDialog = { action = "steps" },
                    onShowParamsDialog = { action = "params" }, onClearContent = {}, currentImageSteps = 4,
                    selectedImageRatio = ImageRatio.AUTO, isGptImage = format.value == "openai",
                    onShowQualityDialog = { action = "quality" }, parameterKeys = imageParameterKeys(format.value),
                )
            }
        }

        val gallery = context.getString(R.string.image_select_image)
        val params = context.getString(R.string.image_adjust_parameters)
        val steps = context.getString(R.string.image_steps_label, 4)
        val ratio = context.getString(R.string.image_ratio_label, "AUTO")
        val search = context.getString(R.string.chat_input_web_search)
        val quality = context.getString(R.string.image_quality_label, context.getString(R.string.image_quality_auto))
        composeRule.onNodeWithText(gallery).assertIsDisplayed().performClick()
        assertEquals("gallery", action)
        composeRule.onNodeWithText(params).performClick()
        assertEquals("params", action)
        composeRule.onNodeWithText(ratio).assertDoesNotExist()
        composeRule.onNodeWithText(search).assertDoesNotExist()
        composeRule.onNodeWithText("MCP").assertDoesNotExist()

        composeRule.runOnIdle { format.value = "modal" }
        composeRule.onNodeWithText(gallery).assertDoesNotExist()
        composeRule.onNodeWithText(ratio).assertIsDisplayed()
        composeRule.onNodeWithText(steps).performClick()
        assertEquals("steps", action)

        composeRule.runOnIdle { format.value = "openai" }
        composeRule.onNodeWithText(steps).assertDoesNotExist()
        composeRule.onNodeWithText(params).assertDoesNotExist()
        composeRule.onNodeWithText(quality).performClick()
        assertEquals("quality", action)

        composeRule.runOnIdle { format.value = "text" }
        composeRule.onNodeWithText(quality).assertDoesNotExist()
        composeRule.onNodeWithText(search).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("MCP").performScrollTo().assertIsDisplayed()
    }
}
