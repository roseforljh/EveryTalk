package com.android.everytalk.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.RequestContextManagement
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.PendingMessageEntity
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.ModelTurnTransport
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    fun `Provider连续失败在第三次重试后终止Run`() = runBlocking {
        val sessionId = "provider-retry-limit"
        seedSession(sessionId)
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                flowOf(
                    AppStreamEvent.Error(
                        message = "connection reset",
                        code = "connection_aborted",
                        type = "retryable_network",
                    )
                )
            },
        )
        val baseRequest = loopRequest(sessionId)
        var events = loop.run(baseRequest).toList()

        repeat(PI_DEFAULT_MAX_PROVIDER_RETRIES) {
            val run = checkNotNull(database.agentDao().getRunsForSession(sessionId).single())
            events = loop.run(baseRequest.copy(existingRun = run)).toList()
        }

        val run = checkNotNull(database.agentDao().getRunsForSession(sessionId).single())
        val requests = database.agentDao().getRequests(run.id)
            .filter { it.purpose == AgentRequestPurpose.AGENT_TURN.name }
        assertEquals(listOf(1, 2, 3, 4), requests.map { it.attempt })
        assertEquals(AgentRunStatus.FAILED.name, run.status)
        assertTrue(events.filterIsInstance<AppStreamEvent.Error>().any { it.code == "provider_retry_exhausted" })
    }

    @Test
    fun `Pending只在Agent准备结束时作为同一Run的followUp消费`() = runBlocking {
        val sessionId = "pi-follow-up"
        seedSession(sessionId)
        val firstRequestStarted = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<Unit>()
        val requestMessages = mutableListOf<List<com.android.everytalk.data.DataClass.AbstractApiMessage>>()
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport { turn ->
                flow {
                    requestMessages += turn.request.messages
                    if (requestMessages.size == 1) {
                        firstRequestStarted.complete(Unit)
                        releaseFirstRequest.await()
                    }
                    emit(AppStreamEvent.Content("第${requestMessages.size}轮"))
                    emit(AppStreamEvent.Finish("stop"))
                }
            },
        )
        val running = async { loop.run(loopRequest(sessionId, compactThresholdTokens = 8_000)).toList() }

        firstRequestStarted.await()
        database.chatDao().enqueuePendingMessage(
            PendingMessageEntity(
                id = "follow-up-1",
                conversationId = sessionId,
                content = "继续检查配置",
                composerText = "继续检查配置",
                createdAt = 2L,
                updatedAt = 2L,
                status = "PENDING",
                queuePosition = 0L,
            )
        )
        releaseFirstRequest.complete(Unit)
        val events = running.await()

        assertEquals(2, requestMessages.size)
        assertTrue(
            requestMessages[1].filterIsInstance<SimpleTextApiMessage>()
                .any { it.role == "user" && it.content == "继续检查配置" }
        )
        assertTrue(events.any { it is AppStreamEvent.AgentFollowUpAccepted && it.messageId == "follow-up-1" })
        assertTrue(database.chatDao().observePendingMessages(sessionId).first().isEmpty())
        assertEquals("继续检查配置", database.chatDao().getMessagesForSession(sessionId).single().text)
        assertEquals(1, database.agentDao().getRunsForSession(sessionId).size)
    }

    @Test
    fun `队列附件在消费时生成多模态消息`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val message = materializeAndroidQueuedMessage(
            context = context,
            instruction = AgentSteeringInstruction(
                id = "queued-image",
                content = "检查图片",
                attachments = listOf(
                    SelectedMediaItem.ImageFromBitmap(
                        bitmapData = "AQ==",
                        id = "image-1",
                        mimeType = "image/png",
                    )
                ),
                createdAt = 1L,
            ),
            request = loopRequest("queued-image", compactThresholdTokens = 8_000).request,
        ) as PartsApiMessage

        assertTrue(message.parts.any { it is ApiContentPart.Text && it.text == "检查图片" })
        assertTrue(message.parts.any {
            it is ApiContentPart.InlineData && it.mimeType == "image/png" && it.base64Data == "AQ=="
        })
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

        val events = loop.run(loopRequest("compression-budget", compactThresholdTokens = 500)).toList()

        assertTrue(checkNotNull(compressionBudgets.single()) <= 256)
        assertTrue(events.any { it is AppStreamEvent.Content && it.text == "已继续处理" })
    }

    @Test
    fun `压缩服务拒绝请求时安全裁剪并继续主请求`() = runBlocking {
        seedSession("compression-fallback")
        var requestCount = 0
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                requestCount++
                if (requestCount == 1) {
                    flowOf(
                        AppStreamEvent.Error(
                            message = "摘要请求被拒绝",
                            code = "bad_request",
                            type = "provider_error",
                        ),
                        AppStreamEvent.Finish("error"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("主请求继续完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )

        val events = loop.run(loopRequest("compression-fallback", compactThresholdTokens = 500)).toList()

        assertEquals(2, requestCount)
        assertTrue(events.any { it is AppStreamEvent.Content && it.text == "主请求继续完成" })
    }

    @Test
    fun `无收益摘要标记失败并继续主请求`() = runBlocking {
        val sessionId = "compression-no-gain"
        seedSession(sessionId)
        var requestCount = 0
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                requestCount++
                if (requestCount == 1) {
                    flowOf(AppStreamEvent.Content("无效摘要".repeat(2_000)), AppStreamEvent.Finish("stop"))
                } else {
                    flowOf(AppStreamEvent.Content("主请求继续完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )

        val events = loop.run(loopRequest(sessionId, compactThresholdTokens = 500)).toList()
        val run = checkNotNull(database.agentDao().getRunsForSession(sessionId).single())
        val compactionRequest = database.agentDao().getRequests(run.id)
            .single { it.purpose == AgentRequestPurpose.COMPACTION.name }

        assertEquals(2, requestCount)
        assertEquals(AgentRequestStatus.FAILED.name, compactionRequest.status)
        assertEquals("compaction_no_gain", compactionRequest.finishReason)
        assertEquals(null, store.latestCompaction(sessionId))
        assertTrue(events.any { it is AppStreamEvent.Content && it.text == "主请求继续完成" })
    }

    @Test
    fun `执行检查点随AgentRun持久化并可恢复`() = runBlocking {
        val sessionId = "execution-checkpoint"
        seedSession(sessionId)
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                flowOf(AppStreamEvent.Content("已完成"), AppStreamEvent.Finish("stop"))
            },
        )

        loop.run(loopRequest(sessionId)).toList()

        val run = checkNotNull(database.agentDao().getRunsForSession(sessionId).single())
        val checkpoint = checkNotNull(store.executionCheckpoint(run.id))
        assertEquals("继续处理", checkpoint.currentGoal)
        assertTrue(checkpoint.currentStep?.contains("模型请求") == true)
    }

    @Test
    fun `Provider上下文超限只执行一次紧急压缩恢复`() = runBlocking {
        val sessionId = "overflow-recovery"
        seedSession(sessionId)
        var requestCount = 0
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                requestCount++
                when (requestCount) {
                    1 -> flowOf(
                        AppStreamEvent.Error(
                            message = "context length exceeded",
                            code = "context_length_exceeded",
                        ),
                        AppStreamEvent.Finish("error"),
                    )
                    2 -> flowOf(AppStreamEvent.Content("压缩摘要"), AppStreamEvent.Finish("stop"))
                    else -> flowOf(AppStreamEvent.Content("恢复成功"), AppStreamEvent.Finish("stop"))
                }
            },
        )

        val events = loop.run(loopRequest(sessionId)).toList()

        assertEquals(3, requestCount)
        assertTrue(events.any { it is AppStreamEvent.Content && it.text == "恢复成功" })
    }

    private suspend fun seedSession(sessionId: String) {
        database.chatDao().insertSession(ChatSessionEntity(sessionId, 1L, 1L, false))
    }

    private fun loopRequest(
        sessionId: String,
        compactThresholdTokens: Long = 800,
    ): AgentLoopRequest {
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
                    compactThresholdTokens = compactThresholdTokens,
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
