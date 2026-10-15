package com.android.everytalk.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
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
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
class AppTopBarInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `顶栏主体遮住正文且仅下边缘渐变兼容深浅底色`() {
        val background = mutableStateOf(Color.Black)
        lateinit var hostView: View
        composeRule.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            MaterialTheme(colorScheme = lightColorScheme(background = background.value)) {
                // 红色代表滚动到顶栏后方的正文，顶部和按钮空隙应被完全遮挡。
                Box(Modifier.fillMaxWidth().background(Color.Red)) {
                    AppTopBar(
                        selectedConfigName = "GPT",
                        onMenuClick = {},
                        onSettingsClick = {},
                        onTitleClick = {},
                        onSystemPromptClick = {},
                        systemPrompt = "",
                        isSystemPromptExpanded = false,
                        modifier = Modifier.testTag("top-bar"),
                    )
                }
            }
        }
        for (color in listOf(Color.Black, Color.White)) {
            composeRule.runOnIdle { background.value = color }
            val bounds = composeRule.onNodeWithTag("top-bar").fetchSemanticsNode().boundsInRoot
            // 直接画进软件 Canvas，不依赖 Robolectric 中不会触发回调的 PixelCopy。
            val pixels = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            composeRule.runOnIdle {
                val canvas = Canvas(pixels)
                canvas.translate(-bounds.left, -bounds.top)
                hostView.draw(canvas)
            }
            val x = pixels.width / 2 // 按钮之间的空隙，避免图标和阴影影响采样。
            val fadeHeight = (12 * composeRule.density.density).toInt()
            val solid = Color(pixels.getPixel(x, pixels.height - fadeHeight - 2))
            assertEquals(color.red, solid.red, 0.03f)
            assertEquals(color.green, solid.green, 0.03f)
            assertEquals(color.blue, solid.blue, 0.03f)
            val middle = Color(pixels.getPixel(x, pixels.height - fadeHeight / 2))
            val edge = Color(pixels.getPixel(x, pixels.height - 1))
            if (color == Color.Black) {
                assertTrue(middle.red in 0.3f..0.7f)
                assertTrue(edge.red > 0.9f)
            } else {
                assertTrue(middle.green in 0.3f..0.7f)
                assertTrue(edge.green < 0.1f)
            }
            pixels.recycle()
        }
    }

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
