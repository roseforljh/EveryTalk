package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * Lua 语法高亮器
 * 
 * 支持：
 * - Lua 关键字
 * - 字符串（单引号、双引号、长字符串[[...]]）
 * - 注释（单行 --、多行 --[[...]]）
 * - 数字（整数、浮点数、十六进制）
 */
object LuaHighlighter : LanguageHighlighter {
    
    private val keywords = setOf(
        // 控制流
        "if", "then", "else", "elseif", "end",
        "for", "while", "do", "repeat", "until",
        "break", "return", "goto",
        // 定义
        "function", "local",
        // 逻辑运算
        "and", "or", "not",
        // 其他
        "in"
    )
    
    private val builtinConstants = setOf(
        "true", "false", "nil"
    )
    
    private val builtinFunctions = setOf(
        // 基础函数
        "assert", "collectgarbage", "dofile", "error", "getmetatable",
        "ipairs", "load", "loadfile", "next", "pairs", "pcall",
        "print", "rawequal", "rawget", "rawlen", "rawset", "require",
        "select", "setmetatable", "tonumber", "tostring", "type", "warn", "xpcall",
        // 库
        "coroutine", "debug", "io", "math", "os", "package", "string", "table", "utf8"
    )
    
    // 预编译正则表达式
    private val longCommentPattern = Pattern.compile("--\\[\\[.*?\\]\\]|--\\[=+\\[.*?\\]=+\\]", Pattern.DOTALL)
    private val singleLineCommentPattern = Pattern.compile("--(?!\\[\\[).*")
    private val longStringPattern = Pattern.compile("\\[\\[.*?\\]\\]|\\[=+\\[.*?\\]=+\\]", Pattern.DOTALL)
    private val doubleStringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val singleStringPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    private val hexNumberPattern = Pattern.compile("\\b0x[0-9a-fA-F]+(?:\\.[0-9a-fA-F]+)?(?:[pP][+-]?[0-9]+)?\\b")
    private val numberPattern = Pattern.compile("\\b\\d+\\.?\\d*(?:[eE][+-]?\\d+)?\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\(|\\{|\"|'|\\[\\[)")
    private val labelPattern = Pattern.compile("::[a-zA-Z_][a-zA-Z0-9_]*::")
    private val operatorPattern = Pattern.compile("\\.\\.\\.|\\.\\.|==|~=|<=|>=|<<|>>|//|[+\\-*/%^#<>=]")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]();,.:@]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 1. 长注释
        findMatches(longCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 2. 单行注释
        findMatches(singleLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 3. 长字符串
        findMatches(longStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 4. 双引号字符串
        findMatches(doubleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 5. 单引号字符串
        findMatches(singleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 6. 标签 (::label::)
        findMatches(labelPattern, code, TokenType.ANNOTATION, tokens, processed)
        
        // 7. 十六进制数字
        findMatches(hexNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 8. 普通数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 9. 函数调用
        val funcMatcher = functionCallPattern.matcher(code)
        while (funcMatcher.find()) {
            val start = funcMatcher.start(1)
            val end = funcMatcher.end(1)
            if (!processed[start]) {
                val funcName = funcMatcher.group(1)
                if (!keywords.contains(funcName)) {
                    val tokenType = if (builtinFunctions.contains(funcName)) {
                        TokenType.FUNCTION
                    } else {
                        TokenType.FUNCTION
                    }
                    tokens.add(Token(tokenType, start, end, funcName))
                    for (i in start until end) processed[i] = true
                }
            }
        }
        
        // 10. 标识符和关键字
        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (!processed[start]) {
                val word = identMatcher.group()
                val tokenType = when {
                    keywords.contains(word) -> TokenType.KEYWORD
                    builtinConstants.contains(word) -> when (word) {
                        "true", "false" -> TokenType.BOOLEAN
                        "nil" -> TokenType.NULL
                        else -> TokenType.KEYWORD
                    }
                    builtinFunctions.contains(word) -> TokenType.FUNCTION
                    else -> TokenType.VARIABLE
                }
                tokens.add(Token(tokenType, start, end, word))
                for (i in start until end) processed[i] = true
            }
        }
        
        // 11. 运算符
        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)
        
        // 12. 标点符号
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
            if (!processed[start]) {
                tokens.add(Token(tokenType, start, end, matcher.group()))
                for (i in start until end) processed[i] = true
            }
        }
    }
}