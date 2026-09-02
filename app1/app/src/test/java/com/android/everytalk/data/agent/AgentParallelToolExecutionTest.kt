package com.android.everytalk.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantContentApiPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.computer.ComputerPermissionMode
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerToolApprovalRequest
import com.android.everytalk.data.computer.ComputerToolNames
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.AppToolExecutionResult
import com.android.everytalk.data.network.AppToolExecutor
import com.android.everytalk.data.network.ModelTurnTransport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
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
            AppToolExecutionResult(buildJsonObject {
                put("execution_id", "execution-$toolCallId")
                put("result", "result-$toolCallId")
            })
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
    fun `工具返回ok false时模型收到失败结果`() = runBlocking {
        val runtime = AgentToolRuntime(
            executorProvider = {
                { _, _, _, _, _ ->
                    AppToolExecutionResult(buildJsonObject { put("ok", false); put("error", "执行失败") })
                }
            },
            approvalProvider = { null },
        )

        val result = runtime.execute(
            call = AgentContentBlock.ToolCall("call-failed", "exec", buildJsonObject {}),
            computerContext = null,
            emit = {},
        )

        assertTrue(result.isError)
    }

    @Test
    fun `旧工具只返回error字段时模型也收到失败结果`() = runBlocking {
        val runtime = AgentToolRuntime(
            executorProvider = {
                { _, _, _, _, _ ->
                    AppToolExecutionResult(buildJsonObject { put("error", "参数无效") })
                }
            },
            approvalProvider = { null },
        )

        val result = runtime.execute(
            call = AgentContentBlock.ToolCall("call-error", "exec", buildJsonObject {}),
            computerContext = null,
            emit = {},
        )

        assertTrue(result.isError)
    }

    @Test
    fun `工具图片在公共执行边界转换成Pi内容块`() = runBlocking {
        val runtime = AgentToolRuntime(
            executorProvider = {
                { _, _, _, _, _ ->
                    AppToolExecutionResult(buildJsonObject {
                        put("ok", true)
                        put("message", "截图完成")
                        put("_images", buildJsonArray {
                            add(buildJsonObject {
                                put("base64", "AQID")
                                put("mimeType", "image/png")
                            })
                        })
                    })
                }
            },
            approvalProvider = { null },
        )

        val result = runtime.execute(
            call = AgentContentBlock.ToolCall("call-image", "camera", buildJsonObject {}),
            computerContext = null,
            emit = {},
        )

        assertEquals(2, result.contentBlocks.size)
        val image = result.contentBlocks.filterIsInstance<
            com.android.everytalk.data.DataClass.AgentToolResultContentApiPart.Image
            >().single()
        assertEquals("AQID", image.data)
        assertEquals("image/png", image.mimeType)
        assertFalse(result.content.toString().contains("_images"))
    }

    @Test
    fun `长度截断的工具调用不执行并让下一轮模型重发`() = runBlocking {
        seedSession("truncated-tool-session")
        val executionCount = AtomicInteger(0)
        val observedRequests = mutableListOf<ChatRequest>()
        var turn = 0
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = {
                    { _, _, _, _, _ ->
                        executionCount.incrementAndGet()
                        AppToolExecutionResult(JsonPrimitive("不应执行"))
                    }
                },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport { modelRequest ->
                observedRequests += modelRequest.request
                turn++
                if (turn == 1) {
                    flowOf(
                        AppStreamEvent.ToolCall("call-truncated", "exec", buildJsonObject {}),
                        AppStreamEvent.Finish("length"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("已重新生成"), AppStreamEvent.Finish("stop"))
                }
            },
        )

        loop.run(loopRequest("truncated-tool-session")).toList()

        assertEquals(0, executionCount.get())
        val result = observedRequests[1].messages.filterIsInstance<AgentToolResultApiMessage>().single()
        assertTrue(result.isError)
        assertTrue(result.content.toString().contains("output token limit"))
    }

    @Test
    fun `工具参数不符合schema时不执行并把错误作为ToolResult交回模型`() = runBlocking {
        seedSession("schema-validation-session")
        val executions = AtomicInteger(0)
        val observedRequests = mutableListOf<ChatRequest>()
        var turn = 0
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = {
                    { _, _, _, _, _ ->
                        executions.incrementAndGet()
                        AppToolExecutionResult(JsonPrimitive("unexpected"))
                    }
                },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport { request ->
                observedRequests += request.request
                turn++
                if (turn == 1) {
                    flowOf(
                        AppStreamEvent.ToolCall(
                            id = "bad-call",
                            name = "typed-tool",
                            argumentsObj = buildJsonObject { put("count", "not-a-number") },
                        ),
                        AppStreamEvent.Finish("tool_use"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("已改正参数"), AppStreamEvent.Finish("stop"))
                }
            },
        )
        val tools = listOf(
            mapOf<String, Any>(
                "type" to "function",
                "function" to mapOf(
                    "name" to "typed-tool",
                    "description" to "typed",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("count" to mapOf("type" to "integer")),
                        "required" to listOf("count"),
                        "additionalProperties" to false,
                    ),
                ),
            ),
        )

        loop.run(loopRequest("schema-validation-session", tools = tools)).toList()

        assertEquals(0, executions.get())
        val result = observedRequests[1].messages.filterIsInstance<AgentToolResultApiMessage>().single()
        assertTrue(result.isError)
        assertTrue(result.content.toString().contains("Validation failed for tool"))
    }

    @Test
    fun `Gemini块级签名随每轮消息落库并可恢复`() = runBlocking {
        seedSession("gemini-signature-session")
        val observedRequests = mutableListOf<ChatRequest>()
        val executor: AppToolExecutor = { _, _, _, _, _ ->
            AppToolExecutionResult(buildJsonObject { put("ok", true) })
        }
        var turn = 0
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = { executor },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport { modelRequest ->
                observedRequests += modelRequest.request
                turn += 1
                if (turn == 1) {
                    flowOf(
                        AppStreamEvent.Reasoning("", thoughtSignature = "cmVhc29uaW5nLXNpZw=="),
                        AppStreamEvent.ToolCall(
                            id = "call-signed",
                            name = "exec",
                            argumentsObj = buildJsonObject {},
                            thoughtSignature = "dG9vbC1zaWc=",
                        ),
                        AppStreamEvent.Finish("tool_calls"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )
        val request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "执行")),
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
        )

        loop.run(
            AgentLoopRequest(
                request = request,
                sessionId = "gemini-signature-session",
                userMessageId = "user-gemini-signature",
                visibleAssistantMessageId = "assistant-gemini-signature",
                tokenLimits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
            )
        ).toList()

        val liveAssistant = observedRequests[1].messages.filterIsInstance<AgentAssistantApiMessage>().single()
        assertEquals("dG9vbC1zaWc=", liveAssistant.toolCalls.single().thoughtSignature)
        assertTrue(liveAssistant.contentParts.first() is AgentAssistantContentApiPart.Reasoning)
        val run = store.getRunsForSession("gemini-signature-session").single()
        val restoredAssistant = store.appendRunTranscript(run.id, emptyList())
            .filterIsInstance<AgentAssistantApiMessage>()
            .first()
        assertEquals("Google", restoredAssistant.sourceProvider)
        assertEquals("gemini-3.7-flash", restoredAssistant.sourceModel)
        assertEquals("dG9vbC1zaWc=", restoredAssistant.toolCalls.single().thoughtSignature)
        assertEquals(
            "cmVhc29uaW5nLXNpZw==",
            (restoredAssistant.contentParts.first() as AgentAssistantContentApiPart.Reasoning).thoughtSignature,
        )
    }

    @Test
    fun `Responses流末签名和namespace合并进原中立块`() = runBlocking {
        seedSession("responses-signature-session")
        val observedRequests = mutableListOf<ChatRequest>()
        var turn = 0
        val reasoningItem = """{"id":"rs_1","type":"reasoning","encrypted_content":"opaque"}"""
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = { { _, _, _, _, _ -> AppToolExecutionResult(JsonPrimitive("ok")) } },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport { modelRequest ->
                observedRequests += modelRequest.request
                turn++
                if (turn == 1) {
                    flowOf(
                        AppStreamEvent.Reasoning("分析"),
                        AppStreamEvent.Reasoning(
                            text = "",
                            thoughtSignature = reasoningItem,
                            signatureOnlyUpdate = true,
                        ),
                        AppStreamEvent.Content("执行步骤"),
                        AppStreamEvent.Content(
                            text = "",
                            thoughtSignature = """{"v":1,"id":"msg_1","phase":"commentary"}""",
                            signatureOnlyUpdate = true,
                        ),
                        AppStreamEvent.ToolCall(
                            id = "call-1|fc_1",
                            name = "exec",
                            argumentsObj = buildJsonObject {},
                            namespace = "runtime",
                        ),
                        AppStreamEvent.Finish("tool_use"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )

        loop.run(loopRequest("responses-signature-session")).toList()

        val assistant = observedRequests[1].messages.filterIsInstance<AgentAssistantApiMessage>().single()
        val reasoning = assistant.contentParts.filterIsInstance<AgentAssistantContentApiPart.Reasoning>().single()
        assertEquals("分析", reasoning.text)
        assertEquals(reasoningItem, reasoning.thoughtSignature)
        assertEquals(
            """{"v":1,"id":"msg_1","phase":"commentary"}""",
            assistant.contentParts.filterIsInstance<AgentAssistantContentApiPart.Text>().single().thoughtSignature,
        )
        assertEquals("runtime", assistant.toolCalls.single().namespace)
        val run = store.getRunsForSession("responses-signature-session").single()
        val restored = store.appendRunTranscript(run.id, emptyList())
            .filterIsInstance<AgentAssistantApiMessage>()
            .first()
        assertEquals(reasoningItem, restored.contentParts
            .filterIsInstance<AgentAssistantContentApiPart.Reasoning>().single().thoughtSignature)
        assertEquals("runtime", restored.toolCalls.single().namespace)
        assertEquals("tool_use", restored.stopReason)
    }

    @Test
    fun `Responses只有终止正文时不会被先到的message签名吞掉`() = runBlocking {
        seedSession("responses-final-text-session")
        val observedRequests = mutableListOf<ChatRequest>()
        var turn = 0
        val messageSignature = """{"v":1,"id":"msg-final","phase":"commentary"}"""
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = { { _, _, _, _, _ -> AppToolExecutionResult(JsonPrimitive("ok")) } },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport { modelRequest ->
                observedRequests += modelRequest.request
                turn++
                if (turn == 1) {
                    flowOf(
                        AppStreamEvent.Content(
                            text = "",
                            thoughtSignature = messageSignature,
                            signatureOnlyUpdate = true,
                        ),
                        AppStreamEvent.ContentFinal("终止事件补齐的正文"),
                        toolCall("call-1", "exec"),
                        AppStreamEvent.Finish("tool_use"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )

        loop.run(loopRequest("responses-final-text-session")).toList()

        val assistant = observedRequests[1].messages.filterIsInstance<AgentAssistantApiMessage>().single()
        val text = assistant.contentParts.filterIsInstance<AgentAssistantContentApiPart.Text>().single()
        assertEquals("终止事件补齐的正文", text.text)
        assertEquals(messageSignature, text.thoughtSignature)
    }

    @Test
    fun `上下文准备期间到达的steering进入当前下一轮请求`() = runBlocking {
        val sessionId = "steering-during-prepare-session"
        seedSession(sessionId)
        val preparationStarted = CompletableDeferred<Unit>()
        val releasePreparation = CompletableDeferred<Unit>()
        val providerCalls = AtomicInteger(0)
        val observedRequests = mutableListOf<ChatRequest>()
        val loop = AgentLoop(
            runStore = store,
            computerSessionStateProvider = {
                if (providerCalls.getAndIncrement() == 0) {
                    preparationStarted.complete(Unit)
                    releasePreparation.await()
                }
                null
            },
            toolRuntime = AgentToolRuntime(executorProvider = { null }, approvalProvider = { null }),
            modelTransport = ModelTurnTransport { turn ->
                observedRequests += turn.request
                flowOf(AppStreamEvent.Content("完成"), AppStreamEvent.Finish("stop"))
            },
        )
        val running = async { loop.run(loopRequest(sessionId)).toList() }

        preparationStarted.await()
        val run = store.getRunsForSession(sessionId).single()
        database.agentDao().enqueueSteeringIfRunActive(
            id = "steering-during-prepare",
            runId = run.id,
            content = "先检查配置再继续",
            createdAt = System.currentTimeMillis(),
        )
        releasePreparation.complete(Unit)
        running.await()

        assertEquals(1, observedRequests.size)
        assertTrue(observedRequests.single().messages.any { message ->
            message is SimpleTextApiMessage && message.content == "先检查配置再继续"
        })
    }

    @Test
    fun `审批请求只执行它前面的工具且不会越过边界`() = runBlocking {
        seedSession("approval-boundary-session")
        val executedIds = mutableListOf<String>()
        val executor: AppToolExecutor = { _, _, toolCallId, _, _ ->
            executedIds += toolCallId
            AppToolExecutionResult(JsonPrimitive("result-$toolCallId"))
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
    fun `并行批次先完成整批预检再开始任何工具`() = runBlocking {
        seedSession("parallel-preflight-session")
        val executedIds = mutableListOf<String>()
        val context = ComputerRequestContext(
            conversationId = "parallel-preflight-session",
            computerId = "computer-1",
            workspaceId = "workspace-1",
            permissionMode = ComputerPermissionMode.FULL,
        )
        var modelTurn = 0
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = {
                    { _, _, toolCallId, _, _ ->
                        executedIds += toolCallId
                        AppToolExecutionResult(JsonPrimitive("ok"))
                    }
                },
                approvalProvider = {
                    { toolName, _, toolCallId, requestContext, _ ->
                        if (toolName != "tool-two") null else ComputerToolApprovalRequest.UnknownExecution(
                            toolCallId = toolCallId,
                            context = requireNotNull(requestContext),
                            computerName = "测试资源",
                            toolName = toolName,
                            detail = "需要批准",
                            isWriteOperation = false,
                        )
                    }
                },
            ),
            modelTransport = ModelTurnTransport {
                modelTurn++
                if (modelTurn == 1) {
                    flowOf(
                        toolCall("call-1", "tool-one"),
                        toolCall("call-2", "tool-two"),
                        toolCall("call-3", "tool-three"),
                        AppStreamEvent.Finish("tool_calls"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )

        loop.run(loopRequest("parallel-preflight-session", context)).toList()

        assertTrue(executedIds.isEmpty())
        val waitingRun = store.getWaitingApprovalRuns().single()
        val approval = requireNotNull(store.pendingApproval(waitingRun.id))
        assertTrue(approval.resumeWholeBatchWithApprovedGate)
        assertEquals(listOf("call-1", "call-2", "call-3"), approval.pendingToolCalls.map { it.id })

        val decided = requireNotNull(
            store.decideApproval(waitingRun.id, approval.approvalRequestId, AgentApprovalDecision.APPROVED),
        )
        loop.run(
            loopRequest("parallel-preflight-session", context).copy(
                existingRun = waitingRun,
                approvalDecision = decided,
            ),
        ).toList()

        assertEquals(listOf("call-1", "call-2", "call-3"), executedIds)
    }

    @Test
    fun `并行预检中间失败也不能让前面的工具越过后续审批`() = runBlocking {
        seedSession("parallel-preflight-failure-session")
        val executedIds = mutableListOf<String>()
        val context = ComputerRequestContext(
            conversationId = "parallel-preflight-failure-session",
            computerId = "computer-1",
            workspaceId = "workspace-1",
            permissionMode = ComputerPermissionMode.FULL,
        )
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = {
                    { _, _, toolCallId, _, _ ->
                        executedIds += toolCallId
                        AppToolExecutionResult(JsonPrimitive("ok"))
                    }
                },
                approvalProvider = {
                    { toolName, _, toolCallId, requestContext, _ ->
                        when (toolName) {
                            "tool-two" -> error("参数无效")
                            "tool-three" -> ComputerToolApprovalRequest.UnknownExecution(
                                toolCallId = toolCallId,
                                context = requireNotNull(requestContext),
                                computerName = "测试资源",
                                toolName = toolName,
                                detail = "需要批准",
                                isWriteOperation = false,
                            )
                            else -> null
                        }
                    }
                },
            ),
            modelTransport = ModelTurnTransport {
                flowOf(
                    toolCall("call-1", "tool-one"),
                    toolCall("call-2", "tool-two"),
                    toolCall("call-3", "tool-three"),
                    AppStreamEvent.Finish("tool_calls"),
                )
            },
        )

        loop.run(loopRequest("parallel-preflight-failure-session", context)).toList()

        assertTrue(executedIds.isEmpty())
        assertTrue(store.getWaitingApprovalRuns().isNotEmpty())
    }

    @Test
    fun `并行批次连续两个审批恢复时已完成工具不会重复执行`() = runBlocking {
        val sessionId = "parallel-two-gates-session"
        seedSession(sessionId)
        val executionCounts = mutableMapOf<String, Int>()
        val approvedIds = mutableSetOf<String>()
        val context = ComputerRequestContext(
            conversationId = sessionId,
            computerId = "computer-1",
            workspaceId = "workspace-1",
            permissionMode = ComputerPermissionMode.FULL,
        )
        var modelTurn = 0
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = {
                    { _, _, toolCallId, _, _ ->
                        executionCounts[toolCallId] = executionCounts.getOrDefault(toolCallId, 0) + 1
                        AppToolExecutionResult(JsonPrimitive("ok-$toolCallId"))
                    }
                },
                approvalProvider = {
                    { toolName, _, toolCallId, requestContext, _ ->
                        if (toolName !in setOf("tool-two", "tool-three") || toolCallId in approvedIds) {
                            null
                        } else {
                            ComputerToolApprovalRequest.UnknownExecution(
                                toolCallId = toolCallId,
                                context = requireNotNull(requestContext),
                                computerName = "测试资源",
                                toolName = toolName,
                                detail = "需要批准",
                                isWriteOperation = false,
                            )
                        }
                    }
                },
            ),
            modelTransport = ModelTurnTransport {
                modelTurn++
                if (modelTurn == 1) {
                    flowOf(
                        toolCall("call-1", "tool-one"),
                        toolCall("call-2", "tool-two"),
                        toolCall("call-3", "tool-three"),
                        AppStreamEvent.Finish("tool_calls"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )

        val request = loopRequest(sessionId, context)
        loop.run(request).toList()
        var run = store.getWaitingApprovalRuns().single()
        var approval = requireNotNull(store.pendingApproval(run.id))
        approvedIds += approval.toolCall.id
        var decision = requireNotNull(
            store.decideApproval(run.id, approval.approvalRequestId, AgentApprovalDecision.APPROVED),
        )
        loop.run(request.copy(existingRun = run, approvalDecision = decision)).toList()

        run = store.getWaitingApprovalRuns().single()
        approval = requireNotNull(store.pendingApproval(run.id))
        approvedIds += approval.toolCall.id
        decision = requireNotNull(
            store.decideApproval(run.id, approval.approvalRequestId, AgentApprovalDecision.APPROVED),
        )
        loop.run(request.copy(existingRun = run, approvalDecision = decision)).toList()

        assertEquals(mapOf("call-1" to 1, "call-2" to 1, "call-3" to 1), executionCounts)
        assertEquals(AgentRunStatus.COMPLETED.name, store.getRun(run.id)?.status)
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
                AppToolExecutionResult(JsonPrimitive("result-$toolCallId"))
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

    @Test
    fun `任一工具声明sequential时整批串行`() = runBlocking {
        seedSession("declared-sequential-session")
        val activeCount = AtomicInteger(0)
        val peakActiveCount = AtomicInteger(0)
        var turn = 0
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = {
                    { _, _, _, _, _ ->
                        val active = activeCount.incrementAndGet()
                        peakActiveCount.updateAndGet { current -> maxOf(current, active) }
                        try {
                            delay(30L)
                            AppToolExecutionResult(JsonPrimitive("ok"))
                        } finally {
                            activeCount.decrementAndGet()
                        }
                    }
                },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport {
                turn++
                if (turn == 1) {
                    flowOf(
                        toolCall("call-1", "tool-one"),
                        toolCall("call-2", "tool-two"),
                        AppStreamEvent.Finish("tool_use"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )
        val tools = listOf(
            mapOf<String, Any>("name" to "tool-one", "executionMode" to "parallel"),
            mapOf<String, Any>("name" to "tool-two", "executionMode" to "sequential"),
        )

        loop.run(loopRequest("declared-sequential-session", tools = tools)).toList()

        assertEquals(1, peakActiveCount.get())
    }

    @Test
    fun `整批受信工具结果都要求终止时不再请求模型`() = runBlocking {
        seedSession("terminate-whole-batch-session")
        val modelTurns = AtomicInteger(0)
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = {
                    { _, _, _, _, _ ->
                        AppToolExecutionResult(
                            content = JsonPrimitive("完成"),
                            terminate = true,
                        )
                    }
                },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport {
                modelTurns.incrementAndGet()
                flowOf(
                    toolCall("terminate-1", "tool-one"),
                    toolCall("terminate-2", "tool-two"),
                    AppStreamEvent.Finish("tool_calls"),
                )
            },
        )

        loop.run(loopRequest("terminate-whole-batch-session")).toList()

        assertEquals(1, modelTurns.get())
    }

    @Test
    fun `混合terminate结果仍进入下一轮模型`() = runBlocking {
        seedSession("terminate-mixed-batch-session")
        val modelTurns = AtomicInteger(0)
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = {
                    { _, _, toolCallId, _, _ ->
                        AppToolExecutionResult(
                            content = JsonPrimitive("完成"),
                            terminate = toolCallId == "terminate-1",
                        )
                    }
                },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport {
                if (modelTurns.incrementAndGet() == 1) {
                    flowOf(
                        toolCall("terminate-1", "tool-one"),
                        toolCall("continue-2", "tool-two"),
                        AppStreamEvent.Finish("tool_calls"),
                    )
                } else {
                    flowOf(AppStreamEvent.Content("继续完成"), AppStreamEvent.Finish("stop"))
                }
            },
        )

        loop.run(loopRequest("terminate-mixed-batch-session")).toList()

        assertEquals(2, modelTurns.get())
    }

    @Test
    fun `工具正文里的terminate字段不能终止Agent`() = runBlocking {
        seedSession("untrusted-terminate-field-session")
        val modelTurns = AtomicInteger(0)
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(
                executorProvider = {
                    { _, _, _, _, _ ->
                        // 普通工具 JSON 属于发给模型的业务数据，不能携带 Agent 控制权限。
                        AppToolExecutionResult(buildJsonObject { put("terminate", true) })
                    }
                },
                approvalProvider = { null },
            ),
            modelTransport = ModelTurnTransport {
                if (modelTurns.incrementAndGet() == 1) {
                    flowOf(toolCall("business-result", "tool-one"), AppStreamEvent.Finish("tool_calls"))
                } else {
                    flowOf(AppStreamEvent.Content("正常继续"), AppStreamEvent.Finish("stop"))
                }
            },
        )

        loop.run(loopRequest("untrusted-terminate-field-session")).toList()

        assertEquals(2, modelTurns.get())
    }

    @Test
    fun `terminate结果落库后崩溃恢复不会多请求一轮模型`() = runBlocking {
        val sessionId = "terminate-crash-recovery-session"
        seedSession(sessionId)
        val input = loopRequest(sessionId)
        val run = store.createRun(
            sessionId = sessionId,
            userMessageId = input.userMessageId,
            visibleAssistantMessageId = input.visibleAssistantMessageId,
            configIdSnapshot = null,
            request = input.request,
        )
        val calls = listOf(
            AgentContentBlock.ToolCall("terminate-1", "tool-one", buildJsonObject {}),
            AgentContentBlock.ToolCall("terminate-2", "tool-two", buildJsonObject {}),
        )
        store.appendAssistant(run.id, "request-before-crash", AgentAssistantTurn(calls))
        calls.forEach { call ->
            store.appendToolResult(
                runId = run.id,
                requestId = "request-before-crash",
                result = AgentContentBlock.ToolResult(
                    toolCallId = call.id,
                    toolName = call.name,
                    content = JsonPrimitive("完成"),
                    terminate = true,
                ),
            )
        }
        val modelTurns = AtomicInteger(0)
        val loop = AgentLoop(
            runStore = store,
            toolRuntime = AgentToolRuntime(executorProvider = { null }, approvalProvider = { null }),
            modelTransport = ModelTurnTransport {
                modelTurns.incrementAndGet()
                flowOf(AppStreamEvent.Content("不应请求"), AppStreamEvent.Finish("stop"))
            },
        )

        loop.run(input.copy(existingRun = run)).toList()

        assertEquals(0, modelTurns.get())
        assertEquals(AgentRunStatus.COMPLETED.name, store.getRun(run.id)?.status)
    }

    @Test
    fun `edit参数兼容Pi的字符串单对象和旧顶层字段`() {
        val stringEdits = preparePiToolCallArguments(
            AgentContentBlock.ToolCall(
                "edit-string",
                ComputerToolNames.EDIT,
                buildJsonObject {
                    put("path", "a.txt")
                    put("edits", "{\"oldText\":\"a\",\"newText\":\"b\"}")
                },
            ),
        )
        val legacy = preparePiToolCallArguments(
            AgentContentBlock.ToolCall(
                "edit-legacy",
                ComputerToolNames.EDIT,
                buildJsonObject {
                    put("path", "a.txt")
                    put("oldText", "c")
                    put("newText", "d")
                },
            ),
        )

        assertEquals(1, (stringEdits.arguments.getValue("edits") as JsonArray).size)
        assertEquals(1, (legacy.arguments.getValue("edits") as JsonArray).size)
        assertFalse(legacy.arguments.containsKey("oldText"))
        assertFalse(legacy.arguments.containsKey("newText"))
    }

    private suspend fun seedSession(sessionId: String) {
        database.chatDao().insertSession(ChatSessionEntity(sessionId, 1L, 1L, false))
    }

    private fun loopRequest(
        sessionId: String,
        computerContext: ComputerRequestContext? = null,
        tools: List<Map<String, Any>>? = null,
    ) = AgentLoopRequest(
        request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "并行执行工具")),
            provider = "OpenAI",
            channel = "OpenAI兼容",
            apiAddress = "https://example.test",
            apiKey = "test-key",
            model = "test-model",
            tools = tools,
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
