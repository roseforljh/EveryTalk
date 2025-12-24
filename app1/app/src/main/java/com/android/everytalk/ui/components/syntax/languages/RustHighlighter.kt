package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

object RustHighlighter : LanguageHighlighter {
    private val keywords = setOf(
        "abstract", "alignof", "as", "become", "box", "break", "const", "continue", "crate", "do",
        "else", "enum", "extern", "false", "final", "fn", "for", "if", "impl", "in", "let", "loop",
        "macro", "match", "mod", "move", "mut", "offsetof", "override", "priv", "proc", "pub",
        "pure", "ref", "return", "self", "sizeof", "static", "struct", "super", "trait", "true",
        "type", "typeof", "unsafe", "unsized", "use", "virtual", "where", "while", "yield"
    )

    private val commentPattern = Pattern.compile("/\\*[\\s\\S]*?\\*/|//.*")
    private val stringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|r#*\"[\\s\\S]*?\"#*")
    private val attributePattern = Pattern.compile("#\\[[^\\]]*\\]|#!?\\[[^\\]]*\\]")
    private val numberPattern = Pattern.compile("\\b0x[0-9a-fA-F_]+\\b|\\b\\d[\\d_]*\\.?[\\d_]*[fiu]?(?:32|64|128|size)?\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()")
    private val operatorPattern = Pattern.compile("==|!=|<=|>=|&&|\\|\\||->|=>|::|\\.\\.|\\.\\.=|\\?|[+\\-*/%=<>!&|^~]")

    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)

        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)
        findMatches(stringPattern, code, TokenType.STRING, tokens, processed)
        findMatches(attributePattern, code, TokenType.ANNOTATION, tokens, processed)
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)

        val funcMatcher = functionCallPattern.matcher(code)
        while (funcMatcher.find()) {
            val start = funcMatcher.start(1)
            val end = funcMatcher.end(1)
            if (!processed[start]) {
                val name = funcMatcher.group(1)
                // 宏调用以 ! 结尾
                if (code.length > end && code[end] == '!') {
                    tokens.add(Token(TokenType.FUNCTION, start, end + 1, name + "!"))
                    processed[end] = true
                } else {
                    tokens.add(Token(TokenType.FUNCTION, start, end, name))
                }
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
                    keywords.contains(word) -> if (word == "true" || word == "false") TokenType.BOOLEAN else TokenType.KEYWORD
                    word[0].isUpperCase() -> TokenType.CLASS_NAME
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