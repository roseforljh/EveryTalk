package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * YAML 语法高亮器（增强版）
 *
 * 支持：
 * - 键名（多种颜色区分层级）
 * - 字符串（单引号、双引号、多行字符串）
 * - 布尔值、Null、数字
 * - 锚点和别名（&anchor, *alias）
 * - 标签（!tag, !!type）
 * - 注释
 * - 特殊 YAML 语法（|, >, ---, ...）
 */
object YamlHighlighter : LanguageHighlighter {
    
    // 预编译正则表达式
    private val commentPattern = Pattern.compile("#.*")
    
    // 文档分隔符
    private val documentSeparatorPattern = Pattern.compile("^(---|\\.\\.\\.)\\s*$", Pattern.MULTILINE)
    
    // 键名（支持嵌套和特殊字符）
    private val keyPattern = Pattern.compile("^(\\s*)([a-zA-Z0-9_][a-zA-Z0-9_.-]*)\\s*:", Pattern.MULTILINE)
    private val quotedKeyPattern = Pattern.compile("^(\\s*)(\"[^\"]+\"|'[^']+')\\s*:", Pattern.MULTILINE)
    
    // 字符串
    private val doubleQuoteStringPattern = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"")
    private val singleQuoteStringPattern = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'")
    
    // 多行字符串指示符
    private val multilineIndicatorPattern = Pattern.compile("[|>][+-]?\\d*")
    
    // 值类型（使用简单匹配，不使用 lookbehind）
    private val booleanPattern = Pattern.compile("\\b(true|false|yes|no|on|off|True|False|TRUE|FALSE|Yes|No|YES|NO|On|Off|ON|OFF)\\b")
    private val nullPattern = Pattern.compile("\\b(null|Null|NULL)\\b|~")
    private val numberPattern = Pattern.compile("\\b(-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|0x[0-9a-fA-F]+|0o[0-7]+)\\b")
    private val infinityPattern = Pattern.compile("[+-]?\\.(?:inf|Inf|INF)|[+-]?(?:infinity|Infinity|INFINITY)")
    private val nanPattern = Pattern.compile("\\.(?:nan|NaN|NAN)")
    
    // 锚点和别名
    private val anchorPattern = Pattern.compile("&[a-zA-Z0-9_-]+")
    private val aliasPattern = Pattern.compile("\\*[a-zA-Z0-9_-]+")
    
    // 标签
    private val tagPattern = Pattern.compile("!(?:![a-zA-Z0-9_-]+|[a-zA-Z0-9_-]+)?")
    
    // 环境变量
    private val envVarPattern = Pattern.compile("\\$\\{[^}]+\\}|\\$[a-zA-Z_][a-zA-Z0-9_]*")
    
    // 时间戳
    private val timestampPattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}(?:[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})?)?")
    
    // 标点符号
    private val colonPattern = Pattern.compile(":")
    private val listItemPattern = Pattern.compile("^\\s*-\\s", Pattern.MULTILINE)
    private val bracketPattern = Pattern.compile("[\\[\\]{}]")

    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)

        // 1. 注释（最高优先级）
        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)

        // 2. 文档分隔符 (--- 或 ...)
        findMatches(documentSeparatorPattern, code, TokenType.KEYWORD, tokens, processed)

        // 3. 键名（带引号的）
        val quotedKeyMatcher = quotedKeyPattern.matcher(code)
        while (quotedKeyMatcher.find()) {
            val keyStart = quotedKeyMatcher.start(2)
            val keyEnd = quotedKeyMatcher.end(2)
            if (!processed[keyStart]) {
                tokens.add(Token(TokenType.PROPERTY, keyStart, keyEnd, quotedKeyMatcher.group(2)))
                for (i in keyStart until keyEnd) processed[i] = true
            }
        }

        // 4. 键名（普通）
        val keyMatcher = keyPattern.matcher(code)
        while (keyMatcher.find()) {
            val keyStart = keyMatcher.start(2)
            val keyEnd = keyMatcher.end(2)
            if (!processed[keyStart]) {
                tokens.add(Token(TokenType.PROPERTY, keyStart, keyEnd, keyMatcher.group(2)))
                for (i in keyStart until keyEnd) processed[i] = true
            }
        }

        // 5. 多行字符串指示符 (| 或 >)
        findMatches(multilineIndicatorPattern, code, TokenType.KEYWORD, tokens, processed)

        // 6. 双引号字符串
        findMatches(doubleQuoteStringPattern, code, TokenType.STRING, tokens, processed)

        // 7. 单引号字符串
        findMatches(singleQuoteStringPattern, code, TokenType.STRING, tokens, processed)

        // 8. 锚点 (&anchor)
        findMatches(anchorPattern, code, TokenType.ANNOTATION, tokens, processed)

        // 9. 别名 (*alias)
        findMatches(aliasPattern, code, TokenType.FUNCTION, tokens, processed)

        // 10. 标签 (!tag, !!type)
        findMatches(tagPattern, code, TokenType.CLASS_NAME, tokens, processed)

        // 11. 环境变量
        findMatches(envVarPattern, code, TokenType.VARIABLE, tokens, processed)

        // 12. 时间戳
        findMatches(timestampPattern, code, TokenType.NUMBER, tokens, processed)

        // 13. 布尔值
        findMatches(booleanPattern, code, TokenType.BOOLEAN, tokens, processed)

        // 14. Null
        findMatches(nullPattern, code, TokenType.NULL, tokens, processed)

        // 15. Infinity
        findMatches(infinityPattern, code, TokenType.NUMBER, tokens, processed)

        // 16. NaN
        findMatches(nanPattern, code, TokenType.NUMBER, tokens, processed)

        // 17. 数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)

        // 18. 列表项 (-)
        findMatches(listItemPattern, code, TokenType.PUNCTUATION, tokens, processed)

        // 19. 括号
        findMatches(bracketPattern, code, TokenType.PUNCTUATION, tokens, processed)

        // 20. 冒号
        findMatches(colonPattern, code, TokenType.OPERATOR, tokens, processed)

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