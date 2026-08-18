package com.android.everytalk.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.RequestContextManagement
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.ModelTurnTransport
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 锁定 Agent 上下文压缩的网络恢复语义和摘要输出预算。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentContextCompressionRecoveryTest {
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
    fun `压缩连接中断保留待恢复状态`() = runBlocking {
        seedSession("compression-recovery")
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                flowOf(
                    AppStreamEvent.Error(
                        message = "OpenAI Responses 连接中断",
                        code = "connection_aborted",
                        type = "retryable_network",
                    )
                )
            },
        )

        val events = loop.run(loopRequest("compression-recovery")).toList()

        assertTrue(events.filterIsInstance<AppStreamEvent.Error>().any { it.type == "retryable_network" })
        assertEquals(1, store.getPendingModelContinuationRuns().size)
    }

    @Test
    fun `压缩摘要预算随待压缩内容收紧`() = runBlocking {
        seedSession("compression-budget")
        val compressionBudgets = mutableListOf<Int?>()
        var requestCount = 0
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport { turn ->
                requestCount++
                if (requestCount == 1) {
                    compressionBudgets += turn.request.generationConfig?.maxOutputTokens
                    flowOf(AppStreamEvent.Content("保留早期目标和关键结论。"), AppStreamEvent.Finish("stop"))
                } else {
                    flowOf(AppStreamEvent.Content("已继续处理"), AppStreamEvent.Finish("stop"))
                }
            },
        )

        val events = loop.run(loopRequest("compression-budget")).toList()

        assertTrue(checkNotNull(compressionBudgets.single()) <= 256)
        assertTrue(events.any { it is AppStreamEvent.Content && it.text == "已继续处理" })
    }

    private suspend fun seedSession(sessionId: String) {
        database.chatDao().insertSession(ChatSessionEntity(sessionId, 1L, 1L, false))
    }

    private fun loopRequest(sessionId: String): AgentLoopRequest {
        val messages = listOf(
            SimpleTextApiMessage(id = "old-user", role = "user", content = "早期需求".repeat(80)),
            SimpleTextApiMessage(id = "old-assistant", role = "assistant", content = "早期结论".repeat(80)),
            SimpleTextApiMessage(id = "latest-user", role = "user", content = "继续处理"),
        )
        return AgentLoopRequest(
            request = ChatRequest(
                messages = messages,
                provider = "OpenAI",
                channel = "OpenAI兼容",
                apiAddress = "https://example.test",
                apiKey = "test-key",
                model = "test-model",
                contextManagement = RequestContextManagement(
                    configId = "config-1",
                    maxContextTokens = 8_192,
                    reservedOutputTokens = 512,
                    compactThresholdTokens = 800,
                    autoCompressionEnabled = true,
                ),
            ),
            sessionId = sessionId,
            userMessageId = "user-$sessionId",
            visibleAssistantMessageId = "assistant-$sessionId",
            tokenLimits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
        )
    }
}
