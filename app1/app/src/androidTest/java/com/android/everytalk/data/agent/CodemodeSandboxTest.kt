package com.android.everytalk.data.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实 AndroidX JavaScriptSandbox 中运行 codemode 脚本，覆盖 JS 运行时与宿主轮询协议。 */
@RunWith(AndroidJUnit4::class)
class CodemodeSandboxTest {
    private val engine = SandboxCodemodeEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    private val tools = CodemodeTool.callableTools(
        listOf("exec", "mcp__dev-radius__search").map { name ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to name,
                    "description" to if (name == "exec") "Run a shell command" else "Search the radius index",
                    "parameters" to mapOf("type" to "object"),
                ),
            )
        },
    )
    private val calls = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun run(code: String, store: MutableMap<String, JsonElement> = mutableMapOf()): CodemodeOutcome = runBlocking {
        engine.open().use { isolate ->
            val outcome = CodemodeTool.run(code, isolate, tools, store, 8_192) { name, args ->
                val command = args["command"]?.jsonPrimitive?.content.orEmpty()
                calls += "$name:$command"
                if (command == "fail") false to JsonPrimitive("boom")
                else true to JsonObject(mapOf("out" to JsonPrimitive(command.uppercase())))
            }
            if (!outcome.isError) {
                val committed = CodemodeTool.applyStoreDelta(store, outcome.storeDelta.orEmpty())
                store.clear()
                store.putAll(committed)
            }
            outcome
        }
    }

    @Test
    fun parallelCallsFilterAndOutputInPiFormat() {
        val outcome = run(
            """
            const [a, b] = await Promise.all([tools.exec({ command: "a" }), tools.exec({ command: "b" })]);
            console.log("got", a.out);
            text(a.out + b.out);
            return { n: 2 };
            """.trimIndent(),
        )
        assertFalse(outcome.text, outcome.isError)
        assertTrue(outcome.text, outcome.text.startsWith("Script completed in "))
        assertTrue(outcome.text, "==> text 1/2 <==\nAB\n==> text 2/2 <==\n{\n  \"n\": 2\n}\n<console_output>\ngot A\n</console_output>" in outcome.text)
        assertEquals(setOf("exec:a", "exec:b"), calls.toSet())
    }

    @Test
    fun rejectedCallsSettleAndScriptErrorsKeepPartialOutput() {
        val settled = run(
            "const r = await Promise.allSettled([tools.exec({ command: \"fail\" })]); return r[0].status + \":\" + r[0].reason.message",
        )
        assertTrue(settled.text, settled.text.endsWith("rejected:boom"))

        val thrown = run("text(\"before\"); await tools.exec({ command: \"fail\" }); text(\"after\")")
        assertTrue(thrown.isError)
        assertTrue(thrown.text, "before" in thrown.text && "Script error: Error: boom" in thrown.text && "after" !in thrown.text)
    }

    @Test
    fun exitSyntaxErrorAndSanitizedToolNames() {
        val exited = run("text(\"x\"); exit(); text(\"y\")")
        assertFalse(exited.isError)
        assertTrue(exited.text, exited.text.endsWith("\nx"))

        val syntax = run("return (")
        assertTrue(syntax.isError)
        assertTrue(syntax.text, "SyntaxError" in syntax.text)

        val search = run(
            """
            const found = await searchTools("radius");
            const declared = await describeTool(found[0].name);
            await tools.mcp__dev_radius__search({ command: "q" });
            return found[0].name + "|" + declared.description + "|" + ALL_TOOLS.length;
            """.trimIndent(),
        )
        assertTrue(search.text, search.text.endsWith("mcp__dev_radius__search|Search the radius index|2"))
        assertTrue(calls.contains("mcp__dev-radius__search:q"))
    }

    @Test
    fun storeSurvivesOnlySuccessfulScripts() {
        val store = mutableMapOf<String, JsonElement>()
        run("store(\"k\", { v: 1 })", store)
        run("store(\"k\", { v: 2 }); throw new Error(\"no\")", store)
        val loaded = run("return load(\"k\").v", store)
        assertTrue(loaded.text, loaded.text.endsWith("\n1"))
    }

    @Test
    fun storeSpecialKeysLimitsAndDeletion() {
        val state = mutableMapOf<String, JsonElement>()
        assertFalse(run("store('__proto__', {v: 1}); store('constructor', 2)", state).isError)
        assertTrue(run("return load('__proto__').v + load('constructor')", state).text.endsWith("\n3"))
        assertFalse(run("store('large', '😀'.repeat(131071))", state).isError)
        assertTrue(run("store('large', 'x'.repeat(262143))", state).isError)
        assertTrue(run("store('a', 'x'.repeat(262142)); store('b', 'x'.repeat(262142)); store('c', 'x'.repeat(262142))", state).isError)
        assertFalse(state.containsKey("a"))
        assertFalse(run("store('__proto__', undefined); store('large', null)", state).isError)
        assertTrue(run("return load('__proto__') === undefined && load('large') === undefined", state).text.endsWith("\ntrue"))
    }

    @Test
    fun agentLoopPersistsSandboxStateAndCallsDiscoveredMcp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, com.android.everytalk.data.database.AppDatabase::class.java).build()
        try {
            val session = "sandbox-e2e"
            database.chatDao().insertSession(com.android.everytalk.data.database.entities.ChatSessionEntity(session, 1, 1, false))
            val all = (1..25).map { n -> mapOf<String, Any>("type" to "function", "function" to mapOf(
                "name" to "mcp_hash_tool$n", "description" to "task$n", "parameters" to mapOf("type" to "object"))) }
            var turn = 0
            val executed = mutableListOf<String>()
            val requests = mutableListOf<com.android.everytalk.data.DataClass.ChatRequest>()
            val store = AgentRunStore(database.agentDao())
            val loop = AgentLoop(runStore = store, codemodeEngine = engine,
                toolRuntime = AgentToolRuntime(executorProvider = {
                    { name, _, _, _, _ -> executed += name; com.android.everytalk.data.network.AppToolExecutionResult(
                        JsonPrimitive("summary"), scriptContent = kotlinx.serialization.json.buildJsonObject {
                            put("padding", JsonPrimitive("A".repeat(100_000))); put("middle", JsonPrimitive("target")); put("tail", JsonPrimitive("B".repeat(100_000)))
                        }, isError = false) }
                }, approvalProvider = { null }),
                modelTransport = com.android.everytalk.data.network.ModelTurnTransport { model ->
                    requests += model.request
                    fun code(id: String, source: String) = com.android.everytalk.data.network.AppStreamEvent.ToolCall(id, CodemodeTool.NAME,
                        JsonObject(mapOf("code" to JsonPrimitive(source))))
                    when (++turn) {
                        1 -> kotlinx.coroutines.flow.flowOf(code("cm1", "store('k', 7); return (await tools.mcp_hash_tool25({})).middle"), com.android.everytalk.data.network.AppStreamEvent.Finish("tool_calls"))
                        2 -> kotlinx.coroutines.flow.flowOf(code("cm2", "return load('k')"), com.android.everytalk.data.network.AppStreamEvent.Finish("tool_calls"))
                        else -> kotlinx.coroutines.flow.flowOf(com.android.everytalk.data.network.AppStreamEvent.Content("done"), com.android.everytalk.data.network.AppStreamEvent.Finish("stop"))
                    }
                })
            val input = AgentLoopRequest(
                request = com.android.everytalk.data.DataClass.ChatRequest(
                    messages = listOf(com.android.everytalk.data.DataClass.SimpleTextApiMessage(role = "user", content = "check")),
                    provider = "OpenAI", channel = "OpenAI兼容", apiAddress = "https://example.test", apiKey = "test", model = "test", tools = all),
                sessionId = session, userMessageId = "u", visibleAssistantMessageId = "a",
                tokenLimits = com.android.everytalk.data.DataClass.ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 32_768))
            val events = mutableListOf<com.android.everytalk.data.network.AppStreamEvent>()
            loop.run(input).collect { events += it }
            assertTrue(events.none { it is com.android.everytalk.data.network.AppStreamEvent.Error })
            assertEquals(listOf("mcp_hash_tool25"), executed)
            val filtered = requests[1].messages.filterIsInstance<com.android.everytalk.data.DataClass.AgentToolResultApiMessage>().single { it.toolCallId == "cm1" }
            assertTrue(filtered.content.toString().contains("target"))
            assertFalse(filtered.content.toString().contains("A".repeat(100)))
            assertEquals(3, turn)
            assertTrue(requests.first().tools!!.none { McpToolDiscovery.name(it) == "mcp_hash_tool25" })
            val result = requests.last().messages.filterIsInstance<com.android.everytalk.data.DataClass.AgentToolResultApiMessage>().single { it.toolCallId == "cm2" }
            assertTrue(result.content.toString(), result.content.jsonPrimitive.content.endsWith("\n7"))
            val run = requireNotNull(database.agentDao().getRunByVisibleMessage("a"))
            assertEquals(mapOf("k" to JsonPrimitive(7)), AgentRunStore(database.agentDao()).codemodeStore(run.id))
            assertTrue(store.executionTrace(run.id).filterIsInstance<com.android.everytalk.data.DataClass.ExecutionTraceEvent.Tool>().all { it.step.durationMs != null })
        } finally { database.close() }
    }

    @Test
    fun neverResolvingPromiseAndInfiniteLoopFail() {
        val waiting = run("text(\"waiting\"); await new Promise(() => {})")
        assertTrue(waiting.isError)
        assertTrue(waiting.text, "waiting" in waiting.text && "never resolves" in waiting.text)

        val started = System.currentTimeMillis()
        val loop = run("// @options: {\"timeout_ms\": 500}\nwhile (true) {}")
        assertTrue(loop.isError)
        assertTrue(loop.text, "timed out after 500ms" in loop.text)
        assertTrue(System.currentTimeMillis() - started < 10_000)
    }
}
