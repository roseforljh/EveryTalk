// file: com/example/everytalk/util/MarkdownUtil.kt
package com.example.everytalk.util

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist

// --- Flexmark-java Imports ---
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.util.data.MutableDataHolder // Correct type for options in Extension methods
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.escaped.character.EscapedCharacterExtension
import com.vladsch.flexmark.ext.typographic.TypographicExtension
import com.vladsch.flexmark.parser.LightInlineParser
import com.vladsch.flexmark.parser.InlineParser // For InlineParserExtension's finalize methods
import com.vladsch.flexmark.parser.InlineParserExtensionFactory
import com.vladsch.flexmark.parser.InlineParserExtension
import com.vladsch.flexmark.util.ast.Node // Flexmark's base AST Node
import com.vladsch.flexmark.util.sequence.BasedSequence
import com.vladsch.flexmark.html.renderer.NodeRenderer
import com.vladsch.flexmark.html.renderer.NodeRendererContext
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler
import com.vladsch.flexmark.html.renderer.NodeRendererFactory
import com.vladsch.flexmark.util.data.DataHolder
import com.vladsch.flexmark.html.HtmlWriter
import com.vladsch.flexmark.util.ast.DoNotDecorate
import com.vladsch.flexmark.util.sequence.CharSubSequence
import com.vladsch.flexmark.util.dependency.Dependent
import java.util.regex.Pattern
import java.util.regex.Matcher as JavaMatcher
import java.util.Collections

// --- Regexes ---
private val REGEX_LEADING_NEWLINES = Regex("(?<!\n\n)([ \t]*\n)?(```)")
private val REGEX_TRAILING_NEWLINES =
    Regex("(```[^\n]*\n(?:[^\n]*\n)*?[^\n]*```)\n?([ \t]*)(?!\n\n)")
private val REGEX_EXCESSIVE_NEWLINES = Regex("\n{3,}")
private val REGEX_CUSTOM_REPLACE = Regex("\\\$\\{([^}]+)\\}")


// --- Flexmark Custom Math Node ---
open class MathNode(chars: BasedSequence) : Node(chars), DoNotDecorate {
    var isBlock: Boolean = false
    var mathText: BasedSequence = BasedSequence.NULL

    private val openingMarkerLength: Int get() = if (isBlock) 2 else 1
    private val closingMarkerLength: Int get() = if (isBlock) 2 else 1

    val openingMarker: BasedSequence
        get() = chars.subSequence(0, openingMarkerLength)

    val closingMarker: BasedSequence
        get() = chars.subSequence(chars.length - closingMarkerLength, chars.length)

    override fun getSegments(): Array<BasedSequence> =
        arrayOf(openingMarker, mathText, closingMarker)

    override fun getAstExtra(out: StringBuilder) {
        delimitedSegmentSpanChars(out, openingMarker, closingMarker, mathText, "math")
    }
}

// --- Custom Inline Parser Extension ---
class MathInlineParserExtension : InlineParserExtension {

    private val BLOCK_DOLLAR_PATTERN = Pattern.compile("""\$\$(.*?)\$\$""", Pattern.DOTALL)
    private val BLOCK_BRACKET_PATTERN = Pattern.compile("""\\\[(.*?)\\]""", Pattern.DOTALL)
    private val INLINE_DOLLAR_PATTERN = Pattern.compile("""\$([^${'$'}]|\\\$)+?\$""")
    private val INLINE_PAREN_PATTERN = Pattern.compile("""\\\((.*?)\\\)""", Pattern.DOTALL)

    override fun finalizeDocument(parser: InlineParser) {}
    override fun finalizeBlock(parser: InlineParser) {}

