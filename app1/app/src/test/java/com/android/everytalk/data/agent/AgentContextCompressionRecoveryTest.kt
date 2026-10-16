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
import com.android.everytalk.data.database.entities.AgentContextSnapshotEntity
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.PendingMessageEntity
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.ModelTurnTransport
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.network.TokenUsageSource
import com.android.everytalk.statecontroller.RequestTokenEstimator
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `已经收到部分正文的网络失败禁止自动重试`() = runBlocking {
        val sessionId = "provider-partial-no-retry"
        seedSession(sessionId)
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                flowOf(
                    AppStreamEvent.Content("已经收到一部分正文"),
                    AppStreamEvent.Error(
                        message = "connection reset",
                        code = "connection_aborted",
                        type = "retryable_network",
                    ),
                )
            },
        )

        val events = loop.run(loopRequest(sessionId, compactThresholdTokens = 8_000)).toList()
        val run = database.agentDao().getRunsForSession(sessionId).single()

        assertEquals(AgentRunStatus.FAILED.name, run.status)
        assertTrue(store.getPendingModelContinuationRuns().none { it.id == run.id })
        assertTrue(events.filterIsInstance<AppStreamEvent.Error>().any { it.code == "connection_aborted" })
        assertTrue(events.filterIsInstance<AppStreamEvent.Error>().none { it.code == "provider_retry_exhausted" })
    }

    @Test
    fun `上游404后发送继续只继承已生成正文不继承界面错误`() = runBlocking {
        val sessionId = "provider-404-follow-up"
        seedSession(sessionId)
        val first = loopRequest(sessionId, compactThresholdTokens = 8_000)
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                flowOf(
                    AppStreamEvent.Content("已经生成的半句"),
                    AppStreamEvent.Error("Gemini HTTP 404", code = "http_404", type = "provider_error"),
                )
            },
        )

        loop.run(first).toList()
        assertEquals(AgentRunStatus.FAILED.name, database.agentDao().getRunsForSession(sessionId).single().status)

        val expanded = store.expandTranscript(
            sessionId,
            first.request.messages + listOf(
                SimpleTextApiMessage(
                    id = first.visibleAssistantMessageId,
                    role = "assistant",
                    content = "已经生成的半句\n\n⚠️ Gemini HTTP 404",
                ),
                SimpleTextApiMessage(id = "continue-user", role = "user", content = "继续"),
            ),
        )

        assertEquals("已经生成的半句", (expanded[expanded.lastIndex - 1] as SimpleTextApiMessage).content)
        assertEquals("继续", (expanded.last() as SimpleTextApiMessage).content)
        assertTrue(expanded.none { it.toString().contains("HTTP 404") })
    }

    @Test
    fun `上游404发生在首个输出前时继续不会继承错误气泡`() = runBlocking {
        val sessionId = "provider-404-before-output"
        seedSession(sessionId)
        val first = loopRequest(sessionId, compactThresholdTokens = 8_000)
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                flowOf(AppStreamEvent.Error("Gemini HTTP 404", code = "http_404", type = "provider_error"))
            },
        )

        loop.run(first).toList()
        val expanded = store.expandTranscript(
            sessionId,
            first.request.messages + listOf(
                SimpleTextApiMessage(first.visibleAssistantMessageId, "assistant", "⚠️ Gemini HTTP 404"),
                SimpleTextApiMessage("continue-user", "user", "继续"),
            ),
        )

        assertEquals(first.request.messages.size + 1, expanded.size)
        assertEquals("继续", (expanded.last() as SimpleTextApiMessage).content)
        assertTrue(expanded.none { it.toString().contains("HTTP 404") })
    }

    @Test
    fun `正文被上游截断时提示用户并停止`() = runBlocking {
        val sessionId = "truncated-text-output-limit"
        seedSession(sessionId)
        var requests = 0
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                requests++
                flowOf(AppStreamEvent.Content("前半段"), AppStreamEvent.Finish("length"))
            },
        )

        val events = loop.run(loopRequest(sessionId, compactThresholdTokens = 8_000)).toList()

        assertEquals(1, requests)
        assertEquals(AgentRunStatus.FAILED.name, database.agentDao().getRunsForSession(sessionId).single().status)
        assertEquals(1, events.count { it is AppStreamEvent.Finish })
        assertTrue(events.any {
            it is AppStreamEvent.Error &&
                it.code == "provider_output_limit" &&
                it.message.contains("不代表模型参数设置错误")
        })
    }

    @Test
    fun `每次 Provider attempt 都重新解析 API Key`() = runBlocking {
        val sessionId = "dynamic-api-key"
        seedSession(sessionId)
        var currentKey = "key-first"
        val observedKeys = mutableListOf<String>()
        val loop = AgentLoop(
            runStore = store,
            apiKeyProvider = { _, _ -> currentKey },
            modelTransport = ModelTurnTransport { turn ->
                observedKeys += turn.request.apiKey
                flowOf(
                    AppStreamEvent.Error(
                        message = "connection reset",
                        code = "connection_aborted",
                        type = "retryable_network",
                    ),
                )
            },
        )
        val baseRequest = loopRequest(sessionId)
        loop.run(baseRequest).toList()
        currentKey = "key-second"
        val run = checkNotNull(database.agentDao().getRunsForSession(sessionId).single())

        loop.run(baseRequest.copy(existingRun = run)).toList()

        assertEquals(listOf("key-first", "key-second"), observedKeys)
    }

    @Test
    fun `Provider恢复仍属于同一Agent生命周期`() = runBlocking {
        val sessionId = "single-lifecycle-across-retry"
        seedSession(sessionId)
        var attempt = 0
        val lifecycle = mutableListOf<AgentLifecycleEvent>()
        val loop = AgentLoop(
            runStore = store,
            lifecycleSink = { lifecycle += it },
            modelTransport = ModelTurnTransport {
                attempt++
                if (attempt == 1) {
                    flowOf(
                        AppStreamEvent.Error(
                            message = "connection reset",
                            code = "connection_aborted",
                            type = "retryable_network",
                        ),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("恢复完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )
        val baseRequest = loopRequest(sessionId, compactThresholdTokens = 8_000)

        loop.run(baseRequest).toList()
        val run = database.agentDao().getRunsForSession(sessionId).single()
        loop.run(baseRequest.copy(existingRun = run)).toList()

        assertEquals(1, lifecycle.count { it.phase == AgentLifecyclePhase.AGENT_START })
        assertEquals(1, lifecycle.count { it.phase == AgentLifecyclePhase.AGENT_END })
        assertEquals(1, lifecycle.count { it.phase == AgentLifecyclePhase.TURN_START })
        assertEquals(1, lifecycle.count { it.phase == AgentLifecyclePhase.TURN_END })
        assertEquals(AgentLifecyclePhase.AGENT_END, lifecycle.last().phase)
    }

    @Test
    fun `无工具模型轮发送完整Pi生命周期且不携带正文`() = runBlocking {
        val sessionId = "lifecycle-no-tools"
        seedSession(sessionId)
        val lifecycle = mutableListOf<AgentLifecycleEvent>()
        val secretText = "secret-message-body"
        val loop = AgentLoop(
            runStore = store,
            lifecycleSink = { lifecycle += it },
            modelTransport = ModelTurnTransport {
                flowOf(AppStreamEvent.Content(secretText), AppStreamEvent.Finish("stop"))
            },
        )

        loop.run(loopRequest(sessionId, compactThresholdTokens = 8_000)).toList()

        assertEquals(
            listOf(
                AgentLifecyclePhase.AGENT_START,
                AgentLifecyclePhase.TURN_START,
                AgentLifecyclePhase.MESSAGE_START,
                AgentLifecyclePhase.MESSAGE_END,
                AgentLifecyclePhase.TURN_END,
                AgentLifecyclePhase.AGENT_END,
            ),
            lifecycle.map(AgentLifecycleEvent::phase),
        )
        assertTrue(lifecycle.none { secretText in it.toString() })
    }

    @Test
    fun `正常结束但只有空思考时明确失败`() = runBlocking {
        val sessionId = "empty-final-response"
        seedSession(sessionId)
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                flowOf(
                    AppStreamEvent.Reasoning("", thoughtSignature = "c2ln"),
                    AppStreamEvent.Finish("stop"),
                )
            },
        )

        val events = loop.run(loopRequest(sessionId, compactThresholdTokens = 8_000)).toList()
        val run = database.agentDao().getRunsForSession(sessionId).single()

        assertEquals(AgentRunStatus.FAILED.name, run.status)
        assertTrue(events.filterIsInstance<AppStreamEvent.Error>().any { it.code == "empty_model_response" })
        assertTrue(events.none { it is AppStreamEvent.Finish && it.reason == "stop" })
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

    @Test
    fun `手动压缩无视阈值直接生成检查点并把隐藏 Run 置为终态`() = runBlocking {
        val sessionId = "manual-compaction"
        seedSession(sessionId)
        var summaryCalls = 0
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                summaryCalls++
                flowOf(
                    AppStreamEvent.Content("## 用户目标\n手动压缩摘要"),
                    AppStreamEvent.StreamEnd("summary"),
                )
            },
        )
        // 阈值和窗口都设成 100 万，正常路径绝不会触发压缩；能压成就是 forceLocalCompaction 生效。
        // 内容要够长，否则会走「没有可压缩内容」的短路。
        val request = manualCompactionRequest(
            listOf(
                SimpleTextApiMessage(id = "old-user", role = "user", content = "早期需求".repeat(7_000)),
                SimpleTextApiMessage(id = "old-assistant", role = "assistant", content = "早期结论".repeat(7_000)),
                SimpleTextApiMessage(id = "latest-user", role = "user", content = "继续处理"),
            ),
        )

        val outcome = loop.compactNow(sessionId, request, ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 1_000_000))

        assertTrue(outcome is ManualCompactionOutcome.Compacted)
        assertEquals(1, summaryCalls)
        assertNotNull(store.latestCompaction(sessionId))
        val hiddenRun = database.agentDao().getRunsForSession(sessionId).single()
        assertEquals(AgentRunStatus.COMPLETED.name, hiddenRun.status)
        assertEquals(AgentTerminalReasons.MANUAL_COMPACTION, hiddenRun.terminalReason)
    }

    @Test
    fun `没有可摘要的历史时手动压缩不调模型并返回 NothingToCompress`() = runBlocking {
        val sessionId = "manual-compaction-nothing"
        seedSession(sessionId)
        var summaryCalls = 0
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                summaryCalls++
                flowOf(AppStreamEvent.Content("不该被调用"), AppStreamEvent.StreamEnd("summary"))
            },
        )
        val request = manualCompactionRequest(
            listOf(SimpleTextApiMessage(id = "only-user", role = "user", content = "你好")),
        )

        val outcome = loop.compactNow(sessionId, request, ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 1_000_000))

        assertEquals(ManualCompactionOutcome.NothingToCompress, outcome)
        assertEquals(0, summaryCalls)
        assertEquals(null, store.latestCompaction(sessionId))
        assertEquals(
            AgentRunStatus.COMPLETED.name,
            database.agentDao().getRunsForSession(sessionId).single().status,
        )
    }

    @Test
    fun `窗口远大于会话时手动压缩仍给出可用的摘要预算`() = runBlocking {
        val sessionId = "manual-compaction-budget"
        seedSession(sessionId)
        var summaryOutputTokens = -1
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport { turn ->
                summaryOutputTokens = turn.request.generationConfig?.maxOutputTokens ?: -1
                flowOf(
                    AppStreamEvent.Content("## 用户目标\n压缩摘要"),
                    AppStreamEvent.StreamEnd("summary"),
                )
            },
        )
        // 约 7 万 token 的中文历史，对应「窗口 100 万、当前占用 7 万」的真实场景。
        // 第一条故意很短：窗口远大于占用时，计划只会摘要最老的那一组。
        val request = manualCompactionRequest(
            buildList {
                add(SimpleTextApiMessage(id = "u0", role = "user", content = "帮我看看这个"))
                repeat(8) { index ->
                    val ordinal = index + 1
                    add(SimpleTextApiMessage(id = "u$ordinal", role = "user", content = "用户第 $ordinal 轮需求".repeat(700)))
                    add(SimpleTextApiMessage(id = "a$ordinal", role = "assistant", content = "助手第 $ordinal 轮结论".repeat(700)))
                }
                add(SimpleTextApiMessage(id = "latest", role = "user", content = "继续处理"))
            },
        )

        val outcome = loop.compactNow(
            sessionId = sessionId,
            baseRequest = request,
            limits = ModelTokenLimits(maxOutputTokens = 4_096, maxContextTokens = 1_000_000),
        )

        assertTrue("压缩没有执行：$outcome", outcome is ManualCompactionOutcome.Compacted)
        assertTrue("摘要输出预算过小：$summaryOutputTokens", summaryOutputTokens >= 1_024)
    }

    @Test
    fun `二十万窗口压缩落库后切换百万窗口及切回均继承活动摘要`() = runBlocking {
        val sessionId = "switch-model-compaction"
        seedSession(sessionId)
        val history = listOf(
            SimpleTextApiMessage(id = "old-user", role = "user", content = "旧".repeat(50_000)),
            SimpleTextApiMessage(id = "old-assistant", role = "assistant", content = "史".repeat(45_000)),
            SimpleTextApiMessage(id = "recent-user", role = "user", content = "近".repeat(95_000)),
        )
        val base = loopRequest(sessionId).copy(
            request = loopRequest(sessionId).request.copy(
                messages = history,
                model = "model-a",
                contextManagement = RequestContextManagement(
                    configId = "config-a",
                    maxContextTokens = 200_000,
                    reservedOutputTokens = 512,
                    compactThresholdTokens = 180_000,
                    autoCompressionEnabled = true,
                ),
            ),
            tokenLimits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 200_000),
        )
        var calls = 0
        val sent = mutableListOf<ChatRequest>()
        val transport = ModelTurnTransport { turn ->
            calls++
            sent += turn.request
            flowOf(
                AppStreamEvent.Content(if (calls == 1) "旧历史的有效摘要" else "完成"),
                AppStreamEvent.Finish("stop"),
            )
        }
        AgentLoop(runStore = store, modelTransport = transport).run(base).toList()
        val checkpoint = checkNotNull(store.latestCompaction(sessionId))
        assertTrue(checkpoint.estimatedTokensAfter in 95_000..110_000)

        // 重建 Store 和 Loop，确保读的是 Room 检查点，而不是上轮内存里的压缩结果。
        // 依次验证完整历史换模型、前段缺失换模型，以及关闭自动压缩后切回小窗口。
        val variants = listOf(
            Triple("model-b", 1_000_000, history),
            Triple("model-c", 1_000_000, history.drop(1)),
            Triple("model-a", 200_000, history),
        )
        variants.forEachIndexed { index, (model, window, messages) ->
            val next = base.copy(
                visibleAssistantMessageId = "switched-assistant-$index",
                userMessageId = "switched-user-$index",
                request = base.request.copy(
                    model = model,
                    messages = messages + SimpleTextApiMessage(
                        id = "switched-user-$index", role = "user", content = "切换后继续",
                    ),
                    contextManagement = checkNotNull(base.request.contextManagement).copy(
                        configId = "config-$model",
                        maxContextTokens = window,
                        compactThresholdTokens = window * 9L / 10,
                        autoCompressionEnabled = index != 2,
                    ),
                ),
                tokenLimits = base.tokenLimits.copy(maxContextTokens = window),
            )
            val restoredStore = AgentRunStore(database.agentDao())
            val events = AgentLoop(runStore = restoredStore, modelTransport = transport).run(next).toList()

            assertTrue(events.any { it is AppStreamEvent.Content && it.text == "完成" })
            val actual = sent.last()
            assertEquals(model, actual.model)
            assertTrue(actual.messages.any { it.id == "agent-compaction:${checkpoint.id}" })
            assertTrue(actual.messages.none { it.id == "old-user" || it.id == "old-assistant" })
            assertTrue(actual.messages.any { it.id == "recent-user" })
            assertEquals("switched-user-$index", actual.messages.last().id)
            assertEquals(1, restoredStore.compactions(sessionId).size)
        }
        assertEquals(5, calls) // 一次摘要、模型 A 的主请求，以及三次切换后的主请求。

        // 再从缺少前段的历史压缩一次，然后恢复完整历史。
        // 新摘要必须继承旧摘要覆盖的 ID，否则又会退回第一次摘要并回灌 recent-user。
        val retained = SimpleTextApiMessage(id = "after-second-compaction", role = "user", content = "继续完成任务")
        val outcome = AgentLoop(runStore = store, modelTransport = transport).compactNow(
            sessionId,
            base.request.copy(messages = history.drop(1) + retained),
            base.tokenLimits,
        )
        assertTrue(outcome is ManualCompactionOutcome.Compacted)
        val latest = checkNotNull(store.latestCompaction(sessionId))
        assertTrue(latest.id != checkpoint.id)
        val restored = base.copy(
            visibleAssistantMessageId = "assistant-after-second-compaction",
            request = base.request.copy(
                model = "model-d",
                messages = history + retained,
                contextManagement = checkNotNull(base.request.contextManagement).copy(
                    configId = "config-d", maxContextTokens = 1_000_000, compactThresholdTokens = 900_000,
                ),
            ),
            tokenLimits = base.tokenLimits.copy(maxContextTokens = 1_000_000),
        )
        AgentLoop(runStore = AgentRunStore(database.agentDao()), modelTransport = transport)
            .run(restored).toList()
        assertTrue(sent.last().messages.any { it.id == "agent-compaction:${latest.id}" })
        assertTrue(sent.last().messages.none { it.id in history.map(SimpleTextApiMessage::id) })
        assertEquals(retained.id, sent.last().messages.last().id)
        assertEquals(7, calls)
    }

    @Test
    fun `压缩后中途停止仍发布真实请求快照且继续时不回灌全文`() = runBlocking {
        val sessionId = "stop-after-compaction"
        seedSession(sessionId)
        val base = loopRequest(sessionId, compactThresholdTokens = 500)
        val partialReceived = CompletableDeferred<Unit>()
        val events = mutableListOf<AppStreamEvent>()
        val sent = mutableListOf<ChatRequest>()
        var calls = 0
        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport { turn ->
                sent += turn.request
                calls++
                if (calls == 1) {
                    flowOf(AppStreamEvent.Content("保留旧需求与结论的摘要"), AppStreamEvent.Finish("stop"))
                } else flow {
                    emit(AppStreamEvent.Content("尚未完成的回答"))
                    awaitCancellation()
                }
            },
        )
        val running = launch {
            loop.run(base).collect { event ->
                events += event
                if (event is AppStreamEvent.Content && event.text == "尚未完成的回答") {
                    partialReceived.complete(Unit)
                }
            }
        }
        try {
            withTimeout(10_000) { partialReceived.await() }
        } finally {
            running.cancelAndJoin()
        }

        val run = store.getRunsForSession(sessionId).single()
        val actualSnapshot = checkNotNull(store.usageSummary(run.id).activeContext)
        val published = events.filterIsInstance<AppStreamEvent.AgentUsage>().lastOrNull()
        assertNotNull("打断前必须发布压缩后的快照，不能等 Finish 才更新界面", published)
        val usage = checkNotNull(published)
        assertEquals(actualSnapshot.activeContextTokens, usage.activeContext?.estimatedInputTokens)
        assertEquals(actualSnapshot.activeContextTokens, usage.activeRequest.inputTokens)
        assertEquals(com.android.everytalk.data.network.TokenUsageSource.ESTIMATED, usage.activeRequest.source)
        assertEquals(false, usage.activeRequest.isFinal)
        assertEquals(AgentRunStatus.CANCELLED.name, run.status)
        assertTrue(sent.last().messages.any { it.id.startsWith("agent-compaction:") })
        assertTrue(sent.last().messages.none { it.id == "old-user" || it.id == "old-assistant" })

        // 停止不会撤销已成功落库的摘要；再次请求仍应继承同一份压缩上下文。
        val continuedRequests = mutableListOf<ChatRequest>()
        AgentLoop(
            runStore = AgentRunStore(database.agentDao()),
            modelTransport = ModelTurnTransport { turn ->
                continuedRequests += turn.request
                flowOf(AppStreamEvent.Content("继续完成"), AppStreamEvent.Finish("stop"))
            },
        ).run(base.copy(visibleAssistantMessageId = "continued-assistant")).toList()
        assertEquals(1, continuedRequests.size)
        assertTrue(continuedRequests.single().messages.none { it.id == "old-user" || it.id == "old-assistant" })
        assertEquals(1, store.compactions(sessionId).size)
    }

    @Test
    fun `中断旧消息的虚假校准不能裁掉历史或跨模型污染请求`() = runBlocking {
        val sessionId = "cancelled-usage-calibration"
        seedSession(sessionId)
        val base = loopRequest(sessionId).let { input ->
            input.copy(
                tokenLimits = ModelTokenLimits(maxOutputTokens = 32_768, maxContextTokens = 1_048_576),
                request = input.request.copy(
                    messages = listOf(
                        SimpleTextApiMessage(id = "retained-user", role = "user", content = "旧".repeat(300_000)),
                        SimpleTextApiMessage(id = "retained-assistant", role = "assistant", content = "文".repeat(300_000)),
                        SimpleTextApiMessage(id = "current-user", role = "user", content = "看看有无最新代码需要拉"),
                    ),
                    contextManagement = checkNotNull(input.request.contextManagement).copy(
                        maxContextTokens = 1_048_576,
                        reservedOutputTokens = 32_768,
                        autoCompressionEnabled = false,
                        // 真机旧版把初始 UI 估算与展开历史后的实测相减，得到这个虚假正偏差。
                        inputTokenCalibration = 624_012,
                    ),
                ),
            )
        }
        val cancelledRun = store.createRun(
            sessionId, "previous-user", "cancelled-assistant", "config-1", base.request,
        )
        // 复现手机账本：请求已完成，随后在工具阶段停止；真实偏差为 861986 - 964168。
        val snapshot = AgentContextManager().prepare("measured-request", base.request, base.tokenLimits).snapshot.copy(
            systemPromptTokens = 30_427, conversationTextTokens = 826_400, mediaTokens = 28_672,
            toolSchemaTokens = 41_905, protocolOverheadTokens = 36_764,
            estimatedPromptTokens = 964_168, activeContextTokens = 1_012_262, calibrationTokens = 48_094,
        )
        val fact = store.createRequest(
            run = cancelledRun, ordinal = 1, purpose = AgentRequestPurpose.AGENT_TURN, modelTurnOrdinal = 1,
            provider = base.request.provider, endpoint = base.request.apiAddress, model = base.request.model,
            transcriptFingerprint = snapshot.transcriptFingerprint, snapshot = snapshot,
        )
        store.saveUsage(fact.id, TokenUsage(
            inputTokens = 861_986, outputTokens = 2_918, isFinal = true,
            source = TokenUsageSource.OPENAI_CHAT,
        ))
        store.updateRequest(fact, AgentRequestStatus.COMPLETED)
        store.updateRunStatus(cancelledRun, AgentRunStatus.CANCELLED)

        val sent = mutableListOf<ChatRequest>()
        suspend fun send(input: AgentLoopRequest): AgentContextSnapshotEntity {
            AgentLoop(
                runStore = AgentRunStore(database.agentDao()),
                modelTransport = ModelTurnTransport { turn ->
                    sent += turn.request
                    val estimate = RequestTokenEstimator.estimate(turn.request.messages, turn.request.tools).totalInputTokens
                    flowOf(
                        AppStreamEvent.Content("已经是最新"),
                        AppStreamEvent.Usage(TokenUsage(
                            inputTokens = estimate - 102_182, outputTokens = 242, isFinal = true,
                            source = TokenUsageSource.OPENAI_CHAT,
                        )),
                        AppStreamEvent.Finish("stop"),
                    )
                },
            ).run(input).toList()
            val run = checkNotNull(store.getRunByVisibleMessage(input.visibleAssistantMessageId))
            return checkNotNull(store.usageSummary(run.id).activeContext)
        }

        val first = send(base)
        assertEquals(-102_182L, first.calibrationTokens)
        assertTrue(sent.last().messages.any { it.id == "retained-user" })
        val next = send(base.copy(visibleAssistantMessageId = "next-assistant"))
        assertEquals(first.activeContextTokens, next.activeContextTokens)
        assertEquals(sent[0].messages.map { it.id }, sent[1].messages.map { it.id })

        // 模型、端点或配置改变后没有匹配的账本，必须从零校准，不能复用旧气泡的偏差。
        listOf(
            base.request.copy(model = "another-model"),
            base.request.copy(apiAddress = "https://another.test"),
            base.request.copy(contextManagement = base.request.contextManagement?.copy(configId = "another-config")),
        ).forEachIndexed { index, request ->
            val changed = send(base.copy(request = request, visibleAssistantMessageId = "changed-$index"))
            assertEquals(0L, changed.calibrationTokens)
        }
    }

    private fun manualCompactionRequest(messages: List<SimpleTextApiMessage>): ChatRequest = ChatRequest(
        messages = messages,
        provider = "OpenAI",
        channel = "OpenAI兼容",
        apiAddress = "https://example.test",
        apiKey = "test-key",
        model = "test-model",
        contextManagement = RequestContextManagement(
            configId = "config-1",
            maxContextTokens = 1_000_000,
            reservedOutputTokens = 512,
            compactThresholdTokens = 1_000_000,
            autoCompressionEnabled = true,
        ),
    )

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
