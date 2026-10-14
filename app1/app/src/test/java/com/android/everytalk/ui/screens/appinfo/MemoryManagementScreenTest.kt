package com.android.everytalk.ui.screens.appinfo

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.memory.MemoryRepository
import com.android.everytalk.navigation.Screen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
class MemoryManagementScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: AppDatabase
    private lateinit var repository: MemoryRepository

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = MemoryRepository(database)
    }
    @After fun close() { database.close() }

    @Test fun `application management entry navigates to memory switches and back`() {
        compose.setContent {
            val nav = rememberNavController()
            MaterialTheme {
                NavHost(navController = nav, startDestination = Screen.APP_INFO_SCREEN) {
                    composable(Screen.APP_INFO_SCREEN) {
                        AppInfoScreen(onBack = {}, onOpenDataManagement = {}, onOpenPrivacyPolicy = {}, onOpenAccountManagement = {}, onOpenSubscription = {},
                            onOpenMemoryManagement = { nav.navigate(Screen.MEMORY_MANAGEMENT_SCREEN) })
                    }
                    composable(Screen.MEMORY_MANAGEMENT_SCREEN) { MemoryManagementScreen(onBack = { nav.popBackStack() }, repository = repository) }
                }
            }
        }
        compose.onNodeWithText("记忆管理").performScrollTo().performClick()
        await("memory_enabled")
        compose.onNodeWithTag("memory_enabled").assertIsOff().performClick()
        compose.waitUntil(5_000) { runBlocking { repository.settings().enabled } }
        compose.onNodeWithTag("memory_auto").assertIsEnabled().assertIsOff()
        compose.onNodeWithTag("memory_enabled").performClick()
        compose.waitUntil(5_000) { !runBlocking { repository.settings().enabled } }
        compose.onNodeWithTag("memory_auto").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("应用管理").assertExists()
    }

    @Test fun `manual add search edit disable and delete all update persistent state`() {
        page()
        compose.onNodeWithText("添加记忆").performScrollTo().performClick()
        compose.onNodeWithTag("memory_editor").performTextInput("请优先使用中文")
        compose.onNodeWithTag("memory_save").performClick()
        compose.waitUntil(5_000) { runBlocking { repository.state().entries.size == 1 } }
        val id = runBlocking { repository.state().entries.single().id }
        awaitCount(1)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("memory_content_$id"))
        compose.onNodeWithTag("memory_search").performScrollTo().performTextInput("没有命中")
        compose.onNodeWithText("没有匹配的记忆").assertExists()
        compose.onNodeWithTag("memory_search").performTextClearance()
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        compose.onNodeWithTag("memory_editor").performTextReplacement("先给结论，再解释")
        compose.onNodeWithTag("memory_save").performClick()
        compose.waitUntil(5_000) { runBlocking { repository.state().entries.single().content == "先给结论，再解释" } }
        compose.onNodeWithTag("memory_entry_$id").performScrollTo().performClick()
        compose.waitUntil(5_000) { !runBlocking { repository.state().entries.single().enabled } }
        compose.onNodeWithText("删除记忆").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, runBlocking { repository.state().entries.size })
        compose.onNodeWithText("删除记忆").performClick()
        compose.onNodeWithTag("memory_confirm_delete").performClick()
        compose.waitUntil(5_000) { runBlocking { repository.state().entries.isEmpty() } }
    }

    @Test fun `clear requires confirmation and invalid input keeps editor and old entries`() {
        runBlocking { repository.save("已有的可靠资料") }
        page()
        compose.onNodeWithText("清空全部记忆").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, runBlocking { repository.state().entries.size })
        compose.onNodeWithText("添加记忆").performClick()
        compose.onNodeWithTag("memory_editor").performTextInput("密码：123456")
        compose.onNodeWithTag("memory_save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("不能保存密钥、令牌、密码、验证码或银行卡等敏感内容").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("memory_editor").assertExists()
        assertEquals(1, runBlocking { repository.state().entries.size })
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("清空全部记忆").performClick()
        compose.onNodeWithTag("memory_confirm_delete").performClick()
        compose.waitUntil(5_000) { runBlocking { repository.state().entries.isEmpty() } }
        awaitCount(0)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("还没有记忆。可在这里添加，或在聊天中明确要求 AI 记住。"))
        compose.onNodeWithText("还没有记忆。可在这里添加，或在聊天中明确要求 AI 记住。").assertExists()
    }

    private fun page() {
        compose.setContent { MaterialTheme { MemoryManagementScreen(onBack = {}, repository = repository) } }
        await("memory_enabled")
    }
    private fun await(tag: String) { compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitCount(count: Int) { compose.waitUntil(5_000) { compose.onAllNodes(hasText("$count / 100", substring = true)).fetchSemanticsNodes().isNotEmpty() } }
}
