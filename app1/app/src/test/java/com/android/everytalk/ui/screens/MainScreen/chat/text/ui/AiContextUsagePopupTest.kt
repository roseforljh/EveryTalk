package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.Sender
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
class AiContextUsagePopupTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `用量摘要使用本轮输入输出和上下文快照`() {
        val summary = aiContextUsageSummary(
            message = usageMessage(),
            conversationTotalTokens = 420,
        )

        assertNotNull(summary)
        assertEquals(100L, summary?.inputTokens)
        assertEquals(20L, summary?.outputTokens)
        assertEquals(120L, summary?.turnTotalTokens)
        assertEquals(1_000L, summary?.contextWindowTokens)
        assertEquals(120L, summary?.currentContextTokens)
        assertEquals(420L, summary?.conversationTotalTokens)
        assertEquals(0.12f, summary?.fraction)
        assertTrue(summary?.isMeasured == true)
    }

    @Test
    fun `目前总消耗累计整个会话并忽略重复消息`() {
        val first = usageMessage(id = "ai-1", inputTokens = 100, outputTokens = 20)
        val second = usageMessage(id = "ai-2", inputTokens = 200, outputTokens = 40)
        val user = Message(id = "user-1", text = "问题", sender = Sender.User)

        assertEquals(
            360L,
            totalConversationTokenUsage(listOf(first, first, user, second)),
        )
    }

    @Test
    fun `模型参数仅在整个会话都有实测用量时返回累计值`() {
        val first = usageMessage(id = "ai-1", inputTokens = 100, outputTokens = 20)
        val second = usageMessage(id = "ai-2", inputTokens = 200, outputTokens = 40)

        assertEquals(360L, totalMeasuredConversationTokenUsage(listOf(first, second)))
        assertEquals(
            null,
            totalMeasuredConversationTokenUsage(
                listOf(first, second.copy(tokenUsage = second.tokenUsage?.copy(source = TokenUsageSource.ESTIMATED)))
            ),
        )
    }

    @Test
    fun `圆环比例和颜色覆盖完整占用区间`() {
        assertEquals(0f, contextUsageFraction(-1, 1_000))
        assertEquals(0.5f, contextUsageFraction(500, 1_000))
        assertEquals(1f, contextUsageFraction(2_000, 1_000))
        assertEquals(Color(0xFF22C55E), contextUsageColor(0f))
        assertEquals(Color(0xFFF59E0B), contextUsageColor(0.5f))
        assertEquals(Color(0xFFEF4444), contextUsageColor(1f))
    }

    @Test
    fun `模型参数更新后圆环使用同一配置的最新上下文上限`() {
        val message = usageMessage(configId = "config-1")
        val updatedConfig = ApiConfig(
            address = "https://example.com",
            key = "test-key",
            model = "gpt-test",
            provider = "OpenAI",
            id = "config-1",
            name = "测试模型",
            modelParameters = ModelParameters(maxContextTokens = 2_000),
        )

        assertEquals(
            2_000L,
            resolveLiveContextWindowTokens(message, listOf(updatedConfig)),
        )
    }

    @Test
    fun `切换模型后圆环使用当前会话模型的上下文上限`() {
        val message = usageMessage(configId = "model-a")
        val modelA = ApiConfig(
            address = "https://example.com",
            key = "test-key",
            model = "model-a",
            provider = "OpenAI",
            id = "model-a",
            name = "模型 A",
            modelParameters = ModelParameters(maxContextTokens = 128_000),
        )
        val modelB = modelA.copy(
            model = "model-b",
            id = "model-b",
            name = "模型 B",
            modelParameters = ModelParameters(maxContextTokens = 1_000_000),
        )

        assertEquals(
            1_000_000L,
            resolveLiveContextWindowTokens(
                message = message,
                configs = listOf(modelA, modelB),
                activeConfigId = modelB.id,
            ),
        )
    }

    @Test
    fun `最新上下文上限变化时圆环实时重组`() {
        val message = usageMessage(configId = "config-1")
        lateinit var updateContextWindow: (Long) -> Unit
        composeRule.setContent {
            MaterialTheme {
                var liveContextWindowTokens by remember { mutableStateOf(1_000L) }
                updateContextWindow = { liveContextWindowTokens = it }
                AiContextUsageButton(
                    message = message,
                    conversationTotalTokens = 420,
                    liveContextWindowTokens = liveContextWindowTokens,
                    expanded = false,
                    onClick = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("查看上下文用量 12%").assertIsDisplayed()
        composeRule.runOnIdle { updateContextWindow(2_000L) }
        composeRule.onNodeWithContentDescription("查看上下文用量 6%").assertIsDisplayed()
    }

    @Test
    fun `点击圆环显示三项用量和上下箭头`() {
        val message = usageMessage()
        composeRule.setContent {
            MaterialTheme {
                var expanded by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    AiContextUsageButton(
                        message = message,
                        conversationTotalTokens = 420,
                        expanded = expanded,
                        onClick = { expanded = true },
                        onDismiss = { expanded = false },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("查看上下文用量 12%").performClick()
        composeRule.onNodeWithText("上下文用量").assertIsDisplayed()
        composeRule.onNodeWithText("本轮会话消耗").assertIsDisplayed()
        composeRule.onNodeWithText("100").assertIsDisplayed()
        composeRule.onNodeWithText("20").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("输入 tokens").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("输出 tokens").assertIsDisplayed()
        composeRule.onNodeWithText("目前总消耗").assertIsDisplayed()
        composeRule.onNodeWithText("420").assertIsDisplayed()
        composeRule.onNodeWithText("总上下文").assertIsDisplayed()
        composeRule.onNodeWithText("1,000").assertIsDisplayed()
    }

    private fun usageMessage(
        id: String = "ai-1",
        inputTokens: Long = 100,
        outputTokens: Long = 20,
        configId: String? = null,
    ): Message {
        val usage = TokenUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = inputTokens + outputTokens,
            isFinal = true,
            source = TokenUsageSource.OPENAI_CHAT,
        )
        val snapshot = ContextUsageSnapshot(
            messageId = id,
            configId = configId,
            systemPromptTokens = 10,
            conversationTextTokens = 70,
            mediaTokens = 0,
            toolSchemaTokens = 15,
            protocolOverheadTokens = 5,
            reservedOutputTokens = 50,
            contextWindowTokens = 1_000,
        ).withFinalUsage(usage)
        return Message(
            id = id,
            text = "测试回复",
            sender = Sender.AI,
            tokenUsage = usage,
            contextUsageSnapshot = snapshot,
        )
    }
}
