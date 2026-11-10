package com.android.everytalk.ui.screens.appinfo

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.util.locale.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
class AppInfoLanguagePopupTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `语言行打开行旁悬浮卡片而不是对话框并正确选择`() {
        var selected = AppLanguage.SYSTEM
        composeRule.setContent {
            MaterialTheme {
                LanguageEntry(AppLanguage.SYSTEM) { selected = it }
            }
        }
        composeRule.onNodeWithText("语言").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(isPopup()).assertCountEquals(1)
        composeRule.onAllNodes(isDialog()).assertCountEquals(0)
        composeRule.onNodeWithText("English").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(AppLanguage.ENGLISH, selected)
        composeRule.onAllNodes(isPopup()).assertCountEquals(0)
    }

    @Test
    fun `亮色阴影留白计入窗口且点击留白关闭菜单`() {
        composeRule.setContent {
            MaterialTheme {
                LanguageEntry(AppLanguage.SYSTEM) {}
            }
        }
        composeRule.onNodeWithText("语言").performClick()
        composeRule.waitForIdle()
        // 窗口只有卡片大（226dp）时，90dp 阴影会被窗口裁成直角；带阴影弹层必须用铺满的窗口。
        val popupWidth = composeRule.onNode(isPopup()).fetchSemanticsNode().size.width
        val cardWidth = with(composeRule.density) { 226.dp.roundToPx() }
        assertTrue("阴影窗口不能只有卡片大", popupWidth > cardWidth)
        composeRule.onNode(isPopup()).performTouchInput { click(Offset(10f, 10f)) }
        composeRule.waitForIdle()
        composeRule.onAllNodes(isPopup()).assertCountEquals(0)
    }
}
