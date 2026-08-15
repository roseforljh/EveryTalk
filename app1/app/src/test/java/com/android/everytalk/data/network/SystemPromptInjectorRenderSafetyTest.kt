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
    fun `stable prompt should include dynamic skill protocol`() {
        val prompt = SystemPromptInjector.getSystemPrompt("zh-CN")

        assertTrue(prompt.contains("load_skill"))
        assertTrue(prompt.contains("read_skill_file"))
        assertTrue(prompt.contains("完整 Skill 目录"))
        assertTrue(prompt.contains("# AI 内容安全"))
        assertTrue(prompt.contains("非自愿私密内容"))
    }

    @Test
    fun `stable prompt should preserve markdown table math and code contracts`() {
        val zhPrompt = SystemPromptInjector.getSystemPrompt("zh-CN")
        val enPrompt = SystemPromptInjector.getSystemPrompt("en")

        assertTrue(zhPrompt.contains("表头、分隔行和所有数据行列数一致"))
        assertTrue(zhPrompt.contains("表格从独立行开始"))
        assertTrue(zhPrompt.contains("竖线写成 `\\|`"))
        assertTrue(zhPrompt.contains("禁止 `\\(...\\)`、`\\[...\\]`"))
        assertTrue(zhPrompt.contains("禁止把代码围栏嵌入列表或引用"))
        assertTrue(zhPrompt.contains("围栏必须从物理行第 1 列开始"))
        assertTrue(zhPrompt.contains("围栏内只保留代码自身需要的缩进"))
        assertTrue(zhPrompt.contains("URL 不放在反引号中"))
        assertTrue(zhPrompt.contains("备用参数另起一行"))

        assertTrue(enPrompt.contains("one row per line"))
        assertTrue(enPrompt.contains("escape `|`"))
        assertTrue(enPrompt.contains("no `\\(...\\)` or `\\[...\\]`"))
        assertTrue(enPrompt.contains("column 1"))
        assertTrue(enPrompt.contains("never nest them in lists or quotes"))
        assertTrue(enPrompt.contains("Never put URLs in backticks"))
        assertTrue(enPrompt.contains("fallback parameters on a separate line"))
    }

    @Test
    fun `stable prompt should preserve list boundary and nesting contracts`() {
        val zhPrompt = SystemPromptInjector.getSystemPrompt("zh-CN")
        val enPrompt = SystemPromptInjector.getSystemPrompt("en")

        assertTrue(zhPrompt.contains("每项只使用一个行首标记"))
        assertTrue(zhPrompt.contains("禁止在同一物理行继续写第二个标记"))
        assertTrue(zhPrompt.contains("缩进到父项正文起始列"))
        assertTrue(zhPrompt.contains("改用同级列表或普通段落"))

        assertTrue(enPrompt.contains("one marker per physical line"))
        assertTrue(enPrompt.contains("parent text column"))
        assertTrue(enPrompt.contains("Use siblings or prose"))
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
