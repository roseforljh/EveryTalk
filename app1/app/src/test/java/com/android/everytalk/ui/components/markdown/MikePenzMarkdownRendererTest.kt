package com.android.everytalk.ui.components.markdown

import androidx.compose.foundation.relocation.BringIntoViewRequester
import com.android.everytalk.ui.components.streaming.BLOCK_FORMULA_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.DETAILS_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.DetailsRequest
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
    fun `脚注导航目标只识别预处理器生成的内部链接`() {
        val referenceParagraph =
            "正文[¹](${footnoteDefinitionUri(1, 2)})，字面量 $FOOTNOTE_DEFINITION_SCHEME" +
                "2 不应注册。"
        val definitionList =
            "[¹](${footnoteReferenceUri(1)}) 第一项\n\n" +
                "[²](${footnoteReferenceUri(2)}) 第二项"

        assertEquals(
            setOf(footnoteReferenceUri(1, 2)),
            footnoteReferenceTargets(referenceParagraph),
        )
        assertEquals(
            setOf(footnoteDefinitionUri(1), footnoteDefinitionUri(2)),
            footnoteDefinitionTargets(definitionList),
        )
        assertTrue(
            footnoteTargets(
                "`[¹](${footnoteDefinitionUri(9, 1)})` " +
                    "[普通链接](${footnoteDefinitionUri(9, 1)})"
            ).isEmpty()
        )
    }

    @Test
    fun `脚注返回入口跟随最近一次点击的重复引用`() {
        val navigation = FootnoteNavigationState()
        val definition = BringIntoViewRequester()
        val firstReference = BringIntoViewRequester()
        val secondReference = BringIntoViewRequester()
        val collapsedDetailsFallback = BringIntoViewRequester()
        navigation.register(footnoteDefinitionUri(1), definition)
        navigation.register(footnoteReferenceUri(1, 1), firstReference)
        navigation.register(
            uri = footnoteReferenceUri(1, 2),
            requester = collapsedDetailsFallback,
            priority = 0,
        )
        navigation.register(footnoteReferenceUri(1, 2), secondReference)

        assertSame(definition, navigation.requesterFor(footnoteDefinitionUri(1, 2)))
        assertSame(secondReference, navigation.requesterFor(footnoteReferenceUri(1)))

        navigation.unregister(footnoteReferenceUri(1, 2), secondReference)
        assertSame(collapsedDetailsFallback, navigation.requesterFor(footnoteReferenceUri(1)))

        navigation.unregister(footnoteReferenceUri(1, 2), collapsedDetailsFallback)
        assertSame(firstReference, navigation.requesterFor(footnoteReferenceUri(1)))
    }

    @Test
    fun `收起的外层details包含内层脚注回跳目标`() {
        val outerId = "a".repeat(64)
        val innerId = "b".repeat(64)
        val inner = DetailsRequest(
            id = innerId,
            summary = "内层",
            markdown = "正文[¹](${footnoteDefinitionUri(1, 1)})",
            contentVersion = 25L,
        )
        val outer = DetailsRequest(
            id = outerId,
            summary = "外层",
            markdown = """
                ```$DETAILS_FENCE_LANGUAGE
                $innerId
                ```
            """.trimIndent(),
            contentVersion = 25L,
        )

        assertEquals(
            setOf(footnoteReferenceUri(1, 1)),
            detailsSubtreeFootnoteReferenceTargets(
                root = outer,
                detailsById = mapOf(outerId to outer, innerId to inner),
            ),
        )
    }

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
    fun `details必须同时通过内部语言ID映射和版本校验`() {
        val id = "d".repeat(64)
        val details = DetailsRequest(
            id = id,
            summary = "详情",
            markdown = "正文",
            contentVersion = 8L,
        )
        val prepared = PreparedMessage(
            markdown = "",
            formulas = emptyMap(),
            hasPendingFormula = false,
            contentVersion = 8L,
            details = mapOf(id to details),
        )

        assertSame(details, resolveDetailsRequest(DETAILS_FENCE_LANGUAGE, id, prepared))
        assertNull(resolveDetailsRequest("kotlin", id, prepared))
        assertNull(resolveDetailsRequest(DETAILS_FENCE_LANGUAGE, "not-an-id", prepared))
        assertNull(
            resolveDetailsRequest(
                DETAILS_FENCE_LANGUAGE,
                id,
                prepared.copy(contentVersion = 9L),
            )
        )
    }

    @Test
    fun `每层details只提交当前Markdown实际引用的公式`() {
        val outerId = "a".repeat(64)
        val summaryId = "b".repeat(64)
        val bodyId = "c".repeat(64)
        val detailsAssetId = "d".repeat(64)
        val formulas = listOf(outerId, summaryId, bodyId).associateWith { id ->
            FormulaRequest(
                id = id,
                latex = id.first().toString(),
                displayMode = FormulaDisplayMode.INLINE,
                contentVersion = 10L,
            )
        }
        val details = DetailsRequest(
            id = detailsAssetId,
            summary = "![math]($INLINE_FORMULA_SCHEME$summaryId)",
            markdown = "![math]($INLINE_FORMULA_SCHEME$bodyId)",
            contentVersion = 10L,
        )
        val prepared = PreparedMessage(
            markdown = """
                ![math]($INLINE_FORMULA_SCHEME$outerId)

                ```$DETAILS_FENCE_LANGUAGE
                $detailsAssetId
                ```
            """.trimIndent(),
            formulas = formulas,
            hasPendingFormula = false,
            contentVersion = 10L,
            details = mapOf(detailsAssetId to details),
        )

        assertEquals(setOf(outerId, summaryId), resolveVisibleFormulaRequests(prepared).keys)
        assertEquals(
            setOf(bodyId),
            resolveVisibleFormulaRequests(
                prepared.copy(markdown = details.markdown)
            ).keys,
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