    override fun parse(parser: LightInlineParser): Boolean {
        val startIndex = parser.index

        fun addMathNode(fullMatchString: String, contentString: String, isBlockType: Boolean) {
            val fullMatchSequence = CharSubSequence.of(fullMatchString)
            val contentSequence = CharSubSequence.of(contentString)
            val mathNode = MathNode(fullMatchSequence)
            mathNode.isBlock = isBlockType
            mathNode.mathText = contentSequence
            parser.flushTextNode()
            parser.block.appendChild(mathNode)
            parser.index = startIndex + fullMatchSequence.length
        }

        var currentMatcher: JavaMatcher?

        currentMatcher = parser.matcher(BLOCK_DOLLAR_PATTERN)
        if (currentMatcher != null && currentMatcher.matches()) {
            val fullMatchStr = currentMatcher.group(0)
            val contentStr = currentMatcher.group(1)
            if (fullMatchStr != null && contentStr != null) {
                addMathNode(fullMatchStr, contentStr, true)
                return true
            }
        }

        currentMatcher = parser.matcher(BLOCK_BRACKET_PATTERN)
        if (currentMatcher != null && currentMatcher.matches()) {
            val fullMatchStr = currentMatcher.group(0)
            val contentStr = currentMatcher.group(1)
            if (fullMatchStr != null && contentStr != null) {
                addMathNode(fullMatchStr, contentStr, true)
                return true
            }
        }

        currentMatcher = parser.matcher(INLINE_DOLLAR_PATTERN)
        if (currentMatcher != null && currentMatcher.matches()) {
            val fullMatchStr = currentMatcher.group(0)
            if (fullMatchStr != null) {
                val contentStr = fullMatchStr.substring(1, fullMatchStr.length - 1)
                addMathNode(fullMatchStr, contentStr, false)
                return true
            }
        }

        currentMatcher = parser.matcher(INLINE_PAREN_PATTERN)
        if (currentMatcher != null && currentMatcher.matches()) {
            val fullMatchStr = currentMatcher.group(0)
            if (fullMatchStr != null) {
                val contentStr = fullMatchStr.substring(2, fullMatchStr.length - 2)
                addMathNode(fullMatchStr, contentStr, false)
                return true
            }
        }
        return false
    }
}

class MathInlineParserExtensionFactory : InlineParserExtensionFactory {
    override fun getCharacters(): CharSequence {
        return "\$\\"
    }

    override fun apply(inlineParser: LightInlineParser): InlineParserExtension {
        return MathInlineParserExtension()
    }

    override fun getAfterDependents(): MutableSet<Class<*>>? {
        return null
    }

    override fun getBeforeDependents(): MutableSet<Class<*>>? {
        return null
    }

    override fun affectsGlobalScope(): Boolean {
        return false
    }
}

// --- Custom Node Renderer for MathNode ---
class MathNodeRenderer(options: DataHolder) : NodeRenderer {
    override fun getNodeRenderingHandlers(): Set<NodeRenderingHandler<*>> {
        return setOf(
            NodeRenderingHandler(
                MathNode::class.java,
                { node: MathNode, context: NodeRendererContext, html: HtmlWriter ->
                    if (node.isBlock) {
                        html.attr("class", "katex-math-display")
                        html.tag("div")
                        html.text(node.mathText.toString())
                        html.tag("/div")
                    } else {
                        html.attr("class", "katex-math-inline")
                        html.tag("span")
                        html.text(node.mathText.toString())
                        html.tag("/span")
                    }
                }
            )
        )
    }

    class Factory : NodeRendererFactory {
        // Corrected: Renamed 'create' to 'apply' to match typical NodeRendererFactory interface
        override fun apply(options: DataHolder): NodeRenderer {
            return MathNodeRenderer(options)
        }
    }
}

// --- Flexmark Extension to tie it all together ---
class CustomMathExtension : Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {

    // From Parser.ParserExtension
    // Corrected parameter type to MutableDataHolder
    override fun parserOptions(options: MutableDataHolder) {
        // Empty implementation if no parser options need to be set by this extension
    }

