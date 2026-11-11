package com.android.everytalk.data.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodemodeToolTest {
    private fun tool(name: String) = mapOf(
        "type" to "function",
        "function" to mapOf("name" to name, "description" to "desc $name", "parameters" to mapOf("type" to "object")),
    )

    @Test
    fun `注入codemode排除控制工具且重复注入保持不变`() {
        val tools = listOf(tool("exec"), tool("mcp__dev-radius__search"), tool(AgentControlToolNames.REQUEST_AGENT))

        val injected = CodemodeTool.withCodemode(tools)!!
        val callable = CodemodeTool.callableTools(injected)

        assertEquals(4, injected.size)
        assertEquals(listOf("exec", "mcp__dev-radius__search"), callable.map { it.name })
        assertEquals("mcp__dev_radius__search", callable[1].id)
        assertSame(injected, CodemodeTool.withCodemode(injected))
        assertEquals(null, CodemodeTool.withCodemode(null))
        assertEquals(listOf(tool(AgentControlToolNames.REQUEST_AGENT)), CodemodeTool.withCodemode(listOf(tool(AgentControlToolNames.REQUEST_AGENT))))
    }

    @Test
    fun `选项行只能在第一行`() {
        assertEquals(CodemodeTool.Options(2_000, 60_000), CodemodeTool.parseOptions("// @options: {\"max_output_tokens\": 2000, \"timeout_ms\": 60000}\nreturn 1"))
        assertEquals(10_000L, CodemodeTool.parseOptions("return 1").maxOutputTokens)
        assertThrows(IllegalArgumentException::class.java) { CodemodeTool.parseOptions("return 1\n// @options: {}") }
        assertThrows(IllegalArgumentException::class.java) { CodemodeTool.parseOptions("// @options: {bad") }
    }

    @Test
    fun `输出按Pi格式排列并保留首尾截断`() {
        val text = CodemodeTool.format(
            CodemodePoll(state = "done", items = listOf("a", "b"), console = listOf("log 1")),
            wallTime = "0.1s",
            maxChars = 1_000,
        )
        assertEquals("Script completed in 0.1s\n==> text 1/2 <==\na\n==> text 2/2 <==\nb\n<console_output>\nlog 1\n</console_output>", text)

        val failed = CodemodeTool.format(CodemodePoll(state = "failed", error = "boom", items = listOf("partial")), "1.0s", 1_000)
        assertEquals("Script failed in 1.0s\npartial\nScript error: boom", failed)

        val truncated = CodemodeTool.truncateMiddle("x".repeat(50) + "y".repeat(50), 20)
        assertTrue(truncated.startsWith("x".repeat(10)) && truncated.endsWith("y".repeat(10)) && "80 characters omitted" in truncated)
    }

    /** 按 JS 运行时协议模拟引擎：第一次轮询发出两个并行调用，回填后脚本完成并写 store。 */
    private class FakeIsolate(private val script: (List<Pair<String, String>>) -> String?) : CodemodeIsolate {
        val resolved = mutableListOf<Pair<String, String>>()
        val evaluated = mutableListOf<String>()
        override suspend fun evaluate(expression: String): String {
            evaluated += expression
            if (expression.startsWith("__cm_resolve(")) {
                val args = Json.parseToJsonElement("[" + expression.removePrefix("__cm_resolve(").removeSuffix(");''") + "]").jsonArray
                resolved += args[0].jsonPrimitive.content to args[2].jsonPrimitive.content
            }
            return when (expression) {
                "__cm_poll()" -> script(resolved) ?: """{"state":"running","calls":[]}"""
                "__cm_snapshot()" -> """{"state":"running","items":["partial"],"console":[]}"""
                else -> ""
            }
        }
        override fun close() = Unit
    }

    @Test
    fun `宿主执行嵌套调用回填结果并在成功后写回store`() = runBlocking {
        var polls = 0
        val isolate = FakeIsolate { resolved ->
            polls++
            when {
                polls == 1 -> """{"state":"running","calls":[{"id":"1","name":"exec","args":{"command":"a"}},{"id":"2","name":"exec","args":{"command":"b"}}]}"""
                resolved.size == 2 -> """{"state":"done","items":["ok"],"console":[],"store":{"k":1,"gone":null}}"""
                else -> null
            }
        }
        val calls = mutableListOf<String>()
        val store = mutableMapOf<String, JsonElement>("gone" to JsonPrimitive(true))
        val outcome = CodemodeTool.run(
            code = "return 1",
            isolate = isolate,
            callable = CodemodeTool.callableTools(listOf(tool("exec"))),
            store = store,
            maxModelResultTokens = 8_192,
        ) { name, args ->
            calls += "$name:${args["command"]?.jsonPrimitive?.content}"
            true to JsonObject(mapOf("out" to JsonPrimitive(args.getValue("command").jsonPrimitive.content.uppercase())))
        }

        assertFalse(outcome.isError)
        assertEquals("Script completed", outcome.text.substringBefore(" in "))
        assertEquals(setOf("exec:a", "exec:b"), calls.toSet())
        assertEquals(listOf("1" to """{"out":"A"}""", "2" to """{"out":"B"}"""), isolate.resolved)
        assertEquals(mapOf<String, JsonElement>("gone" to JsonPrimitive(true)), store)
        assertEquals(mapOf<String, JsonElement>("k" to JsonPrimitive(1)), CodemodeTool.applyStoreDelta(store, outcome.storeDelta.orEmpty()))
    }

    @Test
    fun `状态大小包含JSON转义和键名且超限不修改原值`() {
        val value = JsonPrimitive("x".repeat(CodemodeTool.MAX_STORE_VALUE_CHARS - 2))
        assertEquals(value, CodemodeTool.applyStoreDelta(emptyMap(), mapOf("k" to value))["k"])
        assertThrows(IllegalArgumentException::class.java) {
            CodemodeTool.applyStoreDelta(emptyMap(), mapOf("k" to JsonPrimitive("x".repeat(CodemodeTool.MAX_STORE_VALUE_CHARS - 1))))
        }
        val full = (1..4).associate { "$it" to value }
        assertThrows(IllegalArgumentException::class.java) { CodemodeTool.applyStoreDelta(emptyMap(), full) }
        assertThrows(IllegalArgumentException::class.java) {
            CodemodeTool.applyStoreDelta(emptyMap(), mapOf("k" to JsonPrimitive("\n".repeat(CodemodeTool.MAX_STORE_VALUE_CHARS / 2))))
        }
        val base = mapOf("__proto__" to JsonPrimitive(1), "constructor" to JsonPrimitive(2))
        assertEquals(mapOf("constructor" to JsonPrimitive(2)), CodemodeTool.applyStoreDelta(base, mapOf("__proto__" to kotlinx.serialization.json.JsonNull)))
        assertEquals(2, base.size)
    }

    @Test
    fun `超限成功快照转失败且不返回可提交delta`() = runBlocking {
        val oversized = JsonObject(mapOf("state" to JsonPrimitive("done"), "store" to JsonObject(mapOf("k" to JsonPrimitive("x".repeat(262_144)))))).toString()
        val outcome = CodemodeTool.run("", FakeIsolate { oversized }, emptyList(), emptyMap(), 8_192) { _, _ -> error("unexpected") }
        assertTrue(outcome.isError)
        assertEquals(null, outcome.storeDelta)
    }

    @Test
    fun `连续两次没有调用也没结束判定为等待永不完成的Promise`() = runBlocking {
        val outcome = CodemodeTool.run("await new Promise(() => {})", FakeIsolate { null }, emptyList(), mutableMapOf(), 8_192) { _, _ ->
            error("不应调用工具")
        }
        assertTrue(outcome.isError)
        assertTrue(outcome.text, "partial" in outcome.text && "never resolves" in outcome.text)
    }

    @Test
    fun `超时后失败且不等待被卡住的引擎`() = runBlocking {
        val stuck = object : CodemodeIsolate {
            override suspend fun evaluate(expression: String): String {
                if (expression.startsWith("__cm_start(")) delay(Long.MAX_VALUE)
                return ""
            }
            override fun close() = Unit
        }
        val outcome = CodemodeTool.run("// @options: {\"timeout_ms\": 50}\nwhile(true){}", stuck, emptyList(), mutableMapOf(), 8_192) { _, _ ->
            error("不应调用工具")
        }
        assertTrue(outcome.isError)
        assertTrue(outcome.text, "timed out after 50ms" in outcome.text)
    }
}
