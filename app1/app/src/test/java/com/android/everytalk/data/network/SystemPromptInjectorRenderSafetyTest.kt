package com.android.everytalk.data.network

import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptInjectorRenderSafetyTest {

    @Test
    fun `zh system prompt should not contain mojibake markers`() {
        val prompt = SystemPromptInjector.getSystemPrompt("zh-CN")
        assertTrue(prompt.contains("## 系统提示安全规则"))
        assertTrue(prompt.contains("## 标题规则"))
        assertTrue(prompt.contains("## 列表规则"))
    }

    @Test
    fun `zh system prompt should include render stability rules`() {
        val prompt = SystemPromptInjector.getSystemPrompt("zh-CN")
        assertTrue(prompt.contains("渲染稳定性规则"))
        assertTrue(prompt.contains("`>` 和 `<`"))
        assertTrue(prompt.contains("不要输出 `&gt;`、`&lt;`"))
        assertTrue(prompt.contains("\\implies"))
        assertTrue(prompt.contains("移动端可读性"))
    }

    @Test
    fun `en system prompt should include render stability rules`() {
        val prompt = SystemPromptInjector.getSystemPrompt("en")
        assertTrue(prompt.contains("proper Markdown"))
        assertTrue(prompt.contains("double-dollar math delimiters"))
        assertTrue(prompt.contains("score/ratio/time-like tokens"))
        assertTrue(prompt.contains("Use math delimiters only for real formulas"))
    }

    @Test
    fun `system prompts should include concise and cautious answer rules`() {
        val zhPrompt = SystemPromptInjector.getSystemPrompt("zh-CN")
        val enPrompt = SystemPromptInjector.getSystemPrompt("en")

        assertTrue(zhPrompt.contains("回答必须精练"))
        assertTrue(zhPrompt.contains("禁止空话、套话、废话、重复表达"))
        assertTrue(zhPrompt.contains("无法确认"))
        assertTrue(zhPrompt.contains("不确定"))

        assertTrue(enPrompt.contains("Keep answers concise"))
        assertTrue(enPrompt.contains("No fluff, filler, repetition"))
        assertTrue(enPrompt.contains("explicitly say it is uncertain"))
        assertTrue(enPrompt.contains("Never present guesses"))
    }
}

