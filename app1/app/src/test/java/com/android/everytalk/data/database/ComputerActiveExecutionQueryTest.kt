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
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import com.android.everytalk.data.database.entities.toEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 验证前台服务不会把已经结束的 AgentRun 遗留记录当成活动任务。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ComputerActiveExecutionQueryTest {
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
    fun `活动查询排除已结束Run的前台残留并保留真实待监听任务`() = runBlocking {
        prepareWorkspace()
        val agentDao = database.agentDao()
        agentDao.upsertRun(run("run-completed", "COMPLETED"))
        agentDao.upsertRun(run("run-waiting", "WAITING_REMOTE_EXECUTION"))
        agentDao.upsertRun(run("run-cancelled", "CANCELLED"))

        val computerDao = database.computerDao()
        computerDao.upsertExecution(execution("stale-foreground", "run-completed"))
        computerDao.upsertExecution(execution("active-foreground", "run-waiting"))
        computerDao.upsertExecution(
            execution("active-background", "run-completed", completionMode = "RETURN_HANDLE", localStatus = "SUCCEEDED"),
        )
        computerDao.upsertExecution(
            execution("failed-before-remote-start", "run-completed", completionMode = "RETURN_HANDLE", localStatus = "FAILED"),
        )
        computerDao.upsertExecution(
            execution("cancelling", "run-cancelled", localStatus = "CANCELLED", errorCode = "EXECUTION_CANCEL_REQUESTED"),
        )
        computerDao.upsertExecution(execution("orphan-legacy", null, workspaceId = "workspace-orphan"))

        val activeIds = computerDao.getActiveRemoteExecutions().map { it.id }.toSet()

        assertEquals(
            setOf("active-foreground", "active-background", "cancelling"),
            activeIds,
        )
    }

    private suspend fun prepareWorkspace() {
        database.chatDao().insertSession(ChatSessionEntity("conversation-1", 1L, 1L, isImageGeneration = false))
        database.chatDao().insertSession(ChatSessionEntity("conversation-orphan", 1L, 1L, isImageGeneration = false))
        database.computerDao().upsertComputer(
            Computer(
                id = "computer-1",
                displayName = "测试服务器",
                host = "example.test",
                port = 22,
                username = "root",
                authKind = ComputerAuthKind.PASSWORD,
                runMode = ComputerRunMode.DIRECT,
                status = ComputerStatus.READY,
            ).toEntity(Json),
        )
        database.computerDao().upsertWorkspace(
            ComputerWorkspace(
                id = "workspace-1",
                computerId = "computer-1",
                conversationId = "conversation-1",
                runMode = ComputerRunMode.DIRECT,
                hostPath = "/root/.everytalk/workspaces/workspace-1",
            ).toEntity(),
        )
        database.computerDao().upsertWorkspace(
            ComputerWorkspace(
                id = "workspace-orphan",
                computerId = "computer-1",
                conversationId = "conversation-orphan",
                runMode = ComputerRunMode.DIRECT,
                hostPath = "/root/.everytalk/workspaces/workspace-orphan",
            ).toEntity(),
        )
    }

    private fun run(id: String, status: String) = AgentRunEntity(
        id = id,
        sessionId = "conversation-1",
        userMessageId = "user-$id",
        visibleAssistantMessageId = "assistant-$id",
        configIdSnapshot = "config-1",
        requestSnapshotJson = null,
        status = status,
        currentRequestOrdinal = 1,
        terminalReason = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun execution(
        id: String,
        runId: String?,
        completionMode: String = "WAIT_FOR_RESULT",
        localStatus: String = "RUNNING",
        errorCode: String? = null,
        workspaceId: String = "workspace-1",
    ) = ComputerExecutionEntity(
        id = id,
        toolCallId = "tool-$id",
        computerId = "computer-1",
        workspaceId = workspaceId,
        toolName = "exec",
        requestHash = "hash-$id",
        status = localStatus,
        startedAt = 1L,
        finishedAt = null,
        exitCode = null,
        errorCode = errorCode,
        safeSummary = null,
        target = "HOST",
        completionMode = completionMode,
        remoteProcessId = "process-$id",
        remoteStatePath = "/tmp/$id/state",
        remoteStatus = "RUNNING",
        runId = runId,
    )
}
