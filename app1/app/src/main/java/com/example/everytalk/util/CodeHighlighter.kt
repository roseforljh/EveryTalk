package com.example.everytalk.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Defines the color scheme for syntax highlighting.
 * This allows for easy theme switching.
 */
data class SyntaxTheme(
    val comment: Color,
    val punctuation: Color,
    val keyword: Color,
    val operator: Color,
    val type: Color,
    val function: Color,
    val string: Color,
    val number: Color,
    val variable: Color,
    val annotation: Color,
    val attribute: Color,
    val tag: Color,
    val value: Color,
    val cssSelector: Color,
    val cssProperty: Color,
    val module: Color
)

/**
 * A beautiful, light theme inspired by Catppuccin Latte.
 */
val CatppuccinLatteTheme = SyntaxTheme(
    comment = Color(0xFF9CA0B0),
    punctuation = Color(0xFF888B9D),
    keyword = Color(0xFF8839EF),
    operator = Color(0xFF179299),
    type = Color(0xFFD20F39), // Changed for better visibility
    function = Color(0xFF1E66F5),
    string = Color(0xFF40A02B),
    number = Color(0xFFFE640B),
    variable = Color(0xFF4C4F69),
    annotation = Color(0xFFD20F39),
    attribute = Color(0xFFDD7878),
    tag = Color(0xFF1E66F5),
    value = Color(0xFF179299),
    cssSelector = Color(0xFF7287FD),
    cssProperty = Color(0xFF1E66F5),
    module = Color(0xFF7287FD)
)

object CodeHighlighter {

    private data class Rule(val pattern: Pattern, val color: (SyntaxTheme) -> Color, val groupIndex: Int = 1)

    // Cache for compiled language rules to avoid re-compilation on every call.
    private val languageRuleCache = ConcurrentHashMap<String, List<Rule>>()

