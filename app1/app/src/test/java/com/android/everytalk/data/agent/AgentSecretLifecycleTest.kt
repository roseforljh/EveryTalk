package com.android.everytalk.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.RequestContextManagement
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.ModelTurnTransport
import com.android.everytalk.statecontroller.reduceExecutionTrace
import com.android.everytalk.statecontroller.applyExecutionStatusEventToMessage
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** 执行真实 Coordinator、Broker、Room 和 Loop；仅替换远端写入与模型，不接触真实密钥。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentSecretLifecycleTest {
    private lateinit var database: AppDatabase
    private lateinit var applicationScope: CoroutineScope
    private lateinit var coordinator: AgentRunCoordinator
    private val writerStarted = CompletableDeferred<Unit>()
    private val finishWrite = CompletableDeferred<AdapterFulfillmentResult>()
    private val resumedRequest = CompletableDeferred<ChatRequest>()
    private val finishResponse = CompletableDeferred<Unit>()
    private val turns = AtomicInteger()
    private val writes = AtomicInteger()
    private lateinit var borrowed: CharArray
    private val testSecret = "test-only-secret-do-not-persist"

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        database.chatDao().insertSession(ChatSessionEntity("session", 1L, 1L, false))
        database.apiConfigDao().insertConfigs(listOf(ApiConfig(
            id = "config", name = "测试", address = "https://example.test",
            key = "model-test-key", model = "test-model", provider = "OpenAI",
        ).toEntity()))
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writer = object : AgentInterventionAdapter {
            override suspend fun validate(request: TrustedInterventionRequest): Boolean =
                request.resolutionMaterialKind == ResolutionMaterialKind.EPHEMERAL
            override suspend fun present(request: TrustedInterventionRequest): String = "测试密钥写入"
            override suspend fun fulfill(request: TrustedInterventionRequest, protectedResolution: ProtectedResolution): AdapterFulfillmentResult {
                writes.incrementAndGet()
                borrowed = (protectedResolution as ProtectedResolution.Ephemeral).borrow()
                assertEquals(testSecret, borrowed.concatToString())
                writerStarted.complete(Unit)
                return finishWrite.await()
            }
            override suspend fun reconcile(request: TrustedInterventionRequest): AdapterDeliveryFact = AdapterDeliveryFact.UNKNOWN
            override suspend fun cleanup(request: TrustedInterventionRequest) = Unit
        }
        coordinator = AgentRunCoordinator(
            context, scope = applicationScope, databaseOverride = database,
            interventionAdapters = AgentInterventionAdapterRegistry(mapOf("workspace-secret-adapter" to writer)),
            modelTransport = ModelTurnTransport { turn -> flow {
                if (turns.incrementAndGet() == 1) {
                    emit(secretCall())
                    emit(AppStreamEvent.Finish("tool_calls"))
                } else {
                    resumedRequest.complete(turn.request)
                    finishResponse.await()
                    emit(AppStreamEvent.Content("已继续原任务"))
                    emit(AppStreamEvent.Finish("stop"))
                }
            } },
        )
    }

    @After
    fun tearDown() = runBlocking {
        applicationScope.coroutineContext[Job]?.cancelAndJoin()
        database.close()
    }

    private fun secretCall() = AppStreamEvent.ToolCall(
        "secret-call", AgentControlToolNames.REQUEST_PROTECTED_SECRET,
        buildJsonObject {
            put("scope", "SERVER_ENV")
            put("name", "TEST_API_KEY")
            put("path", "/workspace/.env")
            put("reason", "测试任务需要配置密钥")
        },
    )

    private suspend fun ask(): PendingIntervention {
        val request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "配置并继续任务")),
            provider = "OpenAI", channel = "OpenAI兼容", apiAddress = "https://example.test",
            apiKey = "model-test-key", model = "test-model",
            tools = listOf(protectedSecretRequestToolDefinition()),
            contextManagement = RequestContextManagement("config", 8192, 512, 7000, false),
        )
        withTimeout(15_000) {
            coordinator.run(AgentLoopRequest(request, "session", "user", "assistant",
                ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8192))).toList()
        }
        return coordinator.pendingInterventions.value.single()
    }

    private suspend fun submit(pending: PendingIntervention, chars: CharArray = testSecret.toCharArray()): Boolean =
        coordinator.resolveEphemeralIntervention(pending.suspensionId, pending.rowVersion,
            requireNotNull(pending.resolutionNonce), chars)

    private suspend fun assertSecretNotPersisted(pending: PendingIntervention) {
        assertTrue(borrowed.all { it == '\u0000' })
        assertFalse(database.agentDao().getEntries(pending.runId).toString().contains(testSecret))
        assertFalse(database.agentDao().getSuspension(pending.suspensionId).toString().contains(testSecret))
        assertFalse(database.chatDao().getMessagesForSession("session").toString().contains(testSecret))
        assertEquals(1, writes.get())
    }

    @Test
    fun `远端写入中关闭提交界面不会取消已接收密钥或丢失续写`() = runBlocking {
        val pending = ask()
        val input = testSecret.toCharArray()
        val ui = async { submit(pending, input) }
        withTimeout(15_000) { writerStarted.await() }
        ui.cancelAndJoin()
        finishWrite.complete(AdapterFulfillmentResult(AdapterDeliveryFact.DELIVERED))
        withTimeout(15_000) { resumedRequest.await() }
        assertTrue(input.all { it == '\u0000' })
        assertSecretNotPersisted(pending)
        assertTrue(coordinator.pendingInterventions.value.isEmpty())
    }

    @Test
    fun `结果保留原密钥工具名称并在下一轮模型前通知工具结束`() = runBlocking {
        val pending = ask()
        val events = Channel<AppStreamEvent>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.events.collect { events.send(it.second) }
        }
        try {
            finishWrite.complete(AdapterFulfillmentResult(AdapterDeliveryFact.DELIVERED))
            assertTrue(submit(pending))
            val request = withTimeout(15_000) { resumedRequest.await() }
            val result = request.messages.filterIsInstance<AgentToolResultApiMessage>().single()
            assertEquals(AgentControlToolNames.REQUEST_PROTECTED_SECRET, result.toolName)
            assertEquals("secret-call", result.toolCallId)
            assertFalse(result.isError)
            val suspension = requireNotNull(database.agentDao().getSuspension(pending.suspensionId))
            val persisted = AgentRunStore(database.agentDao()).finalToolResult(pending.runId, suspension.requestId, "secret-call")
            assertEquals(AgentControlToolNames.REQUEST_PROTECTED_SECRET, persisted?.toolName)
            assertFalse(request.messages.toString().contains(testSecret))
            val completed = withTimeout(2_000) {
                var event: AppStreamEvent
                do { event = events.receive() } while (event !is AppStreamEvent.ExecutionStatusUpdate ||
                    event.status != null || event.toolCallId != "secret-call")
                event
            }
            val initialTrace = reduceExecutionTrace(emptyList(), secretCall())
            val trace = reduceExecutionTrace(initialTrace, completed)
            assertTrue(trace.filterIsInstance<ExecutionTraceEvent.Tool>().single().step.completed)
            val message = applyExecutionStatusEventToMessage(Message(
                id = "assistant", text = "", sender = Sender.AI,
                executionSteps = initialTrace.filterIsInstance<ExecutionTraceEvent.Tool>().map { it.step },
                executionTrace = initialTrace,
            ), completed)
            assertTrue(message.executionSteps.single().completed)
            assertSecretNotPersisted(pending)
            finishResponse.complete(Unit)
            withTimeout(15_000) {
                while (events.receive() !is AppStreamEvent.Finish) { /* 等待原任务完成。 */ }
            }
            assertEquals(AgentRunStatus.COMPLETED.name, database.agentDao().getRun(pending.runId)?.status)
        } finally { observer.cancelAndJoin() }
    }

    @Test
    fun `明确写入失败会把错误返回模型继续处理而不是永远等待密钥`() = runBlocking {
        val pending = ask()
        finishWrite.complete(AdapterFulfillmentResult(AdapterDeliveryFact.NOT_DELIVERED, failureCode = "ENV_PERMISSION_DENIED"))
        assertTrue(submit(pending))
        val request = withTimeout(15_000) { resumedRequest.await() }
        val result = request.messages.filterIsInstance<AgentToolResultApiMessage>().single()
        assertTrue(result.isError)
        assertTrue(result.toString().contains("ENV_PERMISSION_DENIED"))
        assertTrue(coordinator.pendingInterventions.value.isEmpty())
        assertSecretNotPersisted(pending)
    }

    @Test
    fun `不提供密钥时发送普通回复会继续同一个任务且不调用密钥Adapter`() = runBlocking {
        val pending = ask()
        val events = Channel<AppStreamEvent>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.events.collect { events.send(it.second) }
        }
        try {
            assertTrue(coordinator.respondToInterventionWithText(
                suspensionId = pending.suspensionId,
                expectedVersion = pending.rowVersion,
                resolutionNonce = requireNotNull(pending.resolutionNonce),
                content = "暂时没有这个变量，请先继续并告诉我缺少什么。",
            ))
            val request = withTimeout(15_000) { resumedRequest.await() }
            val result = request.messages.filterIsInstance<AgentToolResultApiMessage>().single()
            assertTrue(result.isError)
            assertTrue(result.toString().contains("INTERVENTION_REJECTED"))
            assertTrue(request.messages.any {
                it.role == "user" && it.toString().contains("暂时没有这个变量")
            })
            assertTrue(database.chatDao().getMessagesForSession("session").any {
                it.text.contains("暂时没有这个变量")
            })
            assertEquals(0, writes.get())
            assertTrue(coordinator.pendingInterventions.value.isEmpty())
            finishResponse.complete(Unit)
            withTimeout(15_000) {
                while (events.receive() !is AppStreamEvent.Finish) { /* 等待同一 Run 完成。 */ }
            }
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun `写入结果未知显示确认卡片且重复提交不会重放密钥`() = runBlocking {
        val pending = ask()
        finishWrite.complete(AdapterFulfillmentResult(AdapterDeliveryFact.UNKNOWN))
        assertTrue(submit(pending))
        assertEquals(SuspensionState.USER_DECISION_REQUIRED, coordinator.pendingInterventions.value.single().state)
        assertFalse(submit(pending))
        coordinator.recoverInterventions()
        assertEquals(1, turns.get())
        assertSecretNotPersisted(pending)
    }

    @Test
    fun `写入途中停止任务后不能在写入完成时复活`() = runBlocking {
        val pending = ask()
        val ui = async { submit(pending) }
        withTimeout(15_000) { writerStarted.await() }
        coordinator.cancelVisibleRun("assistant")
        withTimeout(15_000) { coordinator.pendingInterventions.first { it.isEmpty() } }
        finishWrite.complete(AdapterFulfillmentResult(AdapterDeliveryFact.DELIVERED))
        withTimeout(15_000) {
            ui.await()
            applicationScope.coroutineContext[Job]!!.children.toList().joinAll()
        }
        coordinator.recoverInterventions()
        assertEquals(AgentRunStatus.CANCELLED.name, database.agentDao().getRun(pending.runId)?.status)
        assertTrue(coordinator.pendingInterventions.value.isEmpty())
        assertEquals(1, turns.get())
        assertSecretNotPersisted(pending)
    }
}
