package com.example.everytalk.util

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist

// --- CommonMark-java Imports ---
import org.commonmark.node.* // Node, CustomNode, Text, AbstractVisitor, etc.
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.html.HtmlNodeRendererFactory
import org.commonmark.renderer.NodeRenderer // Interface for node renderers
import java.util.regex.Pattern
import org.commonmark.Extension // Base class for extensions

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.autolink.AutolinkExtension

// Regexes for Pre-processing (可以保持不变)
private val REGEX_LEADING_NEWLINES = Regex("(?<!\n\n)([ \t]*\n)?(```)")
private val REGEX_TRAILING_NEWLINES =
    Regex("(```[^\n]*\n(?:[^\n]*\n)*?[^\n]*```)\n?([ \t]*)(?!\n\n)")
private val REGEX_EXCESSIVE_NEWLINES = Regex("\n{3,}")
private val REGEX_CUSTOM_REPLACE =
    Regex("\\\$\\{([^}]+)\\}")

// --- Custom Math Node for CommonMark-java ---
class CMMathNode(var latexContent: String, var isBlock: Boolean) : CustomNode() {
    override fun accept(visitor: Visitor?) {
        visitor?.visit(this)
    }
}

// --- Custom HTML Renderer for CMMathNode ---
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
                    node.latexContent.takeForLog(60)
                }'"
            )

            val attributes: MutableMap<String, String> = HashMap()
            attributes["class"] = cssClass

            htmlWriter.tag(tag, attributes)
            htmlWriter.text(node.latexContent) // .unescapeHtml() if you notice issues with HTML entities
            htmlWriter.tag("/$tag") // Ensure closing tag is written
        }
    }
}

class CustomMathHtmlNodeRendererFactory : HtmlNodeRendererFactory {
    override fun create(context: HtmlNodeRendererContext): NodeRenderer {
        return CMMathNodeHtmlRenderer(context)
    }
}

