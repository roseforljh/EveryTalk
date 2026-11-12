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
        // 分组在懒加载列表里，先滚到所在分组让它被组合出来。
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("general_group")
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
        compose.onNodeWithTag("app_info_screen").assertExists()
    }

    @Test fun `notes folder lists each note as a file that can be edited created and deleted`() {
        val note = runBlocking { repository.save("原来的资料") }
        page()
        compose.onNodeWithTag("memory_folder_notes").performScrollTo().performClick()
        await("memory_note_${note.id}")
        compose.onNodeWithTag("memory_note_${note.id}").performClick()
        await("memory_editor")
        compose.onNodeWithTag("memory_editor").performTextReplacement("先给结论，再解释")
        compose.onNodeWithTag("memory_save").performClick()
        compose.waitUntil(5_000) { runBlocking { repository.state().document == "先给结论，再解释" } }

        await("memory_new_note")
        compose.onNodeWithTag("memory_new_note").performClick()
        await("memory_editor")
        compose.onNodeWithTag("memory_editor").performTextReplacement("第二条笔记")
        compose.onNodeWithTag("memory_save").performClick()
        compose.waitUntil(5_000) { runBlocking { repository.state().entries.size == 2 } }

        await("memory_note_${note.id}")
        compose.onNodeWithTag("memory_note_${note.id}").performClick()
        await("memory_note_delete")
        compose.onNodeWithTag("memory_note_delete").performClick()
        compose.onNodeWithText("移除").performClick()
        compose.waitUntil(5_000) { runBlocking { repository.state().entries.map { it.content } == listOf("第二条笔记") } }
    }

    @Test fun `failed validation keeps draft and persistent memories intact`() {
        val note = runBlocking { repository.save("已有的可靠资料") }
        page()
        compose.onNodeWithTag("memory_folder_notes").performScrollTo().performClick()
        await("memory_note_${note.id}")
        compose.onNodeWithTag("memory_note_${note.id}").performClick()
        await("memory_editor")
        compose.onNodeWithTag("memory_editor").performTextReplacement("密码：123456")
        compose.onNodeWithTag("memory_save").performClick()
        await("memory_error")
        compose.onNodeWithTag("memory_editor").assertTextContains("密码：123456")
        assertEquals("已有的可靠资料", runBlocking { repository.state().document })
    }

    @Test fun `overview links open conversation files which can be forgotten`() {
        runBlocking {
            database.memoryDao().upsertDocument(com.android.everytalk.data.database.entities.MemoryDocumentEntity(
                "chat-1", com.android.everytalk.data.database.entities.MemoryDocumentEntity.KIND_CONVERSATION, "节点迁移", "- 用户要求迁到 sing-box", 10, 20))
            database.memoryDao().upsertDocument(com.android.everytalk.data.database.entities.MemoryDocumentEntity(
                com.android.everytalk.data.database.entities.MemoryDocumentEntity.OVERVIEW_ID, com.android.everytalk.data.database.entities.MemoryDocumentEntity.KIND_OVERVIEW,
                "", "## What's in memory\n### 代理\n- [conv:chat-1] 2026-11-11 — 节点迁移", 10, 30))
        }
        page()
        compose.onNodeWithTag("memory_file_overview").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("节点迁移").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("记忆内容").assertExists()
        compose.onNodeWithText("节点迁移").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("用户要求迁到 sing-box").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("memory_conversation_forget").performScrollTo().performClick()
        compose.onNodeWithText("移除").performClick()
        compose.waitUntil(5_000) { runBlocking { repository.conversationSummary("chat-1") == null } }
        // 保留来源时间，没有新消息就不会被重新生成。
        assertEquals(10L, runBlocking { database.memoryDao().getDocument("chat-1")!!.sourceUpdatedAt })
    }

    private fun page() {
        compose.setContent { MaterialTheme { MemoryManagementScreen(onBack = {}, repository = repository) } }
        await("memory_folder_notes")
    }
    private fun await(tag: String) { compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}
