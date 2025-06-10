package com.example.everytalk.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.util.regex.Pattern

object CodeHighlighter {

    // region Final Color Palette (Based on User Image)
    private val colorComment = Color(0xFF7F848E)      // Comment
    private val colorPunctuation = Color(0xFFABB2BF)  // Punctuation: <> {} () , ;
    private val colorHtmlTag = Color(0xFFE06C75)      // HTML Tag: <div> <h1>
    private val colorHtmlAttrName = Color(0xFFD19A66) // HTML Attribute Name: class=, id=
    private val colorHtmlAttrValue = Color(0xFF98C379) // HTML Attribute Value: "container"
    private val colorCssSelector = Color(0xFFC678DD)  // CSS Selector: .class, #id
    private val colorCssProperty = Color(0xFF61AFEF)  // CSS Property: font-size
    private val colorCssValue = Color(0xFFD19A66)      // CSS Value: 16px, #fff, center
    private val colorCssUnit = Color(0xFFABB2BF)      // CSS Unit: px, %, rem
    private val colorJsKeyword = Color(0xFFC678DD)    // JS Keyword: const, function
    private val colorJsFunction = Color(0xFF61AFEF)    // JS Function Name: goToSlide
    private val colorJsVariable = Color(0xFFE06C75)    // JS Variable: carousel
    private val colorJsString = Color(0xFF98C379)      // JS String
    private val colorJsNumber = Color(0xFFD19A66)      // JS Number
    private val colorJsOperator = Color(0xFF56B6C2)    // JS Operator: =, +, %
    // endregion

    private data class Rule(val pattern: Pattern, val color: Color, val groupIndex: Int = 1)

    private fun getRules(language: String?): List<Rule> {
        val lang = language?.lowercase()?.trim()
        // The order of rules is critical. More specific rules must come first.
        return when (lang) {
            "html" -> listOf(
                Rule(Pattern.compile("<!--[\\s\\S]*?-->"), colorComment, 0),
                Rule(Pattern.compile("(<\\/?)([a-zA-Z0-9\\-]+)"), colorHtmlTag, 2),
                Rule(Pattern.compile("\\s([a-zA-Z\\-]+)(?=\\s*=)"), colorHtmlAttrName, 1),
                Rule(Pattern.compile("(\"[^\"]*\"|'[^']*')"), colorHtmlAttrValue, 1),
                Rule(Pattern.compile("([<>/=])"), colorPunctuation, 1)
            )
            "css" -> listOf(
                Rule(Pattern.compile("\\/\\*[\\s\\S]*?\\*\\/"), colorComment, 0),
                Rule(Pattern.compile("(\"[^\"]*\"|'[^']*')"), colorCssValue, 1),
                Rule(Pattern.compile("(#[a-fA-F0-9]{3,8})\\b"), colorCssValue, 1),
                Rule(Pattern.compile("\\b(body|html|div|span|a|p|h[1-6])\\b(?=[\\s,{])"), colorCssSelector, 1),
                Rule(Pattern.compile("([#.]-?[_a-zA-Z]+[_a-zA-Z0-9-]*)(?=[\\s,{.:])"), colorCssSelector, 1),
                Rule(Pattern.compile("(:[:]?[-a-zA-Z_0-9]+)"), colorCssSelector, 1),
                Rule(Pattern.compile("\\b([a-zA-Z\\-]+)(?=\\s*:)"), colorCssProperty, 1),
                Rule(Pattern.compile("\\b(-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:px|em|rem|%|vh|vw|s|ms|deg|turn|fr)?)\\b"), colorCssValue, 1),
                Rule(Pattern.compile("\\b([a-zA-Z-]+)\\b"), colorCssValue, 1),
                Rule(Pattern.compile("([+\\-*/%])"), colorJsOperator, 1),
                Rule(Pattern.compile("([:;{}()\\[\\]])"), colorPunctuation, 1)
            )
            "javascript", "js" -> listOf(
                Rule(Pattern.compile("//.*|\\/\\*[\\s\\S]*?\\*\\/"), colorComment, 0),
                Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*`|`(?:\\\\.|[^`\\\\])*`)"), colorJsString, 1),
                Rule(Pattern.compile("\\b(const|let|var|function|return|if|else|for|while|switch|case|break|continue|new|this|import|export|from|default|async|await|try|catch|finally|class|extends|super|delete|in|instanceof|typeof|void)\\b"), colorJsKeyword, 1),
                Rule(Pattern.compile("\\b(document|window|console|Math|JSON|Promise|Array|Object|String|Number|Boolean|Date|RegExp)\\b"), colorJsVariable, 1),
                Rule(Pattern.compile("\\b(true|false|null|undefined)\\b"), colorJsKeyword, 1),
                Rule(Pattern.compile("(?<=\\bfunction\\s)([a-zA-Z0-9_]+)"), colorJsFunction, 1),
                Rule(Pattern.compile("(?<=[\\s.(])([a-zA-Z0-9_]+)(?=\\s*\\()"), colorJsFunction, 1),
                Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), colorJsNumber, 1),
                Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), colorJsOperator, 1),
                Rule(Pattern.compile("([;,.()\\[\\]{}])"), colorPunctuation, 1)
            )
            else -> emptyList()
        }
    }

    fun highlightToAnnotatedString(code: String, language: String?): AnnotatedString {
        val rules = getRules(language)
        
        return buildAnnotatedString {
            append(code)
            
            val appliedSpans = Array(code.length) { false }

            rules.forEach { rule ->
                val matcher = rule.pattern.matcher(code)
                while (matcher.find()) {
                    val targetGroup = rule.groupIndex
                    if (targetGroup > matcher.groupCount()) continue

                    val start = matcher.start(targetGroup)
                    val end = matcher.end(targetGroup)

                    if (start == -1) continue

                    var alreadyStyled = false
                    for (i in start until end) {
                        if (appliedSpans[i]) {
                            alreadyStyled = true
                            break
                        }
                    }

                    if (!alreadyStyled) {
                        addStyle(SpanStyle(color = rule.color), start, end)
                        for (i in start until end) {
                            appliedSpans[i] = true
                        }
                    }
                }
            }
        }
    }
}