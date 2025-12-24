package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * CSS 语法高亮器（简化版）
 * 
 * 使用简单的正则表达式匹配，确保可靠性
 */
object CssHighlighter : LanguageHighlighter {
    
    // CSS 函数名
    private val cssFunctions = setOf(
        "url", "linear-gradient", "radial-gradient", "conic-gradient",
        "rgb", "rgba", "hsl", "hsla", "calc", "var", "min", "max", "clamp",
        "translate", "translateX", "translateY", "rotate", "scale",
        "blur", "brightness", "contrast", "drop-shadow", "grayscale"
    )
    
    // 预编译的正则表达式
    private val commentPattern = Pattern.compile("/\\*[\\s\\S]*?\\*/")
    private val stringPattern = Pattern.compile("\"[^\"]*\"|'[^']*'")
    private val selectorPattern = Pattern.compile("[.#][a-zA-Z_][a-zA-Z0-9_-]*|[a-zA-Z][a-zA-Z0-9_-]*(?=\\s*[{,:])|::?[a-zA-Z-]+(?=\\s*[{,(])|\\*")
    private val propertyPattern = Pattern.compile("([a-zA-Z-]+)\\s*:")
    private val colorPattern = Pattern.compile("#[0-9a-fA-F]{3,8}\\b")
    private val numberUnitPattern = Pattern.compile("-?\\d+\\.?\\d*(%|px|em|rem|vh|vw|vmin|vmax|deg|s|ms|fr)?\\b")
    private val functionPattern = Pattern.compile("[a-zA-Z-]+(?=\\s*\\()")
    private val keywordPattern = Pattern.compile("\\b(none|auto|inherit|initial|unset|flex|block|inline|grid|absolute|relative|fixed|sticky|center|left|right|top|bottom|solid|dashed|dotted|normal|bold|italic|transparent|hidden|visible|scroll|wrap|nowrap|column|row|start|end|space-between|space-around|stretch|baseline|pointer|default)\\b")
    private val punctuationPattern = Pattern.compile("[{}();:,]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 按优先级处理
        
        // 1. 注释（最高优先级）
        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 2. 字符串
        findMatches(stringPattern, code, TokenType.STRING, tokens, processed)
        
        // 3. 颜色值（在数字之前，防止 #fff 被当成普通数字）
        findMatches(colorPattern, code, TokenType.CSS_COLOR, tokens, processed)
        
        // 4. 选择器（类选择器、ID选择器、元素选择器、伪类）
        findMatches(selectorPattern, code, TokenType.SELECTOR, tokens, processed)
        
        // 5. 属性名（冒号前的标识符）
        val propMatcher = propertyPattern.matcher(code)
        while (propMatcher.find()) {
            val start = propMatcher.start(1)
            val end = propMatcher.end(1)
            if (!processed[start]) {
                tokens.add(Token(TokenType.CSS_PROPERTY, start, end, propMatcher.group(1)))
                for (i in start until end) {
                    processed[i] = true
                }
            }
        }
        
        // 6. 函数调用
        val funcMatcher = functionPattern.matcher(code)
        while (funcMatcher.find()) {
            val start = funcMatcher.start()
            val end = funcMatcher.end()
            if (!processed[start]) {
                val funcName = funcMatcher.group()
                val tokenType = if (cssFunctions.contains(funcName.lowercase())) {
                    TokenType.FUNCTION
                } else {
                    TokenType.CSS_VALUE
                }
                tokens.add(Token(tokenType, start, end, funcName))
                for (i in start until end) {
                    processed[i] = true
                }
            }
        }
        
        // 7. CSS 关键字值
        findMatches(keywordPattern, code, TokenType.CSS_VALUE, tokens, processed)
        
        // 8. 数字和单位
        val numMatcher = numberUnitPattern.matcher(code)
        while (numMatcher.find()) {
            val start = numMatcher.start()
            val end = numMatcher.end()
            if (!processed[start]) {
                val matched = numMatcher.group()
                val unit = numMatcher.group(1)
                
                if (unit != null && unit.isNotEmpty()) {
                    // 有单位：分别标记数字和单位
                    val numEnd = end - unit.length
                    tokens.add(Token(TokenType.NUMBER, start, numEnd, matched.substring(0, matched.length - unit.length)))
                    tokens.add(Token(TokenType.CSS_UNIT, numEnd, end, unit))
                    for (i in start until end) {
                        processed[i] = true
                    }
                } else {
                    // 纯数字
                    tokens.add(Token(TokenType.NUMBER, start, end, matched))
                    for (i in start until end) {
                        processed[i] = true
                    }
                }
            }
        }
        
        // 9. 标点符号
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
            
            // 检查是否已处理
            if (processed[start]) continue
            
            tokens.add(Token(tokenType, start, end, matcher.group()))
            for (i in start until end) {
                processed[i] = true
            }
        }
    }
}