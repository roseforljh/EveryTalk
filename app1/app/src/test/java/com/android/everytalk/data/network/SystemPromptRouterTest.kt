package com.android.everytalk.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptRouterTest {

    @Test
    fun `crash log request should select debugging and code output`() {
        val selection = SystemPromptRouter.select(
            currentText = "这段 Kotlin 崩溃日志为什么报错，帮我修复",
        )

        assertEquals(PromptPrimary.CODING_DEBUGGING, selection.primary)
        assertTrue(PromptOutputModule.CODE_BLOCK in selection.outputModules)
    }

    @Test
    fun `explicit no table request should suppress inferred table output`() {
        val selection = SystemPromptRouter.select(
            currentText = "对比这三个数据库，但不要表格，用列表回答",
        )

        assertEquals(PromptPrimary.DECISION_SUPPORT, selection.primary)
        assertTrue(PromptOutputModule.TABLE in selection.suppressedModules)
        assertTrue(PromptOutputModule.TABLE !in selection.outputModules)
    }

    @Test
    fun `short follow up should inherit previous primary task`() {
        val selection = SystemPromptRouter.select(
            currentText = "继续",
            recentUserTexts = listOf("证明这个矩阵公式"),
        )

        assertEquals(PromptPrimary.MATH_SYMBOLIC, selection.primary)
        assertTrue(PromptOutputModule.MATHJAX in selection.outputModules)
    }

    @Test
    fun `csv attachment should select data analysis`() {
        val selection = SystemPromptRouter.select(
            currentText = "帮我看看附件",
            attachmentNames = listOf("metrics.csv"),
            attachmentMimeTypes = listOf("application/octet-stream"),
        )

        assertEquals(PromptPrimary.DATA_ANALYSIS, selection.primary)
        assertTrue(PromptOutputModule.TABLE in selection.outputModules)
    }
}
