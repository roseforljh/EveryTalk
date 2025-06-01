package com.example.everytalk.util

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlNodeRendererFactory
import org.commonmark.renderer.html.HtmlRenderer
import java.util.regex.Pattern

// Regexes for Pre-processing
private val REGEX_LEADING_NEWLINES = Regex("(?<!\n\n)([ \\t]*\n)?(```)")
private val REGEX_TRAILING_NEWLINES =
    Regex("(```[^\\n]*\\n(?:[^\\n]*\\n)*?[^\\n]*```)\n?([ \\t]*)(?!\n\n)")
private val REGEX_EXCESSIVE_NEWLINES = Regex("\n{3,}")

// 用来将 ${xxx} 形式替换为 [...] 的占位
private val REGEX_CUSTOM_REPLACE = Regex("\\\$\\{([^}]+)\\}")

// **新增**：让标题前也加一个空行，避免 "# 标题" 因行首缺少空行而无法被识别
private val REGEX_HEADING_PREFIX = Regex("(?m)^(?<!\n)(#{1,6} +)")

// **新增**：让“•”这样的符号单独成段，以便 CommonMark 识别为列表项前缀
private val REGEX_BULLET_PREFIX = Regex("(?m)^(?<!\\n)([ \\t]*)•[ \\t]+")

class CMMathNode(var latexContent: String, var isBlock: Boolean) : CustomNode() {
    override fun accept(visitor: Visitor?) {
        visitor?.visit(this)
    }
}

class CMMathNodeHtmlRenderer(private val context: HtmlNodeRendererContext) : NodeRenderer {
    private val htmlWriter = context.writer

    companion object {
        private const val LOG_TAG_RENDERER = "CMMathNodeRenderer"
    }

    override fun getNodeTypes(): Set<Class<out Node>> {
        return setOf(CMMathNode::class.java)
    }

    override fun render(node: Node) {
        if (node is CMMathNode) {
            val tag = if (node.isBlock) "div" else "span"
            val cssClass = if (node.isBlock) "katex-math-display" else "katex-math-inline"
            Log.d(
                LOG_TAG_RENDERER,
                "Rendering CMMathNode. isBlock: ${node.isBlock}, Class: $cssClass, LaTeX: '${
                    node.latexContent.takeForLog(
                        60
                    )
                }'"
            )
            val attributes: MutableMap<String, String> = HashMap()
            attributes["class"] = cssClass
            htmlWriter.tag(tag, attributes)
            htmlWriter.text(node.latexContent)
            htmlWriter.tag("/$tag")
        }
    }
}

class CustomMathHtmlNodeRendererFactory : HtmlNodeRendererFactory {
    override fun create(context: HtmlNodeRendererContext): NodeRenderer {
        return CMMathNodeHtmlRenderer(context)
    }
}

