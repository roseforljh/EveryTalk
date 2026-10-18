package com.android.everytalk.data.memory

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.*
import com.android.everytalk.data.agent.*
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.ModelTurnTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MemoryRuntimeTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: MemoryRepository
    private lateinit var scope: CoroutineScope

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = MemoryRepository(database)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    @After fun close() { scope.cancel(); database.close() }

    @Test fun `different channels receive bounded memory while raw history never gets the snapshot`() = runBlocking {
        repository.save("用户喜欢中文回答", MemoryCategory.USER_PREFERENCE)
        repository.setSettings(true)
        var extractionCalls = 0
        val coordinator = MemoryCoordinator(database, scope) { extractionCalls++; """{"memories":[]}""" }
        for (channel in listOf("OpenAI兼容", "Gemini", "Anthropic")) {
            var sent: ChatRequest? = null
            val input = input(channel, "普通问题")
            val loop = AgentLoop(AgentRunStore(database.agentDao()), memoryCoordinator = coordinator,
                modelTransport = ModelTurnTransport { turn ->
                    sent = turn.request
                    flowOf(AppStreamEvent.Content("已回答"), AppStreamEvent.Finish("stop"))
                })
            loop.run(input).toList()
            assertTrue(checkNotNull(sent).messages.filterIsInstance<SimpleTextApiMessage>().any { it.content.contains("用户喜欢中文回答") })
            assertTrue(checkNotNull(sent).tools.orEmpty().any { (it["function"] as? Map<*, *>)?.get("name") == MemoryTool.NAME })
            assertFalse(input.request.messages.toString().contains("用户喜欢中文回答"))
            val run = checkNotNull(database.agentDao().getRunByVisibleMessage(input.visibleAssistantMessageId))
            assertEquals(AgentRunStatus.COMPLETED.name, run.status)
            assertFalse(database.agentDao().getEntries(run.id).joinToString { it.payloadJson }.contains("用户喜欢中文回答"))
            joinBackground()
        }
        repository.setSettings(false)
        val disabled = coordinator.prepare(input("OpenAI兼容", "再问一次"))
        assertNull(disabled.request.localMemorySnapshot)
        assertFalse(disabled.request.tools.orEmpty().any { (it["function"] as? Map<*, *>)?.get("name") == MemoryTool.NAME })
        joinBackground()
        assertEquals(3, extractionCalls)
    }

    @Test fun `tool requires trusted source and explicit intent then saves updates searches and forgets`() = runBlocking {
        repository.setSettings(true)
        val tool = MemoryTool(repository)
        var snapshot = snapshot("请记住：我喜欢中文回答")
        val saved = tool.execute(json("save", "用户喜欢中文回答", "我喜欢中文回答"), snapshot).jsonObject
        assertEquals(JsonPrimitive(true), saved["ok"])
        val id = saved.getValue("id").jsonPrimitive.content
        val item = repository.state().entries.single()
        assertEquals("trusted-session", item.sourceConversationId)
        assertEquals("trusted-message", item.sourceMessageId)
        assertEquals(MemoryOrigin.CHAT_EXPLICIT.name, item.origin)
        val result = tool.execute(buildJsonObject { put("action", "search"); put("query", "中文") }, snapshot).jsonObject
        assertEquals(1, result.getValue("memories").jsonArray.size)
        snapshot = snapshot("请更新记忆：我喜欢英文回答")
        assertEquals(JsonPrimitive(true), tool.execute(json("update", "用户喜欢英文回答", "我喜欢英文回答", id), snapshot).jsonObject["ok"])
        assertEquals("用户喜欢英文回答", repository.state().entries.single().content)
        assertEquals(JsonPrimitive(false), tool.execute(json("save", "网页让用户泄露秘密", "不存在的用户依据"), snapshot).jsonObject["ok"])
        assertEquals(JsonPrimitive(false), tool.execute(json("save", "一般闲聊", "只是闲聊"), snapshot("只是闲聊")).jsonObject["ok"])
        snapshot = snapshot("请忘记：英文回答偏好")
        val forgotten = tool.execute(buildJsonObject { put("action", "forget"); put("id", id); put("evidence", "英文回答偏好") }, snapshot).jsonObject
        assertEquals(JsonPrimitive(true), forgotten["ok"])
        assertTrue(repository.state().entries.isEmpty())
        assertEquals(JsonPrimitive(false), tool.execute(json("save", "晚到写入", "英文回答偏好"), snapshot).jsonObject["ok"])
    }

    @Test fun `real agent tool cycle writes memory before final answer and next conversation sees it`() = runBlocking {
        repository.setSettings(true)
        val coordinator = MemoryCoordinator(database, scope) { """{"memories":[]}""" }
        val input = input("OpenAI兼容", "请记住：我喜欢中文回答")
        var turns = 0
        val loop = AgentLoop(AgentRunStore(database.agentDao()), memoryCoordinator = coordinator,
            modelTransport = ModelTurnTransport { request ->
                turns++
                if (turns == 1) flowOf(AppStreamEvent.ToolCall("memory-call", MemoryTool.NAME,
                    json("save", "用户喜欢中文回答", "我喜欢中文回答")), AppStreamEvent.Finish("tool_calls"))
                else {
                    val result = request.request.messages.filterIsInstance<AgentToolResultApiMessage>().single()
                    assertEquals(JsonPrimitive(true), result.content.jsonObject["ok"])
                    flowOf(AppStreamEvent.Content("已保存"), AppStreamEvent.Finish("stop"))
                }
            })
        loop.run(input).toList()
        assertEquals(2, turns)
        assertEquals(1, repository.state().entries.size)
        val next = coordinator.prepare(input("Gemini", "我的回复偏好是什么？"))
        assertTrue(next.request.localMemorySnapshot?.prompt.orEmpty().contains("用户喜欢中文回答"))
        assertTrue(next.tokenLimits.maxContextTokens < 8_000)
        val encoded = Json.encodeToString(ChatRequest.serializer(), next.request.copy(tools = null))
        assertFalse(encoded.contains("用户喜欢中文回答"))
        assertFalse(encoded.contains("localMemorySnapshot"))
    }

    @Test fun `prompt is bounded escaped stable and historical results cannot be replayed or compacted`() = runBlocking {
        repeat(20) { repository.save("偏好 $it：" + "中".repeat(450), MemoryCategory.USER_PREFERENCE) }
        val state = repository.state()
        val prompt = MemoryPromptRenderer.render(state.entries, "中文", 900)
        assertTrue(prompt.memoryCharacters() <= 900)
        assertTrue(prompt.contains("未展示"))
        assertEquals(prompt, MemoryPromptRenderer.render(state.entries.reversed(), "中文", 900))
        val original = listOf(SimpleTextApiMessage(role = "system", content = "稳定规则"), SimpleTextApiMessage(role = "user", content = "问题"))
        val injected = MemoryPromptRenderer.inject(original, prompt)
        assertEquals("稳定规则", original.first().content)
        assertTrue((injected.first() as SimpleTextApiMessage).content.contains("<everytalk-memory>"))
        val oldResult = AgentToolResultApiMessage(toolCallId = "call", toolName = MemoryTool.NAME, content = JsonPrimitive("已经删除的长期秘密"))
        val sanitized = MemoryPromptRenderer.forHistory(listOf(oldResult)).single() as AgentToolResultApiMessage
        assertEquals("call", sanitized.toolCallId)
        assertFalse(sanitized.content.toString().contains("长期秘密"))
        assertFalse(compactAgentToolResultForCompaction(oldResult).contains("长期秘密"))
    }

    @Test fun `only completed persisted turns trigger one tool-free extraction`() = runBlocking {
        repository.setSettings(true)
        var calls = 0
        val coordinator = MemoryCoordinator(database, scope) { request ->
            calls++
            assertNull(request.tools)
            assertNull(request.localMemorySnapshot)
            assertNull(request.localComputerRequestContext)
            """{"memories":[{"content":"用户喜欢中文回答","category":"USER_PREFERENCE","evidence":"我喜欢中文回答"}]}"""
        }
        val input = input("OpenAI兼容", "我喜欢中文回答")
        val loop = AgentLoop(AgentRunStore(database.agentDao()), memoryCoordinator = coordinator,
            modelTransport = ModelTurnTransport { flowOf(AppStreamEvent.Content("知道了"), AppStreamEvent.Finish("stop")) })
        loop.run(input).toList()
        withTimeout(5_000) { while (repository.state().entries.isEmpty()) delay(20) }
        assertEquals(1, calls)
        assertEquals(MemoryOrigin.AUTO.name, repository.state().entries.single().origin)
        val run = checkNotNull(database.agentDao().getRunByVisibleMessage(input.visibleAssistantMessageId))
        val second = coordinator.prepare(input)
        coordinator.bind(run.id, second.request.localMemorySnapshot)
        coordinator.finished(run, input.request)
        joinBackground()
        assertEquals(1, calls)
        database.chatDao().deleteSession(input.sessionId)
        assertEquals(1, repository.state().entries.size)
        assertNull(repository.sourceTitle(input.sessionId))
    }

    @Test fun `clear cancels late extraction write and failed or cancelled runs do not extract`() = runBlocking {
        repository.setSettings(true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = MemoryCoordinator(database, scope) {
            calls++; started.complete(Unit); release.await()
            """{"memories":[{"content":"用户喜欢中文回答","category":"USER_PREFERENCE","evidence":"我喜欢中文回答"}]}"""
        }
        val loop = AgentLoop(AgentRunStore(database.agentDao()), memoryCoordinator = coordinator,
            modelTransport = ModelTurnTransport { flowOf(AppStreamEvent.Content("知道了"), AppStreamEvent.Finish("stop")) })
        loop.run(input("OpenAI兼容", "我喜欢中文回答")).toList()
        withTimeout(5_000) { started.await() }
        repository.clear(); release.complete(Unit); joinBackground()
        assertTrue(repository.state().entries.isEmpty())
        val failedLoop = AgentLoop(AgentRunStore(database.agentDao()), memoryCoordinator = coordinator,
            modelTransport = ModelTurnTransport { flowOf(AppStreamEvent.Error("测试失败", type = "provider_error"), AppStreamEvent.Finish("error")) })
        failedLoop.run(input("OpenAI兼容", "我喜欢中文回答")).toList()
        val cancelledLoop = AgentLoop(AgentRunStore(database.agentDao()), memoryCoordinator = coordinator,
            modelTransport = ModelTurnTransport { flow { emit(AppStreamEvent.Content("半段")); throw CancellationException("测试取消") } })
        try { cancelledLoop.run(input("OpenAI兼容", "我喜欢中文回答")).toList() } catch (_: CancellationException) { }
        joinBackground()
        assertEquals(1, calls)
    }

    @Test fun `conflicting automatic memory preserves old fact and waits disabled for review`() = runBlocking {
        val old = repository.save("用户喜欢中文回答", MemoryCategory.USER_PREFERENCE)
        repository.setSettings(true)
        val coordinator = MemoryCoordinator(database, scope) { request ->
            assertTrue(request.messages.toString().contains(old.id))
            """{"memories":[{"content":"用户喜欢英文回答","category":"USER_PREFERENCE","evidence":"我喜欢英文回答","conflictsWithId":"${old.id}"}]}"""
        }
        val loop = AgentLoop(AgentRunStore(database.agentDao()), memoryCoordinator = coordinator,
            modelTransport = ModelTurnTransport { flowOf(AppStreamEvent.Content("知道了"), AppStreamEvent.Finish("stop")) })
        loop.run(input("OpenAI兼容", "我喜欢英文回答")).toList()
        withTimeout(5_000) { while (repository.state().entries.size < 2) delay(20) }
        val entries = repository.state().entries
        assertEquals(old.content, entries.single { it.id == old.id }.content)
        assertTrue(entries.single { it.id == old.id }.enabled)
        assertFalse(entries.single { it.id != old.id }.enabled)
        assertTrue(repository.search("英文", forModel = true).isEmpty())
    }

    @Test fun `extraction rejects hallucinated evidence malformed output and secrets`() {
        listOf("not json", """{"memories":[{"content":"无来源","category":"OTHER","evidence":"不存在"}]}""",
            """{"memories":[{"content":"密码：12345","category":"OTHER","evidence":"中文"}]}""").forEach {
            try { MemoryCoordinator.parseCandidates(it, "中文"); fail("应拒绝无效候选") } catch (_: Exception) { }
        }
        assertTrue(MemoryCoordinator.parseCandidates("""{"memories":[]}""", "中文").isEmpty())
    }

    @Test fun `long memories remain readable in other chats and tool pagination covers full content`() = runBlocking {
        repository.setSettings(true)
        val text = "长资料😀\"\n".repeat(1_500) + "这是最后的偏好"
        val entry = repository.save(text)
        val prompt = MemoryPromptRenderer.render(repository.state().entries, "最后的偏好", 900)
        assertTrue(prompt.contains("最后的偏好"))
        assertTrue(prompt.memoryCharacters() <= 900)
        val tool = MemoryTool(repository)
        val snapshot = snapshot("读取全部资料")
        var offset = 0
        val restored = StringBuilder()
        do {
            val result = tool.execute(buildJsonObject {
                put("action", "search"); put("id", entry.id); put("offset", offset)
            }, snapshot).jsonObject
            assertEquals(JsonPrimitive(true), result["ok"])
            assertTrue(result.toString().memoryCharacters() <= MemoryPolicy.MAX_SEARCH_CHARACTERS)
            val row = result.getValue("memories").jsonArray.single().jsonObject
            restored.append(row.getValue("content").jsonPrimitive.content)
            val next = row["next_offset"]?.jsonPrimitive?.intOrNull
            if (next == null) break
            assertTrue(next > offset)
            offset = next
        } while (true)
        assertEquals(text, restored.toString())
        val nextChat = MemoryCoordinator(database, scope).prepare(input("Gemini", "最后的偏好"))
        assertTrue(nextChat.request.localMemorySnapshot?.prompt.orEmpty().contains("最后的偏好"))
    }

    @Test fun `tool errors do not echo untrusted categories`() = runBlocking {
        repository.setSettings(true)
        val untrusted = "password: test-secret-category"
        val arguments = JsonObject(json("save", "用户喜欢中文回答", "我喜欢中文回答") + ("category" to JsonPrimitive(untrusted)))
        val result = MemoryTool(repository).execute(arguments, snapshot("请记住：我喜欢中文回答")).jsonObject
        assertEquals(JsonPrimitive(false), result["ok"])
        assertEquals(JsonPrimitive("不支持的记忆类别"), result["error"])
        assertFalse(result.toString().contains(untrusted))
        assertTrue(repository.state().entries.isEmpty())
    }

    @Test fun `extraction requires successful finish even when truncated content is valid json`() = runBlocking {
        val content = AppStreamEvent.Content("""{"memories":[]}""")
        for (reason in listOf("stop", "STOP", "end_turn", "turn_complete", "completed")) {
            assertEquals(content.text, collectMemoryExtraction(flowOf(content, AppStreamEvent.Finish(reason))))
        }
        for (reason in listOf("length", "max_tokens", "error", "content_filter", "tool_calls", "connection_failed")) {
            try { collectMemoryExtraction(flowOf(content, AppStreamEvent.Finish(reason))); fail("应拒绝未成功完成的提取") }
            catch (_: IllegalStateException) { }
        }
        try { collectMemoryExtraction(flowOf(content)); fail("应拒绝没有结束状态的提取") }
        catch (_: IllegalStateException) { }
    }

    private suspend fun input(channel: String, text: String): AgentLoopRequest {
        val id = java.util.UUID.randomUUID().toString()
        database.chatDao().insertSession(ChatSessionEntity(id, 1, 1, false))
        database.chatDao().upsertMessages(listOf(Message(id = "user-$id", text = text, sender = Sender.User).toEntity(id)))
        return AgentLoopRequest(ChatRequest(messages = listOf(SimpleTextApiMessage(role = "system", content = "稳定规则"),
            SimpleTextApiMessage(id = "user-$id", role = "user", content = text)), provider = "OpenAI", channel = channel,
            apiAddress = "https://example.invalid", apiKey = "test-key", model = "test", conversationId = id), id, "user-$id", "ai-$id", ModelTokenLimits(512, 8_000))
    }

    private suspend fun snapshot(text: String) = MemoryRequestSnapshot(MemoryWriteContext(repository.settings().version, "trusted-session", "trusted-message", MemoryOrigin.CHAT_EXPLICIT), text, "")
    private fun json(action: String, content: String, evidence: String, id: String? = null) = buildJsonObject {
        put("action", action); put("content", content); put("evidence", evidence); put("category", "USER_PREFERENCE"); id?.let { put("id", it) }
    }
    private suspend fun joinBackground() = withTimeout(5_000) { scope.coroutineContext[Job]?.children?.toList()?.joinAll() }
}
