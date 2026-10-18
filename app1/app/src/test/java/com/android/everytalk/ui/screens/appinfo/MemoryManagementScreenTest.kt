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

    @Test fun `global switch enables automatic capture and removed controls stay absent`() {
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
        compose.waitUntil(5_000) { runBlocking { repository.settings().autoCaptureEnabled } }
        compose.onNodeWithTag("memory_auto").assertDoesNotExist()
        compose.onNodeWithTag("memory_capacity").assertDoesNotExist()
        compose.onNodeWithText("清空全部记忆").assertDoesNotExist()
        compose.onNodeWithText("添加记忆").assertDoesNotExist()
        compose.waitForIdle()
        compose.onNodeWithTag("memory_enabled").performClick()
        compose.waitUntil(5_000) { !runBlocking { repository.settings().autoCaptureEnabled } }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("应用管理").assertExists()
    }

    @Test fun `shared text can be edited saved and deleted through the same editor`() {
        runBlocking { repository.save("原来的资料") }
        page()
        awaitText("原来的资料")
        compose.onNodeWithTag("memory_editor").performScrollTo().performTextReplacement("先给结论，再解释")
        save()
        compose.waitUntil(5_000) { runBlocking { repository.state().document == "先给结论，再解释" } }
        awaitText("先给结论，再解释")
        compose.onNodeWithTag("memory_editor").performScrollTo().performTextClearance()
        save()
        compose.waitUntil(5_000) { runBlocking { repository.state().entries.isEmpty() } }
    }

    @Test fun `AI changes update pristine editor without overwriting a manual draft`() {
        page()
        runBlocking { MemoryRepository(database).save("AI 新保存的共享记忆") }
        awaitText("AI 新保存的共享记忆")
        compose.onNodeWithTag("memory_editor").performScrollTo().performTextReplacement("正在手动修改")
        runBlocking { MemoryRepository(database).save("编辑期间新增的共享记忆") }
        compose.waitForIdle()
        compose.onNodeWithTag("memory_editor").assertTextContains("正在手动修改")
        save()
        compose.waitUntil(5_000) {
            runBlocking { repository.state().entries.map { it.content }.toSet() == setOf("正在手动修改", "编辑期间新增的共享记忆") }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("memory_editor").assertTextContains("编辑期间新增的共享记忆", substring = true)
    }

    @Test fun `failed validation keeps draft and persistent memories intact`() {
        runBlocking { repository.save("已有的可靠资料") }
        page()
        awaitText("已有的可靠资料")
        compose.onNodeWithTag("memory_editor").performScrollTo().performTextReplacement("密码：123456")
        save()
        await("memory_error")
        compose.onNodeWithTag("memory_editor").assertTextContains("密码：123456")
        assertEquals("已有的可靠资料", runBlocking { repository.state().document })
    }

    private fun page() {
        compose.setContent { MaterialTheme { MemoryManagementScreen(onBack = {}, repository = repository) } }
        await("memory_editor")
    }
    private fun save() { compose.onNodeWithTag("memory_save").performScrollTo().performClick() }
    private fun await(tag: String) { compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("memory_editor") and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
