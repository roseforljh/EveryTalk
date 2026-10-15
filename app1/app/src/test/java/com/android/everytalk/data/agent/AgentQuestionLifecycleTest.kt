package com.android.everytalk.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.RequestContextManagement
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.ModelTurnTransport
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
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

/** 真正执行协调器、Loop 和 Room，只替换模型传输；不依靠后台恢复轮询。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentQuestionLifecycleTest {
    private lateinit var database: AppDatabase
    private lateinit var applicationScope: CoroutineScope
    private lateinit var coordinator: AgentRunCoordinator
    private val turns = AtomicInteger()
    private val resumedRequest = CompletableDeferred<ChatRequest>()
    private val finishResponse = CompletableDeferred<Unit>()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        database.chatDao().insertSession(ChatSessionEntity("session", 1L, 1L, false))
        database.apiConfigDao().insertConfigs(listOf(ApiConfig(
            id = "config", name = "测试", address = "https://example.test",
            key = "test-key", model = "test-model", provider = "OpenAI",
        ).toEntity()))
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        coordinator = AgentRunCoordinator(
            context, scope = applicationScope, databaseOverride = database,
            modelTransport = ModelTurnTransport { turn -> flow {
                if (turns.incrementAndGet() == 1) {
                    emit(AppStreamEvent.ToolCall("question-call", AgentControlToolNames.REQUEST_USER_INPUT,
                        buildJsonObject { put("question", "你选择哪个方案？") }))
                    emit(AppStreamEvent.Finish("tool_calls"))
                } else {
                    resumedRequest.complete(turn.request)
                    finishResponse.await()
                    emit(AppStreamEvent.Content("已根据你的回答继续"))
                    emit(AppStreamEvent.Finish("stop"))
                }
            } },
        )
    }

    @After
    fun tearDown() = runBlocking {
        applicationScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        database.close()
    }

    private suspend fun ask(messageId: String = "assistant"): PendingIntervention {
        val request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "执行任务")),
            provider = "OpenAI", channel = "OpenAI兼容", apiAddress = "https://example.test",
            apiKey = "test-key", model = "test-model",
            tools = listOf(userInputRequestToolDefinition()),
            contextManagement = RequestContextManagement("config", 8192, 512, 7000, false),
        )
        withTimeout(15_000) {
            coordinator.run(AgentLoopRequest(request, "session", "user", messageId,
                ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8192))).toList()
        }
        return coordinator.pendingInterventions.value.single()
    }

    @Test
    fun `卡片消失取消提交协程仍立即显示回答并继续同一个任务`() = runBlocking {
        val question = ask()
        val received = Channel<AppStreamEvent>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.events.collect { received.send(it.second) }
        }
        // 对应 Compose 移除卡片时取消 rememberCoroutineScope，不能取消已接受的回答。
        lateinit var submission: Deferred<Boolean>
        val card = launch(Dispatchers.Unconfined) {
            coordinator.pendingInterventions.first { it.isEmpty() }
            submission.cancel()
        }
        submission = async(start = CoroutineStart.LAZY) {
            coordinator.answerUserQuestion(question.suspensionId, question.rowVersion,
                requireNotNull(question.resolutionNonce), "选择方案二")
        }
        try {
            submission.start()
            withTimeout(15_000) {
                card.join()
                val event = received.receive()
                assertTrue("回答接受后应立即产生气泡事件", event is AppStreamEvent.AgentFollowUpAccepted)
                assertEquals("选择方案二", (event as AppStreamEvent.AgentFollowUpAccepted).text)
                assertEquals("选择方案二", database.chatDao().getMessage("session", event.messageId)?.text)
                val request = resumedRequest.await()
                assertTrue(request.messages.any { it.role == "user" && it.toString().contains("选择方案二") })
                assertTrue(submission.isCancelled)
                assertTrue(coordinator.pendingInterventions.value.isEmpty())
                finishResponse.complete(Unit)
                while (received.receive() !is AppStreamEvent.Finish) { /* 等待自然结束。 */ }
                val run = requireNotNull(database.agentDao().getRun(question.runId))
                assertEquals(AgentRunStatus.COMPLETED.name, run.status)
                assertEquals(listOf(question.runId), database.agentDao().getRunsForSession("session").map { it.id })
                assertEquals(1, database.chatDao().getMessagesForSession("session").count { it.id == event.messageId })
            }
        } finally {
            card.cancelAndJoin()
            observer.cancelAndJoin()
        }
    }

    @Test
    fun `旧版本或错误nonce的回答不会进入队列和用户历史`() = runBlocking {
        val question = ask()
        assertFalse(coordinator.answerUserQuestion(question.suspensionId, question.rowVersion,
            "wrong-nonce", "不该被接受"))
        assertFalse(coordinator.answerUserQuestion(question.suspensionId, question.rowVersion + 1,
            requireNotNull(question.resolutionNonce), "不该被接受"))
        assertTrue(database.agentDao().getPendingSteering(question.runId).isEmpty())
        assertTrue(database.chatDao().getMessagesForSession("session").none { it.text == "不该被接受" })
        assertEquals(1, coordinator.pendingInterventions.value.size)
        assertTrue(coordinator.answerUserQuestion(question.suspensionId, question.rowVersion,
            requireNotNull(question.resolutionNonce), "选择方案一"))
        assertFalse(coordinator.answerUserQuestion(question.suspensionId, question.rowVersion,
            requireNotNull(question.resolutionNonce), "重复提交"))
        assertTrue(database.chatDao().getMessagesForSession("session").none { it.text == "重复提交" })
    }

    @Test
    fun `底部停止关闭问题卡片且迟到回答不能重新启动任务`() = runBlocking {
        val question = ask()
        coordinator.cancelVisibleRun("assistant")
        withTimeout(15_000) { coordinator.pendingInterventions.first { it.isEmpty() } }
        // 再跑一次恢复扫描，确认不是仅从当前画面暂时移除。
        coordinator.recoverInterventions()
        assertTrue(coordinator.pendingInterventions.value.isEmpty())
        assertEquals(SuspensionState.CANCELLED.name,
            database.agentDao().getSuspension(question.suspensionId)?.status)
        assertEquals(AgentRunStatus.CANCELLED.name, database.agentDao().getRun(question.runId)?.status)
        assertFalse(coordinator.answerUserQuestion(question.suspensionId, question.rowVersion,
            requireNotNull(question.resolutionNonce), "迟到的回答"))
        assertEquals(1, turns.get())
        assertTrue(database.agentDao().getPendingSteering(question.runId).isEmpty())
    }

    @Test
    fun `回答在旧任务收尾前提交也会在收尾后主动继续`() = runBlocking {
        val question = ask()
        // 精确停在旧任务已发出问题但 Job 尚未完成的窗口，不用延时猜测调度顺序。
        val field = AgentRunCoordinator::class.java.getDeclaredField("activeJobs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(coordinator) as ConcurrentHashMap<String, Job>
        val previousJob = Job()
        jobs["message:assistant"] = previousJob
        try {
            assertTrue(coordinator.answerUserQuestion(question.suspensionId, question.rowVersion,
                requireNotNull(question.resolutionNonce), "立即回答"))
            assertTrue(coordinator.pendingInterventions.value.isEmpty())
            assertFalse(resumedRequest.isCompleted)
            previousJob.complete()
            withTimeout(15_000) { resumedRequest.await() }
            assertEquals(2, turns.get())
        } finally {
            previousJob.cancelAndJoin()
        }
    }

    @Test
    fun `按Run停止与全局停止同样清除待回答卡片`() = runBlocking {
        val question = ask()
        coordinator.cancelRun(question.runId)
        withTimeout(15_000) { coordinator.pendingInterventions.first { it.isEmpty() } }
        assertEquals(SuspensionState.CANCELLED.name,
            database.agentDao().getSuspension(question.suspensionId)?.status)

        // 第二个任务重新提问，验证全局停止不依赖单任务停止入口。
        turns.set(0)
        val second = ask("assistant-2")
        coordinator.cancelAllActiveRuns()
        assertTrue(coordinator.pendingInterventions.value.isEmpty())
        assertEquals(AgentRunStatus.CANCELLED.name, database.agentDao().getRun(second.runId)?.status)
        assertEquals(SuspensionState.CANCELLED.name,
            database.agentDao().getSuspension(second.suspensionId)?.status)
    }

    @Test
    fun `已提交回答等待旧任务退出时停止不会在退出后复活`() = runBlocking {
        val question = ask()
        val field = AgentRunCoordinator::class.java.getDeclaredField("activeJobs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(coordinator) as ConcurrentHashMap<String, Job>
        val previousJob = Job()
        jobs["message:assistant"] = previousJob
        assertTrue(coordinator.answerUserQuestion(question.suspensionId, question.rowVersion,
            requireNotNull(question.resolutionNonce), "回答后立刻停止"))
        coordinator.cancelVisibleRun("assistant")
        // 停止入口会取消旧 Job 并封存 Run，恢复扫描不能把已登记的续写重新启动。
        withTimeout(15_000) {
            previousJob.join()
            applicationScope.coroutineContext[Job]!!.children.toList().joinAll()
        }
        coordinator.recoverInterventions()
        assertEquals(AgentRunStatus.CANCELLED.name, database.agentDao().getRun(question.runId)?.status)
        assertTrue(coordinator.pendingInterventions.value.isEmpty())
        assertEquals(1, turns.get())
    }
}
