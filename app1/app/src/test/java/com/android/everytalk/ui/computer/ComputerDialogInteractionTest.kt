package com.android.everytalk.ui.computer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.HostKeyProbeResult
import com.android.everytalk.ui.screens.computer.ComputerAddCard
import com.android.everytalk.ui.screens.computer.ComputerAddFormState
import com.android.everytalk.ui.screens.computer.ComputerHostKeyDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 小屏深色模式下，操作按钮不能随长表单滚出屏幕或在连接时失效。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w360dp-h480dp-night")
class ComputerDialogInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `连接中长私钥表单仍可直接取消`() {
        var cancelled = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ComputerAddCard(
                    form = ComputerAddFormState(authKind = ComputerAuthKind.PRIVATE_KEY),
                    isBusy = true,
                    progressText = "正在连接",
                    errorText = null,
                    onFormChange = {},
                    onSubmit = {},
                    onDismiss = { cancelled = true },
                    submitLabel = "连接",
                )
            }
        }
        compose.onNodeWithText("取消").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("连接").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun `密码和私钥可以切换且取消按钮无需滚动`() {
        var form by mutableStateOf(ComputerAddFormState())
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ComputerAddCard(form, false, null, errorText = null,
                    onFormChange = { form = it }, onSubmit = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("私钥").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertTrue(form.authKind == ComputerAuthKind.PRIVATE_KEY) }
        compose.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test fun `握手确认和取消均可点击`() {
        var confirmed = false
        var cancelled = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ComputerHostKeyDialog(
                    HostKeyProbeResult("example.com", "192.0.2.1", 22, "ssh-ed25519", byteArrayOf(1), "SHA256:test"),
                    onConfirm = { confirmed = true }, onDismiss = { cancelled = true },
                )
            }
        }
        compose.onNodeWithText("确认并连接").assertIsDisplayed().performClick()
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(confirmed); assertTrue(cancelled) }
    }
}
