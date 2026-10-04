package com.android.everytalk.statecontroller

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.DataClass.ChatRequest
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
            val coordinator = mockk<AgentRunCoordinator>()
            every { coordinator.events } returns MutableSharedFlow<Pair<String, AppStreamEvent>>()
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

    /** 真实停止入口：远端失败不放行队列，可重试，旧消息点击不能取消新会话。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `远端停止失败保留取消屏障且重试成功后才解锁`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val parent = SupervisorJob()
        val liveScope = CoroutineScope(parent + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
        try {
            every { AppDatabase.getDatabase(any()) } returns database
            val coordinator = mockk<AgentRunCoordinator>(relaxed = true)
            every { coordinator.events } returns MutableSharedFlow<Pair<String, AppStreamEvent>>()
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
            var attempts = 0
            val liveHandler = ApiHandler(context, state, liveScope, mockk(relaxed = true), { _, _ -> }, {},
                cancelComputerExecutions = { sessionId, runId, complete ->
                    assertEquals("stop-session", sessionId)
                    assertEquals(run.id, runId)
                    attempts++
                    complete(attempts > 1)
                    Job().apply { complete() }
                })
            val collectors = parent.children.toSet()
            state._currentTextStreamingAiMessageId.value = "assistant"
            state._isTextApiCalling.value = true
            liveHandler.cancelCurrentApiJob("用户停止", showFeedback = true)
            withTimeout(5_000) { parent.children.filter { it !in collectors }.toList().joinAll() }
            assertTrue(state._isRemoteCancellationPending.value)
            assertEquals("assistant", liveHandler.retryableRemoteStopMessageId.value)
            assertEquals("CANCELLED", database.agentDao().getRun(run.id)?.status)
            // 重试必须沿用点击时的会话，不能读取当前已经切换的会话。
            state.setCurrentConversationId("new-session")
            liveHandler.retryRemoteStopConfirmation("wrong-message")
            assertEquals(1, attempts)
            liveHandler.retryRemoteStopConfirmation("assistant")
            liveHandler.retryRemoteStopConfirmation("assistant")
            withTimeout(5_000) { parent.children.filter { it !in collectors }.toList().joinAll() }
            assertEquals(2, attempts)
            assertFalse(state._isRemoteCancellationPending.value)
            assertNull(liveHandler.retryableRemoteStopMessageId.value)
        } finally {
            parent.cancelAndJoin()
            unmockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
            Dispatchers.resetMain()
            database.close()
        }
    }

    /** 使用真实超时路径，验证迟到的远端确认主动解锁，而不是必须再点停止。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `远端停止超时后迟到的成功会自动解锁且旧等待不能覆盖成功`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val parent = SupervisorJob()
        val liveScope = CoroutineScope(parent + Dispatchers.Unconfined)
        val remote = Job()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
        try {
            every { AppDatabase.getDatabase(any()) } returns database
            val coordinator = mockk<AgentRunCoordinator>(relaxed = true)
            every { coordinator.events } returns MutableSharedFlow<Pair<String, AppStreamEvent>>()
            every { AgentRunCoordinator.shared(any(), any()) } returns coordinator
            val state = ViewModelStateHolder()
            state.setCurrentConversationId("slow-stop")
            database.chatDao().insertSession(ChatSessionEntity("slow-stop", 1L, 1L, false))
            AgentRunStore(database.agentDao()).createRun("slow-stop", "user", "assistant", "config", ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "任务")),
                provider = "OpenAI", channel = "OpenAI", apiAddress = "https://example.test",
                apiKey = "test", model = "test",
            ))
            var callback: ((Boolean) -> Unit)? = null
            var attempts = 0
            val liveHandler = ApiHandler(context, state, liveScope, mockk(relaxed = true), { _, _ -> }, {},
                cancelComputerExecutions = { _, _, complete -> attempts++; callback = complete; remote })
            val collectors = parent.children.toSet()
            state._currentTextStreamingAiMessageId.value = "assistant"
            state._isTextApiCalling.value = true
            liveHandler.cancelCurrentApiJob("用户停止")
            withTimeout(REMOTE_STOP_CONFIRMATION_TIMEOUT_MS + 5_000) {
                while (liveHandler.retryableRemoteStopMessageId.value == null) kotlinx.coroutines.delay(10)
            }
            assertTrue(state._isRemoteCancellationPending.value)
            assertEquals("assistant", liveHandler.retryableRemoteStopMessageId.value)
            assertTrue(remote.isActive)
            // 超时后重试必须复用后台取消 Job，不能不断创建新 SSH 任务。
            liveHandler.retryRemoteStopConfirmation("assistant")
            assertNull(liveHandler.retryableRemoteStopMessageId.value)
            requireNotNull(callback).invoke(true)
            remote.complete()
            withTimeout(5_000) {
                while (state._isRemoteCancellationPending.value) kotlinx.coroutines.delay(10)
            }
            assertEquals(1, attempts)
            assertFalse(state._isRemoteCancellationPending.value)
            assertNull(liveHandler.retryableRemoteStopMessageId.value)
        } finally {
            remote.cancel()
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
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
        try {
            every { AppDatabase.getDatabase(any()) } returns database
            // 旧实现会在投影卡片前访问恢复协调器，本测试在该边界明确失败。
            every { AgentRunCoordinator.shared(any(), any()) } throws IllegalStateException("无关恢复不可用")
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
                handler.processStreamEvent(
                    AppStreamEvent.AgentApprovalRequired(run.id, "approval-$index"),
                    run.visibleAssistantMessageId,
                )
            }
            assertEquals("approval-0", handler.pendingMcpEnableApprovals.value.single().approvalRequestId)
            assertEquals("approval-session-0", handler.pendingMcpEnableApprovals.value.single().conversationId)
            assertEquals("读取 Notion 页面", handler.pendingMcpEnableApprovals.value.single().reason)
            assertEquals("approval-1", handler.pendingAgentEnableApprovals.value.single().approvalRequestId)
            assertEquals("TOKEN", handler.pendingSkillSecretApprovals.value.single().name)
            verify(exactly = 0) { AgentRunCoordinator.shared(any(), any()) }
        } finally {
            unmockkObject(AppDatabase.Companion, AgentRunCoordinator.Companion)
            Dispatchers.resetMain()
            database.close()
        }
    }
}
