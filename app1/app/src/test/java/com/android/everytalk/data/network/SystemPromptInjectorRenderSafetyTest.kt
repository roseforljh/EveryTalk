package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptInjectorRenderSafetyTest {
    @Test
    fun `动态检查点变化时稳定前缀不变且重复注入不重复包装`() {
        val history = listOf(
            SimpleTextApiMessage(id = "custom", role = "system", content = "遵守用户约束"),
            SimpleTextApiMessage(id = "user-1", role = "user", content = "hello"),
            SimpleTextApiMessage(id = "assistant-1", role = "assistant", content = "ready"),
        )
        fun project(turn: Int) = SystemPromptInjector.smartInjectSystemPrompt(history + listOf(
            SimpleTextApiMessage(id = "agent-execution-checkpoint", role = "system", content = "准备第 $turn 轮"),
            SimpleTextApiMessage(id = "computer-session-state", role = "system", content = "已运行 $turn 秒"),
        ))
        val first = project(1)
        val next = project(2)
        assertEquals(first.dropLast(2), next.dropLast(2))
        assertEquals(listOf("user", "user"), next.takeLast(2).map { it.role })
        assertTrue((next.last() as SimpleTextApiMessage).content.contains("已运行 2 秒"))
        assertEquals(next, SystemPromptInjector.smartInjectSystemPrompt(next))
        assertEquals(first.first(), SystemPromptInjector.smartInjectSystemPrompt(history +
            SimpleTextApiMessage(role = "user", content = "后续中文问题")).first())
    }

    @Test
    fun `回复语言跟随用户最新消息而不是记忆或提示词语言`() {
        val prompt = SystemPromptInjector.getSystemPrompt()
        assertTrue(prompt.contains("reply in the language of the user's latest message, whatever language that is"))
        assertTrue(prompt.contains("Memories, custom instructions, skills, tool results and earlier turns written in other languages do not change this"))
        // 只有一份规则：首条消息无论什么语言，稳定前缀都一样。
        assertEquals(
            SystemPromptInjector.smartInjectSystemPrompt(listOf(SimpleTextApiMessage(role = "user", content = "你好"))).first(),
            SystemPromptInjector.smartInjectSystemPrompt(listOf(SimpleTextApiMessage(role = "user", content = "こんにちは"))).first(),
        )
    }

    @Test
    fun `规则内容覆盖工具Skill安全和Markdown约定`() {
        val prompt = SystemPromptInjector.getSystemPrompt()
        listOf(
            "Related calls may proceed together", "Reuse existing authorization",
            "Save, download or export phone files only when explicitly requested", "Do not proactively offer phone files",
            "load_skill", "read_skill_file", "A Skill catalog is an index", "Skills grant no permissions",
            "request_agent", "request_user_input", "# AI content safety", "non-consensual intimate",
            "one row per line", "escaped `\\|`", "code fences on separate lines", "do not backtick or split URLs",
            "`\$...\$` or standalone `\$\$...\$\$`", "space after heading/list markers", "one item per line", "proper nesting",
        ).forEach { assertTrue(it, prompt.contains(it)) }
        assertFalse(prompt.contains("Before each tool call"))
    }

    @Test
    fun `打包模块按固定顺序分段组合且与安全策略保持一致`() {
        fun read(name: String) = checkNotNull(javaClass.getResourceAsStream("/system-prompts/base/$name.md"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }.replace("\r\n", "\n").trim()
        fun tag(name: String, body: String) = "<$name>\n$body\n</$name>"
        val expected = listOf(
            SystemPromptInjector.PROTOCOL_MARKER,
            read("core"),
            tag("execution", read("execution")),
            tag("tools", read("tools")),
            tag("output", read("output")),
            tag("safety", com.android.everytalk.util.AiContentSafetyPolicy.systemInstruction("en")),
        ).joinToString("\n\n")
        assertEquals(expected, SystemPromptInjector.getSystemPrompt())
    }

    @Test
    fun `existing custom system prompt should be merged behind stable EveryTalk prompt`() {
        val messages = listOf(
            SimpleTextApiMessage(role = "system", content = "请始终称呼我为用户"),
            SimpleTextApiMessage(role = "user", content = "解释协程"),
        )

        val result = SystemPromptInjector.injectSystemPrompt(messages)
        val systemMessages = result.filter { it.role == "system" }

        assertEquals(1, systemMessages.size)
        val systemContent = (systemMessages.single() as SimpleTextApiMessage).content
        assertTrue(systemContent.contains(SystemPromptInjector.PROTOCOL_MARKER))
        assertTrue(systemContent.endsWith("请始终称呼我为用户"))
        assertEquals("user", result[1].role)
    }

    @Test
    fun `升级旧版包装时移除旧规则但保留自定义指令`() {
        listOf(5, 6, 7).forEach { version ->
            val oldPrompt = "[EveryTalk Prompt Protocol v$version]\n旧版固定规则"
            listOf("", "\n\n[EveryTalk Custom Instructions]\n请保留我的指令").forEach { custom ->
                val result = SystemPromptInjector.injectSystemPrompt(listOf(
                    SimpleTextApiMessage(role = "system", content = oldPrompt + custom),
                    SimpleTextApiMessage(role = "user", content = "继续"),
                ))
                val prompt = (result.first() as SimpleTextApiMessage).content
                assertFalse(prompt.contains("旧版固定规则"))
                assertFalse(prompt.contains("Protocol v$version"))
                assertEquals(custom.isNotEmpty(), prompt.contains("请保留我的指令"))
                assertEquals(result, SystemPromptInjector.injectSystemPrompt(result))
            }
        }
    }

    @Test
    fun `专项提示打包可读且不会混入普通聊天`() {
        val tasks = listOf(SystemPromptModules.historySummary, SystemPromptModules.inputSummary,
            SystemPromptModules.conversationMemory, SystemPromptModules.memoryConsolidation, SystemPromptModules.conversationTitle, SystemPromptModules.mcpAccess)
        val ordinary = SystemPromptInjector.getSystemPrompt()
        tasks.forEach { task ->
            assertTrue(task.isNotBlank())
            assertFalse(ordinary.contains(task))
        }
    }

    @Test
    fun `stable injection should be idempotent`() {
        val original = listOf(
            SimpleTextApiMessage(role = "system", content = "回答时使用简体中文"),
            SimpleTextApiMessage(role = "user", content = "你好"),
        )

        val once = SystemPromptInjector.smartInjectSystemPrompt(original)
        val twice = SystemPromptInjector.smartInjectSystemPrompt(once)

        assertEquals(once, twice)
    }

    @Test
    fun `stable prompt should not contain volatile runtime guidance and stay compact`() {
        val prompt = SystemPromptInjector.getSystemPrompt()

        assertFalse(prompt.contains("get_current_datetime"))
        assertFalse(prompt.contains("local_time"))
        assertTrue("稳定提示词过长: ${prompt.length}", prompt.length <= 3200)
    }
}
