package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * Ruby 语法高亮器
 * 
 * 支持：
 * - Ruby 关键字
 * - 字符串（单引号、双引号、%Q、%q、heredoc）
 * - 符号（:symbol）
 * - 正则表达式
 * - 注释
 * - 字符串插值（#{...}）
 * - 实例变量（@var）、类变量（@@var）、全局变量（$var）
 */
object RubyHighlighter : LanguageHighlighter {
    
    private val keywords = setOf(
        // 控制流
        "if", "elsif", "else", "unless", "case", "when", "then",
        "while", "until", "for", "do", "loop",
        "break", "next", "redo", "retry", "return", "yield",
        "begin", "rescue", "raise", "ensure", "throw", "catch",
        // 定义
        "def", "class", "module", "end", "self", "super",
        "alias", "undef", "defined?",
        // 修饰符
        "public", "private", "protected",
        "attr", "attr_reader", "attr_writer", "attr_accessor",
        // 逻辑
        "and", "or", "not", "in",
        // 其他
        "require", "require_relative", "include", "extend", "prepend",
        "lambda", "proc", "block_given?",
        "__FILE__", "__LINE__", "__ENCODING__", "__dir__"
    )
    
    private val builtinConstants = setOf(
        "true", "false", "nil",
        "ARGV", "ARGF", "ENV", "STDIN", "STDOUT", "STDERR",
        "DATA", "RUBY_VERSION", "RUBY_PLATFORM"
    )
    
    // 预编译正则表达式
    private val multiLineCommentPattern = Pattern.compile("=begin[\\s\\S]*?=end")
    private val singleLineCommentPattern = Pattern.compile("#.*")
    private val heredocPattern = Pattern.compile("<<[-~]?['\"]?([A-Z_][A-Z0-9_]*)['\"]?.*?\\n[\\s\\S]*?\\n\\s*\\1")
    private val doubleStringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val singleStringPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    private val percentStringPattern = Pattern.compile("%[qQwWiIxsr]?(?:\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}|<[^>]*>|[^a-zA-Z0-9\\s][^\\s]*)")
    private val regexPattern = Pattern.compile("/(?:[^/\\\\]|\\\\.)+/[imxo]*")
    private val symbolPattern = Pattern.compile(":[a-zA-Z_][a-zA-Z0-9_]*[!?]?")
    private val symbolStringPattern = Pattern.compile(":\"(?:[^\"\\\\]|\\\\.)*\"|:'(?:[^'\\\\]|\\\\.)*'")
    private val interpolationPattern = Pattern.compile("#\\{[^}]+\\}")
    private val instanceVarPattern = Pattern.compile("@[a-zA-Z_][a-zA-Z0-9_]*")
    private val classVarPattern = Pattern.compile("@@[a-zA-Z_][a-zA-Z0-9_]*")
    private val globalVarPattern = Pattern.compile("\\$[a-zA-Z_][a-zA-Z0-9_]*|\\$[0-9]+|\\$[!@&+`'=~/\\\\,;.<>*$?:\"-]")
    private val constantPattern = Pattern.compile("\\b[A-Z][A-Z0-9_]*\\b")
    private val hexNumberPattern = Pattern.compile("\\b0x[0-9a-fA-F_]+\\b")
    private val binaryNumberPattern = Pattern.compile("\\b0b[01_]+\\b")
    private val octalNumberPattern = Pattern.compile("\\b0o?[0-7_]+\\b")
    private val numberPattern = Pattern.compile("\\b\\d[\\d_]*\\.?[\\d_]*(?:[eE][+-]?[\\d_]+)?\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*[!?]?\\b")
    private val methodCallPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*[!?]?)\\s*(?=\\(|\\s+[^=])")
    private val operatorPattern = Pattern.compile("<=>|===|<<=|>>=|==|!=|<=|>=|&&|\\|\\||\\*\\*|<<|>>|=~|!~|\\.\\.\\.|\\.\\.|\\.&\\.|[+\\-*/%=<>!&|^~]")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]();,.]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 1. 多行注释（=begin...=end）
        findMatches(multiLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 2. 单行注释
        findMatches(singleLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 3. Heredoc
        findMatches(heredocPattern, code, TokenType.STRING, tokens, processed)
        
        // 4. 双引号字符串
        findMatches(doubleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 5. 单引号字符串
        findMatches(singleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 6. 百分号字符串
        findMatches(percentStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 7. 正则表达式
        findMatches(regexPattern, code, TokenType.STRING, tokens, processed)
        
        // 8. 符号（带引号）
        findMatches(symbolStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 9. 符号
        findMatches(symbolPattern, code, TokenType.STRING, tokens, processed)
        
        // 10. 字符串插值
        findMatches(interpolationPattern, code, TokenType.STRING_TEMPLATE, tokens, processed)
        
        // 11. 类变量 @@
        findMatches(classVarPattern, code, TokenType.VARIABLE, tokens, processed)
        
        // 12. 实例变量 @
        findMatches(instanceVarPattern, code, TokenType.PROPERTY, tokens, processed)
        
        // 13. 全局变量 $
        findMatches(globalVarPattern, code, TokenType.VARIABLE, tokens, processed)
        
        // 14. 常量（全大写）
        findMatches(constantPattern, code, TokenType.CLASS_NAME, tokens, processed)
        
        // 15. 十六进制数字
        findMatches(hexNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 16. 二进制数字
        findMatches(binaryNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 17. 八进制数字
        findMatches(octalNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 18. 普通数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 19. 标识符和关键字
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
                        else -> TokenType.CLASS_NAME
                    }
                    word[0].isUpperCase() -> TokenType.CLASS_NAME
                    else -> TokenType.VARIABLE
                }
                tokens.add(Token(tokenType, start, end, word))
                for (i in start until end) processed[i] = true
            }
        }
        
        // 20. 运算符
        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)
        
        // 21. 标点符号
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