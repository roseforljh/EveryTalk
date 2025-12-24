package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * JavaScript/TypeScript 语法高亮器（简化版）
 * 
 * 使用简单的正则表达式匹配，确保可靠性
 */
object JavaScriptHighlighter : LanguageHighlighter {
    
    // JavaScript/TypeScript 关键字
    private val keywords = setOf(
        "const", "let", "var", "function", "class", "interface", "type", "enum",
        "extends", "implements", "static", "readonly", "abstract", "private",
        "public", "protected", "override", "if", "else", "switch", "case",
        "default", "for", "while", "do", "break", "continue", "return", "throw",
        "try", "catch", "finally", "import", "export", "from", "as", "async",
        "await", "yield", "new", "delete", "typeof", "instanceof", "in", "of",
        "void", "this", "super", "with", "debugger", "declare", "namespace",
        "module", "keyof", "infer", "is", "asserts", "never", "unknown", "any",
        "number", "string", "boolean", "symbol", "bigint", "object"
    )
    
    // 预编译的正则表达式
    private val multiLineCommentPattern = Pattern.compile("/\\*[\\s\\S]*?\\*/")
    private val singleLineCommentPattern = Pattern.compile("//.*")
    private val templateStringPattern = Pattern.compile("`[^`]*`")
    private val doubleStringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val singleStringPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    private val hexNumberPattern = Pattern.compile("\\b0x[0-9a-fA-F]+\\b")
    private val numberPattern = Pattern.compile("\\b\\d+\\.?\\d*(?:[eE][+-]?\\d+)?\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_$][a-zA-Z0-9_$]*\\b")
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*(?=\\()")
    private val operatorPattern = Pattern.compile("===|!==|==|!=|<=|>=|&&|\\|\\||=>|\\.\\.\\.|\\+\\+|--|[+\\-*/%=<>!&|^~?:]")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]();,.]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 按优先级处理
        
        // 1. 多行注释
        findMatches(multiLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 2. 单行注释
        findMatches(singleLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 3. 模板字符串
        findMatches(templateStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 4. 双引号字符串
        findMatches(doubleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 5. 单引号字符串
        findMatches(singleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 6. 十六进制数字
        findMatches(hexNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 7. 普通数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 8. 函数调用（在标识符之前，因为函数名也是标识符）
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
        
        // 9. 标识符（关键字、类名、变量）
        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (!processed[start]) {
                val word = identMatcher.group()
                
                val tokenType = when {
                    keywords.contains(word) -> TokenType.KEYWORD
                    word == "true" || word == "false" -> TokenType.BOOLEAN
                    word == "null" || word == "undefined" || word == "NaN" -> TokenType.NULL
                    word[0].isUpperCase() -> TokenType.CLASS_NAME
                    else -> TokenType.VARIABLE
                }
                
                tokens.add(Token(tokenType, start, end, word))
                for (i in start until end) {
                    processed[i] = true
                }
            }
        }
        
        // 10. 运算符
        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)
        
        // 11. 标点符号
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