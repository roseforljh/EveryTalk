package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * C# 语法高亮器
 * 
 * 支持：
 * - C# 关键字和修饰符
 * - 字符串（普通字符串、逐字字符串@""、插值字符串$""）
 * - 注释（单行、多行、XML文档注释）
 * - 属性（[Attribute]）
 * - 泛型
 * - LINQ 关键字
 */
object CSharpHighlighter : LanguageHighlighter {
    
    private val keywords = setOf(
        // 类型定义
        "class", "struct", "interface", "enum", "delegate", "record", "namespace",
        // 修饰符
        "public", "private", "protected", "internal", "static", "readonly", "const",
        "virtual", "override", "abstract", "sealed", "partial", "extern", "unsafe",
        "volatile", "async", "new", "ref", "out", "in", "params", "required",
        // 控制流
        "if", "else", "switch", "case", "default", "for", "foreach", "while", "do",
        "break", "continue", "return", "goto", "throw", "try", "catch", "finally",
        "when", "yield",
        // 表达式
        "is", "as", "typeof", "sizeof", "nameof", "default", "checked", "unchecked",
        "stackalloc", "with", "init", "get", "set", "add", "remove", "value",
        // LINQ
        "from", "where", "select", "group", "into", "orderby", "join", "let",
        "ascending", "descending", "on", "equals", "by",
        // 其他
        "using", "this", "base", "event", "operator", "implicit", "explicit",
        "lock", "fixed", "await", "var", "dynamic", "global"
    )
    
    private val builtinTypes = setOf(
        "bool", "byte", "sbyte", "char", "decimal", "double", "float",
        "int", "uint", "long", "ulong", "short", "ushort", "nint", "nuint",
        "object", "string", "void", "null", "true", "false"
    )
    
    // 预编译正则表达式
    private val xmlDocPattern = Pattern.compile("///.*")
    private val multiLineCommentPattern = Pattern.compile("/\\*[\\s\\S]*?\\*/")
    private val singleLineCommentPattern = Pattern.compile("//.*")
    private val verbatimStringPattern = Pattern.compile("@\"(?:[^\"]|\"\")*\"")
    private val interpolatedVerbatimPattern = Pattern.compile("\\$@\"(?:[^\"]|\"\")*\"|@\\$\"(?:[^\"]|\"\")*\"")
    private val interpolatedStringPattern = Pattern.compile("\\$\"(?:[^\"\\\\]|\\\\.)*\"")
    private val rawStringPattern = Pattern.compile("\"{3,}[\\s\\S]*?\"{3,}")
    private val stringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val charPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)'")
    private val attributePattern = Pattern.compile("\\[[a-zA-Z_][a-zA-Z0-9_]*(?:\\([^)]*\\))?\\]")
    private val interpolationPattern = Pattern.compile("\\{[^}]+\\}")
    private val preprocessorPattern = Pattern.compile("#\\s*(?:if|else|elif|endif|define|undef|warning|error|line|region|endregion|pragma|nullable)\\b.*")
    private val hexNumberPattern = Pattern.compile("\\b0x[0-9a-fA-F_]+[uUlL]*\\b")
    private val binaryNumberPattern = Pattern.compile("\\b0b[01_]+[uUlL]*\\b")
    private val numberPattern = Pattern.compile("\\b\\d[\\d_]*\\.?[\\d_]*(?:[eE][+-]?[\\d_]+)?[fFdDmMuUlL]*\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\(|<[^>]+>\\s*\\()")
    private val genericPattern = Pattern.compile("<[a-zA-Z_][a-zA-Z0-9_,\\s]*>")
    private val operatorPattern = Pattern.compile("=>|\\?\\?=|\\?\\?|\\?\\.|\\.\\.|==|!=|<=|>=|&&|\\|\\||\\+\\+|--|<<|>>|>>>|[+\\-*/%=<>!&|^~?:]")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]();,.]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 1. XML 文档注释
        findMatches(xmlDocPattern, code, TokenType.DOCSTRING, tokens, processed)
        
        // 2. 多行注释
        findMatches(multiLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 3. 单行注释
        findMatches(singleLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 4. 预处理指令
        findMatches(preprocessorPattern, code, TokenType.KEYWORD, tokens, processed)
        
        // 5. 原始字符串（C# 11+）
        findMatches(rawStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 6. 插值逐字字符串
        findMatches(interpolatedVerbatimPattern, code, TokenType.STRING, tokens, processed)
        
        // 7. 逐字字符串
        findMatches(verbatimStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 8. 插值字符串
        findMatches(interpolatedStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 9. 普通字符串
        findMatches(stringPattern, code, TokenType.STRING, tokens, processed)
        
        // 10. 字符
        findMatches(charPattern, code, TokenType.STRING, tokens, processed)
        
        // 11. 属性
        findMatches(attributePattern, code, TokenType.ANNOTATION, tokens, processed)
        
        // 12. 字符串插值表达式
        findMatches(interpolationPattern, code, TokenType.STRING_TEMPLATE, tokens, processed)
        
        // 13. 十六进制数字
        findMatches(hexNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 14. 二进制数字
        findMatches(binaryNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 15. 普通数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 16. 函数调用
        val funcMatcher = functionCallPattern.matcher(code)
        while (funcMatcher.find()) {
            val start = funcMatcher.start(1)
            val end = funcMatcher.end(1)
            if (!processed[start]) {
                val funcName = funcMatcher.group(1)
                if (!keywords.contains(funcName) && !builtinTypes.contains(funcName)) {
                    tokens.add(Token(TokenType.FUNCTION, start, end, funcName))
                    for (i in start until end) processed[i] = true
                }
            }
        }
        
        // 17. 标识符和关键字
        val identMatcher = identifierPattern.matcher(code)
        while (identMatcher.find()) {
            val start = identMatcher.start()
            val end = identMatcher.end()
            if (!processed[start]) {
                val word = identMatcher.group()
                val tokenType = when {
                    keywords.contains(word) -> TokenType.KEYWORD
                    builtinTypes.contains(word) -> when (word) {
                        "true", "false" -> TokenType.BOOLEAN
                        "null" -> TokenType.NULL
                        else -> TokenType.CLASS_NAME
                    }
                    word[0].isUpperCase() -> TokenType.CLASS_NAME
                    else -> TokenType.VARIABLE
                }
                tokens.add(Token(tokenType, start, end, word))
                for (i in start until end) processed[i] = true
            }
        }
        
        // 18. 运算符
        findMatches(operatorPattern, code, TokenType.OPERATOR, tokens, processed)
        
        // 19. 标点符号
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