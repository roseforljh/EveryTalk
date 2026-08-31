package com.android.everytalk.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.AppToolExecutor
import com.android.everytalk.data.network.ModelTurnTransport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
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

/** Safe Pause 只在 LLM / Tool 完整收尾后的边界挂起原 AgentLoop。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentSafePauseTest {
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
    fun `LLM流结束后暂停且Resume继续同一Run执行Tool`() = runBlocking {
        seedSession("llm-pause")
        val pauseController = AgentRunPauseController()
        val llmStarted = CompletableDeferred<Unit>()
        val finishLlmTurn = CompletableDeferred<Unit>()
        val toolExecutions = AtomicInteger(0)
        val observedRequests = mutableListOf<ChatRequest>()
        var modelTurns = 0
        val executor: AppToolExecutor = { _, _, _, _, _ ->
            toolExecutions.incrementAndGet()
            buildJsonObject { put("ok", true) }
        }
        val loop = AgentLoop(
            runStore = store,
            pauseController = pauseController,
            toolRuntime = AgentToolRuntime(executorProvider = { executor }, approvalProvider = { null }),
            modelTransport = ModelTurnTransport { request ->
                observedRequests += request.request
                modelTurns++
                if (modelTurns == 1) {
                    flow {
                        emit(AppStreamEvent.Content("正在分析"))
                        llmStarted.complete(Unit)
                        finishLlmTurn.await()
                        emit(toolCall("tool-1", "test_tool"))
                        emit(AppStreamEvent.Finish("tool_calls"))
                    }
                } else {
                    flowOf(AppStreamEvent.Content("完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )
        val result = async { loop.run(loopRequest("llm-pause")).toList() }

        llmStarted.await()
        assertTrue(pauseController.requestPause("assistant-llm-pause"))
        finishLlmTurn.complete(Unit)
        awaitControlState(pauseController, "assistant-llm-pause", AgentRunControlState.PAUSED)

        assertEquals(0, toolExecutions.get())
        delay(100)
        assertFalse(result.isCompleted)
        assertEquals(1, modelTurns)

        assertTrue(pauseController.resume("assistant-llm-pause"))
        withTimeout(5_000) { result.await() }

        assertEquals(1, toolExecutions.get())
        assertEquals(2, modelTurns)
        assertEquals(1, store.getRunsForSession("llm-pause").size)
        assertEquals(
            1,
            observedRequests.last().messages.count { it.role == "user" },
        )
    }

    @Test
    fun `最终回答无Tool时Pause请求不阻止自然完成`() = runBlocking {
        seedSession("final-answer-pause")
        val pauseController = AgentRunPauseController()
        val answerStarted = CompletableDeferred<Unit>()
        val finishAnswer = CompletableDeferred<Unit>()
        val loop = AgentLoop(
            runStore = store,
            pauseController = pauseController,
            modelTransport = ModelTurnTransport {
                flow {
                    emit(AppStreamEvent.Content("最终回答"))
                    answerStarted.complete(Unit)
                    finishAnswer.await()
                    emit(AppStreamEvent.Finish("stop"))
                }
            },
        )
        val result = async { loop.run(loopRequest("final-answer-pause")).toList() }

        answerStarted.await()
        assertTrue(pauseController.requestPause("assistant-final-answer-pause"))
        finishAnswer.complete(Unit)
        withTimeout(5_000) { result.await() }

        assertTrue(result.isCompleted)
        assertTrue(pauseController.snapshots.value.isEmpty())
        assertEquals(AgentRunStatus.COMPLETED.name, store.getRunsForSession("final-answer-pause").single().status)
    }

    @Test
    fun `edit write exec完成并保存结果后才暂停下一轮LLM`() = runBlocking {
        seedSession("tool-pause")
        val pauseController = AgentRunPauseController()
        val firstToolStarted = CompletableDeferred<Unit>()
        val finishFirstTool = CompletableDeferred<Unit>()
        val executed = mutableListOf<String>()
        var modelTurns = 0
        val executor: AppToolExecutor = { _, _, toolCallId, _, _ ->
            if (executed.isEmpty()) {
                firstToolStarted.complete(Unit)
                finishFirstTool.await()
            }
            executed += toolCallId
            buildJsonObject { put("ok", true); put("tool_call_id", toolCallId) }
        }
        val loop = AgentLoop(
            runStore = store,
            pauseController = pauseController,
            toolRuntime = AgentToolRuntime(executorProvider = { executor }, approvalProvider = { null }),
            modelTransport = ModelTurnTransport {
                modelTurns++
                if (modelTurns == 1) {
                    flowOf(
                        toolCall("edit-1", ComputerToolNames.EDIT),
                        toolCall("write-1", ComputerToolNames.WRITE_FILE),
                        toolCall("exec-1", ComputerToolNames.EXEC),
                        AppStreamEvent.Finish("tool_calls"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("工具结果已处理"), AppStreamEvent.Finish("stop"))
                }
            },
        )
        val result = async { loop.run(loopRequest("tool-pause")).toList() }

        firstToolStarted.await()
        assertTrue(pauseController.requestPause("assistant-tool-pause"))
        finishFirstTool.complete(Unit)
        awaitControlState(pauseController, "assistant-tool-pause", AgentRunControlState.PAUSED)

        assertEquals(listOf("edit-1", "write-1", "exec-1"), executed)
        assertEquals(1, modelTurns)
        assertFalse(result.isCompleted)
        val run = store.getRunsForSession("tool-pause").single()
        val savedResults = store.appendRunTranscript(run.id, emptyList())
            .filterIsInstance<AgentToolResultApiMessage>()
        assertEquals(listOf("edit-1", "write-1", "exec-1"), savedResults.map { it.toolCallId })

        delay(100)
        assertEquals(1, modelTurns)
        assertTrue(pauseController.resume("assistant-tool-pause"))
        withTimeout(5_000) { result.await() }

        assertEquals(2, modelTurns)
        assertEquals(listOf("edit-1", "write-1", "exec-1"), executed)
        assertEquals(1, store.getRunsForSession("tool-pause").size)
    }

    private suspend fun seedSession(sessionId: String) {
        database.chatDao().insertSession(ChatSessionEntity(sessionId, 1L, 1L, false))
    }

    private fun loopRequest(sessionId: String) = AgentLoopRequest(
        request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "执行任务")),
            provider = "OpenAI",
            channel = "OpenAI兼容",
            apiAddress = "https://example.test",
            apiKey = "test-key",
            model = "test-model",
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

    private suspend fun awaitControlState(
        controller: AgentRunPauseController,
        messageId: String,
        expected: AgentRunControlState,
    ) {
        withTimeout(5_000) {
            controller.snapshots
                .mapNotNull { it[messageId]?.state }
                .first { it == expected }
        }
    }
}
