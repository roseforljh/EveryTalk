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

private val REGEX_LEADING_NEWLINES = Regex("(?<!\n\n)([ \\t]*\n)?(```)")
private val REGEX_TRAILING_NEWLINES =
    Regex("(```[^\\n]*\\n(?:[^\\n]*\\n)*?[^\\n]*```)\n?([ \\t]*)(?!\n\n)")
private val REGEX_EXCESSIVE_NEWLINES = Regex("\n{3,}")
private val REGEX_CUSTOM_REPLACE = Regex("\\\$\\{([^}]+)\\}")
private val REGEX_HEADING_PREFIX = Regex("(?m)^[ \t]{0,3}(#{1,6} +)")
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

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(CMMathNode::class.java)
    override fun render(node: Node) {
        if (node is CMMathNode) {
            val tag = if (node.isBlock) "div" else "span"
            val cssClass = if (node.isBlock) "katex-math-display" else "katex-math-inline"
            Log.d(
                LOG_TAG_RENDERER,
                "Rendering CMMathNode. isBlock: ${node.isBlock}, Class: $cssClass, LaTeX: '${
                    node.latexContent.takeForLog(60)
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
    override fun create(context: HtmlNodeRendererContext): NodeRenderer =
        CMMathNodeHtmlRenderer(context)
}

internal fun convertMarkdownToHtml(originalMarkdown: String): String {
    val tag = "MarkdownToCM"
    if (originalMarkdown.isBlank()) return ""
    Log.d(tag, "==== ConvertMarkdownToHtml (CommonMark) START ====")
    Log.d(
        tag,
        "0. Raw LLM MD (len ${originalMarkdown.length}):\n${originalMarkdown.takeForLog(1000)}"
    )

    var processedMarkdown = originalMarkdown
        .replace(REGEX_HEADING_PREFIX) { "\n" + it.value }
        .replace(REGEX_BULLET_PREFIX) { "\n" + it.groupValues[1] + "- " }

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
            processedMarkdown.takeForLog(1000)
        }"
    )

    processedMarkdown =
        processedMarkdown.replace(Regex("""\\\\([a-zA-Z]+|[\(\)\[\]\{\}\$\%\^\_\&\|\\])""")) { "\\${it.groupValues[1]}" }

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
        if (prefix != "\\(" && prefix != "\$(" && suffix != "\\)" && suffix != "\$)") {
            "\\(${latexContent}\\)"
        } else {
            matchResult.value
        }
    }

    val extensions =
        mutableListOf<Extension>(TablesExtension.create(), StrikethroughExtension.create())
    try {
        extensions.add(AutolinkExtension.create())
    } catch (e: NoClassDefFoundError) {
        Log.w(tag, "AutolinkExtension not found. Error: ${e.message}")
    } catch (e: Exception) {
        Log.e(tag, "Error creating AutolinkExtension: ${e.message}", e)
    }

    val parser: Parser = Parser.builder().extensions(extensions).build()
    val renderer: HtmlRenderer = HtmlRenderer.builder().extensions(extensions)
        .nodeRendererFactory(CustomMathHtmlNodeRendererFactory()).build()

    val mathPattern = Pattern.compile(
        """(?<!\\)\\begin\{aligned\}(.*?)(?<!\\)\\end\{aligned\}|""" + // group 1
                """(?<!\\)\\\((.*?)(?<!\\)\\\)""", Pattern.DOTALL    // group 2
    )

    // Pattern for the specific $...$ case shown in the image, ensuring it's not part of $$...$$
    // It looks for a $ not preceded by \ or $, then captures content until another $ not preceded by \.
    // The content itself should not contain a lone $ unless escaped.
    val specificSingleDollarPatternForVisitor =
        Pattern.compile("""(?<![\\\$])\$((?:[^\\\$]+|\\.)*?)(?<!\\)\$""")

    val specificBareFracPatternForVisitor =
        Pattern.compile("""(?<![\\\$])(\\frac\s*\{[^{}]*?\})(?<!\\)\s*(\{[^{}]*?\})""")
    val specificBareComparisonSingleVarPatternForVisitor =
        Pattern.compile("""(?<![\\\$])((\\(?:leq|geq|neq|approx|sim|propto|equiv|subset|supset|in|ni|ll|gg|prec|succ|parallel|perp|mid|vdash|vDash|models))\s+([a-zA-Z]))""")
    val generalBareLatexPatternForVisitor =
        Pattern.compile("""(?<![\\\$])(\\(?:[a-zA-Z]+(?:\s*\{[^{}]*?\})?(?:\s*\^[^{}\s\\]*(?:\{[^{}]*?\})?)?(?:\s*_[^{}\s\\]*(?:\{[^{}]*?\})?)?|[^\s\w\d]))""")


    var htmlResult: String
    try {
        val documentNode: Node = parser.parse(processedMarkdown)
        Log.d(tag, "CommonMark parsing complete.")

        val astVisitor = object : AbstractVisitor() {
            private fun addBareLatexNodes(textSegment: String, targetNodeList: MutableList<Node>) {
                if (textSegment.isEmpty()) return

                var currentPosition = 0
                var lastAddedNodeIsText = false
                val segmentLength = textSegment.length

                while (currentPosition < segmentLength) {
                    val remainingTextToSearch = textSegment.substring(currentPosition)
                    var consumedByMatch = 0

                    val singleDollarMatcher =
                        specificSingleDollarPatternForVisitor.matcher(remainingTextToSearch)
                    if (singleDollarMatcher.lookingAt()) {
                        // Check if this match is actually a $$...$$ block by looking ahead
                        if (!(remainingTextToSearch.length > singleDollarMatcher.end() && remainingTextToSearch[singleDollarMatcher.end()] == '$')) {
                            val mathContent = singleDollarMatcher.group(1)
                            if (mathContent != null) { // Ensure group 1 was captured
                                targetNodeList.add(CMMathNode(mathContent.trim(), false))
                                Log.d(
                                    tag,
                                    "AST Visitor: Specific Single $ bare: '${
                                        mathContent.trim().takeForLog(30)
                                    }'"
                                )
                                consumedByMatch = singleDollarMatcher.end()
                                lastAddedNodeIsText = false
                            }
                        }
                    }

                    if (consumedByMatch == 0) { // If not matched by specific single dollar or it was a $$
                        val fracMatcher =
                            specificBareFracPatternForVisitor.matcher(remainingTextToSearch)
                        if (fracMatcher.lookingAt()) {
                            val fracContent = fracMatcher.group(1) + fracMatcher.group(2)
                            targetNodeList.add(CMMathNode(fracContent.trim(), false))
                            Log.d(
                                tag,
                                "AST Visitor: Specific Frac bare: '${
                                    fracContent.trim().takeForLog(30)
                                }'"
                            )
                            consumedByMatch = fracMatcher.end()
                            lastAddedNodeIsText = false
                        } else {
                            val comparisonMatcher =
                                specificBareComparisonSingleVarPatternForVisitor.matcher(
                                    remainingTextToSearch
                                )
                            if (comparisonMatcher.lookingAt()) {
                                val comparisonContent = comparisonMatcher.group(1)
                                targetNodeList.add(CMMathNode(comparisonContent.trim(), false))
                                Log.d(
                                    tag,
                                    "AST Visitor: Specific Comp+Var bare: '${
                                        comparisonContent.trim().takeForLog(30)
                                    }'"
                                )
                                consumedByMatch = comparisonMatcher.end()
                                lastAddedNodeIsText = false
                            } else {
                                val generalMatcher =
                                    generalBareLatexPatternForVisitor.matcher(remainingTextToSearch)
                                if (generalMatcher.lookingAt()) {
                                    val generalContent = generalMatcher.group(1)
                                    if (generalContent.trim().isNotEmpty()) {
                                        targetNodeList.add(CMMathNode(generalContent.trim(), false))
                                        Log.d(
                                            tag,
                                            "AST Visitor: General bare: '${
                                                generalContent.trim().takeForLog(30)
                                            }'"
                                        )
                                        lastAddedNodeIsText = false
                                    } else if (generalMatcher.group(0).isNotEmpty()) {
                                        val textToAdd = generalMatcher.group(0)
                                        if (lastAddedNodeIsText && targetNodeList.isNotEmpty() && targetNodeList.last() is Text) {
                                            val lastNode = targetNodeList.last() as Text
                                            targetNodeList.removeAt(targetNodeList.size - 1)
                                            targetNodeList.add(Text(lastNode.literal + textToAdd))
                                        } else {
                                            targetNodeList.add(Text(textToAdd))
                                        }
                                        lastAddedNodeIsText = true
                                    }
                                    consumedByMatch = generalMatcher.end()
                                }
                            }
                        }
                    }


                    if (consumedByMatch > 0) {
                        currentPosition += consumedByMatch
                    } else {
                        val charToAdd = textSegment.substring(currentPosition, currentPosition + 1)
                        if (lastAddedNodeIsText && targetNodeList.isNotEmpty() && targetNodeList.last() is Text) {
                            val lastNode = targetNodeList.last() as Text
                            targetNodeList.removeAt(targetNodeList.size - 1)
                            targetNodeList.add(Text(lastNode.literal + charToAdd))
                        } else {
                            targetNodeList.add(Text(charToAdd))
                        }
                        currentPosition++
                        lastAddedNodeIsText = true
                    }
                }
            }

            private fun processTextualContent(textHolderNode: Node, literal: String) {
                if (literal.isEmpty()) return
                val newNodes = mutableListOf<Node>()
                var lastEndDelimitedMatch = 0
                val delimitedMatcher = mathPattern.matcher(literal)

                while (delimitedMatcher.find(lastEndDelimitedMatch)) {
                    val matchStart = delimitedMatcher.start()
                    if (matchStart > lastEndDelimitedMatch) {
                        addBareLatexNodes(
                            literal.substring(lastEndDelimitedMatch, matchStart),
                            newNodes
                        )
                    }
                    val fullMatch = delimitedMatcher.group(0)
                    val content: String
                    val isBlock: Boolean
                    when {
                        delimitedMatcher.group(1) != null -> { // Aligned environment
                            content = delimitedMatcher.group(1).trim(); isBlock = true
                        }
                        delimitedMatcher.group(2) != null -> { // \(...\)
                            content = delimitedMatcher.group(2).trim(); isBlock = false
                        }
                        else -> {
                            Log.w(
                                tag,
                                "AST Visitor: Delimited regex '${fullMatch.takeForLog(30)}' no recognized group. Fallback."
                            )
                            addBareLatexNodes(fullMatch, newNodes)
                            lastEndDelimitedMatch = delimitedMatcher.end()
                            continue
                        }
                    }
                    newNodes.add(CMMathNode(content, isBlock))
                    Log.d(
                        tag,
                        "AST Visitor: Delimited math: '${content.takeForLog(30)}', isBlock: $isBlock"
                    )
                    lastEndDelimitedMatch = delimitedMatcher.end()
                }
                if (lastEndDelimitedMatch < literal.length) {
                    addBareLatexNodes(literal.substring(lastEndDelimitedMatch), newNodes)
                }

                val hasChanged =
                    if (newNodes.size == 1 && newNodes.first() is Text) (newNodes.first() as Text).literal != literal
                    else !(newNodes.isEmpty() && literal.isEmpty())
                if (hasChanged) {
                    var currentNodeSuccessor: Node = textHolderNode
                    newNodes.forEach { newNode ->
                        currentNodeSuccessor.insertAfter(newNode); currentNodeSuccessor = newNode
                    }
                    textHolderNode.unlink()
                    Log.d(
                        tag,
                        "AST Visitor: Replaced ${textHolderNode::class.java.simpleName} ('${
                            literal.takeForLog(50)
                        }') with ${newNodes.size} nodes."
                    )
                }
            }

            override fun visit(textNode: Text) {
                super.visit(textNode); processTextualContent(textNode, textNode.literal)
            }

            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                val info = fencedCodeBlock.info?.trim()?.lowercase()
                val literal = fencedCodeBlock.literal

                if (info != null && info in listOf("math", "latex", "katex", "tex")) {
                    Log.d(tag, "AST Visitor: FencedCodeBlock (info: $info) is explicit KaTeX block. Replacing with CMMathNode.")
                    val mathNode = CMMathNode(literal.trim(), true) // isBlock = true for fenced math
                    fencedCodeBlock.insertAfter(mathNode)
                    fencedCodeBlock.unlink()
                    // No call to super.visit(fencedCodeBlock) as we've replaced it.
                } else {
                    Log.d(tag, "AST Visitor: FencedCodeBlock (info: $info) is not an explicit KaTeX block. Default handling.")
                    // Default commonmark rendering for non-math code blocks
                    super.visit(fencedCodeBlock)
                }
            }

            override fun visit(indentedCodeBlock: IndentedCodeBlock) {
                super.visit(indentedCodeBlock)
                val literal = indentedCodeBlock.literal
                if (literal.count { it == '$' || it == '\\' } > literal.length / 10) {
                    Log.d(tag, "AST Visitor: Processing IndentedCodeBlock for math.")
                    processTextualContent(indentedCodeBlock, literal)
                } else {
                    Log.d(tag, "AST Visitor: Skipping IndentedCodeBlock.")
                }
            }
        }
        documentNode.accept(astVisitor)
        Log.d(tag, "AST traversal complete.")
        htmlResult = renderer.render(documentNode)
        Log.d(
            tag,
            "2. HTML from CommonMark (len ${htmlResult.length}):\n${htmlResult.takeForLog(3000)}"
        )
    } catch (e: Exception) {
        Log.e(tag, "CRITICAL Error during CommonMark: ${e.message}", e)
        return "<p style='color:red; font-weight:bold;'>[Markdown Error: ${e.javaClass.simpleName}]</p>" +
                "<h4>Original (500 chars):</h4><pre style='background:#f0f0f0;padding:10px;border:1px solid #ccc;white-space:pre-wrap;word-wrap:break-word;'>${
                    originalMarkdown.takeForLog(500).replace("<", "<").replace(">", ">")
                }</pre>"
    }

    htmlResult = REGEX_CUSTOM_REPLACE.replace(htmlResult, "[$1]")
    Log.d(
        tag,
        "3. HTML after Custom Replace (len ${htmlResult.length}):\n${htmlResult.takeForLog(1000)}"
    )

    val safelist = Safelist.relaxed()
        .addTags(
            "p",
            "br",
            "hr",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "strong",
            "em",
            "del",
            "code",
            "pre",
            "blockquote",
            "ul",
            "ol",
            "li",
            "table",
            "thead",
            "tbody",
            "tr",
            "th",
            "td",
            "a",
            "span",
            "div",
            "img"
        )
        .addAttributes(":all", "class", "style").addAttributes("a", "href", "title")
        .addAttributes("img", "src", "alt", "title", "width", "height")
        .addProtocols("a", "href", "#", "http", "https", "mailto")
        .addProtocols("img", "src", "http", "https", "data")
    val outputSettings = Document.OutputSettings().prettyPrint(false).indentAmount(0)
    val cleanHtml = Jsoup.clean(htmlResult, "", safelist, outputSettings)

    if (htmlResult != cleanHtml) {
        Log.w(tag, "4. HTML MODIFIED by Jsoup.")
        Log.d(tag, "HTML before Jsoup (len ${htmlResult.length}): \n${htmlResult.takeForLog(1000)}")
        Log.d(tag, "HTML after Jsoup (len ${cleanHtml.length}): \n${htmlResult.takeForLog(1000)}")
    } else {
        Log.d(tag, "4. HTML NOT modified by Jsoup.")
    }
    Log.d(tag, "==== ConvertMarkdownToHtml (CommonMark) END ====")
    return cleanHtml.trim()
}

private fun String.takeForLog(n: Int): String {
    val eolR = "↵"
    return if (this.length > n) this.substring(0, n).replace("\n", eolR) + "..."
    else this.replace("\n", eolR)
}