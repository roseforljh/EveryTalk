package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptInjectorRenderSafetyTest {

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
    fun `stable prompt should include capability catalog and selection protocol`() {
        val prompt = SystemPromptInjector.getSystemPrompt("zh-CN")

        assertTrue(prompt.contains("everytalk_select_capabilities"))
        assertTrue(prompt.contains("markdown-table:"))
        assertTrue(prompt.contains("financial-caution:"))
    }

    @Test
    fun `stable prompt should preserve markdown table math and code contracts`() {
        val zhPrompt = SystemPromptInjector.getSystemPrompt("zh-CN")
        val enPrompt = SystemPromptInjector.getSystemPrompt("en")

        assertTrue(zhPrompt.contains("表头、分隔行和所有数据行列数完全一致"))
        assertTrue(zhPrompt.contains("表格从独立行开始"))
        assertTrue(zhPrompt.contains("竖线写成 `\\|`"))
        assertTrue(zhPrompt.contains("禁止 `\\(...\\)`、`\\[...\\]`"))
        assertTrue(zhPrompt.contains("代码块放在列表外层"))

        assertTrue(enPrompt.contains("keep equal columns"))
        assertTrue(enPrompt.contains("start tables on their own line"))
        assertTrue(enPrompt.contains("escape `|` as `\\|`"))
        assertTrue(enPrompt.contains("no `\\(...\\)`, `\\[...\\]`"))
        assertTrue(enPrompt.contains("blocks outside lists"))
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
