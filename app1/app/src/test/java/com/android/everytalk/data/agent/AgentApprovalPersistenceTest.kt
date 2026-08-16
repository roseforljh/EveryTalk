package com.android.everytalk.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.computer.ComputerExecTarget
import com.android.everytalk.data.computer.ComputerPublicPreviewRequest
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerToolApprovalRequest
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.ComputerEntity
import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import com.android.everytalk.data.database.entities.ComputerWorkspaceEntity
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.computer.ComputerPermissionMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentApprovalPersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var store: AgentRunStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = AgentRunStore(database.agentDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `审批请求可恢复且同一请求只能决策一次`() = runBlocking {
        seedRun()
        val record = approvalRecord()
        store.pauseForApproval(requireNotNull(store.getRun("run-1")), record)

        assertEquals(record, store.pendingApproval("run-1"))

        val decisions = coroutineScope {
            listOf(AgentApprovalDecision.APPROVED, AgentApprovalDecision.REJECTED).map { decision ->
                async { store.decideApproval("run-1", record.approvalRequestId, decision) }
            }.awaitAll()
        }

        assertEquals(1, decisions.count { it != null })
        assertNull(store.pendingApproval("run-1"))
        assertEquals(
            1,
            database.agentDao().getEntries("run-1").count { it.kind == AgentEntryKind.APPROVAL_DECISION.name },
        )
    }

    @Test
    fun `批准和拒绝都保留原toolCallId及冻结请求`() = runBlocking {
        seedRun()
        val record = approvalRecord()
        store.pauseForApproval(requireNotNull(store.getRun("run-1")), record)

        val decided = store.decideApproval("run-1", record.approvalRequestId, AgentApprovalDecision.REJECTED)

        assertNotNull(decided)
        assertEquals("tool-1", decided?.toolCall?.id)
        assertEquals(record.request, decided?.request)
        assertEquals(AgentApprovalDecision.REJECTED, decided?.decision)
        assertEquals(AgentRunStatus.INTERRUPTED.name, store.getRun("run-1")?.status)
        assertEquals(decided, store.decidedApprovalAwaitingResult("run-1"))
    }

    @Test
    fun `审批请求与等待状态同时持久化`() = runBlocking {
        seedRun()

        store.pauseForApproval(requireNotNull(store.getRun("run-1")), approvalRecord())

        assertEquals(AgentRunStatus.WAITING_APPROVAL.name, store.getRun("run-1")?.status)
        assertNotNull(store.pendingApproval("run-1"))
    }

    @Test
    fun `Agent开启申请跨进程保留原因和Skill范围`() = runBlocking {
        seedRun()
        val call = toolCall("request-agent", AgentControlToolNames.REQUEST_AGENT)
        val record = AgentApprovalRecord(
            approvalRequestId = "approval-agent",
            requestId = "request-agent",
            toolCall = call,
            pendingToolCalls = listOf(call),
            agentRequest = AgentPauseRequest.EnableAgent("需要执行脚本", listOf("skill-a")),
        )

        store.pauseForApproval(requireNotNull(store.getRun("run-1")), record)
        val restored = requireNotNull(store.pendingApproval("run-1"))

        assertEquals(record.agentRequest, restored.agentRequest)
        assertEquals(record.toolCall, restored.toolCall)
    }

    @Test
    fun `同会话新Run作废旧审批并只展示最新申请`() = runBlocking {
        seedRun()
        store.pauseForApproval(requireNotNull(store.getRun("run-1")), approvalRecord())

        val newRun = store.createRun(
            sessionId = "session-1",
            userMessageId = "user-2",
            visibleAssistantMessageId = "assistant-2",
            configIdSnapshot = "config-1",
            request = ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "重新申请")),
                provider = "provider",
                channel = "OpenAI",
                apiAddress = "https://example.test",
                apiKey = "secret",
                model = "model",
            ),
        )
        val latestCall = toolCall("request-agent-2", AgentControlToolNames.REQUEST_AGENT)
        store.pauseForApproval(
            newRun,
            AgentApprovalRecord(
                approvalRequestId = "approval-agent-2",
                requestId = "request-agent-2",
                toolCall = latestCall,
                pendingToolCalls = listOf(latestCall),
                agentRequest = AgentPauseRequest.EnableAgent("重新开启 Agent", emptyList()),
            ),
        )

        val waiting = store.getWaitingApprovalRuns()
        assertEquals(AgentRunStatus.CANCELLED.name, store.getRun("run-1")?.status)
        assertEquals(AgentTerminalReasons.SUPERSEDED_BY_NEW_RUN, store.getRun("run-1")?.terminalReason)
        assertEquals(listOf(newRun.id), waiting.map(AgentRunEntity::id))
    }

    @Test
    fun `同会话新Run作废已批准但尚未续接的旧审批`() = runBlocking {
        seedRun()
        store.pauseForApproval(requireNotNull(store.getRun("run-1")), approvalRecord())
        store.decideApproval("run-1", "approval-1", AgentApprovalDecision.APPROVED)

        store.createRun(
            sessionId = "session-1",
            userMessageId = "user-2",
            visibleAssistantMessageId = "assistant-2",
            configIdSnapshot = "config-1",
            request = ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "新消息")),
                provider = "provider",
                channel = "OpenAI",
                apiAddress = "https://example.test",
                apiKey = "secret",
                model = "model",
            ),
        )

        val oldRun = requireNotNull(store.getRun("run-1"))
        assertEquals(AgentRunStatus.CANCELLED.name, oldRun.status)
        assertEquals(AgentTerminalReasons.SUPERSEDED_BY_NEW_RUN, oldRun.terminalReason)
        assertTrue(store.resumableApprovalRuns(database.computerDao()).isEmpty())
    }

    @Test
    fun `并发追加Entry仍保持严格递增序号`() = runBlocking {
        seedRun()
        coroutineScope {
            (1..12).map { index ->
                async {
                    val call = toolCall("tool-sequence-$index", "tool-$index")
                    store.appendToolExecutionStarted("run-1", "request-sequence", call)
                }
            }.awaitAll()
        }

        val sequences = database.agentDao().getEntries("run-1").map { it.sequence }

        assertEquals(12, sequences.size)
        assertEquals((1L..12L).toList(), sequences)
    }

    @Test
    fun `恢复后的内部Assistant标识保持稳定`() = runBlocking {
        seedRun()
        store.appendAssistant(
            runId = "run-1",
            requestId = "request-1",
            turn = AgentAssistantTurn(listOf(AgentContentBlock.Text("完成"))),
        )

        val first = store.appendRunTranscript("run-1", emptyList())
        val second = store.appendRunTranscript("run-1", emptyList())

        assertEquals("assistant:request-1", first.single().id)
        assertEquals(first.single().id, second.single().id)
        assertTrue(first.single() is com.android.everytalk.data.DataClass.AgentAssistantApiMessage)
    }

    @Test
    fun `AgentEntry按真实顺序重建执行链`() = runBlocking {
        seedRun()
        val call = approvalRecord().toolCall
        val secondCall = call.copy(id = "call-2")
        store.appendAssistant(
            runId = "run-1",
            requestId = "request-1",
            turn = AgentAssistantTurn(
                listOf(
                    AgentContentBlock.Text("先检查"),
                    call,
                ),
            ),
        )
        store.appendToolResult(
            runId = "run-1",
            requestId = "request-1",
            result = AgentContentBlock.ToolResult(call.id, call.name, kotlinx.serialization.json.JsonPrimitive("完成")),
        )
        store.appendAssistant(
            runId = "run-1",
            requestId = "request-2",
            turn = AgentAssistantTurn(listOf(AgentContentBlock.Text("继续处理"), secondCall)),
        )
        store.appendToolResult(
            runId = "run-1",
            requestId = "request-2",
            result = AgentContentBlock.ToolResult(
                secondCall.id,
                secondCall.name,
                kotlinx.serialization.json.JsonPrimitive("完成"),
            ),
        )

        val trace = store.executionTrace("run-1")

        assertEquals(4, trace.size)
        assertEquals(
            "先检查",
            (trace[0] as com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content).text,
        )
        assertEquals(call.id, (trace[1] as com.android.everytalk.data.DataClass.ExecutionTraceEvent.Tool).step.id)
        assertEquals(
            "继续处理",
            (trace[2] as com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content).text,
        )
        val tool = trace[3] as com.android.everytalk.data.DataClass.ExecutionTraceEvent.Tool
        assertEquals(secondCall.id, tool.step.id)
        assertTrue(tool.step.completed)
    }

    @Test
    fun `中断工具转UNKNOWN后恢复为审批且重复扫描不重复创建`() = runBlocking {
        database.chatDao().insertSession(ChatSessionEntity("session-unknown", 1L, 1L, false))
        database.computerDao().upsertComputer(
            ComputerEntity(
                id = "computer-1", displayName = "测试 VPS", host = "example.test", port = 22,
                username = "user", resolvedAddress = null, hostKeyAlgorithm = null,
                hostKeyBlobBase64 = null, hostKeyFingerprint = null, authKind = "PASSWORD",
                credentialState = "ORIGINAL_ENCRYPTED", runMode = "CONTAINER", status = "READY",
                capabilitiesJson = null, bootstrapVersion = null, sandboxImage = null,
                allowPrivateNetwork = false, permissionMode = ComputerPermissionMode.MANUAL.name,
                lastConnectedAt = null, lastErrorCode = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        database.computerDao().upsertWorkspace(
            ComputerWorkspaceEntity(
                id = "workspace-1", computerId = "computer-1", conversationId = "session-unknown",
                hostPath = "/home/user/.everytalk/workspaces/workspace-1", containerName = null,
                containerImage = null, runMode = "CONTAINER", status = "READY", createdAt = 1L, lastUsedAt = 1L,
            ),
        )
        val context = ComputerRequestContext("session-unknown", "computer-1", "workspace-1")
        val request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "检查")),
            provider = "provider", channel = "OpenAI", apiAddress = "https://example.test",
            apiKey = "secret", model = "model", localComputerRequestContext = context,
        )
        val run = store.createRun("session-unknown", "user-1", "assistant-unknown", "config-1", request)
        val call = AgentContentBlock.ToolCall(
            id = "tool-unknown", name = "exec",
            arguments = buildJsonObject { put("command", "systemctl restart demo") },
        )
        store.appendAssistant(run.id, "request-unknown", AgentAssistantTurn(listOf(call)))
        store.updateRunStatus(run, AgentRunStatus.INTERRUPTED, terminalReason = "APP_PROCESS_RESTARTED")
        database.computerDao().upsertExecution(
            ComputerExecutionEntity(
                id = "execution-unknown",
                toolCallId = com.android.everytalk.data.computer.ComputerToolRequestHasher.toolCallKey(call.id, context),
                computerId = "computer-1", workspaceId = "workspace-1", toolName = call.name,
                requestHash = "hash", status = "UNKNOWN", startedAt = 1L, finishedAt = 2L,
                exitCode = null, errorCode = "EXECUTION_UNKNOWN", safeSummary = null,
            ),
        )

        store.recoverUnknownComputerExecutions(database.computerDao())
        store.recoverUnknownComputerExecutions(database.computerDao())

        assertEquals(AgentRunStatus.WAITING_APPROVAL.name, store.getRun(run.id)?.status)
        assertNotNull(store.pendingApproval(run.id))
        assertEquals(
            1,
            database.agentDao().getEntries(run.id).count { it.kind == AgentEntryKind.APPROVAL_REQUEST.name },
        )
    }

    @Test
    fun `只读诊断转UNKNOWN后直接交回AI且不弹审批`() = runBlocking {
        database.chatDao().insertSession(ChatSessionEntity("session-unknown-read", 1L, 1L, false))
        database.computerDao().upsertComputer(
            ComputerEntity(
                id = "computer-read", displayName = "测试 VPS", host = "example.test", port = 22,
                username = "user", resolvedAddress = null, hostKeyAlgorithm = null,
                hostKeyBlobBase64 = null, hostKeyFingerprint = null, authKind = "PASSWORD",
                credentialState = "ORIGINAL_ENCRYPTED", runMode = "CONTAINER", status = "READY",
                capabilitiesJson = null, bootstrapVersion = null, sandboxImage = null,
                allowPrivateNetwork = false, permissionMode = ComputerPermissionMode.MANUAL.name,
                lastConnectedAt = null, lastErrorCode = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        database.computerDao().upsertWorkspace(
            ComputerWorkspaceEntity(
                id = "workspace-read", computerId = "computer-read", conversationId = "session-unknown-read",
                hostPath = "/home/user/.everytalk/workspaces/workspace-read", containerName = null,
                containerImage = null, runMode = "CONTAINER", status = "READY", createdAt = 1L, lastUsedAt = 1L,
            ),
        )
        val context = ComputerRequestContext(
            "session-unknown-read",
            "computer-read",
            "workspace-read",
            ComputerPermissionMode.MANUAL,
        )
        val request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "检查磁盘")),
            provider = "provider", channel = "OpenAI", apiAddress = "https://example.test",
            apiKey = "secret", model = "model", localComputerRequestContext = context,
        )
        val run = store.createRun("session-unknown-read", "user-read", "assistant-read", "config-1", request)
        val call = AgentContentBlock.ToolCall(
            id = "tool-read",
            name = "exec",
            arguments = buildJsonObject {
                put("command", "df -h; echo \"=== Top 15 largest dirs in / ===\"; du -ahx / 2>/dev/null | sort -rh | head -n 15")
                put("target", "host")
            },
        )
        store.appendAssistant(run.id, "request-read", AgentAssistantTurn(listOf(call)))
        store.updateRunStatus(run, AgentRunStatus.INTERRUPTED, terminalReason = "APP_PROCESS_RESTARTED")
        database.computerDao().upsertExecution(
            ComputerExecutionEntity(
                id = "execution-read",
                toolCallId = com.android.everytalk.data.computer.ComputerToolRequestHasher.toolCallKey(call.id, context),
                computerId = "computer-read", workspaceId = "workspace-read", toolName = call.name,
                requestHash = "hash", status = "UNKNOWN", startedAt = 1L, finishedAt = 2L,
                exitCode = null, errorCode = "EXECUTION_UNKNOWN", safeSummary = null,
            ),
        )

        store.recoverUnknownComputerExecutions(database.computerDao())
        store.recoverUnknownComputerExecutions(database.computerDao())

        assertNull(store.pendingApproval(run.id))
        val entries = database.agentDao().getEntries(run.id)
        assertEquals(0, entries.count { it.kind == AgentEntryKind.APPROVAL_REQUEST.name })
        assertEquals(1, entries.count { it.kind == AgentEntryKind.TOOL_RESULT.name })
    }

    @Test
    fun `工具结果已落库但模型未续接时恢复而不重放工具`() = runBlocking {
        database.chatDao().insertSession(ChatSessionEntity("session-result", 1L, 1L, false))
        val context = ComputerRequestContext("session-result", "computer-1", "workspace-1")
        val request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "检查")),
            provider = "provider", channel = "OpenAI", apiAddress = "https://example.test",
            apiKey = "secret", model = "model", localComputerRequestContext = context,
        )
        val run = store.createRun("session-result", "user-result", "assistant-result", "config-1", request)
        val call = AgentContentBlock.ToolCall(
            id = "tool-result", name = "exec",
            arguments = buildJsonObject { put("command", "uname -a") },
        )
        store.appendAssistant(run.id, "request-result", AgentAssistantTurn(listOf(call)))
        store.appendToolResult(
            run.id,
            "request-result",
            AgentContentBlock.ToolResult(call.id, call.name, kotlinx.serialization.json.JsonPrimitive("完成")),
        )
        store.updateRunStatus(run, AgentRunStatus.INTERRUPTED)

        val recovered = store.recoverInterruptedToolBatch(run.id, database.computerDao())

        assertNotNull(recovered)
        assertTrue(requireNotNull(recovered).toolResultAlreadyPersisted)
        assertTrue(recovered.pendingToolCalls.isEmpty())
    }

    @Test
    fun `中断批次跳过已有结果并从首个未开始工具继续`() = runBlocking {
        database.chatDao().insertSession(ChatSessionEntity("session-batch", 1L, 1L, false))
        val run = store.createRun(
            "session-batch",
            "user-batch",
            "assistant-batch",
            "config-1",
            ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "执行")),
                provider = "provider",
                channel = "OpenAI",
                apiAddress = "https://example.test",
                apiKey = "secret",
                model = "model",
            ),
        )
        val first = toolCall("tool-first", "first_tool")
        val second = toolCall("tool-second", "second_tool")
        store.appendAssistant(run.id, "request-batch", AgentAssistantTurn(listOf(first, second)))
        store.appendToolExecutionStarted(run.id, "request-batch", first)
        store.appendToolResult(
            run.id,
            "request-batch",
            AgentContentBlock.ToolResult(first.id, first.name, kotlinx.serialization.json.JsonPrimitive("完成")),
        )
        store.updateRunStatus(run, AgentRunStatus.INTERRUPTED)

        val recovered = requireNotNull(store.recoverInterruptedToolBatch(run.id, database.computerDao()))

        assertEquals(second.id, recovered.toolCall.id)
        assertEquals(listOf(second), recovered.pendingToolCalls)
        assertTrue(recovered.resumePendingToolCallsOnly)
    }

    @Test
    fun `非Computer工具开始后中断会补错误结果且不盲目重放`() = runBlocking {
        database.chatDao().insertSession(ChatSessionEntity("session-mcp", 1L, 1L, false))
        val run = store.createRun(
            "session-mcp",
            "user-mcp",
            "assistant-mcp",
            "config-1",
            ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "执行")),
                provider = "provider",
                channel = "OpenAI",
                apiAddress = "https://example.test",
                apiKey = "secret",
                model = "model",
            ),
        )
        val call = toolCall("tool-mcp", "mcp_tool")
        store.appendAssistant(run.id, "request-mcp", AgentAssistantTurn(listOf(call)))
        store.appendToolExecutionStarted(run.id, "request-mcp", call)
        store.updateRunStatus(run, AgentRunStatus.INTERRUPTED)

        val recovered = requireNotNull(store.recoverInterruptedToolBatch(run.id, database.computerDao()))
        val entries = database.agentDao().getEntries(run.id)

        assertTrue(recovered.toolResultAlreadyPersisted)
        assertEquals(1, entries.count { it.kind == AgentEntryKind.TOOL_EXECUTION_STARTED.name })
        assertEquals(1, entries.count { it.kind == AgentEntryKind.TOOL_RESULT.name && it.toolCallId == call.id })
    }

    @Test
    fun `重复恢复扫描不会重复补写非Computer错误结果`() = runBlocking {
        database.chatDao().insertSession(ChatSessionEntity("session-repeat", 1L, 1L, false))
        val run = store.createRun(
            "session-repeat",
            "user-repeat",
            "assistant-repeat",
            "config-1",
            ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "执行")),
                provider = "provider", channel = "OpenAI", apiAddress = "https://example.test",
                apiKey = "secret", model = "model",
            ),
        )
        val call = toolCall("tool-repeat", "mcp_tool")
        store.appendAssistant(run.id, "request-repeat", AgentAssistantTurn(listOf(call)))
        store.appendToolExecutionStarted(run.id, "request-repeat", call)
        store.updateRunStatus(run, AgentRunStatus.INTERRUPTED)

        store.recoverInterruptedToolBatch(run.id, database.computerDao())
        store.recoverInterruptedToolBatch(run.id, database.computerDao())

        assertEquals(
            1,
            database.agentDao().getEntries(run.id)
                .count { it.kind == AgentEntryKind.TOOL_RESULT.name && it.toolCallId == call.id },
        )
    }

    @Test
    fun `中断的非Computer工具补错后继续批次下一项`() = runBlocking {
        database.chatDao().insertSession(ChatSessionEntity("session-next", 1L, 1L, false))
        val run = store.createRun(
            "session-next", "user-next", "assistant-next", "config-1",
            ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "执行")),
                provider = "provider", channel = "OpenAI", apiAddress = "https://example.test",
                apiKey = "secret", model = "model",
            ),
        )
        val interrupted = toolCall("tool-interrupted", "mcp_tool")
        val next = toolCall("tool-next", "next_tool")
        store.appendAssistant(run.id, "request-next", AgentAssistantTurn(listOf(interrupted, next)))
        store.appendToolExecutionStarted(run.id, "request-next", interrupted)
        store.updateRunStatus(run, AgentRunStatus.INTERRUPTED)

        val recovered = requireNotNull(store.recoverInterruptedToolBatch(run.id, database.computerDao()))

        assertEquals(next.id, recovered.toolCall.id)
        assertEquals(listOf(next), recovered.pendingToolCalls)
        assertTrue(recovered.resumePendingToolCallsOnly)
    }

    @Test
    fun `已成功的Computer执行通过幂等入口恢复结果`() = runBlocking {
        database.chatDao().insertSession(ChatSessionEntity("session-computer", 1L, 1L, false))
        seedComputer("session-computer")
        val context = ComputerRequestContext("session-computer", "computer-1", "workspace-1")
        val run = store.createRun(
            "session-computer",
            "user-computer",
            "assistant-computer",
            "config-1",
            ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "检查")),
                provider = "provider",
                channel = "OpenAI",
                apiAddress = "https://example.test",
                apiKey = "secret",
                model = "model",
                localComputerRequestContext = context,
            ),
        )
        val call = AgentContentBlock.ToolCall(
            id = "tool-computer",
            name = "exec",
            arguments = buildJsonObject { put("command", "uname -a") },
        )
        store.appendAssistant(run.id, "request-computer", AgentAssistantTurn(listOf(call)))
        store.appendToolExecutionStarted(run.id, "request-computer", call)
        database.computerDao().upsertExecution(
            ComputerExecutionEntity(
                id = "execution-computer",
                toolCallId = com.android.everytalk.data.computer.ComputerToolRequestHasher.toolCallKey(call.id, context),
                computerId = "computer-1",
                workspaceId = "workspace-1",
                toolName = call.name,
                requestHash = "hash",
                status = "SUCCEEDED",
                startedAt = 1L,
                finishedAt = 2L,
                exitCode = null,
                errorCode = null,
                safeSummary = "exec 已完成",
            ),
        )
        store.updateRunStatus(run, AgentRunStatus.INTERRUPTED)

        val recovered = requireNotNull(store.recoverInterruptedToolBatch(run.id, database.computerDao()))

        assertEquals(AgentApprovalDecision.APPROVED, recovered.decision)
        assertTrue(!recovered.toolResultAlreadyPersisted)
        assertTrue(!recovered.resumePendingToolCallsOnly)
    }

    @Test
    fun `写操作开始事实存在但执行记录缺失时恢复为UNKNOWN审批`() = runBlocking {
        database.chatDao().insertSession(ChatSessionEntity("session-gap", 1L, 1L, false))
        seedComputer("session-gap")
        val context = ComputerRequestContext("session-gap", "computer-1", "workspace-1")
        val run = store.createRun(
            "session-gap", "user-gap", "assistant-gap", "config-1",
            ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "检查")),
                provider = "provider", channel = "OpenAI", apiAddress = "https://example.test",
                apiKey = "secret", model = "model", localComputerRequestContext = context,
            ),
        )
        val call = AgentContentBlock.ToolCall(
            id = "tool-gap", name = "exec",
            arguments = buildJsonObject {
                put("command", "systemctl restart demo")
                put("target", "host")
            },
        )
        store.appendAssistant(run.id, "request-gap", AgentAssistantTurn(listOf(call)))
        store.appendToolExecutionStarted(run.id, "request-gap", call)
        store.updateRunStatus(run, AgentRunStatus.INTERRUPTED)

        val automaticResume = store.recoverInterruptedToolBatch(run.id, database.computerDao())

        assertNull(automaticResume)
        val approval = requireNotNull(store.pendingApproval(run.id))
        assertTrue(approval.request is ComputerToolApprovalRequest.UnknownExecution)
        assertEquals(AgentRunStatus.WAITING_APPROVAL.name, store.getRun(run.id)?.status)
    }

    private suspend fun seedRun() {
        database.chatDao().insertSession(ChatSessionEntity("session-1", 1L, 1L, false))
        database.agentDao().upsertRun(
            AgentRunEntity(
                id = "run-1",
                sessionId = "session-1",
                userMessageId = "user-1",
                visibleAssistantMessageId = "assistant-1",
                configIdSnapshot = "config-1",
                requestSnapshotJson = null,
                status = AgentRunStatus.CHECKING_PERMISSION.name,
                currentRequestOrdinal = 1,
                terminalReason = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private suspend fun seedComputer(conversationId: String) {
        database.computerDao().upsertComputer(
            ComputerEntity(
                id = "computer-1", displayName = "测试 VPS", host = "example.test", port = 22,
                username = "user", resolvedAddress = null, hostKeyAlgorithm = null,
                hostKeyBlobBase64 = null, hostKeyFingerprint = null, authKind = "PASSWORD",
                credentialState = "ORIGINAL_ENCRYPTED", runMode = "CONTAINER", status = "READY",
                capabilitiesJson = null, bootstrapVersion = null, sandboxImage = null,
                allowPrivateNetwork = false, permissionMode = ComputerPermissionMode.MANUAL.name,
                lastConnectedAt = null, lastErrorCode = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        database.computerDao().upsertWorkspace(
            ComputerWorkspaceEntity(
                id = "workspace-1", computerId = "computer-1", conversationId = conversationId,
                hostPath = "/home/user/.everytalk/workspaces/workspace-1", containerName = null,
                containerImage = null, runMode = "CONTAINER", status = "READY", createdAt = 1L, lastUsedAt = 1L,
            ),
        )
    }

    private fun toolCall(id: String, name: String) = AgentContentBlock.ToolCall(
        id = id,
        name = name,
        arguments = buildJsonObject { put("value", id) },
    )

    private fun approvalRecord(): AgentApprovalRecord {
        val context = ComputerRequestContext("session-1", "computer-1", "workspace-1")
        val call = AgentContentBlock.ToolCall(
            id = "tool-1",
            name = "open_port",
            arguments = buildJsonObject { put("port", 8080) },
        )
        return AgentApprovalRecord(
            approvalRequestId = "approval-1",
            requestId = "request-1",
            toolCall = call,
            pendingToolCalls = listOf(call),
            request = ComputerToolApprovalRequest.PublicPreview(
                toolCallId = call.id,
                request = ComputerPublicPreviewRequest(context, 8080, "http", null, ComputerExecTarget.HOST),
                computerName = "VPS",
            ),
        )
    }
}
