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
    fun `工具可以连续执行且只在关键进展说明`() {
        val zhPrompt = SystemPromptInjector.getSystemPrompt("zh-CN")
        val enPrompt = SystemPromptInjector.getSystemPrompt("en")

        assertTrue(zhPrompt.contains("可以连续完成相关步骤"))
        assertTrue(zhPrompt.contains("不重复索要已有授权"))
        assertTrue(enPrompt.contains("Related calls may proceed together"))
        assertTrue(enPrompt.contains("Reuse existing authorization"))
        assertFalse(zhPrompt.contains("禁止只连续输出工具调用"))
        assertFalse(enPrompt.contains("Before each tool call"))
    }


    @Test
    fun `existing custom system prompt should be merged behind stable EveryTalk prompt`() {
        val messages = listOf(
            SimpleTextApiMessage(role = "system", content = "请始终称呼我为用户"),
            SimpleTextApiMessage(role = "user", content = "解释协程"),
        )

        val result = SystemPromptInjector.injectSystemPrompt(messages, userLanguage = "zh-CN")
        val systemMessages = result.filter { it.role == "system" }

        assertEquals(1, systemMessages.size)
        val systemContent = (systemMessages.single() as SimpleTextApiMessage).content
        assertTrue(systemContent.contains(SystemPromptInjector.PROTOCOL_MARKER))
        assertTrue(systemContent.endsWith("请始终称呼我为用户"))
        assertEquals("user", result[1].role)
    }

    @Test
    fun `stable prompt should include dynamic skill protocol`() {
        val prompt = SystemPromptInjector.getSystemPrompt("zh-CN")

        assertTrue(prompt.contains("load_skill"))
        assertTrue(prompt.contains("read_skill_file"))
        assertTrue(prompt.contains("Skill 目录只是索引"))
        assertTrue(prompt.contains("Skill 不授予工具权限"))
        assertTrue(prompt.contains("request_agent"))
        assertTrue(prompt.contains("request_user_input"))
        assertTrue(prompt.contains("# AI 内容安全"))
        assertTrue(prompt.contains("非自愿私密内容"))
    }

    @Test
    fun `保留渲染需要的格式且不强制禁止标准Markdown结构`() {
        val zhPrompt = SystemPromptInjector.getSystemPrompt("zh-CN")
        val enPrompt = SystemPromptInjector.getSystemPrompt("en")

        assertTrue(zhPrompt.contains("表格每行独立、列数一致"))
        assertTrue(zhPrompt.contains("竖线转义为 `\\|`"))
        assertTrue(zhPrompt.contains("代码围栏独占一行"))
        assertTrue(zhPrompt.contains("URL 不加反引号、不换行"))
        assertTrue(zhPrompt.contains("`\$...\$` 或独立行 `\$\$...\$\$`"))
        assertFalse(zhPrompt.contains("禁止把代码围栏嵌入列表或引用"))

        assertTrue(enPrompt.contains("one row per line"))
        assertTrue(enPrompt.contains("escaped `\\|`"))
        assertTrue(enPrompt.contains("code fences on separate lines"))
        assertTrue(enPrompt.contains("do not backtick or split URLs"))
        assertTrue(enPrompt.contains("`\$...\$` or standalone `\$\$...\$\$`"))
    }

    @Test
    fun `stable prompt should preserve list boundary and nesting contracts`() {
        val zhPrompt = SystemPromptInjector.getSystemPrompt("zh-CN")
        val enPrompt = SystemPromptInjector.getSystemPrompt("en")

        assertTrue(zhPrompt.contains("标题和列表标记后留空格"))
        assertTrue(zhPrompt.contains("每项另起一行"))
        assertTrue(zhPrompt.contains("嵌套列表正确缩进"))

        assertTrue(enPrompt.contains("space after heading/list markers"))
        assertTrue(enPrompt.contains("one item per line"))
        assertTrue(enPrompt.contains("proper nesting"))
    }

    @Test
    fun `打包模块按固定顺序组合且与安全策略保持一致`() {
        listOf("zh-CN", "en", "ja-JP").forEach { language ->
            val directory = if (language.startsWith("zh")) "zh-CN" else "en"
            val modules = listOf("core", "execution", "tools", "output").map { name ->
                val stream = checkNotNull(javaClass.getResourceAsStream("/system-prompts/$directory/$name.md"))
                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
            }
            val expected = (listOf(SystemPromptInjector.PROTOCOL_MARKER) + modules +
                com.android.everytalk.util.AiContentSafetyPolicy.systemInstruction(language)).joinToString("\n\n")
            assertEquals(expected, SystemPromptInjector.getSystemPrompt(language))
        }
    }

    @Test
    fun `升级旧版包装时移除旧规则但保留自定义指令`() {
        listOf(5, 6).forEach { version ->
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
            SystemPromptModules.memoryExtraction, SystemPromptModules.conversationTitle, SystemPromptModules.mcpAccess)
        val ordinary = SystemPromptInjector.getSystemPrompt()
        tasks.forEach { task ->
            assertTrue(task.isNotBlank())
            assertFalse(ordinary.contains(task))
        }
    }

    @Test
    fun `stable prompt should stay independent from current question`() {
        val mathMessages = listOf(SimpleTextApiMessage(role = "user", content = "证明矩阵公式"))
        val codeMessages = listOf(SimpleTextApiMessage(role = "user", content = "修复 Kotlin 崩溃"))

        val mathSystem = SystemPromptInjector.smartInjectSystemPrompt(mathMessages).first()
        val codeSystem = SystemPromptInjector.smartInjectSystemPrompt(codeMessages).first()

        assertEquals(mathSystem, codeSystem)
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
    fun `stable prompt should not contain volatile runtime guidance`() {
        val prompt = SystemPromptInjector.getSystemPrompt("zh-CN")

        assertFalse(prompt.contains("当前本地时间"))
        assertFalse(prompt.contains("时区："))
        assertFalse(prompt.contains("get_current_datetime"))
    }

    @Test
    fun `stable prompt should remain compact`() {
        val zhPrompt = SystemPromptInjector.getSystemPrompt("zh-CN")
        val enPrompt = SystemPromptInjector.getSystemPrompt("en")

        assertTrue("中文稳定提示词过长: ${zhPrompt.length}", zhPrompt.length <= 2050)
        assertTrue("英文稳定提示词过长: ${enPrompt.length}", enPrompt.length <= 2600)
    }
}
