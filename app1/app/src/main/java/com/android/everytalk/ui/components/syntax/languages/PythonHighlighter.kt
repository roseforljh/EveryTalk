package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * Python 语法高亮器（简化版）
 * 
 * 使用简单的正则表达式匹配，确保可靠性
 */
object PythonHighlighter : LanguageHighlighter {
    
    // Python 关键字
    private val keywords = setOf(
        "def", "class", "lambda", "if", "elif", "else", "for", "while",
        "break", "continue", "return", "yield", "pass", "try", "except",
        "finally", "raise", "assert", "import", "from", "as", "with",
        "and", "or", "not", "in", "is", "async", "await", "global",
        "nonlocal", "del", "match", "case"
    )
    
    // 预编译的正则表达式
    private val docstringPattern = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''")
    private val commentPattern = Pattern.compile("#.*")
    private val fstringPattern = Pattern.compile("[fFrRbB]*[fF][fFrRbB]*[\"'][^\"']*[\"']")
    private val doubleStringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val singleStringPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    private val decoratorPattern = Pattern.compile("@[a-zA-Z_][a-zA-Z0-9_]*")
    private val hexNumberPattern = Pattern.compile("\\b0x[0-9a-fA-F_]+\\b")
    private val numberPattern = Pattern.compile("\\b\\d[\\d_]*\\.?[\\d_]*(?:[eE][+-]?[\\d_]+)?[jJ]?\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()")
    private val operatorPattern = Pattern.compile("==|!=|<=|>=|<<|>>|\\*\\*|//|->|:=|[+\\-*/%=<>!&|^~@]")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]();:,.]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 按优先级处理
        
        // 1. 文档字符串
        findMatches(docstringPattern, code, TokenType.DOCSTRING, tokens, processed)
        
        // 2. 注释
        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 3. f-string
        findMatches(fstringPattern, code, TokenType.FSTRING, tokens, processed)
        
        // 4. 普通字符串
        findMatches(doubleStringPattern, code, TokenType.STRING, tokens, processed)
        findMatches(singleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 5. 装饰器
        findMatches(decoratorPattern, code, TokenType.DECORATOR, tokens, processed)
        
        // 6. 十六进制数字
        findMatches(hexNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 7. 普通数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 8. 函数调用
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
        
        // 9. 标识符
        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (!processed[start]) {
                val word = identMatcher.group()
                
                val tokenType = when {
                    keywords.contains(word) -> TokenType.KEYWORD
                    word == "True" || word == "False" -> TokenType.BOOLEAN
                    word == "None" -> TokenType.NULL
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