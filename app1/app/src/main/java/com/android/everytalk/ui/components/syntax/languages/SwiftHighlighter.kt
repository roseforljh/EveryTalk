package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * Swift 语法高亮器
 * 
 * 支持：
 * - Swift 关键字和类型
 * - 字符串（包括多行字符串和字符串插值）
 * - 注释（单行、多行、文档注释）
 * - 属性包装器（@State, @Binding 等）
 * - 数字（整数、浮点数、二进制、十六进制）
 */
object SwiftHighlighter : LanguageHighlighter {
    
    private val keywords = setOf(
        // 声明关键字
        "class", "struct", "enum", "protocol", "extension", "func", "var", "let",
        "init", "deinit", "subscript", "typealias", "associatedtype", "actor",
        "import", "operator", "precedencegroup", "macro",
        // 修饰符
        "public", "private", "fileprivate", "internal", "open", "static", "final",
        "override", "mutating", "nonmutating", "dynamic", "lazy", "weak", "unowned",
        "required", "optional", "convenience", "indirect", "nonisolated", "async",
        "await", "throws", "rethrows", "inout", "some", "any",
        // 控制流
        "if", "else", "guard", "switch", "case", "default", "for", "while", "repeat",
        "do", "break", "continue", "return", "throw", "try", "catch", "defer",
        "fallthrough", "where", "in",
        // 表达式
        "as", "is", "self", "Self", "super", "nil", "true", "false",
        // 其他
        "get", "set", "willSet", "didSet", "consuming", "borrowing"
    )
    
    private val builtinTypes = setOf(
        "Int", "Int8", "Int16", "Int32", "Int64",
        "UInt", "UInt8", "UInt16", "UInt32", "UInt64",
        "Float", "Double", "Bool", "String", "Character",
        "Array", "Dictionary", "Set", "Optional", "Result",
        "Void", "Never", "Any", "AnyObject", "Error"
    )
    
    // 预编译正则表达式
    private val docCommentPattern = Pattern.compile("///.*|/\\*\\*[\\s\\S]*?\\*/")
    private val multiLineCommentPattern = Pattern.compile("/\\*[\\s\\S]*?\\*/")
    private val singleLineCommentPattern = Pattern.compile("//.*")
    private val multiLineStringPattern = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"")
    private val stringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val attributePattern = Pattern.compile("@[a-zA-Z_][a-zA-Z0-9_]*")
    private val stringInterpolationPattern = Pattern.compile("\\\\\\([^)]+\\)")
    private val hexNumberPattern = Pattern.compile("\\b0x[0-9a-fA-F_]+\\b")
    private val binaryNumberPattern = Pattern.compile("\\b0b[01_]+\\b")
    private val octalNumberPattern = Pattern.compile("\\b0o[0-7_]+\\b")
    private val numberPattern = Pattern.compile("\\b\\d[\\d_]*\\.?[\\d_]*(?:[eE][+-]?[\\d_]+)?\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()")
    private val operatorPattern = Pattern.compile("===|!==|==|!=|<=|>=|&&|\\|\\||\\?\\?|->|\\.\\.\\.|\\.\\.<?|[+\\-*/%=<>!&|^~?:]")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]();,.]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 1. 文档注释
        findMatches(docCommentPattern, code, TokenType.DOCSTRING, tokens, processed)
        
        // 2. 多行注释
        findMatches(multiLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 3. 单行注释
        findMatches(singleLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 4. 多行字符串
        findMatches(multiLineStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 5. 普通字符串
        findMatches(stringPattern, code, TokenType.STRING, tokens, processed)
        
        // 6. 属性包装器 (@State, @Binding, @Published, etc.)
        findMatches(attributePattern, code, TokenType.ANNOTATION, tokens, processed)
        
        // 7. 字符串插值
        findMatches(stringInterpolationPattern, code, TokenType.STRING_TEMPLATE, tokens, processed)
        
        // 8. 十六进制数字
        findMatches(hexNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 9. 二进制数字
        findMatches(binaryNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 10. 八进制数字
        findMatches(octalNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 11. 普通数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 12. 函数调用
        val funcMatcher = functionCallPattern.matcher(code)
        while (funcMatcher.find()) {
            val start = funcMatcher.start(1)
            val end = funcMatcher.end(1)
            if (!processed[start]) {
                tokens.add(Token(TokenType.FUNCTION, start, end, funcMatcher.group(1)))
                for (i in start until end) processed[i] = true
            }
        }
        
        // 13. 标识符
        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (!processed[start]) {
                val word = identMatcher.group()
                val tokenType = when {
                    keywords.contains(word) -> when (word) {
                        "true", "false" -> TokenType.BOOLEAN
                        "nil" -> TokenType.NULL
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
        
        // 14. 运算符
        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)
        
        // 15. 标点符号
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