package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * PHP 语法高亮器
 * 
 * 支持：
 * - PHP 关键字和内置函数
 * - 字符串（单引号、双引号、heredoc、nowdoc）
 * - 变量（$variable）
 * - 注释（单行、多行、PHPDoc）
 * - 类型声明
 * - 命名空间
 */
object PhpHighlighter : LanguageHighlighter {
    
    private val keywords = setOf(
        // 控制结构
        "if", "else", "elseif", "endif", "switch", "case", "default", "endswitch",
        "while", "endwhile", "do", "for", "endfor", "foreach", "endforeach",
        "break", "continue", "return", "yield", "yield from",
        "try", "catch", "finally", "throw",
        "goto", "declare", "enddeclare",
        // 定义
        "function", "fn", "class", "interface", "trait", "enum", "abstract", "final",
        "extends", "implements", "use", "namespace", "const", "var",
        "public", "private", "protected", "static", "readonly",
        "new", "clone", "instanceof",
        // 其他
        "echo", "print", "exit", "die", "include", "include_once", "require", "require_once",
        "global", "list", "array", "callable", "iterable", "match",
        "and", "or", "xor", "as", "insteadof"
    )
    
    private val builtinTypes = setOf(
        "int", "integer", "float", "double", "bool", "boolean", "string",
        "array", "object", "null", "void", "never", "mixed", "resource",
        "self", "parent", "static", "true", "false"
    )
    
    private val magicConstants = setOf(
        "__CLASS__", "__DIR__", "__FILE__", "__FUNCTION__", "__LINE__",
        "__METHOD__", "__NAMESPACE__", "__TRAIT__"
    )
    
    // 预编译正则表达式
    private val phpDocPattern = Pattern.compile("/\\*\\*[\\s\\S]*?\\*/")
    private val multiLineCommentPattern = Pattern.compile("/\\*[\\s\\S]*?\\*/")
    private val singleLineCommentPattern = Pattern.compile("//.*|#.*")
    private val heredocPattern = Pattern.compile("<<<['\"]?([A-Z_][A-Z0-9_]*)['\"]?\\s*\\n[\\s\\S]*?\\n\\s*\\1;?")
    private val doubleStringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val singleStringPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    private val variablePattern = Pattern.compile("\\$[a-zA-Z_][a-zA-Z0-9_]*")
    private val interpolationPattern = Pattern.compile("\\{\\$[^}]+\\}|\\$\\{[^}]+\\}")
    private val attributePattern = Pattern.compile("#\\[[^\\]]+\\]")
    private val hexNumberPattern = Pattern.compile("\\b0x[0-9a-fA-F_]+\\b")
    private val binaryNumberPattern = Pattern.compile("\\b0b[01_]+\\b")
    private val octalNumberPattern = Pattern.compile("\\b0o?[0-7_]+\\b")
    private val numberPattern = Pattern.compile("\\b\\d[\\d_]*\\.?[\\d_]*(?:[eE][+-]?[\\d_]+)?\\b")
    private val identifierPattern = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
    private val functionCallPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()")
    private val classNamePattern = Pattern.compile("\\b[A-Z][a-zA-Z0-9_]*\\b")
    private val operatorPattern = Pattern.compile("<=>|===|!==|==|!=|<=|>=|&&|\\|\\||\\?\\?|\\?:|->|=>|::|\\*\\*|\\.\\.\\.|[+\\-*/%=<>!&|^~?@]")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]();,.]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 1. PHPDoc 注释
        findMatches(phpDocPattern, code, TokenType.DOCSTRING, tokens, processed)
        
        // 2. 多行注释
        findMatches(multiLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 3. 单行注释
        findMatches(singleLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 4. Heredoc/Nowdoc
        findMatches(heredocPattern, code, TokenType.STRING, tokens, processed)
        
        // 5. 双引号字符串
        findMatches(doubleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 6. 单引号字符串
        findMatches(singleStringPattern, code, TokenType.STRING, tokens, processed)
        
        // 7. 属性（PHP 8+）
        findMatches(attributePattern, code, TokenType.ANNOTATION, tokens, processed)
        
        // 8. 字符串插值
        findMatches(interpolationPattern, code, TokenType.STRING_TEMPLATE, tokens, processed)
        
        // 9. 变量
        findMatches(variablePattern, code, TokenType.VARIABLE, tokens, processed)
        
        // 10. 十六进制数字
        findMatches(hexNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 11. 二进制数字
        findMatches(binaryNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 12. 八进制数字
        findMatches(octalNumberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 13. 普通数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 14. 函数调用
        val funcMatcher = functionCallPattern.matcher(code)
        while (funcMatcher.find()) {
            val start = funcMatcher.start(1)
            val end = funcMatcher.end(1)
            if (!processed[start]) {
                val funcName = funcMatcher.group(1)
                // 检查是否是关键字
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
                    keywords.contains(word.lowercase()) -> TokenType.KEYWORD
                    magicConstants.contains(word) -> TokenType.CLASS_NAME
                    builtinTypes.contains(word.lowercase()) -> when (word.lowercase()) {
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