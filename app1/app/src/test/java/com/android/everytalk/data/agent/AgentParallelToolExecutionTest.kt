package com.android.everytalk.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.computer.ComputerPermissionMode
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.AppToolExecutor
import com.android.everytalk.data.network.ModelTurnTransport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 验证同一模型轮次的独立工具会并发执行，同时保留结果顺序和审批边界。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentParallelToolExecutionTest {
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
    fun `同轮工具并发执行且结果按模型顺序返回`() = runBlocking {
        seedSession("parallel-session")
        val startedCount = AtomicInteger(0)
        val allStarted = CompletableDeferred<Unit>()
        val observedRequests = mutableListOf<ChatRequest>()
        var turn = 0
        val executor: AppToolExecutor = { _, _, toolCallId, _, _ ->
            if (startedCount.incrementAndGet() == 3) allStarted.complete(Unit)
            // 串行实现无法让三条调用同时到达这里，会直接超时并让测试失败。
            withTimeout(1_000L) { allStarted.await() }
            if (toolCallId == "call-2") error("第二条工具失败")
            buildJsonObject {
                put("execution_id", "execution-$toolCallId")
                put("result", "result-$toolCallId")
            }
        }
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = { executor },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport { request ->
                observedRequests += request.request
                turn += 1
                if (turn == 1) {
                    flowOf(
                        toolCall("call-1", "tool-one"),
                        toolCall("call-2", "tool-two"),
                        toolCall("call-3", "tool-three"),
                        AppStreamEvent.Finish("tool_calls"),
                    )
                } else {
                    flowOf(
                        AppStreamEvent.Content("并发结果已处理"),
                        AppStreamEvent.Finish("stop"),
                    )
                }
            },
        )

        val events = withTimeout(5_000L) {
            loop.run(loopRequest("parallel-session")).toList()
        }

        assertEquals(3, startedCount.get())
        val results = observedRequests[1].messages.filterIsInstance<AgentToolResultApiMessage>()
        assertEquals(listOf("call-1", "call-2", "call-3"), results.map { it.toolCallId })
        assertFalse(results[0].isError)
        assertTrue(results[1].isError)
        assertFalse(results[2].isError)
        assertEquals(
            setOf("execution-call-1", "execution-call-3"),
            events.filterIsInstance<AppStreamEvent.ExecutionStatusUpdate>()
                .mapNotNull { it.executionId }
                .toSet(),
        )
        assertTrue(events.any { it is AppStreamEvent.Content && it.text == "并发结果已处理" })
    }

    @Test
    fun `审批请求只执行它前面的工具且不会越过边界`() = runBlocking {
        seedSession("approval-boundary-session")
        val executedIds = mutableListOf<String>()
        val executor: AppToolExecutor = { _, _, toolCallId, _, _ ->
            executedIds += toolCallId
            JsonPrimitive("result-$toolCallId")
        }
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = { executor },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport {
                flowOf(
                    toolCall("call-before", "tool-before"),
                    AppStreamEvent.ToolCall(
                        id = "call-approval",
                        name = AgentControlToolNames.REQUEST_AGENT,
                        argumentsObj = buildJsonObject { put("reason", "需要服务器能力") },
                    ),
                    toolCall("call-after", "tool-after"),
                    AppStreamEvent.Finish("tool_calls"),
                )
            },
        )

        val events = loop.run(loopRequest("approval-boundary-session")).toList()

        assertEquals(listOf("call-before"), executedIds)
        assertTrue(events.any { it is AppStreamEvent.AgentApprovalRequired })
        val waitingRun = store.getWaitingApprovalRuns().single()
        val approval = requireNotNull(store.pendingApproval(waitingRun.id))
        assertEquals(listOf("call-approval", "call-after"), approval.pendingToolCalls.map { it.id })
    }

    @Test
    fun `修改服务器的命令保持模型原始顺序`() = runBlocking {
        seedSession("write-order-session")
        val activeCount = AtomicInteger(0)
        val peakActiveCount = AtomicInteger(0)
        val executionOrder = mutableListOf<String>()
        val executor: AppToolExecutor = { _, _, toolCallId, _, _ ->
            executionOrder += toolCallId
            val active = activeCount.incrementAndGet()
            peakActiveCount.updateAndGet { current -> maxOf(current, active) }
            try {
                delay(50L)
                JsonPrimitive("result-$toolCallId")
            } finally {
                activeCount.decrementAndGet()
            }
        }
        var turn = 0
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = { executor },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport {
                turn += 1
                if (turn == 1) {
                    flowOf(
                        execCall("write-1", "mkdir -p project"),
                        execCall("write-2", "touch project/app.txt"),
                        AppStreamEvent.Finish("tool_calls"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("写入完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )
        val computerContext = ComputerRequestContext(
            conversationId = "write-order-session",
            computerId = "computer-1",
            workspaceId = "workspace-1",
            permissionMode = ComputerPermissionMode.FULL,
        )

        loop.run(loopRequest("write-order-session", computerContext)).toList()

        assertEquals(listOf("write-1", "write-2"), executionOrder)
        assertEquals(1, peakActiveCount.get())
    }

    private suspend fun seedSession(sessionId: String) {
        database.chatDao().insertSession(ChatSessionEntity(sessionId, 1L, 1L, false))
    }

    private fun loopRequest(
        sessionId: String,
        computerContext: ComputerRequestContext? = null,
    ) = AgentLoopRequest(
        request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "并行执行工具")),
            provider = "OpenAI",
            channel = "OpenAI兼容",
            apiAddress = "https://example.test",
            apiKey = "test-key",
            model = "test-model",
            localComputerRequestContext = computerContext,
        ),
        sessionId = sessionId,
        userMessageId = "user-$sessionId",
        visibleAssistantMessageId = "assistant-$sessionId",
        tokenLimits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
    )

    private fun toolCall(id: String, name: String) = AppStreamEvent.ToolCall(
        id = id,
        name = name,
        argumentsObj = buildJsonObject {},
    )

    private fun execCall(id: String, command: String) = AppStreamEvent.ToolCall(
        id = id,
        name = ComputerToolNames.EXEC,
        argumentsObj = buildJsonObject { put("command", command) },
    )
}
