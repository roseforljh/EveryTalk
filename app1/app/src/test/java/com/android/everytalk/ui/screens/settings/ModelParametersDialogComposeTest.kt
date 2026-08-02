package com.android.everytalk.ui.screens.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.CustomModelParameter
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.DataClass.ModelCapabilityCandidate
import com.android.everytalk.data.DataClass.ModelCapabilitySource
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ReasoningMode
import com.android.everytalk.data.DataClass.withModelCapabilityDefaults
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.network.TokenUsageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ModelParametersDialogComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `当前参数在菜单右侧显示勾选图标`() {
        val config = ApiConfig(
            address = "https://example.com",
            key = "test-key",
            model = "gpt-5.6",
            provider = "OpenAI",
            name = "测试模型",
            channel = "OpenAI兼容",
        )

        composeRule.setContent {
            MaterialTheme {
                ModelParametersDialog(
                    config = config,
                    onDismissRequest = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("思考程度下拉框").performClick()

        composeRule
            .onNodeWithContentDescription("已选择 medium", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `OpenAI兼容菜单的自定义入口点击后可直接输入并保存`() {
        val config = openAICompatibleConfig()
        var confirmedConfig: ApiConfig? = null

        composeRule.setContent {
            MaterialTheme {
                ModelParametersDialog(
                    config = config,
                    onDismissRequest = {},
                    onConfirm = { confirmedConfig = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("思考程度下拉框").performClick()
        val customTop = composeRule.onNodeWithText("自定义").fetchSemanticsNode().boundsInRoot.top
        val firstPresetTop = composeRule.onNodeWithText("none").fetchSemanticsNode().boundsInRoot.top
        assertTrue(customTop < firstPresetTop)
        composeRule.onNodeWithText("自定义").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("思考程度下拉框").performTextInput("ultra")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.runOnIdle {
            assertNotNull(confirmedConfig)
            assertEquals("ultra", confirmedConfig?.modelParameters?.reasoningEffort)
            assertEquals(listOf("ultra"), confirmedConfig?.modelParameters?.customReasoningEfforts)
        }
    }

    @Test
    fun `已保存的自定义参数可从菜单删除`() {
        val config = openAICompatibleConfig().copy(
            modelParameters = ModelParameters(
                reasoningEffort = "ultra",
                customParameters = listOf(CustomModelParameter("reasoning_effort", "ultra")),
                customReasoningEfforts = listOf("ultra"),
            )
        )
        var confirmedConfig: ApiConfig? = null

        composeRule.setContent {
            MaterialTheme {
                ModelParametersDialog(
                    config = config,
                    onDismissRequest = {},
                    onConfirm = { confirmedConfig = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("思考程度下拉框").performClick()
        composeRule
            .onNodeWithContentDescription("删除自定义参数 ultra", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("思考程度下拉框").performClick()
        composeRule.onNodeWithText("保存").performClick()

        composeRule.runOnIdle {
            assertEquals("medium", confirmedConfig?.modelParameters?.reasoningEffort)
            assertEquals(emptyList<String>(), confirmedConfig?.modelParameters?.customReasoningEfforts)
        }
    }

    @Test
    fun `最大输出和上下文窗口可编辑并保存`() {
        val config = openAICompatibleConfig()
        var confirmedConfig: ApiConfig? = null

        composeRule.setContent {
            MaterialTheme {
                ModelParametersDialog(
                    config = config,
                    onDismissRequest = {},
                    onConfirm = { confirmedConfig = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("最大输出 tokens")
            .performTextReplacement("8192")
        composeRule.onNodeWithContentDescription("上下文窗口 tokens")
            .performTextReplacement("200000")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.runOnIdle {
            assertEquals(8192, confirmedConfig?.maxTokens)
            assertEquals(200000, confirmedConfig?.modelParameters?.maxContextTokens)
        }
    }

    @Test
    fun `右上角加载按钮自动更新思考能力和 token 限制`() {
        val config = ApiConfig(
            address = "https://api.example.com",
            key = "secret",
            model = "model-a",
            provider = "Gemini",
            name = "测试模型",
            channel = "Gemini",
        )
        val loadedConfig = config.withModelCapabilityDefaults(
            listOf(
                ModelCapabilityCandidate(
                    modelId = "model-a",
                    protocol = ModelParameterProtocol.GEMINI,
                    endpointIdentity = config.address,
                    contextWindowTokens = 1_000_000,
                    maxOutputTokens = 64_000,
                    supportsReasoning = false,
                    source = ModelCapabilitySource.LIVE_ENDPOINT,
                )
            )
        )
        var confirmedConfig: ApiConfig? = null

        composeRule.setContent {
            MaterialTheme {
                ModelParametersDialog(
                    config = config,
                    onDismissRequest = {},
                    onConfirm = { confirmedConfig = it },
                    onAutoLoad = { Result.success(loadedConfig) },
                )
            }
        }

        val titleBounds = composeRule.onNodeWithText("模型参数").fetchSemanticsNode().boundsInRoot
        val loadButton = composeRule.onNodeWithContentDescription("自动获取模型参数")
        assertTrue(loadButton.fetchSemanticsNode().boundsInRoot.left > titleBounds.right)
        loadButton.performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("保存").performClick()

        composeRule.runOnIdle {
            assertEquals(64_000, confirmedConfig?.maxTokens)
            assertEquals(1_000_000, confirmedConfig?.modelParameters?.maxContextTokens)
            assertEquals(ReasoningMode.DISABLED, confirmedConfig?.modelParameters?.reasoningMode)
            assertEquals("none", confirmedConfig?.modelParameters?.reasoningEffort)
            assertEquals(
                ModelCapabilitySource.LIVE_ENDPOINT,
                confirmedConfig?.modelParameters?.resolvedCapability?.contextWindowSource,
            )
        }
    }

    @Test
    fun `参数对话框显示最近请求的上下文占用和数据来源`() {
        val snapshot = ContextUsageSnapshot(
            messageId = "ai-1",
            systemPromptTokens = 10,
            conversationTextTokens = 70,
            mediaTokens = 0,
            toolSchemaTokens = 15,
            protocolOverheadTokens = 5,
            reservedOutputTokens = 50,
            contextWindowTokens = 1_000,
        ).withFinalUsage(
            TokenUsage(
                inputTokens = 100,
                outputTokens = 20,
                totalTokens = 120,
                isFinal = true,
                source = TokenUsageSource.OPENAI_CHAT,
            )
        )

        composeRule.setContent {
            MaterialTheme {
                ModelParametersDialog(
                    config = openAICompatibleConfig(),
                    contextUsageSnapshot = snapshot,
                    onDismissRequest = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithText("上下文占用").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("120 / 1,000 tokens").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("实测").performScrollTo().assertIsDisplayed()
    }

    private fun openAICompatibleConfig() = ApiConfig(
        address = "https://example.com",
        key = "test-key",
        model = "gpt-5.6",
        provider = "OpenAI",
        name = "测试模型",
        channel = "OpenAI兼容",
    )
}
