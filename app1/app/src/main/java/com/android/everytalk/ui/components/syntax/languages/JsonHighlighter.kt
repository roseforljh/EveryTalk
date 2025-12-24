package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * JSON 语法高亮器（简化版）
 * 
 * 使用简单的正则表达式匹配，确保可靠性
 */
object JsonHighlighter : LanguageHighlighter {
    
    // 预编译的正则表达式
    private val multiLineCommentPattern = Pattern.compile("/\\*[\\s\\S]*?\\*/")  // JSONC
    private val singleLineCommentPattern = Pattern.compile("//.*")  // JSONC
    private val keyPattern = Pattern.compile("\"[^\"]*\"\\s*(?=:)")
    private val stringValuePattern = Pattern.compile(":\\s*(\"[^\"]*\")")
    private val stringPattern = Pattern.compile("\"[^\"]*\"")
    private val numberPattern = Pattern.compile("-?\\d+\\.?\\d*(?:[eE][+-]?\\d+)?")
    private val booleanPattern = Pattern.compile("\\b(true|false)\\b")
    private val nullPattern = Pattern.compile("\\bnull\\b")
    private val punctuationPattern = Pattern.compile("[{}\\[\\]:,]")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 按优先级处理
        
        // 1. 注释（JSONC）
        findMatches(multiLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        findMatches(singleLineCommentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 2. 键名（冒号前的字符串）
        val keyMatcher = keyPattern.matcher(code)
        while (keyMatcher.find()) {
            val start = keyMatcher.start()
            val end = keyMatcher.end()
            
            // 只取字符串部分，不包括后面的空白
            val keyEnd = code.indexOf('"', start + 1) + 1
            
            if (!processed[start]) {
                tokens.add(Token(TokenType.PROPERTY, start, keyEnd, code.substring(start, keyEnd)))
                for (i in start until keyEnd) {
                    processed[i] = true
                }
            }
        }
        
        // 3. 字符串值（冒号后的字符串）
        val stringValueMatcher = stringValuePattern.matcher(code)
        while (stringValueMatcher.find()) {
            val start = stringValueMatcher.start(1)
            val end = stringValueMatcher.end(1)
            
            if (!processed[start]) {
                tokens.add(Token(TokenType.STRING, start, end, stringValueMatcher.group(1)))
                for (i in start until end) {
                    processed[i] = true
                }
            }
        }
        
        // 4. 其他字符串（数组中的字符串等）
        findMatches(stringPattern, code, TokenType.STRING, tokens, processed)
        
        // 5. 布尔值
        findMatches(booleanPattern, code, TokenType.BOOLEAN, tokens, processed)
        
        // 6. null
        findMatches(nullPattern, code, TokenType.NULL, tokens, processed)
        
        // 7. 数字
        findMatches(numberPattern, code, TokenType.NUMBER, tokens, processed)
        
        // 8. 标点符号
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