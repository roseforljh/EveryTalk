package com.example.everytalk.util

import android.util.Log
import com.example.everytalk.ui.screens.BubbleMain.FencedCodeBlockAttributeProvider // 确保这个导入路径正确
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.util.Arrays

// 将正则表达式定义为伴生对象或文件顶层常量以缓存编译结果
private val REGEX_CUSTOM_REPLACE = Regex("\\\$\\{([^}]+)\\}")
private val REGEX_LEADING_NEWLINES = Regex("(?<!\n\n)([ \t]*\n)?(```)")
private val REGEX_TRAILING_NEWLINES = Regex("(```[^\n]*\n(?:[^\n]*\n)*?[^\n]*```)\n?(?!\n\n)")
private val REGEX_EXCESSIVE_NEWLINES = Regex("\n{3,}")

internal fun convertMarkdownToHtml(originalMarkdown: String): String {
    if (originalMarkdown.isBlank()) return ""

    var processedMarkdown = originalMarkdown

    // 使用已缓存的Regex对象
    if (REGEX_LEADING_NEWLINES.containsMatchIn(processedMarkdown)) {
        processedMarkdown = REGEX_LEADING_NEWLINES.replace(processedMarkdown, "\n\n$1$2")
    }

    if (REGEX_TRAILING_NEWLINES.containsMatchIn(processedMarkdown)) {
        processedMarkdown = REGEX_TRAILING_NEWLINES.replace(processedMarkdown, "$1\n\n")
    }

    if (REGEX_EXCESSIVE_NEWLINES.containsMatchIn(processedMarkdown)) {
        processedMarkdown = REGEX_EXCESSIVE_NEWLINES.replace(processedMarkdown, "\n\n")
    }

    if (processedMarkdown != originalMarkdown) {
        Log.d("MarkdownToHtml", "Markdown was pre-processed for code block spacing.")
        // Log.d("MarkdownToHtml", "Original MD:\n$originalMarkdown")
        // Log.d("MarkdownToHtml", "Processed MD:\n$processedMarkdown")
    }

    val extensions = Arrays.asList(TablesExtension.create(), StrikethroughExtension.create())
    val parser = Parser.builder().extensions(extensions).build()
    val document = parser.parse(processedMarkdown)
    val renderer = HtmlRenderer.builder()
        .extensions(extensions)
        .attributeProviderFactory { FencedCodeBlockAttributeProvider() }
        .build()
    var html = renderer.render(document)

    html = REGEX_CUSTOM_REPLACE.replace(html, "[$1]") // 使用已缓存的Regex对象

    val safelist = Safelist.relaxed()
        .addTags(
            "span",
            "div",
            "pre",
            "code",
            "br",
            "p",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "ul",
            "ol",
            "li",
            "strong",
            "em",
            "del",
            "table",
            "thead",
            "tbody",
            "tr",
            "th",
            "td",
            "hr",
            "a"
        )
        .addAttributes("span", "class", "style")
        .addAttributes("div", "class", "style")
        .addAttributes("pre", "class")
        .addAttributes("code", "class")
        .addAttributes("a", "href", "title")

    val cleanHtml = Jsoup.clean(html, "", safelist)

    if (html.length != cleanHtml.length) {
        Log.d(
            "MarkdownToHtml",
            "HTML was modified by Jsoup. Original length: ${html.length}, Cleaned length: ${cleanHtml.length}"
        )
        // Log.d("MarkdownToHtml", "Original HTML (first 300 chars): ${html.take(300)}")
        // Log.d("MarkdownToHtml", "Cleaned HTML (first 300 chars): ${cleanHtml.take(300)}")
    }

    return cleanHtml
}