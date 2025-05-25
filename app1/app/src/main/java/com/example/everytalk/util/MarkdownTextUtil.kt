// 确保这个文件顶部的包声明是正确的
package com.example.everytalk.util // 确保与你的项目结构一致

import org.commonmark.parser.Parser
import org.commonmark.renderer.text.TextContentRenderer

fun convertMarkdownToPlainText(markdown: String): String {
    if (markdown.isBlank()) return ""
    val parser = Parser.builder().build()
    val document = parser.parse(markdown)
    val renderer = TextContentRenderer.builder().build()
    return renderer.render(document).trim()
}