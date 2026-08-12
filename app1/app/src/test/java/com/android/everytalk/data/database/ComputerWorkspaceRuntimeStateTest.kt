package com.android.everytalk.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.computer.Computer
import com.android.everytalk.data.computer.ComputerAuthKind
import com.android.everytalk.data.computer.ComputerRunMode
import com.android.everytalk.data.computer.ComputerStatus
import com.android.everytalk.data.computer.ComputerWorkspace
import com.android.everytalk.data.computer.ComputerWorkspaceStatus
import com.android.everytalk.data.database.entities.toEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ComputerWorkspaceRuntimeStateTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `远端准备完成时保留已经迁移的稳定会话ID`() = runBlocking {
        val dao = database.computerDao()
        dao.upsertComputer(
            Computer(
                id = "computer_1",
                displayName = "测试服务器",
                host = "example.test",
                port = 22,
                username = "root",
                authKind = ComputerAuthKind.PASSWORD,
                runMode = ComputerRunMode.CONTAINER,
                status = ComputerStatus.READY,
            ).toEntity(Json),
        )
        dao.upsertWorkspace(
            ComputerWorkspace(
                id = "workspace_1",
                computerId = "computer_1",
                conversationId = "new_chat_1",
                runMode = ComputerRunMode.CONTAINER,
                hostPath = "~/.everytalk/workspaces/workspace_1",
            ).toEntity(),
        )

        dao.updateWorkspaceConversationId("workspace_1", "user_1")
        dao.updateWorkspaceRuntimeState(
            workspaceId = "workspace_1",
            hostPath = "/root/.everytalk/workspaces/workspace_1",
            status = ComputerWorkspaceStatus.READY.name,
            lastUsedAt = 123L,
        )

        val workspace = requireNotNull(dao.getWorkspaceById("workspace_1"))
        assertEquals("user_1", workspace.conversationId)
        assertEquals("/root/.everytalk/workspaces/workspace_1", workspace.hostPath)
        assertEquals(ComputerWorkspaceStatus.READY.name, workspace.status)
        assertEquals(123L, workspace.lastUsedAt)
    }
}
