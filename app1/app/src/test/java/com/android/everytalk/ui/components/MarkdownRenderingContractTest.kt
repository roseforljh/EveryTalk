package com.android.everytalk.ui.components

import com.android.everytalk.ui.components.streaming.BLOCK_FORMULA_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.DETAILS_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.INLINE_FORMULA_SCHEME
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.extractFencedCodeBlockContent
import com.android.everytalk.ui.components.markdown.EveryTalkMarkdownFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderingContractTest {

    @Test
    fun `链接代码标记被恢复为清晰链接并隔离备用参数`() {
        val source = """
            ○ 直达下单链接：
            `https://app.vmiss.com/cart.php?a=add&pid=78` （或备用 pid=73）
            ○ 后台路径：Order New Services -> US -> Los Angeles

            ○ 直达下单链接：
            `https://app.vmiss.com/cart.php?a=add&pid=44` （或备用
            pid=99/85）
            ○ 后台路径：Order New Services -> US -> Los Angeles
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "url-callout-recovery",
            contentVersion = 59L,
        )

        assertEquals(
            """
                ○ 直达下单链接：

                https://app.vmiss.com/cart.php?a=add&pid=78
                （或备用 pid=73）

                ○ 后台路径：Order New Services -> US -> Los Angeles

                ○ 直达下单链接：

                https://app.vmiss.com/cart.php?a=add&pid=44
                （或备用 pid=99/85）

                ○ 后台路径：Order New Services -> US -> Los Angeles
            """.trimIndent(),
            prepared.markdown,
        )
        assertFalse(prepared.markdown.contains('`'))
        assertTrue(parseMarkdown(prepared.markdown) is State.Success)

        val normalizedAgain = StreamBlockParser.prepareMessage(
            content = prepared.markdown,
            messageId = "url-callout-recovery-again",
            contentVersion = 59L,
        )
        assertEquals(prepared.markdown, normalizedAgain.markdown)
    }

    @Test
    fun `代码围栏和普通命令中的 URL 不被链接修复改写`() {
        val source = """
            执行 `curl https://example.com/install.sh`。

            ```bash
            curl https://example.com/install.sh
            ```
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "url-callout-protected",
            contentVersion = 60L,
        )

        assertEquals(source, prepared.markdown)
    }

    @Test
    fun `列表内半缩进围栏提升为顶级代码块且不吞掉后续正文`() {
        val source = """
            3. 服务端返回：
               ```json
               {
            "downloadUrl": "https://jmj.0penwor1d.com/downloads/jmj-bridge.exe"
               }
               ```
            4. 用户点击下载按钮。
            5. 安装并启动 Bridge。

            ## 一个实际注意点

            正文。

            ```text
            /downloads/jmj-bridge.exe
            ```
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "broken-list-fence-indent",
            contentVersion = 52L,
        )
        val state = parseMarkdown(
            prepared.markdown,
            lookupLinks = false,
            flavour = EveryTalkMarkdownFlavourDescriptor,
        ) as State.Success
        val codeFences = state.node.children.filter { it.type == MarkdownElementTypes.CODE_FENCE }
        val codeFenceSources = codeFences.map { node ->
            state.content.substring(node.startOffset, node.endOffset)
        }

        assertTrue(prepared.markdown.contains("\n```json\n{\n\"downloadUrl\""))
        assertEquals(2, codeFences.size)
        assertTrue(codeFenceSources.first().contains("\"downloadUrl\""))
        assertFalse(codeFenceSources.first().contains("4. 用户点击下载按钮"))
        assertTrue(state.node.children.any { it.type == MarkdownElementTypes.ATX_2 })
    }

    @Test
    fun `合法容器围栏与无语言字面围栏保持原文`() {
        val sources = listOf(
            "- 示例\n  ```json\n  {\"ok\": true}\n  ```\n\n正文。",
            "> ```text\n> 内容\n> ```\n\n正文。",
            "- 列表中的字面围栏\n  ```\n正文仍保留。\n  ```",
        )

        sources.forEachIndexed { index, source ->
            val prepared = StreamBlockParser.prepareMessage(
                content = source,
                messageId = "legal-container-fence-$index",
                contentVersion = 53L + index,
            )

            assertEquals(source, prepared.markdown)
        }
    }

    @Test
    fun `只有关闭围栏退出列表缩进时仍恢复完整代码块`() {
        val source = "- 示例\n  ```json\n  {\"ok\": true}\n```\n\n正文。"
        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "broken-closing-fence-indent",
            contentVersion = 56L,
        )
        val state = parseMarkdown(
            prepared.markdown,
            lookupLinks = false,
            flavour = EveryTalkMarkdownFlavourDescriptor,
        ) as State.Success

        assertTrue(prepared.markdown.contains("- 示例\n\n```json\n{\"ok\": true}\n```\n\n正文。"))
        assertEquals(1, state.node.children.count { it.type == MarkdownElementTypes.CODE_FENCE })
    }

    @Test
    fun `正文后粘连的代码围栏恢复边界且不吞掉后续Markdown`() {
        val source = """
            可以理解成下面这条流水线：```text
            FOFA / Shodan / GitHub
                      ↓
            发现暴露的 AI 服务和疑似 API Key
            ```

            它支持的技术栈包括：

            - **后端**：Rust、Axum、Tokio、SQLx
            - **前端**：React、Vite、TailwindCSS

            ## 适合什么场景

            企业自查。
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "embedded-fence-boundary",
            contentVersion = 48L,
        )
        val state = parseMarkdown(
            prepared.markdown,
            lookupLinks = false,
            flavour = EveryTalkMarkdownFlavourDescriptor,
        ) as State.Success
        val codeFences = state.node.children.filter { it.type == MarkdownElementTypes.CODE_FENCE }
        val codeFenceSource = codeFences.single().let { node ->
            state.content.substring(node.startOffset, node.endOffset)
        }

        assertTrue(prepared.markdown.contains("流水线：\n```text"))
        assertTrue(codeFenceSource.contains("FOFA / Shodan / GitHub"))
        assertFalse(codeFenceSource.contains("它支持的技术栈包括"))
        assertTrue(state.node.children.any { it.type == MarkdownElementTypes.UNORDERED_LIST })
        assertTrue(state.node.children.any { it.type == MarkdownElementTypes.ATX_2 })
    }

    @Test
    fun `字面围栏说明不会抢占后续合法代码块`() {
        val source = """
            文档中提到了字面标记：```text

            下面才是真实代码块：

            ```kotlin
            val value = "原样"
            ```
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "literal-fence-before-valid-code",
            contentVersion = 49L,
        )

        assertEquals(source, prepared.markdown)
    }

    @Test
    fun `多组同行围栏按各自标记恢复且公式保持代码原文`() {
        val source = """
            第一段：```text
            ${'$'}x+1${'$'}
            ```

            中间正文。

            第二段：~~~json
            {"enabled": true}
            ~~~

            - 后续列表
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "multiple-embedded-fences",
            contentVersion = 50L,
        )
        val state = parseMarkdown(
            prepared.markdown,
            lookupLinks = false,
            flavour = EveryTalkMarkdownFlavourDescriptor,
        ) as State.Success

        assertTrue(prepared.formulas.isEmpty())
        assertEquals(
            2,
            state.node.children.count { it.type == MarkdownElementTypes.CODE_FENCE },
        )
        assertTrue(state.node.children.any { it.type == MarkdownElementTypes.UNORDERED_LIST })
    }

    @Test
    fun `容器中的字面同行围栏不做高风险跨容器恢复`() {
        val source = """
            - 列表中的字面标记：```text
              内容
              ```

            > 引用中的字面标记：~~~text
            > 内容
            > ~~~
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "container-literal-fences",
            contentVersion = 51L,
        )

        assertEquals(source, prepared.markdown)
    }

    @Test
    fun `独占加粗小标题与紧邻正文被修复为两个段落`() {
        val source = """
            **1. 红卫兵运动**
            学生被动员起来成立“红卫兵”组织，以“革命”名义冲击党政机关。
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "standalone-strong-heading",
            contentVersion = 43L,
        )
        val state = parseMarkdown(
            prepared.markdown,
            lookupLinks = false,
            flavour = EveryTalkMarkdownFlavourDescriptor,
        ) as State.Success

        assertEquals(
            "**1. 红卫兵运动**\n\n学生被动员起来成立“红卫兵”组织，以“革命”名义冲击党政机关。",
            prepared.markdown,
        )
        assertEquals(
            2,
            state.node.children.count { it.type == MarkdownElementTypes.PARAGRAPH },
        )
    }

    @Test
    fun `连续加粗小标题与前后正文均保留段落间距`() {
        val source = """
            **1. 红卫兵运动**
            第一段正文。
            **2. 破“四旧”**
            第二段正文。
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "consecutive-strong-headings",
            contentVersion = 46L,
        )
        val state = parseMarkdown(
            prepared.markdown,
            lookupLinks = false,
            flavour = EveryTalkMarkdownFlavourDescriptor,
        ) as State.Success

        assertEquals(
            """
                **1. 红卫兵运动**

                第一段正文。

                **2. 破“四旧”**

                第二段正文。
            """.trimIndent(),
            prepared.markdown,
        )
        assertEquals(
            4,
            state.node.children.count { it.type == MarkdownElementTypes.PARAGRAPH },
        )
    }

    @Test
    fun `下划线加粗小标题使用相同的段落边界修复`() {
        val source = "__风险提示__\n以下内容需要单独成段。"

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "underscore-strong-heading",
            contentVersion = 44L,
        )

        assertEquals("__风险提示__\n\n以下内容需要单独成段。", prepared.markdown)
    }

    @Test
    fun `代码围栏和普通行内加粗不被小标题边界修复`() {
        val source = """
            普通正文包含 **重点内容**，后面仍是同一段。

            ```text
            **1. 代码中的标题**
            代码中的正文
            ```
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "strong-heading-protected-content",
            contentVersion = 45L,
        )

        assertEquals(source, prepared.markdown)
    }

    @Test
    fun `正文冒号粘连无序列表时修复为独立列表`() {
        val source = """
            你有 **100U**：- 不用杠杆买股票：谷歌涨 1%，大约赚 **1U**；
            - 如果使用 **10倍杠杆合约**，相当于控制约 1000U 的仓位：
              - 谷歌涨 1%，理论上约赚 **10U**；
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "embedded-unordered-list",
            contentVersion = 47L,
        )
        val state = parseMarkdown(
            prepared.markdown,
            lookupLinks = false,
            flavour = EveryTalkMarkdownFlavourDescriptor,
        ) as State.Success

        assertEquals(
            """
                你有 **100U**：

                - 不用杠杆买股票：谷歌涨 1%，大约赚 **1U**；
                - 如果使用 **10倍杠杆合约**，相当于控制约 1000U 的仓位：
                  - 谷歌涨 1%，理论上约赚 **10U**；
            """.trimIndent(),
            prepared.markdown,
        )
        assertTrue(state.node.children.any { it.type == MarkdownElementTypes.UNORDERED_LIST })
    }

    @Test
    fun `正文冒号粘连有序列表时首项与后续项保持同一列表`() {
        val source = """
            比较适合：1. **企业自查**
            排查自己公司的服务器、代码仓库中是否暴露了 AI 服务或密钥。

            2. **授权的安全测试**
               在获得明确授权的范围内，发现 AI API、代理服务、管理面板等暴露资产。

            3. **泄露凭证治理**
               对 GitHub 等公开来源中出现的疑似 API Key 进行识别、验证和归档。

            4. **AI 资产管理**
               统一记录不同 AI 服务的密钥状态、余额和历史扫描结果。
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "embedded-ordered-list",
            contentVersion = 52L,
        )
        val state = parseMarkdown(
            prepared.markdown,
            lookupLinks = false,
            flavour = EveryTalkMarkdownFlavourDescriptor,
        ) as State.Success
        val orderedList = state.node.children.single {
            it.type == MarkdownElementTypes.ORDERED_LIST
        }

        assertTrue(prepared.markdown.startsWith("比较适合：\n\n1. **企业自查**"))
        assertEquals(
            4,
            orderedList.children.count { it.type == MarkdownElementTypes.LIST_ITEM },
        )
    }

    @Test
    fun `正文粘连列表支持全部首项标记且不误判普通编号`() {
        listOf("-", "*", "+", "1.", "1)").forEachIndexed { index, marker ->
            val source = "说明： $marker 项目"
            val prepared = StreamBlockParser.prepareMessage(
                content = source,
                messageId = "embedded-list-marker-$index",
                contentVersion = 53L + index,
            )

            assertEquals("说明：\n\n$marker 项目", prepared.markdown)
        }

        val prose = "发布年份：2026. 版本保持支持。"
        val preparedProse = StreamBlockParser.prepareMessage(
            content = prose,
            messageId = "ordinary-numbered-prose",
            contentVersion = 58L,
        )

        assertEquals(prose, preparedProse.markdown)
    }

    @Test
    fun `正文粘连表头时只修复表格边界并交给 GFM 表格解析`() {
        val source = """
            特征：| 财务指标 | 2024 财年 | 2025 财年 | 年同比变化 |
            | :--- | :--- | :--- | :--- |
            | 总营收 | 37 亿美元 | 130.7 亿美元 | 增长 |
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "financial-table-boundary",
            contentVersion = 41L,
        )
        val state = parseMarkdown(
            prepared.markdown,
            lookupLinks = false,
            flavour = EveryTalkMarkdownFlavourDescriptor,
        ) as State.Success

        assertTrue(prepared.markdown.contains("特征：\n\n| 财务指标"))
        assertTrue(state.node.children.any { it.type == GFMElementTypes.TABLE })
    }

    @Test
    fun `代码围栏内的表格样式文本不被边界修复`() {
        val source = """
            ```text
            前缀：| a | b |
            | --- | --- |
            ```
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = source,
            messageId = "table-in-code-fence",
            contentVersion = 42L,
        )

        assertTrue(prepared.markdown.contains("前缀：| a | b |"))
    }

    @Test
    fun `renderable markdown without outer fence uses markdown code and math owners`() {
        val markdown = """
            # 一级标题

            普通文本包含 **加粗**、*斜体* 和 ~~删除线~~。

            > 这是引用。

            - [x] 已完成
            - [ ] 待完成

            | 名称 | 公式 |
            |:---|:---:|
            | 质能方程 | ${'$'}E = mc^2${'$'} |

            行内公式 ${'$'}a^2 + b^2 = c^2${'$'}。

            ```python
            print("${'$'}HOME")
            ```

            ${'$'}${'$'}
            \int_0^1 x^2 dx
            ${'$'}${'$'}
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = markdown,
            messageId = "renderable-markdown",
            contentVersion = 23L,
        )

        assertEquals(3, prepared.formulas.size)
        assertEquals(
            2,
            prepared.formulas.values.count { it.displayMode == FormulaDisplayMode.INLINE },
        )
        assertEquals(
            1,
            prepared.formulas.values.count { it.displayMode == FormulaDisplayMode.BLOCK },
        )
        assertFalse(prepared.hasPendingFormula)
        assertTrue(prepared.markdown.contains("# 一级标题"))
        assertTrue(prepared.markdown.contains("> 这是引用。"))
        assertTrue(prepared.markdown.contains("| 名称 | 公式 |"))
        assertTrue(prepared.markdown.contains("```python\nprint(\"${'$'}HOME\")\n```"))
        assertTrue(prepared.markdown.contains(INLINE_FORMULA_SCHEME))
        assertTrue(prepared.markdown.contains("```$BLOCK_FORMULA_FENCE_LANGUAGE"))
        assertTrue(parseMarkdown(prepared.markdown) is State.Success)
    }

    @Test
    fun `markdown source fence renders content while nested code keeps CodeBlockCard`() {
        val markdownSource = """
            ````markdown
            # 一级标题

            | 名称 | 公式 |
            |:---|:---:|
            | 质能方程 | ${'$'}E = mc^2${'$'} |

            ```python
            print("hello")
            ```

            ${'$'}${'$'}
            \int_0^1 x^2 dx
            ${'$'}${'$'}
            ````
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = markdownSource,
            messageId = "markdown-source",
            contentVersion = 24L,
        )
        val codeBlocks = StreamBlockParser.parse(prepared.markdown, "markdown-source-result")
            .blocks
            .filterIsInstance<StreamBlock.CodeBlock>()
            .map { block -> extractFencedCodeBlockContent(block.text) }

        assertEquals(2, prepared.formulas.size)
        assertFalse(prepared.hasPendingFormula)
        assertTrue(prepared.markdown.contains("# 一级标题"))
        assertTrue(prepared.markdown.contains("| 名称 | 公式 |"))
        assertTrue(prepared.markdown.contains("```python\nprint(\"hello\")\n```"))
        assertTrue(prepared.markdown.contains(INLINE_FORMULA_SCHEME))
        assertTrue(prepared.markdown.contains(BLOCK_FORMULA_FENCE_LANGUAGE))
        assertFalse(prepared.markdown.contains("````markdown"))
        assertTrue(codeBlocks.any { it.language == "python" })
        assertFalse(codeBlocks.any { it.language == "markdown" })
        assertEquals(
            setOf("E = mc^2", "\\int_0^1 x^2 dx"),
            prepared.formulas.values.mapTo(mutableSetOf()) { it.latex },
        )
    }

    @Test
    fun `multiple markdown fences render while surrounding explanation stays markdown`() {
        val response = """
            以下示例需要直接渲染：

            ```markdown
            # 标题
            ${'$'}E = mc^2${'$'}
            ```

            第二个示例：

            ```markdown
            | 名称 | 公式 |
            |:---|:---:|
            | 欧拉公式 | ${'$'}e^{i\pi}+1=0${'$'} |
            ```
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = response,
            messageId = "multiple-markdown-examples",
            contentVersion = 25L,
        )
        val blocks = StreamBlockParser.parse(prepared.markdown, "multiple-markdown-result").blocks
        val codeBlocks = blocks.filterIsInstance<StreamBlock.CodeBlock>()

        assertEquals(2, prepared.formulas.size)
        assertTrue(codeBlocks.isEmpty())
        assertFalse(prepared.markdown.contains("```markdown"))
        assertTrue(prepared.markdown.contains("# 标题"))
        assertTrue(prepared.markdown.contains("| 名称 | 公式 |"))
        assertTrue(blocks.filterIsInstance<StreamBlock.PlainText>().any { it.text.contains("第二个示例") })
    }

    @Test
    fun `md fence follows the same renderable markdown rule`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = """
                ```md
                ## 标题

                公式 ${'$'}a+b${'$'}。
                ```
            """.trimIndent(),
            messageId = "md-source",
            contentVersion = 29L,
        )

        assertFalse(prepared.markdown.contains("```md"))
        assertTrue(prepared.markdown.contains("## 标题"))
        assertTrue(prepared.markdown.contains(INLINE_FORMULA_SCHEME))
        assertEquals("a+b", prepared.formulas.values.single().latex)
    }

    @Test
    fun `unfinished markdown fence streams as renderable content`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = """
                ```markdown
                ## 流式标题

                公式 ${'$'}x+y${'$'}
            """.trimIndent(),
            messageId = "streaming-markdown-source",
            contentVersion = 30L,
        )

        assertFalse(prepared.markdown.contains("```markdown"))
        assertTrue(prepared.markdown.contains("## 流式标题"))
        assertEquals("x+y", prepared.formulas.values.single().latex)
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `markdown-looking fence inside real code remains untouched`() {
        val pythonSource = listOf(
            "````python",
            "```markdown",
            "# 这是 Python 字符串中的原文",
            "```",
            "````",
        ).joinToString("\n")

        val prepared = StreamBlockParser.prepareMessage(
            content = pythonSource,
            messageId = "python-with-markdown-source",
            contentVersion = 31L,
        )
        val code = StreamBlockParser.parse(prepared.markdown, "python-result")
            .blocks
            .filterIsInstance<StreamBlock.CodeBlock>()
            .single()
            .let { block -> extractFencedCodeBlockContent(block.text) }

        assertEquals(pythonSource, prepared.markdown)
        assertTrue(prepared.formulas.isEmpty())
        assertEquals("python", code.language)
        assertTrue(code.code.contains("```markdown"))
    }

    @Test
    fun `formulas beside lists quotes emphasis and links use the same parser`() {
        val markdown = """
            - 列表公式 ${'$'}a+1${'$'}

            > 引用公式 ${'$'}b+1${'$'}

            **粗体文本**旁边是 ${'$'}c+1${'$'}。

            [链接](https://example.com)旁边是 ${'$'}d+1${'$'}。
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = markdown,
            messageId = "markdown-formula-contexts",
            contentVersion = 26L,
        )

        assertEquals(listOf("a+1", "b+1", "c+1", "d+1"), prepared.formulas.values.map { it.latex })
        assertTrue(prepared.formulas.values.all { it.displayMode == FormulaDisplayMode.INLINE })
        assertTrue(prepared.markdown.contains("- 列表公式"))
        assertTrue(prepared.markdown.contains("> 引用公式"))
        assertTrue(prepared.markdown.contains("**粗体文本**"))
        assertTrue(prepared.markdown.contains("[链接](https://example.com)"))
        assertTrue(parseMarkdown(prepared.markdown) is State.Success)
    }

    @Test
    fun `pricing dollars remain ordinary markdown`() {
        val markdown = """
            **Free** 套餐为 **${'$'}0/月**，每月包含 ${'$'}30 inference credit。

            > No overage charges until August 2026.
        """.trimIndent()
        val prepared = StreamBlockParser.prepareMessage(
            content = markdown,
            messageId = "pricing",
            contentVersion = 27L,
        )
        assertEquals(markdown, prepared.markdown)
        assertTrue(prepared.formulas.isEmpty())
        assertTrue(parseMarkdown(prepared.markdown) is State.Success)
    }

    @Test
    fun `中文正文中的多组美元金额保持原文`() {
        val markdown = """
            - **EASICOIN**：每次验证扣除 ${'$'}0.03。如果卡内余额正好只有 ${'$'}20，可能因为多了 ${'$'}0.03 的 3DS 费或未预留税费而导致扣款失败。

            1. 预留足够余额：ChatGPT 账单默认是 **${'$'}20**，但如果你的账单地址不在免税州，可能会被收取 6%~10% 的消费税（约 ${'$'}21.20~${'$'}22）。卡内建议至少保留 **${'$'}22 ~ ${'$'}25**。
        """.trimIndent()
        val prepared = StreamBlockParser.prepareMessage(
            content = markdown,
            messageId = "currency-prose",
            contentVersion = 33L,
        )

        assertTrue(prepared.formulas.isEmpty())
        assertEquals(markdown, prepared.markdown)
    }

    @Test
    fun `formula placeholder uses stable lowercase SHA256 id without payload encoding`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = "公式 ${'$'}E = mc^2${'$'}",
            messageId = "stable-id",
            contentVersion = 28L,
        )
        val formula = prepared.formulas.values.single()

        assertTrue(formula.id.matches(Regex("[0-9a-f]{64}")))
        assertEquals("公式 ![math](everytalk-math-inline:${formula.id})", prepared.markdown)
        assertFalse(prepared.markdown.contains("E = mc^2"))
        assertFalse(prepared.markdown.contains("base64", ignoreCase = true))
        assertFalse(prepared.markdown.contains("katex", ignoreCase = true))
    }

    @Test
    fun `edge case extensions keep one markdown math and code ownership chain`() {
        val markdown = """
            <span style="color:red;font-weight:bold">红色粗体</span>

            联系邮箱 <test@example.com>，实体 &copy; &amp; &lt; &gt;，表情 :rocket:。

            转义括号 \[这不是链接\]。

            正文脚注[^note]。

            <details>
            <summary>折叠详情 :smile:</summary>

            公式 ${'$'}E=mc^2${'$'} 与 **粗体**。

            </details>

            ```text
            <code@example.com> :rocket:
            ```

            <script>alert('不执行')</script>

            [^note]: 脚注包含 [链接](https://example.com/docs)。
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = markdown,
            messageId = "edge-case-extensions",
            contentVersion = 32L,
        )
        val details = prepared.details.values.single()

        assertEquals(1, prepared.formulas.size)
        assertTrue(prepared.markdown.contains("[test@example.com](mailto:test@example.com)"))
        assertTrue(prepared.markdown.contains("实体 &copy; &amp; &lt; &gt;，表情 🚀"))
        assertTrue(prepared.markdown.contains("\\[这不是链接\\]"))
        assertTrue(
            prepared.markdown.contains(
                "正文脚注[¹](everytalk-footnote-definition:1:1)"
            )
        )
        assertTrue(
            prepared.markdown.contains(
                "[¹](everytalk-footnote-reference:1) " +
                    "脚注包含 [链接](https://example.com/docs)"
            )
        )
        assertTrue(prepared.markdown.contains("```$DETAILS_FENCE_LANGUAGE\n${details.id}\n```"))
        assertEquals("折叠详情 😄", details.summary)
        assertTrue(details.markdown.contains(INLINE_FORMULA_SCHEME))
        assertTrue(details.markdown.contains("**粗体**"))
        assertTrue(prepared.markdown.contains("<code@example.com> :rocket:"))
        assertFalse(prepared.markdown.contains("alert('不执行')"))
        assertTrue(parseMarkdown(prepared.markdown) is State.Success)
    }
}
