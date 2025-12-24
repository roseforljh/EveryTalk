package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * Scala 语法高亮器
 * 
 * 支持：
 * - Scala 2/3 关键字
 * - 字符串（普通字符串、多行字符串、字符串插值）
 * - 注释（单行、多行、ScalaDoc）
 * - 注解
 * - 符号字面量
 */
object ScalaHighlighter : LanguageHighlighter {
    
    private val keywords = setOf(
        // 定义
        "class", "trait", "object", "enum", "def", "val", "var", "type", "given", "using",
        "extends", "with", "derives", "new", "this", "super",
        "package", "import", "export",
        // 修饰符
        "abstract", "final", "sealed", "implicit", "lazy", "override", "private", "protected",
        "inline", "opaque", "transparent", "erased", "open", "infix",
        // 控制流
        "if", "else", "then", "match", "case", "for", "while", "do", "return", "throw",
        "try", "catch", "finally", "yield",
        // 表达式
        "true", "false", "null",
        // 其他
        "macro", "end", "extension"
    )
    
    private val softKeywords = setOf(
        "as", "derives", "end", "extension", "infix", "inline", "opaque", "open", "transparent", "using"
    )
    
    private val builtinTypes = setOf(
        "Any", "AnyRef", "AnyVal", "Nothing", "Null", "Unit",
        "Boolean", "Byte", "Char", "Short", "Int", "Long", "Float", "Double",
        "String", "Array", "List", "Map", "Set", "Option", "Either", "Try", "Future"
    )
    
    // 预编译正则表达式
    private val scaladocPattern = Pattern.compile("/\\*\\*[\\s\\S]*?\\*/")
    private val multiLineCommentPattern = Pattern.compile("/\\*[\\s\\S]*?\\*/")
    private val singleLineCommentPattern = Pattern.compile("//.*")
    private val multiLineStringPattern = Pattern.compile("\"{3}[\\s\\S]*?\"{3}")
    private val interpolatedMultiLinePattern = Pattern.compile("[srf]\"{3}[\\s\\S]*?\"{3}")
    private val interpolatedStringPattern = Pattern.compile("[srf]\"(?:[^\"\\\\]|\\\\.)*\"")
    private val stringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val charPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)'")
    private val symbolPattern = Pattern.compile("'[a-zA-Z_][a-zA-Z0-9_]*")
    private val annotationPattern = Pattern.compile("@[a-zA-Z_][a-zA-Z0-9_]*")
    private val interpolationPattern = Pattern.compile("\\$\\{[^}]+\\}|\\$[a-zA-Z_][a-zA-Z0-9_]*")
    private val hexNumberPattern = Pattern.compile("\\b0x[0-9a-fA-F_]+[lL]?\\b")
    private val numberPattern = Pattern.compile("\\b\\d[\\d_]*\\.?[\\d_]*(?:[eE][+-]?[\\d_]+)?[fFdDlL]?\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\(|\\[)")
    private val operatorPattern = Pattern.compile("=>|<-|->|::|##|<:|>:|=:=|<:<|<%<|:=|\\+\\+|--|[+\\-*/%=<>!&|^~?:]")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]();,.]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 1. ScalaDoc
        findMatches(scaladocPattern, code, TokenType.DOCSTRING, tokens, processed)
        
        // 2. 多行注释
        findMatches(multiLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 3. 单行注释
        findMatches(singleLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 4. 插值多行字符串
        findMatches(interpolatedMultiLinePattern, code, TokenType.STRING, tokens, processed)
        
        // 5. 多行字符串
        findMatches(multiLineStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 6. 插值字符串
        findMatches(interpolatedStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 7. 普通字符串
        findMatches(stringPattern, code, TokenType.STRING, tokens, processed)
        
        // 8. 字符
        findMatches(charPattern, code, TokenType.STRING, tokens, processed)
        
        // 9. 符号字面量
        findMatches(symbolPattern, code, TokenType.STRING, tokens, processed)
        
        // 10. 注解
        findMatches(annotationPattern, code, TokenType.ANNOTATION, tokens, processed)
        
        // 11. 字符串插值
        findMatches(interpolationPattern, code, TokenType.STRING_TEMPLATE, tokens, processed)
        
        // 12. 十六进制数字
        findMatches(hexNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 13. 普通数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 14. 函数调用
        val funcMatcher = functionCallPattern.matcher(code)
        while (funcMatcher.find()) {
            val start = funcMatcher.start(1)
            val end = funcMatcher.end(1)
            if (!processed[start]) {
                val funcName = funcMatcher.group(1)
                if (!keywords.contains(funcName)) {
                    tokens.add(Token(TokenType.FUNCTION, start, end, funcName))
                    for (i in start until end) processed[i] = true
                }
            }
        }
        
        // 15. 标识符和关键字
        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (!processed[start]) {
                val word = identMatcher.group()
                val tokenType = when {
                    keywords.contains(word) -> when (word) {
                        "true", "false" -> TokenType.BOOLEAN
                        "null" -> TokenType.NULL
                        else -> TokenType.KEYWORD
                    }
                    builtinTypes.contains(word) -> TokenType.CLASS_NAME
                    word[0].isUpperCase() -> TokenType.CLASS_NAME
                    else -> TokenType.VARIABLE
                }
                tokens.add(Token(tokenType, start, end, word))
                for (i in start until end) processed[i] = true
            }
        }
        
        // 16. 运算符
        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)
        
        // 17. 标点符号
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