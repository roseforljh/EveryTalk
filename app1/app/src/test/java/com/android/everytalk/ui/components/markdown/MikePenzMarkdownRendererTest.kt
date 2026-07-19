package com.android.everytalk.ui.components.markdown

import com.android.everytalk.ui.components.streaming.BLOCK_FORMULA_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.FormulaRequest
import com.android.everytalk.ui.components.streaming.INLINE_FORMULA_SCHEME
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MikePenzMarkdownRendererTest {

    @Test
    fun `行内公式链接只按64位小写SHA256查PreparedMessage映射`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = "勾股定理为 ${'$'}a^2+b^2=c^2${'$'}。",
            messageId = "inline",
            contentVersion = 1L,
        )
        val formula = prepared.formulas.values.single()
        val link = INLINE_FORMULA_SCHEME + formula.id

        assertSame(formula, resolveInlineFormula(link, prepared))
        assertEquals("${'$'}${formula.latex}${'$'}", inlineFormulaAlternateText(formula))
        assertNull(resolveInlineFormula("${INLINE_FORMULA_SCHEME}ABC", prepared))
        assertNull(resolveInlineFormula("${INLINE_FORMULA_SCHEME}${"a".repeat(63)}", prepared))
        assertNull(resolveInlineFormula("${INLINE_FORMULA_SCHEME}${"a".repeat(64)}", prepared))
    }

    @Test
    fun `行内公式拒绝块模式和过期contentVersion`() {
        val id = "a".repeat(64)
        val blockFormula = FormulaRequest(id, "x", FormulaDisplayMode.BLOCK, 7L)
        val staleInlineFormula = FormulaRequest(id, "x", FormulaDisplayMode.INLINE, 6L)

        assertNull(
            resolveInlineFormula(
                INLINE_FORMULA_SCHEME + id,
                PreparedMessage("", mapOf(id to blockFormula), false, 7L),
            )
        )
        assertNull(
            resolveInlineFormula(
                INLINE_FORMULA_SCHEME + id,
                PreparedMessage("", mapOf(id to staleInlineFormula), false, 7L),
            )
        )
    }

    @Test
    fun `块公式必须同时通过语言ID模式映射和版本校验`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = "${'$'}${'$'}\\int_0^1 x^2 dx${'$'}${'$'}",
            messageId = "block",
            contentVersion = 2L,
        )
        val formula = prepared.formulas.values.single()

        assertSame(
            formula,
            resolveBlockFormula(BLOCK_FORMULA_FENCE_LANGUAGE, formula.id, prepared),
        )
        assertNull(resolveBlockFormula("kotlin", formula.id, prepared))
        assertNull(resolveBlockFormula(BLOCK_FORMULA_FENCE_LANGUAGE, "not-an-id", prepared))
        assertNull(
            resolveBlockFormula(
                BLOCK_FORMULA_FENCE_LANGUAGE,
                "b".repeat(64),
                prepared,
            )
        )
    }

    @Test
    fun `Markdown围栏解包但任务标记和表格原文不改写`() {
        val markdown = """
            ```markdown
            - [/] 保留非标准任务标记

            | 名称 | 状态 |
            |:---|:---:|
            | 示例 | 正常 |
            ```
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = markdown,
            messageId = "native-markdown",
            contentVersion = 3L,
        )

        assertFalse(prepared.markdown.contains("```markdown"))
        assertTrue(prepared.markdown.contains("- [/] 保留非标准任务标记"))
        assertTrue(prepared.markdown.contains("| 名称 | 状态 |"))
        assertTrue(prepared.formulas.isEmpty())
        assertTrue(parseMarkdown(prepared.markdown) is State.Success)
    }

    @Test
    fun `表格代码块和公式占位可由MikePenz一次解析`() {
        val markdown = """
            # 渲染测试

            | 名称 | 表达式 |
            |:---|:---:|
            | 欧拉公式 | ${'$'}e^{i\\pi}+1=0${'$'} |

            ```kotlin
            val answer = 42
            ```

            ${'$'}${'$'}\\int_0^1 x^2 dx${'$'}${'$'}
        """.trimIndent()
        val prepared = StreamBlockParser.prepareMessage(
            content = markdown,
            messageId = "full-sample",
            contentVersion = 4L,
        )

        val state = parseMarkdown(prepared.markdown)

        assertTrue(state is State.Success)
        assertEquals(prepared.markdown, (state as State.Success).content)
        assertEquals(2, prepared.formulas.size)
    }
}
