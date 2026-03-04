package com.android.everytalk.ui.components

import org.junit.Test
import org.junit.Assert.*

/**
 * 测试 infographic 代码块的解析行为
 */
class InfographicParseTest {

    @Test
    fun `infographic code fence should be parsed as Code with language infographic`() {
        val input = """
## 曼城 3 月后续关键赛程

曼城在 3 月份面临多线作战的巨大压力

```infographic
infographic
data
title 曼城 2026 年 3 月赛程表
items
- label 英超第 29 轮
  desc 3月5日 03:30 | 主场 vs 诺丁汉森林
  icon mdi:soccer
- label 足总杯 1/8 决赛
  desc 3月8日 04:00 | 客场 vs 纽卡斯尔联
  icon mdi:trophy-variant
```

*注：以上时间均为北京时间*
        """.trimIndent()

        val parts = ContentParser.parseCompleteContent(input)

        // Print all parts for debugging
        parts.forEachIndexed { index, part ->
            println("Part $index: ${part.javaClass.simpleName}")
            when (part) {
                is ContentPart.Text -> println("  Text content (first 100 chars): ${part.content.take(100)}")
                is ContentPart.Code -> println("  Language: '${part.language}', Code (first 100 chars): ${part.content.take(100)}")
                is ContentPart.Table -> println("  Table lines: ${part.lines.size}")
                is ContentPart.Math -> println("  Math content: ${part.content.take(100)}")
            }
        }

        // Find the Code part
        val codeParts = parts.filterIsInstance<ContentPart.Code>()
        assertTrue("Should have at least one Code part", codeParts.isNotEmpty())

        val infographicPart = codeParts.first()
        assertEquals("Language should be 'infographic'", "infographic", infographicPart.language)
        assertTrue("Content should contain 'title'", infographicPart.content.contains("title"))
        assertTrue("Content should contain 'items'", infographicPart.content.contains("items"))
    }

    @Test
    fun `simple infographic code fence`() {
        val input = """```infographic
infographic
data
title Test Title
items
- label Item 1
  desc Description 1
```"""

        val parts = ContentParser.parseCompleteContent(input)

        parts.forEachIndexed { index, part ->
            println("Part $index: ${part.javaClass.simpleName}")
            when (part) {
                is ContentPart.Text -> println("  Text: '${part.content}'")
                is ContentPart.Code -> println("  Language: '${part.language}', Content: '${part.content}'")
                is ContentPart.Table -> println("  Table")
                is ContentPart.Math -> println("  Math")
            }
        }

        val codeParts = parts.filterIsInstance<ContentPart.Code>()
        assertTrue("Should have a Code part", codeParts.isNotEmpty())
        assertEquals("infographic", codeParts.first().language)
    }
}
