package com.android.everytalk.ui.components.markdown

import com.android.everytalk.ui.components.streaming.DETAILS_FENCE_LANGUAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExtensionPreprocessorTest {

    @Test
    fun `脚注使用上标内部链接且不注入分隔线`() {
        val input = """
            正文[^note]。

            [^note]: 脚注内容
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 18L)

        assertTrue(
            result.markdown.contains(
                "正文[¹](everytalk-footnote-definition:1:1)。"
            )
        )
        assertTrue(
            result.markdown.contains(
                "[¹](everytalk-footnote-reference:1) 脚注内容"
            )
        )
        assertEquals("¹⁰", footnoteNumberLabel(10))
        assertFalse(result.markdown.contains("\n\n---\n\n"))
    }

    @Test
    fun `重复脚注引用生成唯一来源地址且定义保留统一返回入口`() {
        val input = """
            第一处[^note]，第二处[^note]。

            [^note]: 重复脚注
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 21L)

        assertTrue(
            result.markdown.contains(
                "第一处[¹](everytalk-footnote-definition:1:1)，" +
                    "第二处[¹](everytalk-footnote-definition:1:2)。"
            )
        )
        assertTrue(
            result.markdown.contains(
                "[¹](everytalk-footnote-reference:1) 重复脚注"
            )
        )
    }

    @Test
    fun `正文有序列表与脚注定义保持独立结构`() {
        val input = """
            1. 用户列表

            正文[^note]

            [^note]: 脚注内容
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 22L)

        assertTrue(result.markdown.contains("1. 用户列表\n\n正文"))
        assertTrue(
            result.markdown.contains(
                "\n\n[¹](everytalk-footnote-reference:1) 脚注内容"
            )
        )
        assertFalse(result.markdown.contains("\n\n1. [↩]"))
    }

    @Test
    fun `已有Markdown链接文本内的脚注标记不生成嵌套链接`() {
        val input = """
            [链接[^note]](https://example.com) 正文[^note]

            [^note]: 脚注内容
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 19L)

        assertTrue(result.markdown.contains("[链接[^note]](https://example.com)"))
        assertTrue(
            result.markdown.contains(
                "正文[¹](everytalk-footnote-definition:1:1)"
            )
        )
        assertFalse(result.markdown.contains("[链接[¹]"))
    }

    @Test
    fun `标准Markdown结构未经预处理器改写`() {
        val input = """
            # 标题

            > 引用

            - 列表
              - 子项

            | 名称 | 状态 |
            |:---|---:|
            | A | 1 |

            [链接](https://example.com/path?q=1)

            ---

            ```kotlin
            val value = "原样"
            ```
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 20L)

        assertEquals(input, result.markdown)
    }

    @Test
    fun `引用块围栏内扩展语法保持代码原文`() {
        val input = """
            > ```text
            > literal ``` 仍是代码
            > <script>alert('quote')</script>
            > <quote@example.com> :rocket:
            > ```

            正文 <normal@example.com> :rocket:
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 8L)

        assertTrue(result.markdown.contains("> <script>alert('quote')</script>"))
        assertTrue(result.markdown.contains("> <quote@example.com> :rocket:"))
        assertTrue(result.markdown.contains("[normal@example.com](mailto:normal@example.com) 🚀"))
    }

    @Test
    fun `未闭合引用围栏离开容器后恢复正文处理`() {
        val input = """
            > ```text

            > <inside@example.com> :warning: [^note]
            > <script>alert('inside')</script>
            正文 <outside@example.com> :rocket: [^note]
            <script>alert('outside')</script>

            [^note]: 脚注 :smile:
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 15L)

        assertTrue(result.markdown.contains("> <inside@example.com> :warning: [^note]"))
        assertTrue(result.markdown.contains("> <script>alert('inside')</script>"))
        assertTrue(
            result.markdown.contains(
                "正文 [outside@example.com](mailto:outside@example.com) 🚀 " +
                    "[¹](everytalk-footnote-definition:1:1)"
            )
        )
        assertFalse(result.markdown.contains("alert('outside')"))
        assertTrue(result.markdown.contains("[¹](everytalk-footnote-reference:1) 脚注 😄"))
    }

    @Test
    fun `列表四空格与组合容器围栏内扩展语法保持代码原文`() {
        val input = """
            - 列表项
                ~~~text
                <script>alert('list')</script>
                <list@example.com> :warning:
                ~~~

            > - 组合容器
            >   ~~~text
            >   <script>alert('nested')</script>
            >   <nested@example.com> :smile:
            >   ~~~

            普通正文 :white_check_mark:
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 9L)

        assertTrue(result.markdown.contains("<script>alert('list')</script>"))
        assertTrue(result.markdown.contains("<list@example.com> :warning:"))
        assertTrue(result.markdown.contains("<script>alert('nested')</script>"))
        assertTrue(result.markdown.contains("<nested@example.com> :smile:"))
        assertTrue(result.markdown.contains("普通正文 ✅"))
    }

    @Test
    fun `未闭合列表围栏离开容器后恢复正文处理`() {
        val input = """
            - ```text

              <inside@example.com> :warning: [^note]
              <script>alert('inside')</script>
            正文 <outside@example.com> :rocket: [^note]
            <script>alert('outside')</script>

            [^note]: 脚注 :smile:
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 16L)

        assertTrue(result.markdown.contains("  <inside@example.com> :warning: [^note]"))
        assertTrue(result.markdown.contains("  <script>alert('inside')</script>"))
        assertTrue(
            result.markdown.contains(
                "正文 [outside@example.com](mailto:outside@example.com) 🚀 " +
                    "[¹](everytalk-footnote-definition:1:1)"
            )
        )
        assertFalse(result.markdown.contains("alert('outside')"))
        assertTrue(result.markdown.contains("[¹](everytalk-footnote-reference:1) 脚注 😄"))
    }

    @Test
    fun `未闭合组合容器围栏离开容器后恢复正文处理`() {
        val input = """
            > - ```text

            >   <inside@example.com> :warning: [^note]
            >   <script>alert('inside')</script>
            正文 <outside@example.com> :rocket: [^note]
            <script>alert('outside')</script>

            [^note]: 脚注 :smile:
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 17L)

        assertTrue(result.markdown.contains(">   <inside@example.com> :warning: [^note]"))
        assertTrue(result.markdown.contains(">   <script>alert('inside')</script>"))
        assertTrue(
            result.markdown.contains(
                "正文 [outside@example.com](mailto:outside@example.com) 🚀 " +
                    "[¹](everytalk-footnote-definition:1:1)"
            )
        )
        assertFalse(result.markdown.contains("alert('outside')"))
        assertTrue(result.markdown.contains("[¹](everytalk-footnote-reference:1) 脚注 😄"))
    }

    @Test
    fun `三空格顶层及同行列表引用围栏关闭后恢复扩展处理`() {
        val input = """
            |   ```text
            |   <top@example.com> :rocket:
            |   ```
            |
            |- ```text
            |  <list@example.com> :warning:
            |  ```
            |
            |> ```text
            |> <quote@example.com> :smile:
            |> ```
            |
            |正文 :white_check_mark:
        """.trimMargin()

        val result = preprocessMarkdownExtensions(input, contentVersion = 13L)

        assertTrue(result.markdown.contains("   <top@example.com> :rocket:"))
        assertTrue(result.markdown.contains("  <list@example.com> :warning:"))
        assertTrue(result.markdown.contains("> <quote@example.com> :smile:"))
        assertTrue(result.markdown.contains("正文 ✅"))
    }

    @Test
    fun `顶层四空格缩进反引号不会开启跨行围栏`() {
        val input = """
                ```literal
            正文 :rocket:
            <script>alert('outside')</script>
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 10L)

        assertTrue(result.markdown.contains("    ```literal"))
        assertTrue(result.markdown.contains("正文 🚀"))
        assertFalse(result.markdown.contains("alert('outside')"))
    }

    @Test
    fun `顶层空格与Tab缩进代码完整保留扩展原文`() {
        val input = """
            |    <script>alert('spaces')</script>
            |    <spaces@example.com> :rocket: [^note]
            |	<script>alert('tab')</script>
            |	<tab@example.com> :warning: [^note]
            |
            |正文[^note] :smile:
            |
            |[^note]: 脚注 :white_check_mark:
        """.trimMargin()

        val result = preprocessMarkdownExtensions(input, contentVersion = 14L)

        assertTrue(result.markdown.contains("    <script>alert('spaces')</script>"))
        assertTrue(result.markdown.contains("    <spaces@example.com> :rocket: [^note]"))
        assertTrue(result.markdown.contains("\t<script>alert('tab')</script>"))
        assertTrue(result.markdown.contains("\t<tab@example.com> :warning: [^note]"))
        assertTrue(
            result.markdown.contains(
                "正文[¹](everytalk-footnote-definition:1:1) 😄"
            )
        )
        assertTrue(result.markdown.contains("[¹](everytalk-footnote-reference:1) 脚注 ✅"))
    }

    @Test
    fun `details引用外层定义时共享文档级脚注并保持源码顺序`() {
        val input = """
            <details>
            <summary>详情</summary>
            详情先引用[^b]。
            </details>

            外层后引用[^a]。

            [^a]: 外层定义 :smile:
            [^b]: 详情定义 :rocket:
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 11L)
        val details = result.details.values.single()

        assertTrue(
            details.markdown.contains(
                "详情先引用[¹](everytalk-footnote-definition:1:1)。"
            )
        )
        assertTrue(
            result.markdown.contains(
                "外层后引用[²](everytalk-footnote-definition:2:1)。"
            )
        )
        assertTrue(result.markdown.contains("[¹](everytalk-footnote-reference:1) 详情定义 🚀"))
        assertTrue(result.markdown.contains("[²](everytalk-footnote-reference:2) 外层定义 😄"))
        assertFalse(result.markdown.contains("[^a]"))
        assertFalse(result.markdown.contains("[^b]"))
    }

    @Test
    fun `外层引用details内定义时定义不会被递归预处理删除`() {
        val input = """
            外层引用[^inside]。

            <details>
            <summary>详情</summary>

            [^inside]: details 内定义 :white_check_mark:

            </details>
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 12L)

        assertTrue(
            result.markdown.contains(
                "外层引用[¹](everytalk-footnote-definition:1:1)。"
            )
        )
        assertTrue(
            result.markdown.contains(
                "[¹](everytalk-footnote-reference:1) details 内定义 ✅"
            )
        )
        assertFalse(result.markdown.contains("[^inside]"))
        assertFalse(result.details.values.single().markdown.contains("[^inside]:"))
    }

    @Test
    fun `邮箱和Emoji只转换普通正文`() {
        val input = """
            联系 <test@example.com> :smile: :+1: :warning: :rocket: :white_check_mark:
            标签同名邮箱 <code@example.com> <pre@example.com> <script@example.com>
            行内代码 `<code@example.com> :rocket:`
            [链接 :smile:](https://example.com/:rocket:)
            <span title=":rocket:">正文 :rocket:</span>
            转义 \:smile:

            ```text
            <block@example.com> :rocket:
            ```
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 1L)

        assertTrue(result.markdown.contains("[test@example.com](mailto:test@example.com)"))
        assertTrue(result.markdown.contains("[code@example.com](mailto:code@example.com)"))
        assertTrue(result.markdown.contains("[pre@example.com](mailto:pre@example.com)"))
        assertTrue(result.markdown.contains("[script@example.com](mailto:script@example.com)"))
        assertTrue(result.markdown.contains("😄 👍 ⚠️ 🚀 ✅"))
        assertTrue(result.markdown.contains("`<code@example.com> :rocket:`"))
        assertTrue(result.markdown.contains("[链接 😄](https://example.com/:rocket:)"))
        assertTrue(result.markdown.contains("<span title=\":rocket:\">正文 🚀</span>"))
        assertTrue(result.markdown.contains("转义 \\:smile:"))
        assertTrue(result.markdown.contains("<block@example.com> :rocket:"))
    }

    @Test
    fun `脚注按首次引用排序并保留多行Markdown`() {
        val input = """
            正文[^b]，再次[^b]，然后[^a]，未知[^missing]。

            [^a]: 第二项
            [^b]: 第一项 **粗体**
                续行 :rocket:
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 2L)

        assertTrue(
            result.markdown.contains(
                "正文[¹](everytalk-footnote-definition:1:1)，" +
                    "再次[¹](everytalk-footnote-definition:1:2)，" +
                    "然后[²](everytalk-footnote-definition:2:1)，未知[^missing]。"
            )
        )
        assertTrue(
            result.markdown.contains(
                "[¹](everytalk-footnote-reference:1) 第一项 **粗体**\n续行 🚀"
            )
        )
        assertTrue(result.markdown.contains("[²](everytalk-footnote-reference:2) 第二项"))
        assertFalse(result.markdown.contains("[^a]:"))
        assertFalse(result.markdown.contains("[^b]:"))
    }

    @Test
    fun `脚注Emoji邮箱和script不会改写代码区域`() {
        val input = """
            <script type="text/javascript">alert(':rocket:')</script>
            正文 :rocket:

            ```markdown
            [^code]: 代码脚注
            <script>alert(1)</script>
            <code@example.com> :rocket:
            ```

            `[^inline] <inline@example.com> :smile:`
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 3L)

        assertFalse(result.markdown.contains("alert(':rocket:')"))
        assertTrue(result.markdown.contains("正文 🚀"))
        assertTrue(result.markdown.contains("[^code]: 代码脚注"))
        assertTrue(result.markdown.contains("<script>alert(1)</script>"))
        assertTrue(result.markdown.contains("<code@example.com> :rocket:"))
        assertTrue(result.markdown.contains("`[^inline] <inline@example.com> :smile:`"))
    }

    @Test
    fun `原生HTML pre和code内扩展语法保持原文`() {
        val input = """
            <pre>
            [^hidden]: 隐藏定义 :rocket:
            代码引用[^hidden]
            <script>alert('literal')</script>
            </pre>

            <code>[^inline] <inline@example.com> :smile:</code>

            外部正文[^note] :white_check_mark:

            [^note]: 外部脚注
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 23L)

        assertTrue(result.markdown.contains("[^hidden]: 隐藏定义 :rocket:"))
        assertTrue(result.markdown.contains("代码引用[^hidden]"))
        assertTrue(result.markdown.contains("<script>alert('literal')</script>"))
        assertTrue(result.markdown.contains("<code>[^inline] <inline@example.com> :smile:</code>"))
        assertTrue(
            result.markdown.contains(
                "外部正文[¹](everytalk-footnote-definition:1:1) ✅"
            )
        )
        assertTrue(result.markdown.contains("[¹](everytalk-footnote-reference:1) 外部脚注"))
    }

    @Test
    fun `原生HTML pre内反引号不延长代码保护范围`() {
        val input = """
            <pre>
            ```text
            [^hidden]: 隐藏定义
            </pre>

            外部正文[^note]

            [^note]: 外部脚注
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 24L)

        assertTrue(result.markdown.contains("```text\n[^hidden]: 隐藏定义\n</pre>"))
        assertTrue(
            result.markdown.contains(
                "外部正文[¹](everytalk-footnote-definition:1:1)"
            )
        )
        assertTrue(result.markdown.contains("[¹](everytalk-footnote-reference:1) 外部脚注"))
    }

    @Test
    fun `HTML注释内扩展和脚注定义保持原文`() {
        val input = """
            <!--
            [^hidden]: 隐藏定义 :rocket:
            注释引用[^hidden]
            -->

            外部正文[^hidden] :rocket:
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 27L)

        assertTrue(
            result.markdown.contains(
                "<!--\n[^hidden]: 隐藏定义 :rocket:\n注释引用[^hidden]\n-->"
            )
        )
        assertTrue(result.markdown.contains("外部正文[^hidden] 🚀"))
        assertFalse(result.markdown.contains(FOOTNOTE_DEFINITION_SCHEME))
        assertFalse(result.markdown.contains(FOOTNOTE_REFERENCE_SCHEME))
    }

    @Test
    fun `未闭合HTML注释保护到文档结尾`() {
        val input = """
            <!--
            [^hidden]: 隐藏定义 :rocket:
            注释引用[^hidden]
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 28L)

        assertEquals(input, result.markdown)
    }

    @Test
    fun `raw text元素内扩展和脚注定义保持原文`() {
        val input = """
            <style>
            <style>
            [^style]: 隐藏定义 :rocket:
            </style>

            <textarea>
            [^textarea]: 隐藏定义 :smile:
            </textarea>

            <title>
            [^title]: 隐藏定义 :warning:
            </title>

            外部正文[^style][^textarea][^title] :rocket:
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 29L)

        assertTrue(result.markdown.contains("[^style]: 隐藏定义 :rocket:"))
        assertTrue(result.markdown.contains("[^textarea]: 隐藏定义 :smile:"))
        assertTrue(result.markdown.contains("[^title]: 隐藏定义 :warning:"))
        assertTrue(result.markdown.contains("外部正文[^style][^textarea][^title] 🚀"))
        assertFalse(result.markdown.contains(FOOTNOTE_DEFINITION_SCHEME))
        assertFalse(result.markdown.contains(FOOTNOTE_REFERENCE_SCHEME))
    }

    @Test
    fun `未闭合raw text元素保护到文档结尾`() {
        val input = """
            <textarea>
            [^hidden]: 隐藏定义 :rocket:
            文本引用[^hidden]
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 30L)

        assertEquals(input, result.markdown)
    }

    @Test
    fun `raw text内同名脚注定义不抢占外部定义`() {
        val input = """
            <style>
            [^same]: 隐藏定义
            </style>

            外部正文[^same]

            [^same]: 可见定义
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 31L)

        assertTrue(result.markdown.contains("[^same]: 隐藏定义"))
        assertTrue(
            result.markdown.contains(
                "外部正文[¹](everytalk-footnote-definition:1:1)"
            )
        )
        assertTrue(result.markdown.contains("[¹](everytalk-footnote-reference:1) 可见定义"))
        assertFalse(result.markdown.contains("[^same]: 可见定义"))
    }

    @Test
    fun `转义HTML code标签不启动代码保护范围`() {
        val input = """
            \<code>
            外部正文[^note]

            [^note]: 外部脚注
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 26L)

        assertTrue(result.markdown.contains("\\<code>"))
        assertTrue(
            result.markdown.contains(
                "外部正文[¹](everytalk-footnote-definition:1:1)"
            )
        )
        assertTrue(result.markdown.contains("[¹](everytalk-footnote-reference:1) 外部脚注"))
    }

    @Test
    fun `合法details转换为受版本约束的内部围栏`() {
        val input = """
            前文

            <details>
            <summary>标题 :rocket:</summary>

            正文包含 **粗体**，联系 <inside@example.com>，脚注[^note]。

            [^note]: 详情脚注 :white_check_mark:

            ```kotlin
            val value = ":smile:"
            ```

            </details>

            后文
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 4L)
        val details = result.details.values.single()

        assertTrue(details.id.matches(Regex("[0-9a-f]{64}")))
        assertEquals("标题 🚀", details.summary)
        assertEquals(4L, details.contentVersion)
        assertTrue(details.markdown.contains("正文包含 **粗体**"))
        assertTrue(details.markdown.contains("[inside@example.com](mailto:inside@example.com)"))
        assertTrue(
            details.markdown.contains(
                "脚注[¹](everytalk-footnote-definition:1:1)"
            )
        )
        assertFalse(
            details.markdown.contains(
                "[¹](everytalk-footnote-reference:1) 详情脚注 ✅"
            )
        )
        assertTrue(
            result.markdown.contains(
                "[¹](everytalk-footnote-reference:1) 详情脚注 ✅"
            )
        )
        assertTrue(details.markdown.contains("val value = \":smile:\""))
        assertTrue(result.markdown.contains("```$DETAILS_FENCE_LANGUAGE\n${details.id}\n```"))
        assertFalse(result.markdown.contains("<details>"))
        assertFalse(result.markdown.contains("<summary>"))
    }

    @Test
    fun `危险details属性不进入内部资产`() {
        val input = "<details onclick=\"alert(1)\"><summary>标题</summary>正文</details>"

        val result = preprocessMarkdownExtensions(input, contentVersion = 5L)

        assertTrue(result.details.isEmpty())
        assertEquals(input, result.markdown)
    }

    @Test
    fun `嵌套details递归处理并合并资产`() {
        val input = """
            <details>
            <summary>外层</summary>

            <details>
            <summary>内层 :smile:</summary>
            内容 :rocket:
            </details>

            </details>
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 7L)
        val outer = result.details.values.first { it.summary == "外层" }
        val inner = result.details.values.first { it.summary == "内层 😄" }

        assertEquals(2, result.details.size)
        assertTrue(result.markdown.contains("```$DETAILS_FENCE_LANGUAGE\n${outer.id}\n```"))
        assertTrue(outer.markdown.contains("```$DETAILS_FENCE_LANGUAGE\n${inner.id}\n```"))
        assertTrue(inner.markdown.contains("内容 🚀"))
    }

    @Test
    fun `数学和内部围栏目标保持原样`() {
        val formulaId = "a".repeat(64)
        val input = """
            ![math](everytalk-math-inline:$formulaId)

            ```everytalk-internal-math-v1
            $formulaId
            ```
        """.trimIndent()

        val result = preprocessMarkdownExtensions(input, contentVersion = 6L)

        assertEquals(input, result.markdown)
        assertTrue(result.details.isEmpty())
    }
}
