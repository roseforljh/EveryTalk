package com.android.everytalk.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.database.entities.AgentRequestEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.agent.AgentAssistantTurn
import com.android.everytalk.data.agent.AgentContentBlock
import com.android.everytalk.data.agent.AgentEntryStatus
import com.android.everytalk.data.agent.AgentRunStore
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentRunRecoveryTest {
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
    fun `App重启会封存旧请求并把模型Run转成待续写`() = runBlocking {
        val dao = database.agentDao()
        database.chatDao().insertSession(
            ChatSessionEntity("session-1", 1L, 1L, isImageGeneration = false),
        )
        dao.upsertRun(
            AgentRunEntity(
                id = "run-1",
                sessionId = "session-1",
                userMessageId = "user-1",
                visibleAssistantMessageId = "assistant-1",
                configIdSnapshot = "config-1",
                requestSnapshotJson = null,
                status = "WAITING_MODEL",
                currentRequestOrdinal = 1,
                terminalReason = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        dao.upsertRequest(
            AgentRequestEntity(
                id = "request-1",
                runId = "run-1",
                ordinal = 1,
                purpose = "AGENT_TURN",
                modelTurnOrdinal = 1,
                attempt = 1,
                retryOfRequestId = null,
                provider = "OpenAI",
                endpoint = "https://example.test",
                model = "model",
                payloadFingerprint = "fingerprint",
                status = "STREAMING",
                finishReason = null,
                startedAt = 1L,
                firstEventAt = null,
                finishedAt = null,
            ),
        )

        dao.recoverInterruptedAgentRuns(timestamp = 10L)

        assertEquals("MODEL_CONTINUATION_PENDING", dao.getRun("run-1")?.status)
        assertEquals("MODEL_CONTINUATION_PENDING", dao.getRun("run-1")?.terminalReason)
        assertEquals("INTERRUPTED", dao.getRequests("run-1").single().status)
        assertEquals(10L, dao.getRequests("run-1").single().finishedAt)
    }

    @Test
    fun `App重启会中断可能产生副作用的工具Run`() = runBlocking {
        val dao = database.agentDao()
        database.chatDao().insertSession(
            ChatSessionEntity("session-tool", 1L, 1L, isImageGeneration = false),
        )
        listOf("CHECKING_PERMISSION", "EXECUTING_TOOL").forEachIndexed { index, status ->
            dao.upsertRun(
                AgentRunEntity(
                    id = "run-tool-$index",
                    sessionId = "session-tool",
                    userMessageId = "user-tool-$index",
                    visibleAssistantMessageId = "assistant-tool-$index",
                    configIdSnapshot = "config-1",
                    requestSnapshotJson = "{}",
                    status = status,
                    currentRequestOrdinal = 1,
                    terminalReason = null,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }

        dao.recoverInterruptedAgentRuns(timestamp = 10L)

        listOf("run-tool-0", "run-tool-1").forEach { runId ->
            assertEquals("INTERRUPTED", dao.getRun(runId)?.status)
            assertEquals("APP_PROCESS_RESTARTED", dao.getRun(runId)?.terminalReason)
        }
    }

    @Test
    fun `App重启会把正在保存工具结果的Run交给工具账本对账`() = runBlocking {
        val dao = database.agentDao()
        database.chatDao().insertSession(
            ChatSessionEntity("session-persisting", 1L, 1L, isImageGeneration = false),
        )
        dao.upsertRun(
            AgentRunEntity(
                id = "run-persisting",
                sessionId = "session-persisting",
                userMessageId = "user-persisting",
                visibleAssistantMessageId = "assistant-persisting",
                configIdSnapshot = "config-1",
                requestSnapshotJson = "{}",
                status = "PERSISTING_RESULT",
                currentRequestOrdinal = 1,
                terminalReason = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        dao.recoverInterruptedAgentRuns(timestamp = 10L)

        assertEquals("INTERRUPTED", dao.getRun("run-persisting")?.status)
        assertEquals("APP_PROCESS_RESTARTED", dao.getRun("run-persisting")?.terminalReason)
        assertTrue(dao.getActiveRuns().any { it.id == "run-persisting" })
    }

    @Test
    fun `工具结果已经落库时恢复器只续写不会重复执行`() = runBlocking {
        val dao = database.agentDao()
        database.chatDao().insertSession(
            ChatSessionEntity("session-result", 1L, 1L, isImageGeneration = false),
        )
        val run = AgentRunEntity(
            id = "run-result",
            sessionId = "session-result",
            userMessageId = "user-result",
            visibleAssistantMessageId = "assistant-result",
            configIdSnapshot = "config-1",
            requestSnapshotJson = "{}",
            status = "PERSISTING_RESULT",
            currentRequestOrdinal = 1,
            terminalReason = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        dao.upsertRun(run)
        val store = AgentRunStore(dao)
        val call = AgentContentBlock.ToolCall(
            id = "call-result",
            name = "exec",
            arguments = buildJsonObject { put("command", JsonPrimitive("echo ok")) },
        )
        store.appendAssistant("run-result", "request-result", AgentAssistantTurn(listOf(call)))
        store.appendToolExecutionStarted("run-result", "request-result", call)
        store.appendToolResult(
            "run-result",
            "request-result",
            AgentContentBlock.ToolResult(
                toolCallId = call.id,
                toolName = call.name,
                content = JsonPrimitive("ok"),
            ),
        )
        dao.recoverInterruptedAgentRuns(timestamp = 10L)

        val recovered = store.recoverInterruptedToolBatch("run-result", database.computerDao())

        assertEquals(true, recovered?.toolResultAlreadyPersisted)
        assertTrue(recovered?.pendingToolCalls?.isEmpty() == true)
        assertEquals(1, dao.getEntries("run-result").count { it.kind == "TOOL_RESULT" })
    }

    @Test
    fun `流式检查点会覆盖且最终回复会清理检查点`() = runBlocking {
        val dao = database.agentDao()
        database.chatDao().insertSession(
            ChatSessionEntity("session-checkpoint", 1L, 1L, isImageGeneration = false),
        )
        dao.upsertRun(
            AgentRunEntity(
                id = "run-checkpoint",
                sessionId = "session-checkpoint",
                userMessageId = "user-checkpoint",
                visibleAssistantMessageId = "assistant-checkpoint",
                configIdSnapshot = "config-1",
                requestSnapshotJson = "{}",
                status = "STREAMING_MODEL",
                currentRequestOrdinal = 1,
                terminalReason = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val store = AgentRunStore(dao)

        store.appendAssistant(
            "run-checkpoint",
            "request-checkpoint",
            AgentAssistantTurn(listOf(AgentContentBlock.Text("部分"))),
            AgentEntryStatus.PARTIAL,
        )
        store.appendAssistant(
            "run-checkpoint",
            "request-checkpoint",
            AgentAssistantTurn(listOf(AgentContentBlock.Text("部分回复"))),
            AgentEntryStatus.PARTIAL,
        )

        assertEquals(1, dao.getEntries("run-checkpoint").size)
        assertEquals(
            "部分回复",
            store.executionTrace("run-checkpoint")
                .filterIsInstance<ExecutionTraceEvent.Content>()
                .single()
                .text,
        )

        store.appendAssistant(
            "run-checkpoint",
            "request-checkpoint",
            AgentAssistantTurn(listOf(AgentContentBlock.Text("最终回复"))),
        )

        assertEquals(listOf("FINAL"), dao.getEntries("run-checkpoint").map { it.status })
        assertEquals(
            "最终回复",
            store.executionTrace("run-checkpoint")
                .filterIsInstance<ExecutionTraceEvent.Content>()
                .single()
                .text,
        )
    }

    @Test
    fun `App重启保留等待审批Run并封存已经结束的模型请求`() = runBlocking {
        val dao = database.agentDao()
        database.chatDao().insertSession(
            ChatSessionEntity("session-approval", 1L, 1L, isImageGeneration = false),
        )
        dao.upsertRun(
            AgentRunEntity(
                id = "run-approval",
                sessionId = "session-approval",
                userMessageId = "user-approval",
                visibleAssistantMessageId = "assistant-approval",
                configIdSnapshot = "config-1",
                requestSnapshotJson = "{}",
                status = "WAITING_APPROVAL",
                currentRequestOrdinal = 1,
                terminalReason = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        dao.upsertRequest(
            AgentRequestEntity(
                id = "request-approval",
                runId = "run-approval",
                ordinal = 1,
                purpose = "AGENT_TURN",
                modelTurnOrdinal = 1,
                attempt = 1,
                retryOfRequestId = null,
                provider = "OpenAI",
                endpoint = "https://example.test",
                model = "model",
                payloadFingerprint = "fingerprint",
                status = "COMPLETED",
                finishReason = "tool_calls",
                startedAt = 1L,
                firstEventAt = 2L,
                finishedAt = 3L,
            ),
        )

        dao.recoverInterruptedAgentRuns(timestamp = 10L)

        assertEquals("WAITING_APPROVAL", dao.getRun("run-approval")?.status)
        assertEquals(null, dao.getRun("run-approval")?.terminalReason)
        assertEquals("COMPLETED", dao.getRequests("run-approval").single().status)
    }
}
