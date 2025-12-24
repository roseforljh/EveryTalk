package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * Kotlin 语法高亮器（简化版）
 * 
 * 使用简单的正则表达式匹配，确保可靠性
 */
object KotlinHighlighter : LanguageHighlighter {
    
    // Kotlin 关键字
    private val keywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "enum", "annotation",
        "typealias", "companion", "data", "sealed", "inner", "value",
        "public", "private", "protected", "internal", "open", "final", "abstract",
        "override", "lateinit", "const", "suspend", "inline", "noinline",
        "crossinline", "reified", "external", "tailrec", "operator", "infix",
        "expect", "actual", "vararg", "out", "in", "if", "else", "when", "for",
        "while", "do", "break", "continue", "return", "throw", "try", "catch",
        "finally", "is", "as", "by", "where", "import", "package", "this", "super",
        "constructor", "init", "get", "set"
    )
    
    // 预编译的正则表达式
    private val kdocPattern = Pattern.compile("/\\*\\*[\\s\\S]*?\\*/")
    private val multiLineCommentPattern = Pattern.compile("/\\*[\\s\\S]*?\\*/")
    private val singleLineCommentPattern = Pattern.compile("//.*")
    private val multiLineStringPattern = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"")
    private val doubleStringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val charPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)'")
    private val annotationPattern = Pattern.compile("@[a-zA-Z_][a-zA-Z0-9_]*")
    private val stringTemplatePattern = Pattern.compile("\\$\\{[^}]+\\}|\\$[a-zA-Z_][a-zA-Z0-9_]*")
    private val hexNumberPattern = Pattern.compile("\\b0x[0-9a-fA-F_]+[uUlL]*\\b")
    private val numberPattern = Pattern.compile("\\b\\d[\\d_]*\\.?[\\d_]*(?:[eE][+-]?[\\d_]+)?[fFdDlLuU]*\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()")
    private val operatorPattern = Pattern.compile("===|!==|==|!=|<=|>=|&&|\\|\\||\\?:|\\?\\.|!!|->|::|[+\\-*/%=<>!&|^~?:]")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]();,.]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 按优先级处理
        
        // 1. KDoc
        findMatches(kdocPattern, code, TokenType.DOCSTRING, tokens, processed)
        
        // 2. 多行注释
        findMatches(multiLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 3. 单行注释
        findMatches(singleLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 4. 多行字符串
        findMatches(multiLineStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 5. 普通字符串
        findMatches(doubleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 6. 字符
        findMatches(charPattern, code, TokenType.STRING, tokens, processed)
        
        // 7. 注解
        findMatches(annotationPattern, code, TokenType.ANNOTATION, tokens, processed)
        
        // 8. 字符串模板
        findMatches(stringTemplatePattern, code, TokenType.STRING_TEMPLATE, tokens, processed)
        
        // 9. 十六进制数字
        findMatches(hexNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 10. 普通数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 11. 函数调用
        val funcMatcher = functionCallPattern.matcher(code)
        while (funcMatcher.find()) {
            val start = funcMatcher.start(1)
            val end = funcMatcher.end(1)
            if (!processed[start]) {
                tokens.add(Token(TokenType.FUNCTION, start, end, funcMatcher.group(1)))
                for (i in start until end) {
                    processed[i] = true
                }
            }
        }
        
        // 12. 标识符
        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (!processed[start]) {
                val word = identMatcher.group()
                
                val tokenType = when {
                    keywords.contains(word) -> TokenType.KEYWORD
                    word == "true" || word == "false" -> TokenType.BOOLEAN
                    word == "null" -> TokenType.NULL
                    word[0].isUpperCase() -> TokenType.CLASS_NAME
                    else -> TokenType.VARIABLE
                }
                
                tokens.add(Token(tokenType, start, end, word))
                for (i in start until end) {
                    processed[i] = true
                }
            }
        }
        
        // 13. 运算符
        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)
        
        // 14. 标点符号
        findMatches(punctuationPattern, code, TokenType.PUNCTUATION, tokens, processed)
        
        return tokens.sortedBy { it.start }
    }
    
    private fun findMatches(
        pattern: Pattern,
        code: String,
        tokenType: TokenType,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ) {
        val matcher = pattern.matcher(code)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            
            if (processed[start]) continue
            
            tokens.add(Token(tokenType, start, end, matcher.group()))
            for (i in start until end) {
                processed[i] = true
            }
        }
    }
}