internal fun convertMarkdownToHtml(originalMarkdown: String): String {
    val tag = "MarkdownToCM"
    if (originalMarkdown.isBlank()) return ""

    Log.d(tag, "==== ConvertMarkdownToHtml (CommonMark) START ====")
    Log.d(
        tag,
        "0. Raw LLM MD (len ${originalMarkdown.length}):\n${originalMarkdown.takeForLog(1000)}"
    )

    // 1. 先做最简单的空行前缀补齐，保证标题（#）、列表前缀（•）等能被正确识别
    var processedMarkdown = originalMarkdown
        // 如果一行以“# ”开头但前面没有空行，就在前面强制加一个换行
        .replace(REGEX_HEADING_PREFIX) { match ->
            "\n" + match.value
        }
        // 如果一行以“• ”开头但前面没有空行，也先加一个 \n
        .replace(REGEX_BULLET_PREFIX) { match ->
            "\n" + match.groupValues[1] + "- "
            // 将“•”替换成 Markdown “-”，CommonMark 会自动识别为无序列表
        }

    // 2. 保证 Markdown 代码块用空行隔开，避免和其他内容黏在一起
    processedMarkdown = REGEX_LEADING_NEWLINES.replace(processedMarkdown) {
        val optionalNewline = it.groups[1]?.value ?: ""
        val codeFence = it.groups[2]!!.value
        if (optionalNewline.isBlank() || optionalNewline.trim()
                .isEmpty()
        ) "\n\n$optionalNewline$codeFence"
        else "\n$optionalNewline$codeFence"
    }
    processedMarkdown = REGEX_TRAILING_NEWLINES.replace(processedMarkdown) {
        val codeBlock = it.groups[1]!!.value
        val trailingWhitespace = it.groups[2]?.value ?: ""
        "$codeBlock$trailingWhitespace\n\n"
    }
    processedMarkdown = REGEX_EXCESSIVE_NEWLINES.replace(processedMarkdown, "\n\n")

    Log.d(
        tag,
        "1. MD after Structural Pre-processing (len ${processedMarkdown.length}):\n${
            processedMarkdown.takeForLog(
                1000
            )
        }"
    )

    // 3. 处理所有“转义了的 LaTeX 反斜杠”-> 变回单个反斜杠
    processedMarkdown = processedMarkdown.replace(
        Regex("""\\\\([a-zA-Z]+|[\(\)\[\]\{\}\$\%\^\_\&\|\\])""")
    ) { "\\${it.groupValues[1]}" }

    // 4. 将纯 “( \something )” 形式的 LaTeX，用 \( \) 包一层，方便后续处理
    val plainParenLatexRegex = Regex("""\(\s*(\\[a-zA-Z]+[^\)]*?)\s*\)""")
    processedMarkdown = plainParenLatexRegex.replace(processedMarkdown) { matchResult ->
        val latexContent = matchResult.groupValues[1].trim()
        val startIndex = matchResult.range.first
        val endIndex = matchResult.range.last
        val prefix =
            if (startIndex > 1) processedMarkdown.substring(startIndex - 2, startIndex) else ""
        val suffix = if (endIndex < processedMarkdown.length - 1) processedMarkdown.substring(
            endIndex + 1,
            endIndex + 2
        ) else ""
        // 避免已经被包在 \(…\) 或 $…$ 里，就不要重复包了
        if (prefix != "\\(" && prefix != "\$(" && suffix != "\\)" && suffix != "\$)") {
            "\\(${latexContent}\\)"
        } else {
            matchResult.value
        }
    }

    // 5. CommonMark 解析并提取数学
    val extensions = mutableListOf<Extension>(
        TablesExtension.create(),
        StrikethroughExtension.create()
    )
    try {
        extensions.add(AutolinkExtension.create())
    } catch (e: NoClassDefFoundError) {
        Log.w(
            tag,
            "AutolinkExtension not found. Links will not be auto-converted. Error: ${e.message}"
        )
    } catch (e: Exception) {
        Log.e(tag, "Error attempting to create AutolinkExtension: ${e.message}", e)
    }

    val parser: Parser = Parser.builder().extensions(extensions).build()
    val renderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(extensions)
        .nodeRendererFactory(CustomMathHtmlNodeRendererFactory())
        .build()

    // **改动：在正则里加入对 “裸写 \\begin{aligned} … \\end{aligned}” 的捕获**
    val mathPattern = Pattern.compile(
        // 1) 裸写的 \begin{aligned} … \end{aligned}（DOTALL 模式下，能跨行匹配）
        """(?<!\\)\\begin\{aligned\}(.*?)(?<!\\)\\end\{aligned\}|""" +
                // 2) 原有的 \( … \)
                """(?<!\\)\\\((.*?)(?<!\\)\\\)|""" +
                // 3) 原有的 $…$ （非 $$）
                """(?<!\\)\$((?:[^$\s\\](?:\\.)*?))(?<!\\)\$|""" +
                // 4) 原有的 $$…$$
                """(?<!\\)\$\$(.*?)(?<!\\)\$\$|""" +
                // 5) 原有的 \[ … \]
                """(?<!\\)\\\[(.*?)(?<!\\)\\]""",
        Pattern.DOTALL
    )

    var htmlResult: String
    try {
        val documentNode: Node = parser.parse(processedMarkdown)
        Log.d(tag, "CommonMark parsing complete.")

        val astVisitor = object : AbstractVisitor() {
            private fun processTextualContent(textHolderNode: Node, literal: String) {
                if (literal.isEmpty() ||
                    (!literal.contains('$') && !literal.contains("\\(") && !literal.contains("\\[")
                            && !literal.contains("\\begin{aligned}")
                            )
                ) {
                    return
                }

                val matcher = mathPattern.matcher(literal)
                var lastEnd = 0
                val newNodes = mutableListOf<Node>()
                var modified = false

                while (matcher.find(lastEnd)) {
                    modified = true
                    val matchStart = matcher.start()
                    val matchEnd = matcher.end()

                    // 1) 把前面那一段普通文本作为 Text node
                    if (matchStart > lastEnd) {
                        newNodes.add(Text(literal.substring(lastEnd, matchStart)))
                    }

                    // 2) 判断是哪种数学匹配
                    val fullMatch = matcher.group(0)
                    val content: String
                    val isBlock: Boolean

                    when {
                        // 裸写 aligned
                        matcher.group(1) != null && fullMatch.startsWith("\\begin{aligned}") -> {
                            content = matcher.group(1).trim()
                            isBlock = true
                        }
                        // \( … \)
                        matcher.group(2) != null && fullMatch.startsWith("\\(") -> {
                            content = matcher.group(2).trim()
                            isBlock = false
                        }
                        // $…$
                        matcher.group(3) != null && fullMatch.startsWith("$") && !fullMatch.startsWith(
                            "$$"
                        ) -> {
                            content = matcher.group(3).trim()
                            isBlock = false
                        }
                        // $$…$$
                        matcher.group(4) != null && fullMatch.startsWith("$$") -> {
                            content = matcher.group(4).trim()
                            isBlock = true
                        }
                        // \[ … \]
                        matcher.group(5) != null && fullMatch.startsWith("\\[") -> {
                            content = matcher.group(5).trim()
                            isBlock = true
                        }

                        else -> {
                            Log.w(
                                tag,
                                "AST Visitor: Regex matched but no known group captured for: '${
                                    fullMatch.takeForLog(
                                        30
                                    )
                                }'"
                            )
                            newNodes.add(Text(fullMatch))
                            lastEnd = matchEnd
                            continue
                        }
                    }

                    newNodes.add(CMMathNode(content, isBlock))
                    lastEnd = matchEnd
                }

                // 3) 把剩余的文本也加入
                if (lastEnd < literal.length) {
                    newNodes.add(Text(literal.substring(lastEnd)))
                }

                // 4) 如果发生过替换，就把原来的 Text node 替换成新的一系列 Node（Text + CMMathNode + Text…）
                if (modified && newNodes.isNotEmpty()) {
                    var currentNodeSuccessor: Node = textHolderNode
                    newNodes.forEach { newNode ->
                        currentNodeSuccessor.insertAfter(newNode)
                        currentNodeSuccessor = newNode
                    }
                    textHolderNode.unlink()
                    Log.d(
                        tag,
                        "AST Visitor: Replaced node ${textHolderNode::class.java.simpleName} with ${newNodes.size} new nodes for math."
                    )
                }
            }

            override fun visit(textNode: Text) {
                super.visit(textNode)
                processTextualContent(textNode, textNode.literal)
            }

            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                super.visit(fencedCodeBlock)
                val info = fencedCodeBlock.info?.trim()?.lowercase()
                val looksLikeMathHeavyContent =
                    fencedCodeBlock.literal.count { it == '$' || it == '\\' } > fencedCodeBlock.literal.length / 10
                val shouldProcessAsMath = info.isNullOrEmpty() ||
                        info in listOf("math", "latex", "katex", "tex") ||
                        (info !in listOf(
                            "python",
                            "java",
                            "javascript",
                            "c++",
                            "csharp",
                            "kotlin",
                            "swift",
                            "rust",
                            "go",
                            "html",
                            "css",
                            "xml",
                            "json",
                            "yaml",
                            "sql",
                            "bash",
                            "shell"
                        ) && looksLikeMathHeavyContent)

                if (shouldProcessAsMath) {
                    Log.d(
                        tag,
                        "AST Visitor: Processing FencedCodeBlock (info: $info, len: ${fencedCodeBlock.literal.length}) for potential math content."
                    )
                    processTextualContent(fencedCodeBlock, fencedCodeBlock.literal)
                } else {
                    Log.d(
                        tag,
                        "AST Visitor: Skipping FencedCodeBlock (info: $info) - not identified as math."
                    )
                }
            }

            override fun visit(indentedCodeBlock: IndentedCodeBlock) {
                super.visit(indentedCodeBlock)
                val looksLikeMathHeavyContent =
                    indentedCodeBlock.literal.count { it == '$' || it == '\\' } > indentedCodeBlock.literal.length / 10
                if (looksLikeMathHeavyContent) {
                    Log.d(
                        tag,
                        "AST Visitor: Processing IndentedCodeBlock (len: ${indentedCodeBlock.literal.length}) for potential math content."
                    )
                    processTextualContent(indentedCodeBlock, indentedCodeBlock.literal)
                } else {
                    Log.d(tag, "AST Visitor: Skipping IndentedCodeBlock - not identified as math.")
                }
            }
        }

        // 解析 AST
        documentNode.accept(astVisitor)
        Log.d(tag, "AST traversal for math complete.")

        // 渲染为 HTML
        htmlResult = renderer.render(documentNode)
        Log.d(
            tag,
            "2. HTML from CommonMark (len ${htmlResult.length}):\n${htmlResult.takeForLog(3000)}"
        )

    } catch (e: Exception) {
        Log.e(tag, "CRITICAL Error during CommonMark parsing or rendering: ${e.message}", e)
        return "<p style='color:red; font-weight:bold;'>[Markdown Rendering Error: ${e.javaClass.simpleName}]</p>" +
                "<h4>Original Content (first 500 chars):</h4><pre style='background-color:#f0f0f0; padding:10px; border:1px solid #ccc; white-space: pre-wrap; word-wrap: break-word;'>${
                    originalMarkdown.takeForLog(500).replace("<", "&lt;").replace(">", "&gt;")
                }</pre>"
    }

    // 6. 做自定义的替换：把 ${xxx} -> [xxx]。不再主动替换“【…】”，保留原样
    htmlResult = REGEX_CUSTOM_REPLACE.replace(htmlResult, "[$1]")
    Log.d(
        tag,
        "3. HTML after Custom Replace (len ${htmlResult.length}):\n${htmlResult.takeForLog(1000)}"
    )

    // 7. 用 Jsoup 对 HTML 做 sanitize
    val safelist: Safelist = Safelist.relaxed()
        .addTags(
            "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
            "strong", "em", "del", "code", "pre", "blockquote",
            "ul", "ol", "li", "table", "thead", "tbody", "tr", "th", "td", "a",
            "span", "div", "img"
        )
        .addAttributes(":all", "class", "style")
        .addAttributes("a", "href", "title")
        .addAttributes("img", "src", "alt", "title", "width", "height")
        .addProtocols("a", "href", "#", "http", "https", "mailto")
        .addProtocols("img", "src", "http", "https", "data")

    val outputSettings = Document.OutputSettings().prettyPrint(false).indentAmount(0)
    val cleanHtml: String = Jsoup.clean(htmlResult, "", safelist, outputSettings)

    if (htmlResult != cleanHtml) {
        Log.w(tag, "4. HTML was MODIFIED by Jsoup sanitization.")
        Log.d(tag, "HTML before Jsoup: \n${htmlResult.takeForLog(1000)}")
        Log.d(tag, "HTML after Jsoup (Cleaned): \n${cleanHtml.takeForLog(1000)}")
    } else {
        Log.d(tag, "4. HTML was NOT modified by Jsoup sanitization.")
    }
    Log.d(tag, "==== ConvertMarkdownToHtml (CommonMark) END ====")
    return cleanHtml.trim()
}

private fun String.takeForLog(n: Int): String {
    val eolReplacement = "↵"
    return if (this.length > n) {
        this.substring(0, n).replace("\n", eolReplacement) + "..."
    } else {
        this.replace("\n", eolReplacement)
    }
}
