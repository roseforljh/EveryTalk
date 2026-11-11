package com.android.everytalk.data.mcp

import io.mockk.coEvery
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class McpClientManagerRecoveryTest {
    @Test fun `传输包装后的授权失效仍归类为认证错误并显示重新登录`() = runBlocking {
        val manager = McpClientManager(connectClient = { _, _ ->
            throw IllegalStateException("Error connecting to transport", McpAuthorizationRequiredException(McpOAuthProvider.NOTION))
        })
        try {
            manager.addServer(testConfig)
            val state = manager.serverStates.value.getValue(testConfig.id)
            assertEquals("AUTH_ERROR", (state.status as McpStatus.Error).failureType)
            assertTrue(state.errorMessage.orEmpty().contains("重新登录"))
        } finally { manager.close() }
    }


    @Test
    fun `同服务器并发保留结构化结果且不信任正文错误字段`() = runBlocking {
        val both = kotlinx.coroutines.CompletableDeferred<Unit>()
        val count = AtomicInteger()
        coEvery { anyConstructed<Client>().listTools(any(), any()) } returns ListToolsResult(
            tools = listOf(Tool(name = "query", inputSchema = ToolSchema())))
        coEvery { anyConstructed<Client>().callTool(request = any<CallToolRequest>(), options = any()) } coAnswers {
            if (count.incrementAndGet() == 2) both.complete(Unit)
            kotlinx.coroutines.withTimeout(5_000) { both.await() }
            CallToolResult(content = listOf(TextContent(text = "summary")),
                structuredContent = buildJsonObject { put("ok", false); put("answer", 42); put("terminate", true) })
        }
        val manager = McpClientManager(connectClient = { _, _ -> })
        try {
            manager.addServer(testConfig)
            val alias = buildMcpToolAlias(testConfig.id, "query")
            val first = kotlinx.coroutines.coroutineScope {
                val a = async { manager.callToolResult(alias, buildJsonObject {}) }
                val b = async { manager.callToolResult(alias, buildJsonObject {}) }
                listOf(a.await(), b.await())
            }
            assertEquals(2, count.get())
            first.forEach {
                assertEquals(false, it.isError)
                assertFalse(it.terminate)
                assertEquals("42", (it.scriptContent as JsonObject)["answer"]?.jsonPrimitive?.content)
                assertTrue(it.content.toString().contains("structuredContent"))
            }
        } finally { manager.close() }
    }

    @Test
    fun `MCP协议失败保留结构化数据且旧文本返回形态不变`() = runBlocking {
        coEvery { anyConstructed<Client>().listTools(any(), any()) } returns ListToolsResult(
            tools = listOf(Tool(name = "query", inputSchema = ToolSchema())))
        val content = listOf(TextContent(text = "details"))
        coEvery { anyConstructed<Client>().callTool(request = any<CallToolRequest>(), options = any()) } returns
            CallToolResult(content = content, isError = true, structuredContent = buildJsonObject { put("reason", "rejected") })
        val manager = McpClientManager(connectClient = { _, _ -> })
        try {
            manager.addServer(testConfig)
            val alias = buildMcpToolAlias(testConfig.id, "query")
            val failed = manager.callToolResult(alias, buildJsonObject {})
            assertEquals(true, failed.isError)
            assertTrue(failed.content.toString().contains("details"))
            assertEquals("rejected", (failed.scriptContent as JsonObject)["reason"]?.jsonPrimitive?.content)
            coEvery { anyConstructed<Client>().callTool(request = any<CallToolRequest>(), options = any()) } returns CallToolResult(content = content)
            val plain = manager.callToolResult(alias, buildJsonObject {})
            assertTrue(plain.content is kotlinx.serialization.json.JsonArray)
            assertEquals(plain.content, manager.callTool(alias, buildJsonObject {}))
        } finally { manager.close() }
    }

    @Test
    fun `完整请求超时不重放且父取消传播`() = runBlocking {
        coEvery { anyConstructed<Client>().listTools(any(), any()) } returns ListToolsResult(
            tools = listOf(Tool(name = "wait", inputSchema = ToolSchema())))
        val attempts = AtomicInteger()
        coEvery { anyConstructed<Client>().callTool(request = any<CallToolRequest>(), options = any()) } coAnswers {
            attempts.incrementAndGet(); kotlinx.coroutines.awaitCancellation()
        }
        val manager = McpClientManager(toolTimeoutMillis = 50, connectClient = { _, _ -> })
        try {
            manager.addServer(testConfig)
            val alias = buildMcpToolAlias(testConfig.id, "wait")
            val result = manager.callToolResult(alias, buildJsonObject {})
            assertEquals(true, result.isError)
            assertEquals(1, attempts.get())
            assertEquals("TIMEOUT", (manager.serverStates.value.getValue(testConfig.id).status as McpStatus.Error).failureType)
            var cancelled = false
            try { kotlinx.coroutines.withTimeout(10) { manager.callToolResult(alias, buildJsonObject {}) } }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { cancelled = true }
            assertTrue(cancelled)
        } finally { manager.close() }
    }

    @Test
    fun `关闭会取消工具触发的自愈握手且不复活服务器`() = runBlocking {
        coEvery { anyConstructed<Client>().listTools(any(), any()) } returns ListToolsResult(
            tools = listOf(Tool(name = "query", inputSchema = ToolSchema())))
        coEvery { anyConstructed<Client>().callTool(request = any<CallToolRequest>(), options = any()) } throws IOException("broken")
        coEvery { anyConstructed<Client>().close() } returns Unit
        val reconnecting = kotlinx.coroutines.CompletableDeferred<Unit>()
        val connects = AtomicInteger()
        val manager = McpClientManager(connectClient = { _, _ ->
            if (connects.incrementAndGet() > 1) { reconnecting.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        })
        try {
            manager.addServer(testConfig)
            val alias = buildMcpToolAlias(testConfig.id, "query")
            manager.callToolResult(alias, buildJsonObject {})
            val recovering = async(kotlinx.coroutines.Dispatchers.Default) { manager.callToolResult(alias, buildJsonObject {}) }
            kotlinx.coroutines.withTimeout(5000) { reconnecting.await() }
            kotlinx.coroutines.withTimeout(1000) { manager.disconnectServer(testConfig.id) }
            assertTrue(recovering.isCancelled)
            assertFalse(manager.serverStates.value.getValue(testConfig.id).config.enabled)
        } finally { manager.close() }
    }

    @Test
    fun `关闭不等待在途工具且旧失败不污染新连接`() = runBlocking {
        coEvery { anyConstructed<Client>().listTools(any(), any()) } returns ListToolsResult(
            tools = listOf(Tool(name = "query", inputSchema = ToolSchema())))
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { anyConstructed<Client>().close() } returns Unit
        coEvery { anyConstructed<Client>().callTool(request = any<CallToolRequest>(), options = any()) } coAnswers {
            started.complete(Unit); release.await(); throw IOException("old connection failed")
        }
        val manager = McpClientManager(connectClient = { _, _ -> })
        try {
            manager.addServer(testConfig)
            val alias = buildMcpToolAlias(testConfig.id, "query")
            val old = async { manager.callToolResult(alias, buildJsonObject {}) }
            kotlinx.coroutines.withTimeout(5_000) { started.await() }
            kotlinx.coroutines.withTimeout(1_000) { manager.disconnectServer(testConfig.id) }
            assertFalse(manager.serverStates.value.getValue(testConfig.id).config.enabled)
            assertEquals(true, manager.callToolResult(alias, buildJsonObject {}).isError)
            manager.addServer(testConfig)
            release.complete(Unit)
            old.await()
            assertTrue(manager.serverStates.value.getValue(testConfig.id).status is McpStatus.Connected)
        } finally { release.complete(Unit); manager.close() }
    }

    private val testConfig = McpServerConfig.SseTransportServer(
        id = "recovery-server",
        commonOptions = McpCommonOptions(name = "RecoveryServer"),
        url = "https://example.com/sse",
    )

    @Before
    fun setUp() {
        mockkConstructor(Client::class)
    }

    @After
    fun tearDown() {
        unmockkConstructor(Client::class)
    }

    @Test
    fun `callTool失败时不自动重试避免副作用且下次调用可自愈重连`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connectAttempts = AtomicInteger(0)
        val toolCallAttempts = AtomicInteger(0)
        var shouldFailToolCall = true

        coEvery { anyConstructed<Client>().listTools(any(), any()) } returns ListToolsResult(
            tools = listOf(
                Tool(
                    name = "transfer_money",
                    description = "模拟有副作用的工具",
                    inputSchema = ToolSchema(),
                )
            )
        )

        coEvery { anyConstructed<Client>().callTool(request = any<CallToolRequest>(), options = any()) } answers {
            toolCallAttempts.incrementAndGet()
            if (shouldFailToolCall) {
                throw IOException("Connection reset by peer")
            }
            CallToolResult(
                content = listOf(TextContent(text = "transfer_success")),
                isError = false,
            )
        }

        val manager = McpClientManager(
            scope = scope,
            connectClient = { _, _ ->
                connectAttempts.incrementAndGet()
            },
        )

        try {
            manager.addServer(testConfig)
            assertEquals("初始连接应执行一次 connectClient", 1, connectAttempts.get())
            val stateBefore = manager.serverStates.value[testConfig.id]
            assertTrue(stateBefore?.status is McpStatus.Connected)
            assertEquals(1, stateBefore?.tools?.size)

            val toolAlias = buildMcpToolAlias(testConfig.id, "transfer_money")

            // 首次调用：遇到底层网络异常
            val firstResult = manager.callTool(toolAlias, buildJsonObject { put("amount", 100) })

            // 验证：绝不自动重试！底层 tool call 仅执行了 1 次
            assertEquals("遇到异常时严禁自动重放 tool call 造成副作用", 1, toolCallAttempts.get())
            val firstJson = firstResult as? JsonObject
            assertEquals(false, firstJson?.get("ok")?.jsonPrimitive?.content?.toBoolean())

            // 验证：坏连接被移除，状态被标记为 Error，但工具元数据完整保留
            val errorState = manager.serverStates.value[testConfig.id]
            assertTrue("连接异常后状态必须更新为 Error", errorState?.status is McpStatus.Error)
            assertEquals("保留工具元数据以支持后续路由与自愈", 1, errorState?.tools?.size)

            // 下次调用：服务端网络恢复
            shouldFailToolCall = false
            val secondResult = manager.callTool(toolAlias, buildJsonObject { put("amount", 100) })

            // 验证：下次调用在发送 tool call 之前触发重新连接，且成功完成调用
            assertEquals("下次调用必须触发自愈重连", 2, connectAttempts.get())
            assertEquals("自愈后成功发出第二次 tool call", 2, toolCallAttempts.get())
            val secondState = manager.serverStates.value[testConfig.id]
            assertTrue("重连后状态恢复为 Connected", secondState?.status is McpStatus.Connected)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `重连后若远端工具已删除则安全拒绝执行`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var provideTool = true

        coEvery { anyConstructed<Client>().listTools(any(), any()) } answers {
            ListToolsResult(
                tools = if (provideTool) {
                    listOf(
                        Tool(
                            name = "dynamic_tool",
                            description = "动态工具",
                            inputSchema = ToolSchema(),
                        )
                    )
                } else {
                    emptyList()
                }
            )
        }

        coEvery { anyConstructed<Client>().callTool(request = any<CallToolRequest>(), options = any()) } throws IOException("Initial connection broken")

        val manager = McpClientManager(
            scope = scope,
            connectClient = { _, _ -> },
        )

        try {
            manager.addServer(testConfig)
            val toolAlias = buildMcpToolAlias(testConfig.id, "dynamic_tool")

            // 首次调用失败，进入 Error 状态
            manager.callTool(toolAlias, buildJsonObject {})
            assertTrue(manager.serverStates.value[testConfig.id]?.status is McpStatus.Error)

            // 远端在重连时删除了该工具
            provideTool = false

            // 再次调用该工具：应重连并在重连后重新校验，发现已不存在后安全拒绝，绝不向 client 发起调用
            val result = manager.callTool(toolAlias, buildJsonObject {})
            val json = result as? JsonObject
            assertEquals(false, json?.get("ok")?.jsonPrimitive?.content?.toBoolean())
            assertTrue(
                "工具已被删除时应返回 no longer available",
                json?.get("error")?.jsonPrimitive?.content?.contains("no longer available") == true,
            )
        } finally {
            manager.close()
        }
    }
}
