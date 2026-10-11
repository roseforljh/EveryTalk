package com.android.everytalk.ui.screens.account

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.android.everytalk.data.account.prepareAccountAvatar
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.account.AccountManager
import com.android.everytalk.data.account.AccountOAuthProvider
import com.android.everytalk.data.account.AccountUiState
import com.android.everytalk.data.account.AccountUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.android.everytalk.ui.theme.App1Theme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 使用真实登录组件验证入口切换，网络认证由已有 AccountAuthTest 覆盖。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class AccountEntryGateTest {
    @get:Rule val composeRule = createComposeRule()

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `恢复页为黑底白圈且两套主题的操作色保持灰阶`() {
        val state = MutableStateFlow(AccountUiState(configured = true))
        val darkTheme = mutableStateOf(false)
        val manager = mockk<AccountManager>(relaxed = true)
        every { manager.state } returns state.asStateFlow()
        var scheme: ColorScheme? = null
        composeRule.setContent {
            App1Theme(darkTheme = darkTheme.value) {
                val current = MaterialTheme.colorScheme
                SideEffect { scheme = current }
                AccountEntryGate(manager) { Text("Main app") }
            }
        }
        for (dark in listOf(false, true)) {
            composeRule.runOnIdle { darkTheme.value = dark }
            composeRule.runOnIdle {
                val colors = requireNotNull(scheme)
                assertEquals(if (dark) Color.Black else Color.White, colors.background)
                assertEquals(if (dark) Color.White else Color.Black, colors.primary)
                assertEquals(if (dark) Color.Black else Color.White, colors.onPrimary)
                for (color in listOf(colors.primary, colors.secondary, colors.tertiary,
                    colors.primaryContainer, colors.secondaryContainer, colors.tertiaryContainer,
                    colors.surfaceTint, colors.outline, colors.outlineVariant)) {
                    assertEquals(color.red, color.green, 0.001f)
                    assertEquals(color.green, color.blue, 0.001f)
                }
            }
            composeRule.onNodeWithContentDescription("Processing…").assertIsDisplayed()
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `头像始终有占位且图片就绪后逐渐显现`() {
        // 使用本地图片验证真实 Coil 解码和 Compose 动画，不依赖外部头像网站。
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = java.io.File(context.cacheDir, "account-avatar-test.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val url = mutableStateOf<String?>(null)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            App1Theme(darkTheme = true) {
                AccountAvatar(url.value, "A", "Test avatar", Modifier.size(48.dp))
            }
        }
        composeRule.onNodeWithContentDescription("Test avatar").assertIsDisplayed()
        composeRule.runOnIdle { url.value = android.net.Uri.fromFile(file).toString() }
        // 占位和图片使用同一个语义容器；URL 更新期间容器不能消失。
        composeRule.onNodeWithContentDescription("Test avatar").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(220)
        composeRule.onNodeWithContentDescription("Test avatar").assertIsDisplayed()
        composeRule.runOnIdle { url.value = android.net.Uri.fromFile(java.io.File(context.cacheDir, "missing-avatar.png")).toString() }
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithContentDescription("Test avatar").assertIsDisplayed()
        file.delete()
    }

    @Test fun `浅深主题切换保留邮箱输入且验证码操作仍可用`() {
        val state = MutableStateFlow(AccountUiState(configured = true, initialized = true))
        val darkTheme = mutableStateOf(false)
        val manager = mockk<AccountManager>(relaxed = true)
        every { manager.state } returns state.asStateFlow()
        composeRule.setContent {
            App1Theme(darkTheme = darkTheme.value, dynamicColor = false) {
                AccountScreen(manager, onBack = {}, onPrivacy = {}, onContinueWithoutAccount = {})
            }
        }
        composeRule.onNodeWithText("Send code").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Email address").performTextInput("hello@example.com")
        composeRule.runOnIdle { darkTheme.value = true }
        composeRule.onNodeWithText("hello@example.com").assertIsDisplayed()
        // Robolectric 下不依赖键盘和滚动后的触摸坐标，直接执行按钮的无障碍点击动作。
        composeRule.onNodeWithText("Send code").performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { verify(exactly = 1) { manager.sendEmailCode("hello@example.com") } }
        composeRule.runOnIdle { state.value = state.value.copy(pendingEmail = "hello@example.com") }
        composeRule.onNodeWithText("Verify and continue").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("6-digit email code").performTextInput("a1b23456")
        composeRule.onNodeWithText("Verify and continue").performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { verify(exactly = 1) { manager.verifyEmailCode("123456") } }
    }

    @Test fun `第三方图标选择正确提供方且等待授权时禁用邮箱和重复登录`() {
        val state = MutableStateFlow(AccountUiState(configured = true, initialized = true))
        val manager = mockk<AccountManager>(relaxed = true)
        every { manager.state } returns state.asStateFlow()
        composeRule.setContent {
            App1Theme(darkTheme = true, dynamicColor = false) {
                AccountScreen(manager, onBack = {}, onPrivacy = {}, onContinueWithoutAccount = {})
            }
        }
        composeRule.onNodeWithContentDescription("Email address").performTextInput("hello@example.com")
        // 两个入口只显示图标；其读屏名称和点击操作仍需完整保留。
        composeRule.onNodeWithText("Continue with Google").assertDoesNotExist()
        composeRule.onNodeWithText("Continue with GitHub").assertDoesNotExist()
        for ((label, provider) in listOf("Continue with Google" to AccountOAuthProvider.Google,
            "Continue with GitHub" to AccountOAuthProvider.GitHub)) {
            composeRule.onNodeWithContentDescription(label).performScrollTo().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            composeRule.runOnIdle { verify(exactly = 1) { manager.startOAuthLogin(provider, any()) } }
        }
        composeRule.runOnIdle { state.value = state.value.copy(awaitingOAuth = true,
            message = "请在浏览器中完成第三方登录") }
        composeRule.onNodeWithText("Waiting for browser sign-in").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Complete third-party sign-in in your browser").assertDoesNotExist()
        // 隐私与游客入口必须随同一滚动内容可达，不能再被独立底栏遮住。
        composeRule.onNodeWithText("Privacy policy").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Continue without an account").assertIsDisplayed()
        composeRule.runOnIdle { state.value = state.value.copy(message = "登录回调不属于本次授权") }
        composeRule.onNodeWithText("This sign-in callback belongs to another authorization request")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Continue with Google").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Continue with GitHub").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Email address").assertIsNotEnabled()
        composeRule.onNodeWithText("Send code").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel sign-in").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { verify(exactly = 1) { manager.cancelOAuthLogin() } }
        composeRule.runOnIdle { state.value = state.value.copy(awaitingOAuth = false) }
        composeRule.onNodeWithText("hello@example.com").assertExists()
        composeRule.onNodeWithText("Send code").assertIsEnabled()
    }

    @Test fun `账号管理可以保存名称并且退出必须确认`() {
        val state = MutableStateFlow(AccountUiState(configured = true, initialized = true,
            user = AccountUser("user-1", "hello@example.com")))
        val manager = mockk<AccountManager>(relaxed = true)
        every { manager.state } returns state.asStateFlow()
        composeRule.setContent {
            App1Theme(darkTheme = false, dynamicColor = false) {
                AccountScreen(manager, onBack = {}, onPrivacy = {})
            }
        }
        composeRule.onNodeWithText("Save name").assertIsNotEnabled()
        composeRule.onNodeWithText("Name").performTextClearance()
        composeRule.onNodeWithText("Name").performTextInput("New name")
        composeRule.onNodeWithText("Save name").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { verify(exactly = 1) { manager.updateDisplayName("New name") } }
        composeRule.onNodeWithText("Sign out").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { verify(exactly = 0) { manager.logout() } }
        composeRule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.runOnIdle { verify(exactly = 0) { manager.logout() } }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `大照片转为小头像且损坏或超大输入被拒绝`() {
        val original = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { stream ->
            assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        }
        original.recycle()
        val png = prepareAccountAvatar(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        assertTrue(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
        assertEquals("image/png", bounds.outMimeType)
        assertTrue(runCatching { prepareAccountAvatar(byteArrayOf(1, 2, 3)) }.isFailure)
        assertTrue(runCatching { prepareAccountAvatar(ByteArray(8 * 1024 * 1024 + 1)) }.isFailure)
    }

    @Test fun `恢复期间不闪登录页且游客登录退出均切换到正确页面`() {
        val state = MutableStateFlow(AccountUiState(configured = true))
        val manager = mockk<AccountManager>(relaxed = true)
        every { manager.state } returns state.asStateFlow()
        composeRule.setContent {
            MaterialTheme { AccountEntryGate(manager) { Text("Main app") } }
        }
        composeRule.onNodeWithText("Main app").assertDoesNotExist()
        composeRule.onNodeWithText("Continue without an account").assertDoesNotExist()

        // 恢复已登录会话后直接挂载主界面；失效或退出后才显示注册/登录表单。
        composeRule.runOnIdle {
            state.value = state.value.copy(initialized = true, user = AccountUser("user-1", "hello@example.com"))
        }
        composeRule.onNodeWithText("Main app").assertIsDisplayed()
        composeRule.onNodeWithText("Continue without an account").assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(user = null) }
        composeRule.onNodeWithText("Main app").assertDoesNotExist()
        composeRule.onNodeWithText("Continue without an account").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithText("Main app").assertIsDisplayed()

        // 游客后来登录，再退出时应重新显示登录页，不能沿用先前的游客选择。
        composeRule.runOnIdle { state.value = state.value.copy(user = AccountUser("user-1", "hello@example.com")) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { state.value = state.value.copy(user = null) }
        composeRule.onNodeWithText("Main app").assertDoesNotExist()
        composeRule.onNodeWithText("Continue without an account").performScrollTo().assertIsDisplayed()
    }
}
