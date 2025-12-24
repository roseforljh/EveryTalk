package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

object GoHighlighter : LanguageHighlighter {
    private val keywords = setOf(
        "break", "default", "func", "interface", "select", "case", "defer", "go", "map", "struct",
        "chan", "else", "goto", "package", "switch", "const", "fallthrough", "if", "range", "type",
        "continue", "for", "import", "return", "var", "true", "false", "nil", "iota"
    )

    private val commentPattern = Pattern.compile("/\\*[\\s\\S]*?\\*/|//.*")
    private val stringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|`[^`]*`")
    private val numberPattern = Pattern.compile("\\b0x[0-9a-fA-F]+\\b|\\b\\d+\\.?\\d*\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()")
    private val operatorPattern = Pattern.compile("==|!=|<=|>=|&&|\\|\\||<-|:=|\\.\\.\\.|[+\\-*/%=<>!&|^~?:]")

    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)

        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)
        findMatches(stringPattern, code, TokenType.STRING, tokens, processed)
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)

        val funcMatcher = functionCallPattern.matcher(code)
        while (funcMatcher.find()) {
            val start = funcMatcher.start(1)
            val end = funcMatcher.end(1)
            if (!processed[start]) {
                tokens.add(Token(TokenType.FUNCTION, start, end, funcMatcher.group(1)))
                for (i in start until end) processed[i] = true
            }
        }

        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (!processed[start]) {
                val word = identMatcher.group()
                val tokenType = when {
                    keywords.contains(word) -> if (word == "true" || word == "false") TokenType.BOOLEAN else if (word == "nil") TokenType.NULL else TokenType.KEYWORD
                    else -> TokenType.VARIABLE
                }
                tokens.add(Token(tokenType, start, end, word))
                for (i in start until end) processed[i] = true
            }
        }

        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)
        findMatches(Pattern.compile("[{}\\[\\]();,]"), code, TokenType.PUNCTUATION, tokens, processed)

        return tokens.sortedBy { it.start }
    }

    private fun findMatches(pattern: Pattern, code: String, type: TokenType, tokens: MutableList<Token>, processed: BooleanArray) {
        val matcher = pattern.matcher(code)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            if (!processed[start]) {
                tokens.add(Token(type, start, end, matcher.group()))
                for (i in start until end) processed[i] = true
            }
        }
    }
}