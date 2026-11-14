package com.android.everytalk.statecontroller

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.database.entities.toMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.agent.AgentApprovalRecord
import com.android.everytalk.data.agent.AgentApprovalDecision
import com.android.everytalk.data.agent.AgentContentBlock
import com.android.everytalk.data.agent.AgentPauseRequest
import com.android.everytalk.data.agent.AgentRunCoordinator
import com.android.everytalk.data.agent.AgentRunStore
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.network.AppStreamEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 实际调用公共取消入口：没有任务时的初始化清理不能产生 Toast，也不能遗漏原有状态清理。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiHandlerQuietCleanupTest {
    /** mock 只提供事件与状态，不启动真实应用级协调器或恢复任务。 */
    private fun mockCoordinator(): AgentRunCoordinator = mockk<AgentRunCoordinator>(relaxed = true).also { coordinator ->
        every { coordinator.events } returns MutableSharedFlow<Pair<String, AppStreamEvent>>()
        every { coordinator.uiEventCollectors } returns AtomicInteger()
        every { coordinator.cloudToolApprovals } returns MutableStateFlow(emptyList())
        every { coordinator.cloudAgentEnableApprovals } returns MutableStateFlow(emptyList())
        every { coordinator.cloudMcpEnableApprovals } returns MutableStateFlow(emptyList())
        every { coordinator.cloudSkillSecretApprovals } returns MutableStateFlow(emptyList())
    }

    /** 真实点击审批入口：即使恢复暂时排队，获批标签也应立即出现，且不能影响别的会话。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `批准MCP或Agent立即启用原会话标签而拒绝不启用`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val parent = SupervisorJob()
        val liveScope = CoroutineScope(parent + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
        try {
            every { AppDatabase.getDatabase(any()) } returns database
            val coordinator = mockCoordinator()
            every { AgentRunCoordinator.shared(any(), any()) } returns coordinator
            val store = AgentRunStore(database.agentDao())
            val state = ViewModelStateHolder()
            val liveHandler = ApiHandler(context, state, liveScope, mockk(relaxed = true), { _, _ -> }, {})
            val collectors = parent.children.toSet()
            val busyJob = Job(parent)
            // 占用文本流使恢复排队：标签必须在连接和模型恢复之前生效。
            state.textApiJob = busyJob
            state._currentTextStreamingAiMessageId.value = "other-running-message"
            for (isMcp in listOf(true, false)) {
                for (approved in listOf(true, false)) {
                    for (visible in listOf(true, false)) {
                        val sessionId = "tag-$isMcp-$approved-$visible"
                        database.chatDao().insertSession(ChatSessionEntity(sessionId, 1L, 1L, false))
                        state.setCurrentConversationId(if (visible) sessionId else "other-session")
                        val run = store.createRun(sessionId, "user", "assistant-$sessionId", "config", ChatRequest(
                            messages = listOf(SimpleTextApiMessage(role = "user", content = "继续任务")),
                            provider = "OpenAI", channel = "OpenAI", apiAddress = "https://example.test",
                            apiKey = "test", model = "test",
                        ))
                        val call = AgentContentBlock.ToolCall("call", if (isMcp) "request_mcp" else "request_agent", buildJsonObject {})
                        store.pauseForApproval(run, AgentApprovalRecord(
                            approvalRequestId = "approval-$sessionId", requestId = "request-$sessionId",
                            toolCall = call, pendingToolCalls = listOf(call),
                            agentRequest = if (isMcp) AgentPauseRequest.EnableMcp("读取页面") else AgentPauseRequest.EnableAgent("执行脚本"),
                        ))
                        liveHandler.respondToAgentApproval(run.id, "approval-$sessionId",
                            if (approved) AgentApprovalDecision.APPROVED else AgentApprovalDecision.REJECTED)
                        withTimeout(5_000) {
                            parent.children.filter { it !in collectors && it !== busyJob }.toList().joinAll()
                        }
                        val toggles = state.conversationFunctionToggleStates.value[sessionId] ?: ConversationFunctionToggleState()
                        assertEquals(approved && isMcp, toggles.mcpEnabled)
                        assertEquals(approved && !isMcp, toggles.agentEnabled)
                        assertEquals(visible && approved && isMcp, state._isMcpEnabledForNextRequest.value)
                        assertEquals(visible && approved && !isMcp, state._isAgentEnabled.value)
                    }
                }
            }
        } finally {
            parent.cancelAndJoin()
            unmockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
            Dispatchers.resetMain()
            database.close()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `后台流交错处理及结束写回原会话并隔离停止`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val parent = SupervisorJob()
        val liveScope = CoroutineScope(parent + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
        try {
            every { AppDatabase.getDatabase(any()) } returns database
            val coordinator = mockCoordinator()
            every { AgentRunCoordinator.shared(any(), any()) } returns coordinator
            val state = ViewModelStateHolder().also { it.initializeBufferScope(liveScope) }
            val liveHandler = ApiHandler(context, state, liveScope, mockk(relaxed = true), { _, _ -> }, {})
            val store = AgentRunStore(database.agentDao())
            val request = ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "任务")),
                provider = "OpenAI", channel = "OpenAI", apiAddress = "https://example.test",
                apiKey = "test", model = "test",
            )
            for (session in listOf("A", "B")) {
                database.chatDao().insertSession(ChatSessionEntity(session, 1L, 1L, false))
                store.createRun(session, "user-$session", "ai-$session", "config", request)
                state.setCurrentConversationId(session)
                state.messages.add(Message(id = "ai-$session", text = "", sender = Sender.AI))
                state.registerTextJob(session, "ai-$session", Job(parent))
                state.createStreamingBuffer("ai-$session")
            }
            liveHandler.processStreamEvent(AppStreamEvent.Reasoning("思考A"), "ai-A")
            liveHandler.processStreamEvent(AppStreamEvent.Reasoning("思考B"), "ai-B")
            liveHandler.processStreamEvent(AppStreamEvent.Content("正文A"), "ai-A")
            liveHandler.processStreamEvent(AppStreamEvent.ToolCall("tool-A", "search", buildJsonObject {}), "ai-A")
            liveHandler.processStreamEvent(AppStreamEvent.Content("正文B"), "ai-B")
            liveHandler.processStreamEvent(AppStreamEvent.ToolCall("tool-B", "search", buildJsonObject {}), "ai-B")
            val runA = requireNotNull(store.getRunByVisibleMessage("ai-A"))
            store.updateRunStatus(runA, AgentRunStatus.COMPLETED)
            liveHandler.processStreamEvent(AppStreamEvent.Finish("stop"), "ai-A")
            val storedA = database.chatDao().getMessagesForSession("A").single { it.id == "ai-A" }.toMessage()
            assertEquals("正文A", storedA.text)
            assertEquals("思考A", storedA.reasoning)
            assertTrue(storedA.executionTrace.any { it is ExecutionTraceEvent.Tool && it.step.id == "tool-A" })
            // B 仍在流式中，正文在缓冲区；按页面切换时的同步方式投影后再核对。
            state.syncStreamingSnapshotToList("ai-B")
            assertEquals("正文B", state.messages.single().text)
            assertEquals("ai-B", state._currentTextStreamingAiMessageId.value)
            val jobA = requireNotNull(state.textApiJobs["A"])
            val jobB = requireNotNull(state.textApiJobs["B"])
            liveHandler.cancelCurrentApiJob("用户停止")
            assertTrue(jobB.isCancelled)
            assertTrue(jobA.isActive)
            state.setCurrentConversationId("A")
            assertEquals("正文A", state.messages.single().text)
            assertEquals("思考A", state.messages.single().reasoning)
        } finally {
            parent.cancelAndJoin()
            unmockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
            Dispatchers.resetMain()
            database.close()
        }
    }

    private val holder = spyk(ViewModelStateHolder())
    // 本测试只验证同步清理，不启动无关的应用级 Agent 事件收集器。
    private val scope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
    private val handler = ApiHandler(
        context = ApplicationProvider.getApplicationContext<Application>(),
        stateHolder = holder,
        viewModelScope = scope,
        historyManager = mockk(relaxed = true),
        onAiMessageFullTextChanged = { _, _ -> },
        triggerScrollToBottom = {},
    )

    @Test
    fun `首次流请求在启动前取消也会结束Pending派发回调并清理加载状态`() {
        holder._currentTextStreamingAiMessageId.value = "prepared"
        holder._isTextApiCalling.value = true
        var finishedCount = 0
        handler.streamChatResponse(
            requestBody = ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "测试")),
                provider = "OpenAI", channel = "OpenAI", apiAddress = "https://example.test",
                apiKey = "test", model = "test",
            ),
            attachmentsToPassToApiClient = emptyList(),
            applicationContextForApiClient = ApplicationProvider.getApplicationContext(),
            userMessageTextForContext = "测试",
            afterUserMessageId = "user",
            onMessagesProcessed = {}, onRequestFailed = {}, onNewAiMessageAdded = {},
            preCreatedAiMessageId = "prepared",
            onRequestFinished = { finishedCount++ },
        )
        assertFalse(holder._isTextApiCalling.value)
        assertNull(holder._currentTextStreamingAiMessageId.value)
        assertNull(holder.textApiJob)
        assertEquals(1, finishedCount)
    }

    /** 真实停止入口：先落库 USER_STOP，再立即解除取消屏障。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `停止后Run落库为取消并解除取消屏障`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val parent = SupervisorJob()
        val liveScope = CoroutineScope(parent + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
        try {
            every { AppDatabase.getDatabase(any()) } returns database
            val coordinator = mockCoordinator()
            every { AgentRunCoordinator.shared(any(), any()) } returns coordinator
            val state = ViewModelStateHolder()
            state.setCurrentConversationId("stop-session")
            database.chatDao().insertSession(ChatSessionEntity("stop-session", 1L, 1L, false))
            val store = AgentRunStore(database.agentDao())
            val run = store.createRun("stop-session", "user", "assistant", "config", ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "任务")),
                provider = "OpenAI", channel = "OpenAI", apiAddress = "https://example.test",
                apiKey = "test", model = "test",
            ))
            val liveHandler = ApiHandler(context, state, liveScope, mockk(relaxed = true), { _, _ -> }, {})
            val collectors = parent.children.toSet()
            state._currentTextStreamingAiMessageId.value = "assistant"
            state._isTextApiCalling.value = true
            liveHandler.cancelCurrentApiJob("用户停止", showFeedback = true)
            withTimeout(5_000) { parent.children.filter { it !in collectors }.toList().joinAll() }
            assertEquals("CANCELLED", database.agentDao().getRun(run.id)?.status)
            assertFalse(state._isRemoteCancellationPending.value)
        } finally {
            parent.cancelAndJoin()
            unmockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
            Dispatchers.resetMain()
            database.close()
        }
    }

    @Test
    fun `启动新聊天切换配置加载历史的空清理不弹提示`() {
        listOf("开始新聊天", "Switching selected config to ID test", "加载文本模式历史索引 0").forEach {
            handler.cancelCurrentApiJob(it)
        }
        verify(exactly = 0) { holder.showSnackbar(any()) }
        assertFalse(holder._isTextApiCalling.value)
        assertNull(holder._currentTextStreamingAiMessageId.value)
    }

    @Test
    fun `静默清理仍然取消遗留Job并重置状态`() {
        val job = Job()
        holder.textApiJob = job
        holder._isTextApiCalling.value = true
        handler.cancelCurrentApiJob("开始新聊天")
        assertTrue(job.isCancelled)
        assertNull(holder.textApiJob)
        assertFalse(holder._isTextApiCalling.value)
        verify(exactly = 0) { holder.showSnackbar(any()) }
    }

    @Test
    fun `任务恰好结束后的停止点击也不弹没有任务的无用提示`() {
        handler.cancelCurrentApiJob("用户取消操作", showFeedback = true)
        verify(exactly = 0) { holder.showSnackbar(any()) }
    }

    /** 使用真实 Room 审批记录和事件处理入口，确保无关恢复不可用时仍能显示所有审批类型。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `实时审批不依赖全局恢复且保留MCP和Secret申请`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val parent = SupervisorJob()
        val liveScope = CoroutineScope(parent + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
        try {
            every { AppDatabase.getDatabase(any()) } returns database
            val coordinator = mockCoordinator()
            every { AgentRunCoordinator.shared(any(), any()) } returns coordinator
            // 合并云端卡片需要协调器的状态，但本机审批显示不能触发全局恢复扫描。
            coEvery { coordinator.recoverInterventions() } throws IllegalStateException("无关恢复不可用")
            val liveHandler = ApiHandler(context, ViewModelStateHolder(), liveScope, mockk(relaxed = true), { _, _ -> }, {})
            val store = AgentRunStore(database.agentDao())
            val requests = listOf(
                AgentPauseRequest.EnableMcp("读取 Notion 页面"),
                AgentPauseRequest.EnableAgent("执行脚本"),
                AgentPauseRequest.SkillSecret("skill-1", "TOKEN", "需要密钥"),
            )
            for ((index, request) in requests.withIndex()) {
                val sessionId = "approval-session-$index"
                database.chatDao().insertSession(ChatSessionEntity(sessionId, 1L, 1L, false))
                val run = store.createRun(
                    sessionId, "user-$index", "assistant-$index", "config-1",
                    ChatRequest(
                        messages = listOf(SimpleTextApiMessage(role = "user", content = "继续任务")),
                        provider = "OpenAI", channel = "OpenAI", apiAddress = "https://example.test",
                        apiKey = "test", model = "test",
                    ),
                )
                val call = AgentContentBlock.ToolCall("call-$index", "request", buildJsonObject {})
                store.pauseForApproval(run, AgentApprovalRecord(
                    approvalRequestId = "approval-$index", requestId = "request-$index",
                    toolCall = call, pendingToolCalls = listOf(call), agentRequest = request,
                ))
                liveHandler.processStreamEvent(
                    AppStreamEvent.AgentApprovalRequired(run.id, "approval-$index"),
                    run.visibleAssistantMessageId,
                )
            }
            withTimeout(5_000) {
                val mcp = liveHandler.pendingMcpEnableApprovals.first { it.isNotEmpty() }.single()
                assertEquals("approval-0", mcp.approvalRequestId)
                assertEquals("approval-session-0", mcp.conversationId)
                assertEquals("读取 Notion 页面", mcp.reason)
                assertEquals("approval-1", liveHandler.pendingAgentEnableApprovals.first { it.isNotEmpty() }.single().approvalRequestId)
                assertEquals("TOKEN", liveHandler.pendingSkillSecretApprovals.first { it.isNotEmpty() }.single().name)
            }
            coVerify(exactly = 0) { coordinator.recoverInterventions() }
        } finally {
            parent.cancelAndJoin()
            unmockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
            Dispatchers.resetMain()
            database.close()
        }
    }
}