    // From Parser.ParserExtension
    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.customInlineParserExtensionFactory(MathInlineParserExtensionFactory())
    }

    // From HtmlRenderer.HtmlRendererExtension
    // Corrected parameter type to MutableDataHolder
    override fun rendererOptions(options: MutableDataHolder) {
        // Empty implementation if no renderer options need to be set by this extension
    }

    // From HtmlRenderer.HtmlRendererExtension
    override fun extend(rendererBuilder: HtmlRenderer.Builder, rendererType: String) {
        if (rendererType == "HTML") {
            rendererBuilder.nodeRendererFactory(MathNodeRenderer.Factory())
        }
    }

    companion object {
        @JvmStatic
        fun create(): CustomMathExtension {
            return CustomMathExtension()
        }
    }
}


// --- Main Conversion Function ---
internal fun convertMarkdownToHtml(originalMarkdown: String): String {
    if (originalMarkdown.isBlank()) {
        Log.d("MarkdownToHtmlFlex", "Input MD is blank, returning empty string.")
        return ""
    }
    Log.d("MarkdownToHtmlFlex", "==== ConvertMarkdownToHtml (Flexmark) START ====")
    Log.d(
        "MarkdownToHtmlFlex",
        "1. Original MD (len ${originalMarkdown.length}):\n${originalMarkdown.take(1000)}"
    )

    var processedMarkdown = originalMarkdown

    val mdBeforePreProcessing = processedMarkdown
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

    if (processedMarkdown != mdBeforePreProcessing) {
        Log.d(
            "MarkdownToHtmlFlex",
            "2. MD after Pre-processing (len ${processedMarkdown.length}):\n${
                processedMarkdown.take(
                    1000
                )
            }"
        )
    } else {
        Log.d(
            "MarkdownToHtmlFlex",
            "2. MD after Pre-processing: No changes by pre-processing regexes."
        )
    }

    val options = MutableDataSet()
    options.set(
        Parser.EXTENSIONS, listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create(),
            EscapedCharacterExtension.create(),
            TypographicExtension.create(),
            CustomMathExtension.create()
        )
    )

    val parser: Parser = Parser.builder(options).build()
    val renderer: HtmlRenderer = HtmlRenderer.builder(options).build()

    val documentNode = parser.parse(processedMarkdown)
    var html: String = renderer.render(documentNode)
    Log.d("MarkdownToHtmlFlex", "3. HTML from Flexmark (len ${html.length}):\n${html.take(1000)}")

    val htmlBeforeCustomReplace = html
    html = REGEX_CUSTOM_REPLACE.replace(html, "[$1]")
    if (html != htmlBeforeCustomReplace) {
        Log.d(
            "MarkdownToHtmlFlex",
            "4. HTML after Custom Replace (len ${html.length}):\n${html.take(1000)}"
        )
    } else {
        Log.d("MarkdownToHtmlFlex", "4. HTML after Custom Replace: No changes by custom replace.")
    }

    val safelist: Safelist = Safelist.relaxed()
        .addTags(
            "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
            "strong", "em", "del", "code", "pre", "blockquote",
            "ul", "ol", "li", "table", "thead", "tbody", "tr", "th", "td", "a",
            "span", "div"
        )
        .addAttributes(":all", "class", "style")
        .addAttributes("a", "href", "title")
        .addProtocols("a", "href", "#", "http", "https", "mailto")

    val outputSettings = Document.OutputSettings().prettyPrint(false)
    val cleanHtml: String = Jsoup.clean(html, "", safelist, outputSettings)

    if (html != cleanHtml) {
        Log.w("MarkdownToHtmlFlex", "5. HTML was MODIFIED by Jsoup sanitization.")
        Log.d(
            "MarkdownToHtmlFlex",
            "   Original HTML before Jsoup (len ${html.length}):\n${html.take(1000)}"
        )
        Log.d(
            "MarkdownToHtmlFlex",
            "   Cleaned HTML after Jsoup (len ${cleanHtml.length}):\n${cleanHtml.take(1000)}"
        )
    } else {
        Log.d("MarkdownToHtmlFlex", "5. HTML was NOT modified by Jsoup sanitization.")
    }
    Log.d("MarkdownToHtmlFlex", "==== ConvertMarkdownToHtml (Flexmark) END ====")
    return cleanHtml.trim()
}