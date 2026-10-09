package com.android.everytalk.util.locale

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.screens.skill.SkillScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 直接操作真实 Skills 页面，不安装 APK，也不请求远端目录或修改设备数据。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LocalizedSkillScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en")
    fun `英文Skills空页面与创建弹窗完整显示英文`() {
        verifyCreateFlow("No Skills yet", "Back", "Add", "Create Skill", "Name", "Description", "Instructions", "Cancel")
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `中文Skills空页面与创建弹窗保持中文`() {
        verifyCreateFlow("还没有 Skill", "返回", "添加", "创建 Skill", "名称", "用途说明", "具体规则", "取消")
    }

    private fun verifyCreateFlow(
        emptyTitle: String,
        back: String,
        add: String,
        create: String,
        name: String,
        description: String,
        instructions: String,
        cancel: String,
    ) {
        composeRule.setContent {
            // 页面、独立 Popup 和 Dialog 共用真实宿主语言，覆盖新窗口的资源读取。
            MaterialTheme {
                SkillScreen(navController = rememberNavController(), onImportExport = {})
            }
        }
        composeRule.onNodeWithText(emptyTitle).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(back).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(add).performClick()
        composeRule.onNodeWithText(create).performClick()
        composeRule.onNodeWithText(create).assertIsDisplayed()
        composeRule.onNodeWithText(name).assertIsDisplayed()
        composeRule.onNodeWithText(description).assertIsDisplayed()
        composeRule.onNodeWithText(instructions).assertIsDisplayed()
        composeRule.onNodeWithText(cancel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(emptyTitle).assertIsDisplayed()
    }
}