// --- Main Conversion Function using CommonMark-java ---
internal fun convertMarkdownToHtml(originalMarkdown: String): String {
    val tag = "MarkdownToCM"

    if (originalMarkdown.isBlank()) {
        Log.d(tag, "Input MD is blank, returning empty string.")
        return ""
    }
    Log.d(tag, "==== ConvertMarkdownToHtml (CommonMark) START ====")
    Log.d(
        tag,
        "0. Raw LLM MD (len ${originalMarkdown.length}):\n${originalMarkdown.takeForLog(1000)}"
    )

    // --- START: LaTeX Pre-processing and Normalization ---
    var preProcessedLatexMd = originalMarkdown

    // 1. Correct over-escaped LaTeX commands like \\Delta to \Delta, \\( to \(, etc.
    // This specifically targets double backslashes followed by common LaTeX characters or commands.
    preProcessedLatexMd = preProcessedLatexMd.replace(Regex("""\\\\([a-zA-Z]+|[\(\)\[\]\{\}\$\%\^\_\&\|\\])""")) { matchResult ->
        "\\${matchResult.groupValues[1]}"
    }
    // Log if changes were made by this step
    if (preProcessedLatexMd != originalMarkdown) {
        Log.d(tag, "0.1. MD after correcting over-escaped LaTeX:\n${preProcessedLatexMd.takeForLog(1000)}")
    }


    val plainParenLatexRegex = Regex("""\(\s*(\\([a-zA-Z]+|[\[\]\{\}\$\%\^\_\&\|\\]).*?)\s*\)""")
    preProcessedLatexMd = plainParenLatexRegex.replace(preProcessedLatexMd) { matchResult ->
        val latexContent = matchResult.groupValues[1].trim()
        // Check if it looks like it's not already inside \( ... \) or $...$
        val startIndex = matchResult.range.first
        val endIndex = matchResult.range.last
        val prefix = if (startIndex > 1) preProcessedLatexMd.substring(startIndex - 2, startIndex) else ""
        val suffix = if (endIndex < preProcessedLatexMd.length - 1) preProcessedLatexMd.substring(endIndex + 1, endIndex + 2) else ""

        if (prefix != "\\(" && prefix != "\$(" && suffix != "\\)" && suffix != "\$)") {
            Log.d(tag, "Attempting to fix plain parentheses for LaTeX: '${matchResult.value}' -> '\\(${latexContent}\\)'")
            "\\(${latexContent}\\)"
        } else {
            matchResult.value // Already seems to be correctly delimited, leave it.
        }
    }
    // Log if changes were made by this step
    val mdAfterPlainParenFix = preProcessedLatexMd
    if (mdAfterPlainParenFix != originalMarkdown && mdAfterPlainParenFix != preProcessedLatexMd.substringBefore("Regex(")) { // Avoid re-logging if no change in this step
        Log.d(tag, "0.2. MD after attempting plain parentheses fix:\n${mdAfterPlainParenFix.takeForLog(1000)}")
    }


    var processedMarkdown = mdAfterPlainParenFix // Use the LaTeX-preprocessed version
    // --- END: LaTeX Pre-processing and Normalization ---


    val mdBeforeStructuralPreProcessing = processedMarkdown
    // Standard Markdown structural pre-processing (leading/trailing newlines for code blocks, excessive newlines)
    processedMarkdown = REGEX_LEADING_NEWLINES.replace(processedMarkdown) { matchResult ->
        val optionalNewline = matchResult.groups[1]?.value ?: ""
        val codeFence = matchResult.groups[2]!!.value
        if (optionalNewline.isBlank() || optionalNewline.trim().isEmpty()) {
            "\n\n" + optionalNewline + codeFence
        } else {
            "\n" + optionalNewline + codeFence
        }
    }
    processedMarkdown = REGEX_TRAILING_NEWLINES.replace(processedMarkdown) { matchResult ->
        val codeBlock = matchResult.groups[1]!!.value
        val trailingWhitespace = matchResult.groups[2]?.value ?: ""
        codeBlock + trailingWhitespace + "\n\n"
    }
    processedMarkdown = REGEX_EXCESSIVE_NEWLINES.replace(processedMarkdown, "\n\n")

    if (processedMarkdown != mdBeforeStructuralPreProcessing) {
        Log.d(
            tag,
            "1. MD after Structural Pre-processing (len ${processedMarkdown.length}):\n${
                processedMarkdown.takeForLog(1000)
            }"
        )
    } else {
        Log.d(tag, "1. MD after Structural Pre-processing: No changes by structural regexes.")
    }

    val extensions = mutableListOf<Extension>(
        TablesExtension.create(),
        StrikethroughExtension.create()
    )
    try {
        extensions.add(AutolinkExtension.create())
        Log.i(tag, "AutolinkExtension successfully added.")
    } catch (e: NoClassDefFoundError) {
        Log.w(
            tag,
            "AutolinkExtension not found at runtime. Links will not be auto-converted. Add 'org.commonmark:commonmark-ext-autolink' to dependencies. Error: ${e.message}"
        )
    } catch (e: Exception) {
        Log.e(tag, "Error attempting to create AutolinkExtension: ${e.message}", e)
    }


    val parser: Parser = Parser.builder()
        .extensions(extensions)
        .build()

    val renderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(extensions)
        .nodeRendererFactory(CustomMathHtmlNodeRendererFactory())
        .build()

    var html: String
    try {
        Log.d(tag, "Parsing with CommonMark...")
        val documentNode: Node = parser.parse(processedMarkdown)
        Log.d(tag, "CommonMark parsing complete. AST Root: $documentNode")

        Log.d(tag, "Starting AST traversal to find and replace math delimiters...")
        // This pattern should be fine as pre-processing aims to normalize input to these formats.
        val mathPattern = Pattern.compile(
            """(?<!\\)\\\((.*?)(?<!\\)\\\)|""" + // \( ... \) - Group 1
                    """(?<!\\)\$((?:[^$\\](?:\\.)*)+?)(?<!\\)\$|""" + // $ ... $ (non-greedy, handles escaped $) - Group 2
                    """(?<!\\)\$\$(.*?)(?<!\\)\$\$|""" + // $$ ... $$ - Group 3
                    """(?<!\\)\\\[(.*?)(?<!\\)\\]""", // \[ ... \] - Group 4
            Pattern.DOTALL
        )


        val astVisitor = object : AbstractVisitor() {
            override fun visit(textNode: Text) {
                super.visit(textNode)
                val literal = textNode.literal
                // Optimization: only proceed if there's a potential math delimiter
                if (literal.isNullOrEmpty() || (!literal.contains('$') && !literal.contains("\\(") && !literal.contains("\\["))) {
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

                    if (matchStart > lastEnd) {
                        newNodes.add(Text(literal.substring(lastEnd, matchStart)))
                    }

                    val fullMatch = matcher.group(0)
                    val content: String
                    val isBlock: Boolean

                    when {
                        // Group 1: \( ... \)
                        matcher.group(1) != null && fullMatch.startsWith("\\(") -> {
                            content = matcher.group(1)
                            isBlock = false
                            Log.d(tag, "AST Visitor: Found INLINE_PAREN: ${content.takeForLog(30)}")
                        }
                        // Group 2: $ ... $
                        matcher.group(2) != null && fullMatch.startsWith("$") && !fullMatch.startsWith("$$") -> {
                            content = matcher.group(2)
                            isBlock = false
                            Log.d(tag, "AST Visitor: Found INLINE_DOLLAR: ${content.takeForLog(30)}")
                        }
                        // Group 3: $$ ... $$
                        matcher.group(3) != null && fullMatch.startsWith("$$") -> {
                            content = matcher.group(3)
                            isBlock = true
                            Log.d(tag, "AST Visitor: Found BLOCK_DOLLAR: ${content.takeForLog(30)}")
                        }
                        // Group 4: \[ ... \]
                        matcher.group(4) != null && fullMatch.startsWith("\\[") -> {
                            content = matcher.group(4)
                            isBlock = true
                            Log.d(tag, "AST Visitor: Found BLOCK_BRACKET: ${content.takeForLog(30)}")
                        }
                        else -> {
                            // This case should ideally not be hit if regex groups are exhaustive for matches
                            Log.w(
                                tag,
                                "AST Visitor: Regex matched but no known group captured. Full: '${fullMatch.takeForLog(30)}'. Matched groups: ${matcher.group(1)}, ${matcher.group(2)}, ${matcher.group(3)}, ${matcher.group(4)}"
                            )
                            newNodes.add(Text(fullMatch)) // Add the unmatched part as text
                            lastEnd = matchEnd
                            continue
                        }
                    }
                    newNodes.add(CMMathNode(content.trim(), isBlock))
                    lastEnd = matchEnd
                }

                if (modified && lastEnd < literal.length) {
                    newNodes.add(Text(literal.substring(lastEnd)))
                }

                if (modified && newNodes.isNotEmpty()) {
                    var currentNode: Node = textNode
                    newNodes.forEach { newNode ->
                        currentNode.insertAfter(newNode)
                        currentNode = newNode
                    }
                    textNode.unlink()
                    Log.d(tag, "AST Visitor: Replaced TextNode with ${newNodes.size} new nodes.")
                }
            }
        }
        documentNode.accept(astVisitor)
        Log.d(tag, "AST traversal complete.")

        html = renderer.render(documentNode)
        Log.d(tag, "2. HTML from CommonMark (len ${html.length}):\n${html.takeForLog(3000)}")

    } catch (e: Exception) {
        Log.e(tag, "CRITICAL Error during CommonMark parsing or rendering: ${e.message}", e)
        return "<p style='color:red; font-weight:bold;'>[Markdown Rendering Error: ${e.javaClass.simpleName}]</p>" +
                "<h4>Original Content (first 500 chars):</h4><pre style='background-color:#f0f0f0; padding:10px; border:1px solid #ccc; white-space: pre-wrap; word-wrap: break-word;'>${
                    originalMarkdown.takeForLog(500).replace("<", "<").replace(">", ">")
                }</pre>"
    }

    val htmlBeforeCustomReplace = html
    html = REGEX_CUSTOM_REPLACE.replace(html, "[$1]") // Your custom placeholder replacement
    if (html != htmlBeforeCustomReplace) {
        Log.d(tag, "3. HTML after Custom Replace (len ${html.length}):\n${html.takeForLog(1000)}")
    } else {
        Log.d(tag, "3. HTML after Custom Replace: No changes by custom replace.")
    }

    val safelist: Safelist = Safelist.relaxed()
        .addTags(
            "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
            "strong", "em", "del", "code", "pre", "blockquote",
            "ul", "ol", "li", "table", "thead", "tbody", "tr", "th", "td", "a",
            "span", "div" // Ensure span and div are allowed for KaTeX output
        )
        .addAttributes(":all", "class", "style") // Allow class for katex-math-inline/display
        .addAttributes("a", "href", "title")
        .addProtocols("a", "href", "#", "http", "https", "mailto")

    val outputSettings = Document.OutputSettings().prettyPrint(false).indentAmount(0)
    val cleanHtml: String = Jsoup.clean(html, "", safelist, outputSettings)

    if (html != cleanHtml) {
        Log.w(tag, "4. HTML was MODIFIED by Jsoup sanitization.")
        Log.d(tag, "HTML before Jsoup: \n${html.takeForLog(1000)}")
        Log.d(tag, "HTML after Jsoup: \n${cleanHtml.takeForLog(1000)}")
    } else {
        Log.d(tag, "4. HTML was NOT modified by Jsoup sanitization.")
    }
    Log.d(tag, "==== ConvertMarkdownToHtml (CommonMark) END ====")
    return cleanHtml.trim()
}

// Helper to log only a portion of long strings
private fun String.takeForLog(n: Int): String {
    val eolReplacement = "↵" // Unicode for newline symbol
    return if (this.length > n) {
        this.substring(0, n).replace("\n", eolReplacement) + "..."
    } else {
        this.replace("\n", eolReplacement)
    }
}
