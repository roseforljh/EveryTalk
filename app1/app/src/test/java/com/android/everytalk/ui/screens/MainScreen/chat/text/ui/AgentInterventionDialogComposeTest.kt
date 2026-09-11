package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.agent.AgentInterventionPolicyRegistry
import com.android.everytalk.data.agent.InterventionRequestSource
import com.android.everytalk.data.agent.PendingIntervention
import com.android.everytalk.data.agent.ResolutionMaterialKind
import com.android.everytalk.data.agent.SuspensionState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
class AgentInterventionDialogComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `拒绝等待真实结果期间禁止重复提交失败后显示反馈`() {
        val result = CompletableDeferred<Boolean>()
        var calls = 0
        val pending = PendingIntervention(
            suspensionId = "suspension", runId = "run", sessionId = "session",
            capabilityId = "server.restart.confirm", reasonSafe = "是否允许操作", userVisibleContext = null,
            materialKind = ResolutionMaterialKind.NONE,
            fields = AgentInterventionPolicyRegistry().resolve("server.restart.confirm")!!.fields,
            requestSource = InterventionRequestSource.MODEL_HINT,
            rowVersion = 0, state = SuspensionState.WAITING_USER, resolutionNonce = "nonce",
        )
        composeRule.setContent {
            MaterialTheme {
                AgentInterventionDialog(
                    intervention = pending,
                    onResolveNone = {}, onResolveEphemeral = { _, _ -> false },
                    onCreateAuthorization = { _, _ -> false }, onStartCloudflareReauthorization = {},
                    onLoadCloudflareResources = { emptyList() }, onSelectCloudflareResource = { _, _ -> },
                    onReject = { calls++; result.await() },
                    onConfirmUnknownDelivered = {}, onContinueUnknown = {},
                )
            }
        }
        composeRule.onNodeWithText("拒绝").performClick()
        composeRule.onNodeWithText("拒绝").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, calls) }
        composeRule.runOnIdle { result.complete(false) }
        composeRule.onNodeWithText("拒绝操作未被接收，请重试。").assertIsDisplayed()
        composeRule.onNodeWithText("拒绝").assertIsEnabled()
    }
}
