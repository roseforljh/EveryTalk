package com.android.everytalk.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.ModelTurnTransport
import com.android.everytalk.statecontroller.appendExecutionTraceContent
import com.android.everytalk.statecontroller.applyAgentTurnRetryReset
import com.android.everytalk.statecontroller.reduceExecutionTrace
import com.android.everytalk.statecontroller.requiresOrderedContentFlush
import com.android.everytalk.ui.screens.MainScreen.chat.core.orderedAiOutputSegments
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 使用真实 Loop 和 Room，传输层用确定性闸门，验证边界而不访问外网模型。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentSteeringTimelineTest {
    @Test
    fun `steering在旧turn结束后新回答前发出边界且历史恢复顺序一致`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            database.chatDao().insertSession(ChatSessionEntity("session", 1L, 1L, false))
            val store = AgentRunStore(database.agentDao())
            val started = CompletableDeferred<Unit>()
            val finishTurn = CompletableDeferred<Unit>()
            val requests = mutableListOf<ChatRequest>()
            val loop = AgentLoop(runStore = store, modelTransport = ModelTurnTransport { turn ->
                requests += turn.request
                flow {
                    if (requests.size == 1) {
                        emit(AppStreamEvent.Content("旧回答"))
                        started.complete(Unit)
                        finishTurn.await()
                        emit(AppStreamEvent.Content("完整结束"))
                    } else {
                        emit(AppStreamEvent.Content("新回答"))
                    }
                    emit(AppStreamEvent.Finish("stop"))
                }
            })
            val request = ChatRequest(
                messages = listOf(SimpleTextApiMessage(role = "user", content = "任务")),
                provider = "OpenAI", channel = "OpenAI兼容", apiAddress = "https://example.test",
                apiKey = "test-key", model = "test-model",
            )
            withTimeout(10_000) {
                val running = async {
                    loop.run(AgentLoopRequest(
                        request = request, sessionId = "session", userMessageId = "u0",
                        visibleAssistantMessageId = "same-ai",
                        tokenLimits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8192),
                    )).toList()
                }
                started.await()
                val run = store.getRunsForSession("session").single()
                assertTrue(store.enqueueSteering(run.id, AgentSteeringInstruction("u1", "调整方向", createdAt = 2L)))
                finishTurn.complete(Unit)
                val events = running.await()
                assertEquals(listOf("旧回答", "完整结束", "用户:u1", "新回答"), events.mapNotNull {
                    when (it) {
                        is AppStreamEvent.Content -> it.text
                        is AppStreamEvent.AgentFollowUpAccepted -> "用户:${it.messageId}"
                        else -> null
                    }
                })
                assertEquals(2, requests.size)
                // 运行时检查点也可能以 user role 冻结，直接检查两条真实输入，避免把状态快照算成用户消息。
                assertEquals(1, requests.last().messages.count { it is SimpleTextApiMessage && it.role == "user" && it.content == "任务" })
                assertEquals(1, requests.last().messages.count { it is SimpleTextApiMessage && it.role == "user" && it.content == "调整方向" })
                val completed = store.getRunsForSession("session").single()
                assertEquals(run.id, completed.id)
                assertEquals("same-ai", completed.visibleAssistantMessageId)
                assertEquals(AgentRunStatus.COMPLETED.name, completed.status)

                var liveTrace = emptyList<ExecutionTraceEvent>()
                events.forEach { event ->
                    liveTrace = if (event is AppStreamEvent.Content) {
                        appendExecutionTraceContent(liveTrace, event.text)
                    } else reduceExecutionTrace(liveTrace, event)
                }
                val restored = store.executionTrace(run.id)
                fun withoutTime(trace: List<ExecutionTraceEvent>) = trace.map {
                    when (it) {
                        is ExecutionTraceEvent.Content -> it.copy(startedAtMillis = null)
                        is ExecutionTraceEvent.UserMessageBoundary -> it.copy(startedAtMillis = null)
                        else -> it
                    }
                }
                assertEquals(withoutTime(liveTrace), withoutTime(restored))
                val message = Message(id = "same-ai", text = "旧回答完整结束新回答", sender = Sender.AI, executionTrace = restored)
                assertEquals(message, Json.decodeFromString(Message.serializer(), Json.encodeToString(Message.serializer(), message)))
                val reset = applyAgentTurnRetryReset(message, AppStreamEvent.AgentTurnRetryReset(message.text, retainedTrace = restored))
                assertEquals(orderedAiOutputSegments(restored), orderedAiOutputSegments(reset.executionTrace))

                val accepted = events.filterIsInstance<AppStreamEvent.AgentFollowUpAccepted>().single()
                assertTrue(accepted.requiresOrderedContentFlush())
                assertEquals(liveTrace, reduceExecutionTrace(liveTrace, accepted))
            }
        } finally {
            database.close()
        }
    }
}
