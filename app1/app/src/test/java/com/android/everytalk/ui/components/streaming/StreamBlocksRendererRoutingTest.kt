package com.android.everytalk.ui.components.streaming

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamBlocksRendererRoutingTest {

    @Test
    fun `stream blocks renderer should not use markdown segment fallback`() {
        val source = streamBlocksRendererSource()

        assertFalse(source.contains("SegmentMarkdown("))
    }

    @Test
    fun `unified markdown renderer has no alternate markdown engine`() {
        val source = streamBlocksRendererSource()

        assertTrue(source.contains("fun UnifiedMarkdownRenderer("))
        assertTrue(source.contains("parseUnifiedStreamingMarkdownBlocks(markdown, contentKey)"))
        assertFalse(source.contains("import com.android.everytalk.ui.components.markdown.MarkdownRenderer"))
        assertFalse(source.contains("FullMarkdownFallbackSegment("))
        assertFalse(source.contains("RenderSegment"))
    }

    @Test
    fun `unified parser keeps unsupported markdown in compose paragraph`() {
        val markdown = "A &InvisibleTimes; B"

        assertNull(parseNativeStreamingMarkdownBlocks(markdown, "strict"))
        val blocks = parseUnifiedStreamingMarkdownBlocks(markdown, "unified")

        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals(markdown, blocks.single().text)
    }

    @Test
    fun `production markdown entry points only call unified renderer`() {
        val entryPoints = listOf(
            "com/android/everytalk/ui/components/coordinator/ContentCoordinator.kt",
            "com/android/everytalk/ui/screens/MainScreen/chat/text/ui/ChatMessagesList.kt",
            "com/android/everytalk/ui/screens/ImageGeneration/ImageGenerationMessagesList.kt",
            "com/android/everytalk/ui/screens/BubbleMain/Main/BubbleContentTypes.kt",
        )

        entryPoints.forEach { path ->
            val source = mainSource(path)
            assertTrue(path, source.contains("UnifiedMarkdownRenderer("))
            assertFalse(path, Regex("(?m)^\\s*MarkdownRenderer\\(").containsMatchIn(source))
            assertFalse(path, Regex("(?m)^\\s*StreamBlocksRenderer\\(").containsMatchIn(source))
        }

        val renderStateSource = mainSource(
            "com/android/everytalk/ui/components/streaming/StreamingRenderState.kt"
        )
        assertTrue(renderStateSource.contains("parseUnifiedStreamingMarkdownBlocks(content, messageId)"))
        assertFalse(renderStateSource.contains("parseNativeStreamingMarkdownBlocks(content, messageId)"))
    }

    @Test
    fun `stream blocks renderer should not call enhanced markdown text directly`() {
        val source = streamBlocksRendererSource()

        assertFalse(source.contains("EnhancedMarkdownText("))
    }

    @Test
    fun `native markdown code block uses chatgpt wr7 style renderer`() {
        val source = streamBlocksRendererSource()

        assertTrue(source.contains("ChatGptMarkdownCodeBlockSegment("))
        assertTrue(source.contains("CHATGPT_CODE_BLOCK_BACKGROUND_HEX = 0xFFCCCCCC"))
        assertTrue(source.contains("CHATGPT_CODE_BLOCK_BACKGROUND_ALPHA = 0.5f"))
        assertTrue(source.contains("CHATGPT_CODE_BLOCK_PADDING_DP = 16f"))
        assertTrue(source.contains("fontFamily = FontFamily.Monospace"))
        assertFalse(source.contains("NativeStreamingMarkdownBlockType.CodeBlock -> {\n                        CodeBlockCardSegment("))
    }

    @Test
    fun `native heading renders inline markdown content through inline renderer`() {
        val source = streamBlocksRendererSource()
        val headingSource = source
            .substringAfter("private fun NativeHeadingText(")
            .substringBefore("@Composable\nprivate fun NativeBlockQuote(")

        assertTrue(headingSource.contains("NativeInlineText("))
        assertFalse(headingSource.contains("androidx.compose.material3.Text("))
    }

    @Test
    fun `plain text uses compose text path`() {
        val path = resolveStreamingMarkdownRenderPath("plain text without markdown")

        assertEquals(StreamingMarkdownRenderPath.PlainText, path)
    }

    @Test
    fun `rendered math block uses native latex render path`() {
        val block = StreamBlock.MathBlock(
            stableId = "math-1",
            text = "${'$'}${'$'}x^2${'$'}${'$'}",
            start = 0,
            endExclusive = 7,
            state = MathBlockState.RENDERED,
        )

        assertEquals(StreamMathRenderPath.NativeLatex, resolveStreamMathRenderPath(block))
    }

    @Test
    fun `raw math block keeps raw text render path`() {
        val block = StreamBlock.MathBlock(
            stableId = "math-raw",
            text = "${'$'}${'$'}x^2",
            start = 0,
            endExclusive = 5,
            state = MathBlockState.RAW,
        )

        assertEquals(StreamMathRenderPath.RawText, resolveStreamMathRenderPath(block))
    }

    @Test
    fun `simple inline dollar math symbols use compose block markdown path`() {
        val markdown = "公式里的 ${'$'}\\hbar${'$'} 和 ${'$'}\\nabla^2${'$'} 可见"
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-inline-simple-math")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("公式里的 ℏ 和 ∇² 可见", blocks.single().text)
    }

    @Test
    fun `simple escaped inline math symbols use compose block markdown path`() {
        val markdown = """公式里的 \(\partial\) 和 \(\nabla\) 可见"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-escaped-inline-simple-math")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("公式里的 ∂ 和 ∇ 可见", blocks.single().text)
    }

    @Test
    fun `native inline parts preserve inline math token instead of merging into markdown text`() {
        val parts = requireNotNull(buildNativeInlinePartsForText("公式 ${'$'}x+1${'$'} 成立"))

        assertEquals(3, parts.size)
        assertEquals("公式 ", (parts[0] as InlineRenderPart.Text).text)
        assertEquals("${'$'}x+1${'$'}", (parts[1] as InlineRenderPart.Math).block.text)
        assertEquals(" 成立", (parts[2] as InlineRenderPart.Text).text)
    }

    @Test
    fun `inline parts text model uses compose placeholder for complex math token`() {
        val markdown = "公式 ${'$'}\\frac{a}{b}${'$'} 仍交给完整数学渲染"
        val parts = requireNotNull(buildNativeInlinePartsForText(markdown))

        val model = buildInlinePartsTextModel(
            parts = parts,
            baseColor = Color.Black,
            codeBackground = Color.Transparent,
            codeColor = Color.Black,
            codeFontSize = 14.sp,
        )

        assertEquals("公式 � 仍交给完整数学渲染", model.annotatedText.text)
        assertEquals(1, model.mathPlaceholders.size)
        assertEquals("\\frac{a}{b}", model.mathPlaceholders.single().latex)
    }

    @Test
    fun `native paragraph inline math builds compose placeholder parts`() {
        val markdown = "公式 ${'$'}\\frac{a}{b}${'$'} 仍交给完整数学渲染"
        val parts = requireNotNull(buildNativeInlinePartsForText(markdown))

        val model = buildInlinePartsTextModel(
            parts = parts,
            baseColor = Color.Black,
            codeBackground = Color.Transparent,
            codeColor = Color.Black,
            codeFontSize = 14.sp,
        )

        assertEquals("公式 � 仍交给完整数学渲染", model.annotatedText.text)
        assertEquals(1, model.mathPlaceholders.size)
        assertEquals("\\frac{a}{b}", model.mathPlaceholders.single().latex)
    }

    @Test
    fun `complex inline math full text path uses native paragraph when no stream blocks are available`() {
        val markdown = "公式 ${'$'}\\frac{a}{b}${'$'} 仍交给完整数学渲染"
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-complex-inline-math")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals(markdown, blocks.single().text)
    }

    @Test
    fun `single currency dollar does not force full markdown path`() {
        val markdown = "价格是 ${'$'}30 每月"

        assertEquals(StreamingMarkdownRenderPath.PlainText, resolveStreamingMarkdownRenderPath(markdown))
        assertEquals(markdown, parseNativeStreamingMarkdownBlocks(markdown, "msg-currency")?.single()?.text)
    }

    @Test
    fun `multiple currency dollars do not force full markdown path`() {
        val markdown = "Free ${'$'}0, Pro ${'$'}20, credit ${'$'}30"

        assertEquals(StreamingMarkdownRenderPath.PlainText, resolveStreamingMarkdownRenderPath(markdown))
        assertEquals(markdown, parseNativeStreamingMarkdownBlocks(markdown, "msg-multi-currency")?.single()?.text)
    }

    @Test
    fun `compact body text style keeps assistant prose start aligned`() {
        val style = compactBodyTextStyle(
            style = TextStyle(fontSize = 16.sp),
            color = Color.Black,
        )

        assertEquals(TextAlign.Start, style.textAlign)
    }

    @Test
    fun `simple bold uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("This is **important** content")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `heading uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("# Heading")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `horizontal rule uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("before\n---\nafter")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `list uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("- first\n- second")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `quote uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("> quote")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native block quote preserves child block markdown like chatgpt content composable`() {
        val markdown = "> ## 引用标题\n> - 第一项\n> - **第二项**"
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-quote-children")

        requireNotNull(blocks)
        val quote = blocks.single()
        assertEquals(NativeStreamingMarkdownBlockType.BlockQuote, quote.type)

        val children = requireNotNull(
            quote.readNullableFieldForTest<List<NativeStreamingMarkdownBlock>>("children")
        )
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Heading,
                NativeStreamingMarkdownBlockType.ListBlock,
            ),
            children.map { it.type },
        )
        assertEquals("引用标题", children[0].text)
        assertEquals(listOf("第一项", "**第二项**"), children[1].items)
    }

    @Test
    fun `table uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("| A | B |\n|---|---|\n| 1 | 2 |")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `table with html line breaks uses compose block markdown path`() {
        val markdown = "| A | B |\n|---|---|\n| 1<br>2 | 3 |"
        val path = resolveStreamingMarkdownRenderPath(markdown)
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-table-br")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Table, blocks.single().type)
    }

    @Test
    fun `table with standalone image cell uses compose block markdown path`() {
        val markdown = "| 图 | 说明 |\n|---|---|\n| ![预览](https://example.com/a.png) | ok |"
        val path = resolveStreamingMarkdownRenderPath(markdown)
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-table-image")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Table, blocks.single().type)
    }

    @Test
    fun `table with mixed prose image cell uses compose block markdown path`() {
        val markdown = "| 图 | 说明 |\n|---|---|\n| 前缀 ![预览](https://example.com/a.png) | ok |"
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-table-mixed-image")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Table, blocks.single().type)
    }

    @Test
    fun `native parser handles markdown table block`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "before\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\nafter",
            segmentId = "msg-table",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.Table,
                NativeStreamingMarkdownBlockType.Paragraph,
            ),
            blocks.map { it.type },
        )
        assertEquals(
            listOf("| A | B |", "|---|---|", "| 1 | 2 |"),
            blocks[1].tableLines,
        )
    }

    @Test
    fun `stream renderer keeps complex inline math embedded in table row`() {
        val input = """
            | Metric | LaTeX | Edge |
            | :--- | :---: | :--- |
            | **Set A** | ${'$'}\sum_{i=1}^{n} x_i^2 \ge \frac{(\sum x_i)^2}{n}${'$'} | `\|` pipe escape |
        """.trimIndent()

        val blocks = parseUnifiedStreamingMarkdownBlocks(input, "msg-table")

        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Table, blocks.single().type)
        assertEquals(input.lines(), blocks.single().tableLines)
    }

    @Test
    fun `setext heading uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Heading\n===")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `ordered list with parenthesis uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("1) first")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `span html tag uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("""<span class="note">content</span>""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `styled span html tag uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath(
            """A <span style="color:#ff0000; background-color:#00ff00; text-decoration:line-through">red</span> B"""
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `styled span html tag with android text decoration first token uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath(
            """A <span style="text-decoration:line-through underline">red</span> B"""
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `styled span html tag with android standard named colors uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath(
            """A <span style="color:lime; background-color:silver">tone</span> B"""
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `styled span html tag with android numeric colors uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath(
            """A <span style="color:0x00ff0000; background-color:65280">tone</span> B"""
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `font color html tag uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("""A <font color="#ff0000">red</font> B""")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `gpt html text span tags use compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("""A <tt>code</tt> <big>large</big> <small>tiny</small> B""")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `gpt annotation html tag uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath(
            """A <annotation openai_meta="tool">annotated</annotation> B"""
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `native parser normalizes span html tag`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """A <span class="note">content</span> block""",
            segmentId = "msg-span-html",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("A content block", blocks.single().text)
    }

    @Test
    fun `paragraph html tags use compose block markdown path`() {
        val markdown = """<p>第一段</p><p class="next">第二段</p>"""

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
    }

    @Test
    fun `native parser normalizes paragraph html tags into markdown paragraphs`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """<p>第一段</p><p class="next">第二段</p>""",
            segmentId = "msg-p-html",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.Paragraph,
            ),
            blocks.map { it.type },
        )
        assertEquals("第一段", blocks[0].text)
        assertEquals("第二段", blocks[1].text)
    }

    @Test
    fun `native parser preserves supported paragraph html style as inline span`() {
        val markdown = """<p style="color:#ff0000; background-color:#00ff00">red <strong>bold</strong></p>"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-p-style-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals(
            """<span style="color:#ff0000; background-color:#00ff00">red **bold**</span>""",
            blocks.single().text,
        )
    }

    @Test
    fun `div html tag uses compose block markdown path and preserves inline markdown`() {
        val markdown = """<div class="note">A <strong>bold</strong> item</div>"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-div-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("A **bold** item", blocks.single().text)
    }

    @Test
    fun `native parser strips div html style because android html does not apply css style to div`() {
        val markdown = """<div style="text-decoration:line-through">A <em>marked</em> item</div>"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-div-style-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("A *marked* item", blocks.single().text)
    }

    @Test
    fun `native parser preserves paragraph text align style like android html renderer`() {
        val markdown = """<p style="text-align:center">Centered **text**</p>"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-p-align-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Centered **text**", blocks.single().text)
        assertEquals(TextAlign.Center, blocks.single().textAlign)
    }

    @Test
    fun `native parser preserves div text align style like android html renderer`() {
        val markdown = """<div style="text-align:end">Right side</div>"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-div-align-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Right side", blocks.single().text)
        assertEquals(TextAlign.End, blocks.single().textAlign)
    }

    @Test
    fun `unordered html list uses compose block markdown path`() {
        val markdown = """<ul><li>第一项</li><li><strong>第二项</strong></li></ul>"""

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
    }

    @Test
    fun `native parser normalizes unordered html list into native list block`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """<ul><li>第一项</li><li><strong>第二项</strong></li></ul>""",
            segmentId = "msg-ul-html",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.ListBlock, blocks.single().type)
        assertEquals(
            listOf(
                NativeStreamingListItem(text = "第一项"),
                NativeStreamingListItem(text = "**第二项**"),
            ),
            blocks.single().listItems,
        )
    }

    @Test
    fun `native parser preserves html list item text align style like android html renderer`() {
        val markdown = """<ul><li style="text-align:center">居中项</li><li>普通项</li></ul>"""
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-li-align-html",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.ListBlock, blocks.single().type)
        assertEquals("居中项", blocks.single().listItems[0].text)
        assertEquals(TextAlign.Center, blocks.single().listItems[0].textAlign)
        assertEquals("普通项", blocks.single().listItems[1].text)
        assertEquals(TextAlign.Start, blocks.single().listItems[1].textAlign)
    }

    @Test
    fun `native parser preserves html list item css spans like android html renderer`() {
        val markdown = """<ul><li style="color:#ff0000; background-color:#00ff00; text-decoration:line-through">彩色项</li></ul>"""
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-li-css-html",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.ListBlock, blocks.single().type)
        assertEquals(
            """<span style="color:#ff0000; background-color:#00ff00; text-decoration:line-through">彩色项</span>""",
            blocks.single().listItems.single().text,
        )
    }

    @Test
    fun `native parser preserves nested unordered html list levels like gpt html handler`() {
        val markdown = """<ul><li>父项<ul><li>子项</li></ul></li><li>同级</li></ul>"""
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-nested-ul-html",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.ListBlock, blocks.single().type)
        assertEquals(
            listOf(
                NativeStreamingListItem(text = "父项"),
                NativeStreamingListItem(text = "子项", level = 1),
                NativeStreamingListItem(text = "同级"),
            ),
            blocks.single().listItems,
        )
    }

    @Test
    fun `ordered html list normalizes into numbered native list block`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """<ol><li>第一项</li><li>第二项</li></ol>""",
            segmentId = "msg-ol-html",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.ListBlock, blocks.single().type)
        assertEquals(
            listOf(
                NativeStreamingListItem(text = "第一项", ordered = true, number = 1),
                NativeStreamingListItem(text = "第二项", ordered = true, number = 2),
            ),
            blocks.single().listItems,
        )
    }

    @Test
    fun `ordered html list preserves start attribute in native list block`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """<ol start="3"><li>第三项</li><li>第四项</li></ol>""",
            segmentId = "msg-ol-html-start",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.ListBlock, blocks.single().type)
        assertEquals(
            listOf(
                NativeStreamingListItem(text = "第三项", ordered = true, number = 3),
                NativeStreamingListItem(text = "第四项", ordered = true, number = 4),
            ),
            blocks.single().listItems,
        )
    }

    @Test
    fun `ordered html list preserves li value attribute in native list block`() {
        val markdown = """<ol start="3"><li>第三项</li><li value="10">第十项</li><li>第十一项</li></ol>"""
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-ol-html-li-value",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.ListBlock, blocks.single().type)
        assertEquals(
            listOf(
                NativeStreamingListItem(text = "第三项", ordered = true, number = 3),
                NativeStreamingListItem(text = "第十项", ordered = true, number = 10),
                NativeStreamingListItem(text = "第十一项", ordered = true, number = 11),
            ),
            blocks.single().listItems,
        )
    }

    @Test
    fun `html heading tag uses compose block markdown path`() {
        val markdown = """<h2 class="title">章节标题</h2>"""

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
    }

    @Test
    fun `native parser normalizes html heading tag into native heading`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """<h2 class="title">章节标题</h2>""",
            segmentId = "msg-h2-html",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Heading, blocks.single().type)
        assertEquals(2, blocks.single().level)
        assertEquals("章节标题", blocks.single().text)
    }

    @Test
    fun `native parser preserves heading text align style like android html renderer`() {
        val markdown = """<h2 style="text-align:center">Section <strong>Title</strong></h2>"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-heading-align-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Heading, blocks.single().type)
        assertEquals(2, blocks.single().level)
        assertEquals("Section **Title**", blocks.single().text)
        assertEquals(TextAlign.Center, blocks.single().textAlign)
    }

    @Test
    fun `html blockquote tag uses compose block markdown path and preserves inline markdown`() {
        val markdown = """<blockquote>A <strong>bold</strong> quote</blockquote>"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-blockquote-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.BlockQuote, blocks.single().type)
        assertEquals("A **bold** quote", blocks.single().text)
    }

    @Test
    fun `native parser preserves blockquote text align style like android html renderer`() {
        val markdown = """<blockquote style="text-align:center">A <strong>bold</strong> quote</blockquote>"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-blockquote-align-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.BlockQuote, blocks.single().type)
        assertEquals("A **bold** quote", blocks.single().text)
        assertEquals(TextAlign.Center, blocks.single().textAlign)
    }

    @Test
    fun `html img tag normalizes into native image block`() {
        val markdown = """<img src="https://example.com/a.png" alt="diagram">"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-img-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Image, blocks.single().type)
        assertEquals("diagram", blocks.single().imageAlt)
        assertEquals("https://example.com/a.png", blocks.single().imageUrl)
    }

    @Test
    fun `html img tag with unquoted self closing src keeps url exact`() {
        val markdown = """<img src=https://example.com/a.png/>"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-img-html-unquoted-self-closing")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Image, blocks.single().type)
        assertEquals("https://example.com/a.png", blocks.single().imageUrl)
    }

    @Test
    fun `paragraph html img tag stays inline inside native paragraph`() {
        val markdown = """before <img alt='diagram' src='https://example.com/a.png' /> after"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-inline-img-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("before ![diagram](https://example.com/a.png) after", blocks.single().text)
    }

    @Test
    fun `html horizontal rule tag normalizes into native horizontal rule`() {
        val markdown = """before<hr class="sep" />after"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-hr-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.HorizontalRule,
                NativeStreamingMarkdownBlockType.Paragraph,
            ),
            blocks.map { it.type },
        )
        assertEquals("before", blocks[0].text)
        assertEquals("after", blocks[2].text)
    }

    @Test
    fun `simple inline html tags use compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("A <strong>bold</strong> and <code>x</code>")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser normalizes simple inline html tags`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "A <strong>bold</strong> and <code>x</code>",
            segmentId = "msg-inline-html",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("A **bold** and `x`", blocks.single().text)
    }

    @Test
    fun `native parser normalizes gpt html italic and strike aliases`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "A <cite>cite</cite> <dfn>term</dfn> <strike>old</strike>",
            segmentId = "msg-html-aliases",
        )

        assertEquals(
            StreamingMarkdownRenderPath.ComposeBlockMarkdown,
            resolveStreamingMarkdownRenderPath("A <cite>cite</cite> <dfn>term</dfn> <strike>old</strike>"),
        )
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("A *cite* *term* ~~old~~", blocks.single().text)
    }

    @Test
    fun `simple inline html tags with attributes use compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("""A <strong class="mark">bold</strong> and <code data-lang="kotlin">x</code>""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `underline html tag uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("""A <u>marked</u> word""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `mark html tag uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("""A <mark>marked</mark> word""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `sup html tag uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("""x<sup>2</sup> value""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `sub html tag uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("""H<sub>2</sub>O""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `kbd html tag uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("""Press <kbd>Ctrl</kbd> + <kbd>C</kbd>""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser normalizes underline html tag`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """A <u class="mark">marked</u> word""",
            segmentId = "msg-underline-html",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("A ++marked++ word", blocks.single().text)
    }

    @Test
    fun `native parser normalizes mark html tag`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """A <mark class="hit">marked</mark> word""",
            segmentId = "msg-mark-html",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("A ==marked== word", blocks.single().text)
    }

    @Test
    fun `native parser normalizes sup html tag`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """x<sup class="power">2</sup> value""",
            segmentId = "msg-sup-html",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("x^^2^^ value", blocks.single().text)
    }

    @Test
    fun `native parser normalizes sub html tag`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """H<sub class="chem">2</sub>O""",
            segmentId = "msg-sub-html",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("H,,2,,O", blocks.single().text)
    }

    @Test
    fun `native parser normalizes kbd html tag`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """Press <kbd class="key">Ctrl</kbd> + <kbd>C</kbd>""",
            segmentId = "msg-kbd-html",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Press `Ctrl` + `C`", blocks.single().text)
    }

    @Test
    fun `native parser normalizes simple inline html tags with attributes`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """A <strong class="mark">bold</strong> and <code data-lang="kotlin">x</code>""",
            segmentId = "msg-inline-html-attrs",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("A **bold** and `x`", blocks.single().text)
    }

    @Test
    fun `html anchor link uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("""Read <a href="https://example.com/docs">docs</a> now""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser normalizes html anchor link`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """Read <a class="primary" href="https://example.com/docs">docs</a> now""",
            segmentId = "msg-anchor-html",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Read [docs](https://example.com/docs) now", blocks.single().text)
    }

    @Test
    fun `html anchor link with unquoted href uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("""Read <a href=https://example.com/docs>docs</a> now""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser normalizes html anchor link with unquoted href`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """Read <a class=primary href=https://example.com/docs>docs</a> now""",
            segmentId = "msg-anchor-html-unquoted",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Read [docs](https://example.com/docs) now", blocks.single().text)
    }

    @Test
    fun `html relative anchor link uses compose block markdown path like gpt url span`() {
        val path = resolveStreamingMarkdownRenderPath("""Read <a href="/docs/start">docs</a> now""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser normalizes html relative anchor link like gpt url span`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """Read <a class="primary" href="/docs/start">docs</a> now""",
            segmentId = "msg-anchor-html-relative",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Read [docs](/docs/start) now", blocks.single().text)
    }

    @Test
    fun `html mailto anchor link uses compose block markdown path like gpt url span`() {
        val path = resolveStreamingMarkdownRenderPath("""Email <a href="mailto:team@example.com">team</a> now""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser normalizes html mailto anchor link like gpt url span`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """Email <a href="mailto:team@example.com">team</a> now""",
            segmentId = "msg-anchor-html-mailto",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Email [team](mailto:team@example.com) now", blocks.single().text)
    }

    @Test
    fun `html tel anchor link uses compose block markdown path like gpt url span`() {
        val path = resolveStreamingMarkdownRenderPath("""Call <a href="tel:+123456789">support</a> now""")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser normalizes html tel anchor link like gpt url span`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """Call <a href="tel:+123456789">support</a> now""",
            segmentId = "msg-anchor-html-tel",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Call [support](tel:+123456789) now", blocks.single().text)
    }

    @Test
    fun `html entity uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("A &amp; B")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `common named html entities use compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("A &ndash; B &hellip;")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `list separator html entities use compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Alpha &middot; Beta &bull; Gamma")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `typographic and math html entities use compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("&ldquo;A&rdquo; &minus; &times; &divide; &rarr; &larr;")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `extended typographic and math html entities use compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("&lsquo;A&rsquo; &plusmn; &deg; &le; &ge; &ne;")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `greek named html entities use compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("&alpha; &beta; &gamma; &Delta; &Omega;")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `native parser keeps paragraph with html entity`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "A &amp; B",
            segmentId = "msg-entity",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("A &amp; B", blocks.single().text)
    }

    @Test
    fun `reference link with definition uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Read [docs][doc]\n\n[doc]: https://example.com")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser resolves reference link definitions`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "Read [docs][doc]\n\n[doc]: https://example.com",
            segmentId = "msg-ref-link",
        )

        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Read [docs](https://example.com)", blocks.single().text)
    }

    @Test
    fun `native parser strips angle destination from reference link definitions`() {
        val markdown = """
            Read [docs][doc]

            [doc]: <https://example.com/docs> "文档"
        """.trimIndent()
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-angle-ref-link",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Read [docs](https://example.com/docs)", blocks.single().text)
    }

    @Test
    fun `native parser drops multiline reference link title definition`() {
        val markdown = """
            Read [docs][doc]

            [doc]: https://example.com/docs
              "文档"
        """.trimIndent()
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-multiline-title-ref-link",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Read [docs](https://example.com/docs)", blocks.single().text)
    }

    @Test
    fun `native parser resolves shortcut reference link definitions`() {
        val markdown = "Read [docs]\n\n[docs]: https://example.com"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-shortcut-ref-link",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Read [docs](https://example.com)", blocks.single().text)
    }

    @Test
    fun `native parser normalizes reference labels by case and internal whitespace`() {
        val markdown = "Read [my docs]\n\n[My   Docs]: https://example.com"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-ref-label-whitespace",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Read [my docs](https://example.com)", blocks.single().text)
    }

    @Test
    fun `reference link without definition stays native literal text`() {
        val markdown = "Read [docs][missing]"
        val path = resolveStreamingMarkdownRenderPath(markdown)
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-missing-ref")

        assertEquals(StreamingMarkdownRenderPath.PlainText, path)
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals(markdown, blocks.single().text)
    }

    @Test
    fun `unknown reference link stays literal while known reference link resolves natively`() {
        val markdown = "Read [docs][doc] and [later][missing]\n\n[doc]: https://example.com"
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-mixed-ref")

        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Read [docs](https://example.com) and [later][missing]", blocks.single().text)
    }

    @Test
    fun `standard markdown link uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Read [docs](https://example.com) now")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `email autolink uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Contact <team@example.com> now")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `html like text inside inline code uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Use `<span>` literally")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `unsupported html entity inside inline code uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Use `&InvisibleTimes;` literally")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `image markdown inside inline code uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Use `![diagram](https://example.com/a.png)` literally")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `dollar math syntax inside inline code uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Use `${'$'}x${'$'}` literally")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `simple dollar math symbol inside inline code is not normalized into block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Use `${'$'}\\partial${'$'}` literally")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `standalone markdown image uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("![diagram](https://example.com/a.png)")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser handles standalone markdown image`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "before\n\n![diagram](https://example.com/a.png)\n\nafter",
            segmentId = "msg-image",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.Image,
                NativeStreamingMarkdownBlockType.Paragraph,
            ),
            blocks.map { it.type },
        )
        assertEquals("diagram", blocks[1].imageAlt)
        assertEquals("https://example.com/a.png", blocks[1].imageUrl)
    }

    @Test
    fun `standalone markdown image with title uses native image block`() {
        val path = resolveStreamingMarkdownRenderPath("""![diagram](https://example.com/a.png "Preview")""")
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """![diagram](https://example.com/a.png "Preview")""",
            segmentId = "msg-image-title",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Image, blocks.single().type)
        assertEquals("diagram", blocks.single().imageAlt)
        assertEquals("https://example.com/a.png", blocks.single().imageUrl)
    }

    @Test
    fun `standalone markdown image with parenthesized url uses native image block`() {
        val markdown = """![diagram](https://example.com/wiki/A_(B).png)"""
        val path = resolveStreamingMarkdownRenderPath(markdown)
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-image-parentheses",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Image, blocks.single().type)
        assertEquals("diagram", blocks.single().imageAlt)
        assertEquals("https://example.com/wiki/A_(B).png", blocks.single().imageUrl)
    }

    @Test
    fun `standalone markdown image with angle bracket destination uses native image block`() {
        val markdown = """![diagram](<https://example.com/wiki/A_(B).png>)"""
        val path = resolveStreamingMarkdownRenderPath(markdown)
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-image-angle-destination",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Image, blocks.single().type)
        assertEquals("diagram", blocks.single().imageAlt)
        assertEquals("https://example.com/wiki/A_(B).png", blocks.single().imageUrl)
    }

    @Test
    fun `standalone image reference with definition uses native image block`() {
        val markdown = "![diagram][img]\n\n[img]: https://example.com/a.png"
        val path = resolveStreamingMarkdownRenderPath(markdown)
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-image-reference",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Image, blocks.single().type)
        assertEquals("diagram", blocks.single().imageAlt)
        assertEquals("https://example.com/a.png", blocks.single().imageUrl)
    }

    @Test
    fun `paragraph image markdown uses compose block markdown path`() {
        val markdown = "before ![diagram](https://example.com/a.png) after"
        val path = resolveStreamingMarkdownRenderPath(markdown)

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser keeps paragraph image markdown as inline paragraph`() {
        val markdown = "before ![diagram](https://example.com/a.png) after"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-inline-image",
        )

        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals(markdown, blocks.single().text)
    }

    @Test
    fun `native inline parts model builds image placeholder from markdown image`() {
        val parts = buildNativeInlinePartsForText("before ![diagram](https://example.com/a.png) after")

        requireNotNull(parts)
        val model = buildInlinePartsTextModel(
            parts = parts,
            baseColor = Color.Black,
            codeBackground = Color.Transparent,
            codeColor = Color.Black,
            codeFontSize = 14.sp,
        )
        val imagePlaceholders = requireNotNull(
            model.readNullableFieldForTest<List<*>>("imagePlaceholders")
        )

        assertEquals("before � after", model.annotatedText.text)
        assertEquals(1, imagePlaceholders.size)
    }

    @Test
    fun `native inline image placeholder preserves image click callback`() {
        val source = streamBlocksRendererSource()
        val inlinePartsSource = source
            .substringAfter("internal fun InlinePartsSegment(")
            .substringBefore("internal fun buildInlinePartsTextModel")

        assertTrue(inlinePartsSource.contains("onImageClick: ((String) -> Unit)?"))
        assertTrue(inlinePartsSource.contains("Modifier.clickable { onImageClick(placeholder.url) }"))
        assertTrue(source.contains("onImageClick = onImageClick"))
    }

    @Test
    fun `native parser keeps paragraph image with parenthesized url as inline paragraph`() {
        val markdown = "before ![diagram](https://example.com/wiki/A_(B).png) after"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-inline-image-parentheses",
        )

        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals(markdown, blocks.single().text)
    }

    @Test
    fun `native parser keeps paragraph with standard markdown link`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "Read [docs](https://example.com) now",
            segmentId = "msg-link",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Read [docs](https://example.com) now", blocks.single().text)
    }

    @Test
    fun `native parser normalizes paragraph soft line break to space like gpt`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "first line\nsecond line",
            segmentId = "msg-soft-break",
        )

        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("first line second line", blocks.single().text)
    }

    @Test
    fun `native parser keeps markdown hard line break as newline`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "first line  \nsecond line",
            segmentId = "msg-hard-break",
        )

        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("first line\nsecond line", blocks.single().text)
    }

    @Test
    fun `html line break in prose becomes native paragraph newline`() {
        val path = resolveStreamingMarkdownRenderPath("first<br>second")
        val blocks = parseNativeStreamingMarkdownBlocks("first<br>second", "msg-br")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("first\nsecond", blocks.single().text)
    }

    @Test
    fun `autolink uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("<https://example.com>")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `bare url uses compose inline markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("Open https://example.com/docs now")

        assertEquals(StreamingMarkdownRenderPath.ComposeInlineMarkdown, path)
    }

    @Test
    fun `native parser keeps paragraph with autolink`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "Open <https://example.com> now",
            segmentId = "msg-autolink",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, blocks.single().type)
        assertEquals("Open <https://example.com> now", blocks.single().text)
    }

    @Test
    fun `html pre code block normalizes into native code block`() {
        val markdown = """<pre><code class="language-kotlin">val x = 1</code></pre>"""
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-pre-code-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.CodeBlock, blocks.single().type)
        assertEquals("kotlin", blocks.single().language)
        assertEquals("val x = 1", blocks.single().code)
    }

    @Test
    fun `html pre code block with surrounding prose splits into native blocks`() {
        val markdown = """
            before

            <pre><code data-lang="js">const x = 1;</code></pre>

            after
        """.trimIndent()
        val blocks = parseNativeStreamingMarkdownBlocks(markdown, "msg-pre-code-prose-html")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.CodeBlock,
                NativeStreamingMarkdownBlockType.Paragraph,
            ),
            blocks.map { it.type },
        )
        assertEquals("before", blocks[0].text)
        assertEquals("js", blocks[1].language)
        assertEquals("const x = 1;", blocks[1].code)
        assertEquals("after", blocks[2].text)
    }

    @Test
    fun `fenced code uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("```kotlin\nval x = 1\n```")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser handles fenced code block`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "before\n\n```kotlin\nval x = 1\n```\n\nafter",
            segmentId = "msg-code",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.CodeBlock,
                NativeStreamingMarkdownBlockType.Paragraph,
            ),
            blocks.map { it.type },
        )
        assertEquals("kotlin", blocks[1].language)
        assertEquals("val x = 1", blocks[1].code)
    }

    @Test
    fun `indented code uses compose block markdown path`() {
        val path = resolveStreamingMarkdownRenderPath("    val x = 1\n    println(x)")

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, path)
    }

    @Test
    fun `native parser handles indented code block`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "before\n\n    val x = 1\n    println(x)\n\nafter",
            segmentId = "msg-indented-code",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.CodeBlock,
                NativeStreamingMarkdownBlockType.Paragraph,
            ),
            blocks.map { it.type },
        )
        assertEquals(null, blocks[1].language)
        assertEquals("val x = 1\nprintln(x)", blocks[1].code)
    }

    @Test
    fun `native parser handles standalone dollar math block`() {
        val markdown = "before\n\n${'$'}${'$'}x+1${'$'}${'$'}\n\nafter"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-native-math-block",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(
            listOf("Paragraph", "MathBlock", "Paragraph"),
            blocks.map { it.type.name },
        )
        assertEquals("${'$'}${'$'}x+1${'$'}${'$'}", blocks[1].text)
    }

    @Test
    fun `native parser handles standalone escaped math block`() {
        val markdown = """before

\[x+1\]

after"""
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-native-escaped-math-block",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(
            listOf("Paragraph", "MathBlock", "Paragraph"),
            blocks.map { it.type.name },
        )
        assertEquals("""\[x+1\]""", blocks[1].text)
    }

    @Test
    fun `native parser splits paragraph around dollar math block`() {
        val markdown = "公式 ${'$'}${'$'}x+1${'$'}${'$'} 成立"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-mixed-dollar-math-block",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(
            listOf("Paragraph", "MathBlock", "Paragraph"),
            blocks.map { it.type.name },
        )
        assertEquals("公式", blocks[0].text)
        assertEquals("${'$'}${'$'}x+1${'$'}${'$'}", blocks[1].text)
        assertEquals("成立", blocks[2].text)
    }

    @Test
    fun `native parser splits paragraph around escaped math block`() {
        val markdown = """公式 \[x+1\] 成立"""
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-mixed-escaped-math-block",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(
            listOf("Paragraph", "MathBlock", "Paragraph"),
            blocks.map { it.type.name },
        )
        assertEquals("公式", blocks[0].text)
        assertEquals("""\[x+1\]""", blocks[1].text)
        assertEquals("成立", blocks[2].text)
    }

    @Test
    fun `native parser handles common non heading block markdown`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "---\n\n- first\n- **second**\n\n> quote",
            segmentId = "msg-1",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.HorizontalRule,
                NativeStreamingMarkdownBlockType.ListBlock,
                NativeStreamingMarkdownBlockType.BlockQuote,
            ),
            blocks.map { it.type },
        )
        assertEquals(listOf("first", "**second**"), blocks[1].items)
        assertEquals(0, blocks.first().start)
        assertTrue(blocks.first().endExclusive > blocks.first().start)
    }

    @Test
    fun `native parser keeps list continuations and nested bullets`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "- **first**\n    continuation\n- **second**\n    - child one\n    - child two",
            segmentId = "msg-list",
        )

        requireNotNull(blocks)
        assertEquals(1, blocks.size)
        assertEquals(NativeStreamingMarkdownBlockType.ListBlock, blocks.single().type)
        assertEquals(
            listOf(
                NativeStreamingListItem(text = "**first** continuation"),
                NativeStreamingListItem(text = "**second**"),
                NativeStreamingListItem(text = "child one", level = 1),
                NativeStreamingListItem(text = "child two", level = 1),
            ),
            blocks.single().listItems,
        )
    }

    @Test
    fun `native parser preserves blank list continuation as child block like chatgpt content composable`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "- parent\n\n    child paragraph\n- next",
            segmentId = "msg-list-child-paragraph",
        )

        requireNotNull(blocks)
        val rows = blocks.single().listItems
        assertEquals("parent", rows[0].text)
        assertEquals("next", rows[1].text)

        val children = requireNotNull(
            rows[0].readNullableFieldForTest<List<NativeStreamingMarkdownBlock>>("children")
        )
        assertEquals(1, children.size)
        assertEquals(NativeStreamingMarkdownBlockType.Paragraph, children.single().type)
        assertEquals("child paragraph", children.single().text)
    }

    @Test
    fun `native list child blocks preserve recursive media and code callbacks like chatgpt content composable`() {
        val source = streamBlocksRendererSource()
        val listRenderer = source
            .substringAfter("private fun NativeListBlock(")
            .substringBefore("internal fun nativeListMarkerText")

        assertTrue(listRenderer.contains("isStreaming: Boolean"))
        assertTrue(listRenderer.contains("onCodePreviewRequested: ((String, String) -> Unit)?"))
        assertTrue(listRenderer.contains("onCodeCopied: (() -> Unit)?"))
        assertTrue(listRenderer.contains("onImageClick: ((String) -> Unit)?"))
        assertTrue(listRenderer.contains("isStreaming = isStreaming"))
        assertTrue(listRenderer.contains("onCodePreviewRequested = onCodePreviewRequested"))
        assertTrue(listRenderer.contains("onCodeCopied = onCodeCopied"))
        assertTrue(listRenderer.contains("onImageClick = onImageClick"))
        assertFalse(listRenderer.contains("isStreaming = false"))
        assertFalse(listRenderer.contains("onCodePreviewRequested = null"))
        assertFalse(listRenderer.contains("onCodeCopied = null"))
        assertFalse(listRenderer.contains("onImageClick = null"))
    }

    @Test
    fun `native parser treats common indented child bullets as nested`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "1. parent\n   * child with three spaces\n2. another\n  - child with two spaces",
            segmentId = "msg-common-list",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingListItem(text = "parent", ordered = true, number = 1),
                NativeStreamingListItem(text = "child with three spaces", level = 1),
                NativeStreamingListItem(text = "another", ordered = true, number = 2),
                NativeStreamingListItem(text = "child with two spaces", level = 1),
            ),
            blocks.single().listItems,
        )
    }

    @Test
    fun `native parser splits paragraphs by blank lines`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = "first paragraph\n\nsecond paragraph\n\nthird paragraph",
            segmentId = "msg-paragraphs",
        )

        requireNotNull(blocks)
        assertEquals(
            listOf(
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.Paragraph,
                NativeStreamingMarkdownBlockType.Paragraph,
            ),
            blocks.map { it.type },
        )
    }

    @Test
    fun `native block spacing uses chatgpt paragraph spacing token`() {
        val paragraph = NativeStreamingMarkdownBlock(
            stableId = "p",
            type = NativeStreamingMarkdownBlockType.Paragraph,
            text = "paragraph",
        )
        val heading = NativeStreamingMarkdownBlock(
            stableId = "h",
            type = NativeStreamingMarkdownBlockType.Heading,
            text = "heading",
            level = 1,
        )
        val rule = NativeStreamingMarkdownBlock(
            stableId = "hr",
            type = NativeStreamingMarkdownBlockType.HorizontalRule,
        )

        assertEquals(8f, ChatMarkdownTextStyle.SPACING_PARAGRAPH_DP, 0.001f)
        assertEquals(8f, ChatMarkdownTextStyle.SPACING_BEFORE_HEADING_DP, 0.001f)
        assertEquals(8.dp, nativeMarkdownBlockSpacingAfter(paragraph, paragraph))
        assertEquals(8.dp, nativeMarkdownBlockSpacingAfter(paragraph, heading))
        assertEquals(4.dp, nativeMarkdownBlockSpacingAfter(heading, paragraph))
        assertEquals(0.dp, nativeMarkdownBlockSpacingAfter(rule, paragraph))
        assertEquals(1f, ChatMarkdownTextStyle.HORIZONTAL_RULE_THICKNESS_DP, 0.001f)
        assertEquals(8f, ChatMarkdownTextStyle.HORIZONTAL_RULE_VERTICAL_PADDING_DP, 0.001f)
        assertEquals(0.2f, ChatMarkdownTextStyle.HORIZONTAL_RULE_COLOR_ALPHA, 0.001f)
    }

    @Test
    fun `native block quote uses chatgpt bar gutter without panel background`() {
        val rendererSource = streamBlocksRendererSource()
        val styleSource = chatMarkdownTextStyleSource()

        assertTrue(styleSource.contains("BLOCK_QUOTE_START_MARGIN_DP = 6f"))
        assertTrue(styleSource.contains("BLOCK_QUOTE_BAR_WIDTH_DP = 3f"))
        assertTrue(styleSource.contains("BLOCK_QUOTE_END_MARGIN_DP = 6f"))
        assertTrue(styleSource.contains("BLOCK_QUOTE_VERTICAL_CONTENT_PADDING_DP = 4f"))
        assertTrue(styleSource.contains("BLOCK_QUOTE_BAR_COLOR_ALPHA = 0.25f"))
        assertTrue(rendererSource.contains("ChatMarkdownTextStyle.BLOCK_QUOTE_START_MARGIN_DP.dp"))
        assertTrue(rendererSource.contains("ChatMarkdownTextStyle.BLOCK_QUOTE_BAR_WIDTH_DP.dp"))
        assertTrue(rendererSource.contains("ChatMarkdownTextStyle.BLOCK_QUOTE_END_MARGIN_DP.dp"))
        assertTrue(rendererSource.contains("ChatMarkdownTextStyle.BLOCK_QUOTE_VERTICAL_CONTENT_PADDING_DP.dp"))
        assertTrue(rendererSource.contains("color.copy(alpha = ChatMarkdownTextStyle.BLOCK_QUOTE_BAR_COLOR_ALPHA)"))
        assertFalse(rendererSource.contains("MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)"))
        assertFalse(rendererSource.contains("color.copy(alpha = 0.88f)"))
    }

    @Test
    fun `native heading text spec follows chatgpt heading style evidence`() {
        val h1 = chatGptHeadingTextSpecForLevel(1)
        val h2 = chatGptHeadingTextSpecForLevel(2)
        val h3 = chatGptHeadingTextSpecForLevel(3)
        val h4 = chatGptHeadingTextSpecForLevel(4)
        val h5 = chatGptHeadingTextSpecForLevel(5)
        val h6 = chatGptHeadingTextSpecForLevel(6)

        assertEquals(26.sp, h1.fontSize)
        assertEquals(24.sp, h1.lineHeight)
        assertEquals(FontWeight.W700, h1.fontWeight)
        assertEquals(1f, h1.readFloatFieldForTest("colorAlpha"), 0.001f)
        assertNull(h1.readNullableFieldForTest<FontStyle>("fontStyle"))
        assertEquals(22.sp, h2.fontSize)
        assertEquals(24.sp, h2.lineHeight)
        assertEquals(FontWeight.W700, h2.fontWeight)
        assertEquals(0.7f, h2.readFloatFieldForTest("colorAlpha"), 0.001f)
        assertNull(h2.readNullableFieldForTest<FontStyle>("fontStyle"))
        assertEquals(20.sp, h3.fontSize)
        assertEquals(24.sp, h3.lineHeight)
        assertEquals(FontWeight.W700, h3.fontWeight)
        assertEquals(1f, h3.readFloatFieldForTest("colorAlpha"), 0.001f)
        assertEquals(FontStyle.Italic, h3.readNullableFieldForTest("fontStyle"))
        assertEquals(18.sp, h4.fontSize)
        assertEquals(24.sp, h4.lineHeight)
        assertEquals(FontWeight.W700, h4.fontWeight)
        assertEquals(0.7f, h4.readFloatFieldForTest("colorAlpha"), 0.001f)
        assertNull(h4.readNullableFieldForTest<FontStyle>("fontStyle"))
        assertEquals(16.sp, h5.fontSize)
        assertEquals(24.sp, h5.lineHeight)
        assertEquals(FontWeight.W700, h5.fontWeight)
        assertEquals(0.5f, h5.readFloatFieldForTest("colorAlpha"), 0.001f)
        assertNull(h5.readNullableFieldForTest<FontStyle>("fontStyle"))
        assertEquals(16.sp, h6.fontSize)
        assertEquals(24.sp, h6.lineHeight)
        assertEquals(FontWeight.Normal, h6.fontWeight)
        assertEquals(1f, h6.readFloatFieldForTest("colorAlpha"), 0.001f)
        assertNull(h6.readNullableFieldForTest<FontStyle>("fontStyle"))
    }

    @Test
    fun `native streaming body text uses simple non hyphenating line break strategy`() {
        val textStyle = compactBodyTextStyle(
            style = TextStyle(fontSize = 16.sp),
            color = Color.Black,
        )

        assertEquals(LineBreak.Simple, textStyle.lineBreak)
        assertEquals(Hyphens.None, textStyle.hyphens)
    }

    @Test
    fun `inline code uses gray bold compact style without background in streaming renderer`() {
        val surfaceVariant = Color(0xFF888888)

        assertEquals(
            ChatMarkdownTextStyle.INLINE_CODE_BACKGROUND_LIGHT_ALPHA,
            chatInlineCodeBackgroundColor(surfaceVariant, isDark = false).alpha,
            0.001f,
        )
        assertEquals(
            ChatMarkdownTextStyle.INLINE_CODE_BACKGROUND_DARK_ALPHA,
            chatInlineCodeBackgroundColor(surfaceVariant, isDark = true).alpha,
            0.001f,
        )
        assertEquals(Color(0xFF4F5661), chatInlineCodeTextColor(isDark = false))
        assertEquals(Color(0xFFD1D5DB), chatInlineCodeTextColor(isDark = true))
        assertEquals(14.72.sp, chatInlineCodeFontSize(16.sp))
    }

    @Test
    fun `native list marker column follows verified chatgpt marker padding formula`() {
        val expectedTopLevelMarkerWidth =
            8f + ChatMarkdownTextStyle.listBulletSizeDp(level = 0) + 4f

        assertEquals(expectedTopLevelMarkerWidth, ChatMarkdownTextStyle.LIST_MARKER_WIDTH_DP, 0.001f)
        assertEquals(8f, ChatMarkdownTextStyle.LIST_BULLET_START_PADDING_DP, 0.001f)
        assertEquals(5f, ChatMarkdownTextStyle.listBulletSizeDp(level = 0), 0.001f)
        assertEquals(4f, ChatMarkdownTextStyle.listBulletSizeDp(level = 1), 0.001f)
        assertEquals(24f, ChatMarkdownTextStyle.LIST_NESTED_INDENT_DP, 0.001f)
        assertEquals(4f, ChatMarkdownTextStyle.LIST_TOP_LEVEL_ITEM_SPACING_DP, 0.001f)
        assertEquals(0f, ChatMarkdownTextStyle.LIST_NESTED_TOP_SPACING_DP, 0.001f)
        assertEquals(24f, ChatMarkdownTextStyle.LIST_ITEM_LINE_HEIGHT_SP, 0.001f)
        assertTrue(ChatMarkdownTextStyle.listBulletFilled(level = 0))
        assertFalse(ChatMarkdownTextStyle.listBulletFilled(level = 1))
    }

    @Test
    fun `native nested list indent follows recursive chatgpt marker column accumulation`() {
        val source = streamBlocksRendererSource()

        assertTrue(source.contains("nativeListNestedStartPaddingDp("))
        assertTrue(source.contains("(0 until safeLevel).sumOf"))
        assertFalse(source.contains("LIST_NESTED_INDENT_DP *"))
        assertEquals(
            0.dp,
            nativeListNestedStartPaddingDp(
                level = 0,
                markerWidthByLevel = mapOf(0 to 17.dp, 1 to 16.dp),
            ),
        )
        assertEquals(
            17.dp,
            nativeListNestedStartPaddingDp(
                level = 1,
                markerWidthByLevel = mapOf(0 to 17.dp, 1 to 16.dp),
            ),
        )
        assertEquals(
            33.dp,
            nativeListNestedStartPaddingDp(
                level = 2,
                markerWidthByLevel = mapOf(0 to 17.dp, 1 to 16.dp),
            ),
        )
    }

    @Test
    fun `ordered list marker column measures marker text like chatgpt`() {
        val source = streamBlocksRendererSource()

        assertTrue(source.contains("rememberTextMeasurer("))
        assertTrue(source.contains("nativeOrderedListMarkerColumnWidthDp("))
        assertTrue(source.contains("nativeListMarkerText("))
    }

    @Test
    fun `ordered list marker text rotates through chatgpt marker functions by nesting level`() {
        val rows = listOf(
            NativeStreamingListItem(text = "top numeric", ordered = true, number = 1, level = 0),
            NativeStreamingListItem(text = "alpha dot", ordered = true, number = 2, level = 1),
            NativeStreamingListItem(text = "numeric paren", ordered = true, number = 3, level = 2),
            NativeStreamingListItem(text = "alpha paren", ordered = true, number = 4, level = 3),
            NativeStreamingListItem(text = "repeat numeric", ordered = true, number = 5, level = 4),
        )

        assertEquals("1.", nativeListMarkerText(rows[0]))
        assertEquals("b.", nativeListMarkerText(rows[1]))
        assertEquals("3)", nativeListMarkerText(rows[2]))
        assertEquals("d)", nativeListMarkerText(rows[3]))
        assertEquals("5.", nativeListMarkerText(rows[4]))
    }

    @Test
    fun `native list spacing applies between items by level only`() {
        val rows = listOf(
            NativeStreamingListItem(text = "first wraps naturally"),
            NativeStreamingListItem(text = "second"),
            NativeStreamingListItem(text = "child one", level = 1),
            NativeStreamingListItem(text = "child two", level = 1),
            NativeStreamingListItem(text = "third"),
        )

        assertEquals(0.dp, nativeListItemTopSpacing(rows, 0))
        assertEquals(4.dp, nativeListItemTopSpacing(rows, 1))
        assertEquals(0.dp, nativeListItemTopSpacing(rows, 2))
        assertEquals(4.dp, nativeListItemTopSpacing(rows, 3))
        assertEquals(4.dp, nativeListItemTopSpacing(rows, 4))
    }

    @Test
    fun `native emergency list keeps parent child tight and next parent separated`() {
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = """
                1. 调整呼吸（最重要）：
                   * 面罩式呼吸：这样可以快速提高体内的二氧化碳浓度，几分钟内就能缓解浑身瘫软和发麻的症状。

                2. 改变体位：
                   * 找个沙发或地板坐下来或平躺，解开衣领和皮带，保证呼吸顺畅。
                   * 不要强撑着站立，防止因瘫软摔倒受伤。

                3. 心理暗示：
                   * 在心里反复对自己说：“这只是惊恐发作。”
            """.trimIndent(),
            segmentId = "emergency-list",
        )

        requireNotNull(blocks)
        val rows = blocks.single().listItems
        assertEquals("改变体位：", rows[2].text)
        assertEquals(0, rows[2].level)
        assertEquals(4.dp, nativeListItemTopSpacing(rows, 2))
        assertEquals("找个沙发或地板坐下来或平躺，解开衣领和皮带，保证呼吸顺畅。", rows[3].text)
        assertEquals(1, rows[3].level)
        assertEquals(0.dp, nativeListItemTopSpacing(rows, 3))
    }

    @Test
    fun `native parser handles heading with inline code`() {
        val markdown = "# Heading with `pmatrix` and `\\partial`"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "heading-inline-code",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Heading, blocks.single().type)
        assertEquals("Heading with `pmatrix` and `\\partial`", blocks.single().text)
        assertEquals(
            StreamingMarkdownRenderPath.ComposeBlockMarkdown,
            resolveStreamingMarkdownRenderPath(markdown),
        )
    }

    @Test
    fun `native parser preserves trailing hash when atx heading has no closing marker space`() {
        val markdown = "# C#"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "heading-trailing-hash",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Heading, blocks.single().type)
        assertEquals("C#", blocks.single().text)
    }

    @Test
    fun `native parser strips atx heading closing marker when separated by space`() {
        val markdown = "### Title ###"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "heading-closing-marker",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Heading, blocks.single().type)
        assertEquals("Title", blocks.single().text)
    }

    @Test
    fun `native parser handles setext heading with inline code`() {
        val markdown = "Heading with `cases`\n---"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "setext-heading-inline-code",
        )

        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Heading, blocks.single().type)
        assertEquals(2, blocks.single().level)
        assertEquals("Heading with `cases`", blocks.single().text)
        assertEquals(
            StreamingMarkdownRenderPath.ComposeBlockMarkdown,
            resolveStreamingMarkdownRenderPath(markdown),
        )
    }

    @Test
    fun `native parser accepts table with inline math cell`() {
        val markdown = "| A | B |\n|---|---|\n| ${'$'}x${'$'} | 2 |"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-2",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Table, blocks.single().type)
        assertEquals(listOf("| A | B |", "|---|---|", "| ${'$'}x${'$'} | 2 |"), blocks.single().tableLines)
    }

    @Test
    fun `native parser accepts table with escaped inline math cell`() {
        val markdown = """| A | B |
|---|---|
| \(x+1\) | 2 |"""
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-escaped-table-math",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Table, blocks.single().type)
        assertEquals(listOf("| A | B |", "|---|---|", """| \(x+1\) | 2 |"""), blocks.single().tableLines)
    }

    @Test
    fun `native parser accepts table with pure block math cell`() {
        val markdown = "| A | B |\n|---|---|\n| ${'$'}${'$'}x+1${'$'}${'$'} | 2 |"
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-table-block-math",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Table, blocks.single().type)
        assertEquals(listOf("| A | B |", "|---|---|", "| ${'$'}${'$'}x+1${'$'}${'$'} | 2 |"), blocks.single().tableLines)
    }

    @Test
    fun `native parser accepts table with escaped block math cell`() {
        val markdown = """| A | B |
|---|---|
| \[x+1\] | 2 |"""
        val blocks = parseNativeStreamingMarkdownBlocks(
            text = markdown,
            segmentId = "msg-table-escaped-block-math",
        )

        assertEquals(StreamingMarkdownRenderPath.ComposeBlockMarkdown, resolveStreamingMarkdownRenderPath(markdown))
        requireNotNull(blocks)
        assertEquals(NativeStreamingMarkdownBlockType.Table, blocks.single().type)
        assertEquals(listOf("| A | B |", "|---|---|", """| \[x+1\] | 2 |"""), blocks.single().tableLines)
    }

    @Test
    fun `native parser keeps stable ids for unchanged prefix blocks`() {
        val first = parseNativeStreamingMarkdownBlocks(
            text = "- first",
            segmentId = "msg-3",
        )
        val second = parseNativeStreamingMarkdownBlocks(
            text = "- first\n- second",
            segmentId = "msg-3",
        )

        requireNotNull(first)
        requireNotNull(second)
        assertEquals(first[0].stableId, second[0].stableId)
    }

    @Test
    fun `fenced code extraction strips outer fence only`() {
        val codeBlock = extractFencedCodeBlockContent(
            "````markdown\n```kotlin\nval x = 1\n```\n````"
        )

        assertEquals("markdown", codeBlock.language)
        assertEquals("```kotlin\nval x = 1\n```", codeBlock.code)
    }

    private fun Any.readFloatFieldForTest(name: String): Float {
        val field = runCatching {
            this::class.java.getDeclaredField(name)
        }.getOrElse { error ->
            throw AssertionError("缺少字段 $name", error)
        }
        field.isAccessible = true
        return field.get(this) as Float
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.readNullableFieldForTest(name: String): T? {
        val field = runCatching {
            this::class.java.getDeclaredField(name)
        }.getOrElse { error ->
            throw AssertionError("缺少字段 $name", error)
        }
        field.isAccessible = true
        return field.get(this) as? T
    }

    private fun streamBlocksRendererSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt"),
            File("app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/components/streaming/StreamBlocksRenderer.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 StreamBlocksRenderer.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }

    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath"),
            File("app1/app/src/main/java/$relativePath"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 $relativePath" }
        return sourceFile.readText(Charsets.UTF_8)
    }

    private fun chatMarkdownTextStyleSource(): String {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/components/ChatMarkdownTextStyle.kt"),
            File("app/src/main/java/com/android/everytalk/ui/components/ChatMarkdownTextStyle.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/components/ChatMarkdownTextStyle.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 ChatMarkdownTextStyle.kt" }
        return sourceFile.readText(Charsets.UTF_8)
    }
}