    private fun getRules(language: String?, theme: SyntaxTheme): List<Rule> {
        val lang = language?.lowercase()?.trim() ?: "text"
        return languageRuleCache.getOrPut(lang) {
            // The order of rules is critical. More specific rules must come first.
            when (lang) {
                "html" -> listOf(
                    Rule(Pattern.compile("<!--[\\s\\S]*?-->"), { it.comment }, 0),
                    Rule(Pattern.compile("(<\\/?)([a-zA-Z0-9\\-]+)"), { it.tag }, 2),
                    Rule(Pattern.compile("\\s([a-zA-Z\\-]+)(?=\\s*=)"), { it.attribute }, 1),
                    Rule(Pattern.compile("(\"[^\"]*\"|'[^']*')"), { it.value }, 1),
                    Rule(Pattern.compile("(&[a-zA-Z0-9#]+;)"), { it.punctuation }, 1),
                    Rule(Pattern.compile("([<>/=])"), { it.punctuation }, 1)
                )
                "css" -> listOf(
                    Rule(Pattern.compile("\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"[^\"]*\"|'[^']*')"), { it.value }, 1),
                    Rule(Pattern.compile("(#[a-fA-F0-9]{3,8})\\b"), { it.value }, 1),
                    Rule(Pattern.compile("\\b(body|html|div|span|a|p|h[1-6])\\b(?=[\\s,{])"), { it.cssSelector }, 1),
                    Rule(Pattern.compile("([#.]-?[_a-zA-Z]+[_a-zA-Z0-9-]*)(?=[\\s,{.:])"), { it.cssSelector }, 1),
                    Rule(Pattern.compile("(:[:]?[-a-zA-Z_0-9]+)"), { it.cssSelector }, 1),
                    Rule(Pattern.compile("\\b([a-zA-Z\\-]+)(?=\\s*:)"), { it.cssProperty }, 1),
                    Rule(Pattern.compile("\\b(-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:px|em|rem|%|vh|vw|s|ms|deg|turn|fr)?)\\b"), { it.value }, 1),
                    Rule(Pattern.compile("([+\\-*/%])"), { it.operator }, 1),
                    Rule(Pattern.compile("([:;{}()\\[\\]])"), { it.punctuation }, 1)
                )
                "javascript", "js", "typescript", "ts" -> {
                    val keywords = "const|let|var|function|return|if|else|for|while|switch|case|break|continue|new|this|import|export|from|default|async|await|try|catch|finally|class|extends|super|delete|in|instanceof|typeof|void|get|set|public|private|protected|readonly|enum|type|interface|implements|declare|namespace"
                    listOf(
                        Rule(Pattern.compile("//.*|\\/\\*[\\s\\S]*?\\*\\/"), { it.comment }, 0),
                        Rule(Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)"), { it.string }),
                        Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), { it.number }),
                        Rule(Pattern.compile("\\b($keywords)\\b"), { it.keyword }),
                        Rule(Pattern.compile("\\b(document|window|console|Math|JSON|Promise|Array|Object|String|Number|Boolean|Date|RegExp|any|string|number|boolean|void|null|undefined|never)\\b"), { it.type }),
                        Rule(Pattern.compile("\\b(true|false|null|undefined)\\b"), { it.keyword }),
                        Rule(Pattern.compile("(@[a-zA-Z_][a-zA-Z0-9_]*)"), { it.annotation }),
                        Rule(Pattern.compile("(?<=[\\s.(,])(?!($keywords)\\b)([a-zA-Z_][a-zA-Z0-9_]*)(?=\\s*\\()"), { it.function }),
                        Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), { it.operator }),
                        Rule(Pattern.compile("([;,.()\\[\\]{}])"), { it.punctuation })
                    )
                }
                "python", "py" -> listOf(
                    Rule(Pattern.compile("#.*"), { it.comment }, 0),
                    Rule(Pattern.compile("(\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), { it.string }),
                    Rule(Pattern.compile("\\b([0-9]+(?:\\.[0-9]+)?)\\b"), { it.number }),
                    Rule(Pattern.compile("\\b(def|class|if|else|elif|for|while|return|import|from|as|try|except|finally|with|lambda|pass|break|continue|in|is|not|and|or|True|False|None|self|async|await)\\b"), { it.keyword }),
                    Rule(Pattern.compile("\\b(int|str|float|list|dict|tuple|set|bool|object)\\b"), { it.type }),
                    Rule(Pattern.compile("(@[a-zA-Z_][a-zA-Z0-9_]*)"), { it.annotation }),
                    Rule(Pattern.compile("(?<=\\bdef\\s)([a-zA-Z0-9_]+)"), { it.function }),
                    Rule(Pattern.compile("(?<=\\bclass\\s)([a-zA-Z0-9_]+)"), { it.type }),
                    Rule(Pattern.compile("([=+\\-*/%<>!&|?^])"), { it.operator }),
                    Rule(Pattern.compile("([:,.()\\[\\]{}])"), { it.punctuation })
                )
                // Add other languages here...
                else -> listOf( // Default fallback for plain text
                    Rule(Pattern.compile("."), { it.variable }, 0)
                )
            }
        }
    }

    fun highlightToAnnotatedString(
        code: String,
        language: String?,
        theme: SyntaxTheme = CatppuccinLatteTheme
    ): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString(code)

        return try {
            val rules = getRules(language, theme)
            buildAnnotatedString {
                append(code) // Append the raw code first

                // Use a list of ranges for more efficient overlap checking
                val appliedRanges = mutableListOf<IntRange>()

                rules.forEach { rule ->
                    val matcher = rule.pattern.matcher(code)
                    while (matcher.find()) {
                        val targetGroup = rule.groupIndex
                        if (targetGroup > matcher.groupCount()) continue

                        val start = matcher.start(targetGroup)
                        val end = matcher.end(targetGroup)

                        if (start == -1 || start >= end) continue

                        val currentRange = start until end
                        val isOverlapping = appliedRanges.any { it.first < currentRange.last && it.last > currentRange.first }

                        if (!isOverlapping) {
                            addStyle(SpanStyle(color = rule.color(theme)), start, end)
                            appliedRanges.add(currentRange)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Graceful fallback in case of a regex error or other issue
            AnnotatedString(code)
        }
    }
}