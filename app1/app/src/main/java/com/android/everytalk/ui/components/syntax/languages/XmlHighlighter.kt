package com.android.everytalk.ui.components.syntax.languages

import com.android.everytalk.ui.components.syntax.LanguageHighlighter
import com.android.everytalk.ui.components.syntax.Token
import com.android.everytalk.ui.components.syntax.TokenType
import java.util.regex.Pattern

/**
 * XML/SVG 语法高亮器
 * 
 * 支持：
 * - XML 标签和属性
 * - XML 声明 (<?xml ... ?>)
 * - 注释 (<!-- ... -->)
 * - CDATA 区块 (<![CDATA[ ... ]]>)
 * - DOCTYPE 声明
 * - 命名空间前缀
 * - SVG 特有元素和属性
 */
object XmlHighlighter : LanguageHighlighter {
    
    // 预编译正则表达式
    private val commentPattern = Pattern.compile("<!--[\\s\\S]*?-->")
    private val cdataPattern = Pattern.compile("<!\\[CDATA\\[[\\s\\S]*?\\]\\]>")
    private val xmlDeclPattern = Pattern.compile("<\\?xml[^?]*\\?>")
    private val processingInstructionPattern = Pattern.compile("<\\?[a-zA-Z][a-zA-Z0-9-]*[^?]*\\?>")
    private val doctypePattern = Pattern.compile("<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE)
    
    // 标签解析
    private val tagStartPattern = Pattern.compile("</?([a-zA-Z_][a-zA-Z0-9_:.-]*)")
    private val tagEndPattern = Pattern.compile("/?>")
    
    override fun tokenize(code: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val processed = BooleanArray(code.length)
        
        // 1. 注释
        findMatches(commentPattern, code, TokenType.COMMENT, tokens, processed)
        
        // 2. CDATA
        findMatches(cdataPattern, code, TokenType.STRING, tokens, processed)
        
        // 3. XML 声明
        processXmlDeclaration(code, tokens, processed)
        
        // 4. 处理指令
        findMatches(processingInstructionPattern, code, TokenType.KEYWORD, tokens, processed)
        
        // 5. DOCTYPE
        findMatches(doctypePattern, code, TokenType.KEYWORD, tokens, processed)
        
        // 6. 解析 XML 标签
        parseXmlTags(code, tokens, processed)
        
        return tokens.sortedBy { it.start }
    }
    
    /**
     * 处理 XML 声明 <?xml ... ?>
     */
    private fun processXmlDeclaration(
        code: String,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ) {
        val matcher = xmlDeclPattern.matcher(code)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            
            if (processed[start]) continue
            
            val content = matcher.group()
            
            // <?xml 部分
            tokens.add(Token(TokenType.KEYWORD, start, start + 5, "<?xml"))
            for (i in start until start + 5) processed[i] = true
            
            // 解析内部属性
            parseAttributesInRange(code, start + 5, end - 2, tokens, processed)
            
            // ?> 部分
            tokens.add(Token(TokenType.KEYWORD, end - 2, end, "?>"))
            for (i in end - 2 until end) processed[i] = true
        }
    }
    
    /**
     * 解析 XML 标签
     */
    private fun parseXmlTags(
        code: String,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ) {
        var i = 0
        while (i < code.length) {
            if (processed[i]) {
                i++
                continue
            }
            
            if (code[i] == '<') {
                // 尝试匹配标签开始
                val matcher = tagStartPattern.matcher(code)
                if (matcher.find(i) && matcher.start() == i) {
                    val tagStart = matcher.start()
                    val tagEnd = matcher.end()
                    val tagName = matcher.group(1)
                    
                    // 标记 < 或 </
                    val bracketLen = if (i + 1 < code.length && code[i + 1] == '/') 2 else 1
                    tokens.add(Token(TokenType.PUNCTUATION, tagStart, tagStart + bracketLen, code.substring(tagStart, tagStart + bracketLen)))
                    
                    // 标记标签名（支持命名空间前缀的着色）
                    val colonIndex = tagName.indexOf(':')
                    if (colonIndex > 0) {
                        // 有命名空间前缀
                        val nsStart = tagStart + bracketLen
                        val nsEnd = nsStart + colonIndex
                        tokens.add(Token(TokenType.KEYWORD, nsStart, nsEnd, tagName.substring(0, colonIndex)))
                        tokens.add(Token(TokenType.PUNCTUATION, nsEnd, nsEnd + 1, ":"))
                        tokens.add(Token(TokenType.TAG, nsEnd + 1, tagEnd, tagName.substring(colonIndex + 1)))
                    } else {
                        tokens.add(Token(TokenType.TAG, tagStart + bracketLen, tagEnd, tagName))
                    }
                    
                    for (j in tagStart until tagEnd) processed[j] = true
                    i = tagEnd
                    
                    // 解析属性直到 > 或 />
                    i = parseAttributes(code, i, tokens, processed)
                    continue
                }
            }
            i++
        }
    }
    
    /**
     * 解析标签内的属性
     */
    private fun parseAttributes(
        code: String,
        startPos: Int,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ): Int {
        var i = startPos
        
        while (i < code.length) {
            // 跳过空白
            while (i < code.length && code[i].isWhitespace()) {
                processed[i] = true
                i++
            }
            if (i >= code.length) break
            
            // 检查是否是标签结束
            val endMatcher = tagEndPattern.matcher(code)
            if (endMatcher.find(i) && endMatcher.start() == i) {
                val endTagStart = endMatcher.start()
                val endTagEnd = endMatcher.end()
                tokens.add(Token(TokenType.PUNCTUATION, endTagStart, endTagEnd, endMatcher.group()))
                for (j in endTagStart until endTagEnd) processed[j] = true
                return endTagEnd
            }
            
            // 尝试解析属性
            val attrResult = parseAttribute(code, i, tokens, processed)
            if (attrResult > i) {
                i = attrResult
            } else {
                // 无法识别的字符，跳过
                i++
            }
        }
        
        return i
    }
    
    /**
     * 解析指定范围内的属性（用于 XML 声明）
     */
    private fun parseAttributesInRange(
        code: String,
        startPos: Int,
        endPos: Int,
        tokens: MutableList<Token>,
        processed: BooleanArray
    ) {
        var i = startPos
        
        while (i < endPos) {
            // 跳过空白
            while (i < endPos && code[i].isWhitespace()) {
                processed[i] = true
                i++
            }
            if (i >= endPos) break
            
            // 尝试解析属性
            val attrResult = parseAttribute(code, i, tokens, processed, maxEnd = endPos)
            if (attrResult > i) {
                i = attrResult
            } else {
                i++
            }
        }
    }
    
    /**
     * 解析单个属性
     */
    private fun parseAttribute(
        code: String,
        startPos: Int,
        tokens: MutableList<Token>,
        processed: BooleanArray,
        maxEnd: Int = code.length
    ): Int {
        var i = startPos
        val char = code[i]
        
        // 属性名必须以字母或下划线开头
        if (!char.isLetter() && char != '_') {
            return i
        }
        
        // 读取属性名
        val attrNameStart = i
        while (i < maxEnd && (code[i].isLetterOrDigit() || code[i] == '-' || code[i] == '_' || code[i] == ':' || code[i] == '.')) {
            i++
        }
        val attrNameEnd = i
        
        if (attrNameEnd <= attrNameStart) {
            return startPos
        }
        
        val attrName = code.substring(attrNameStart, attrNameEnd)
        
        // 检查是否有命名空间前缀（如 xmlns:svg, xlink:href）
        val colonIndex = attrName.indexOf(':')
        if (colonIndex > 0) {
            // 有命名空间前缀
            tokens.add(Token(TokenType.KEYWORD, attrNameStart, attrNameStart + colonIndex, attrName.substring(0, colonIndex)))
            tokens.add(Token(TokenType.PUNCTUATION, attrNameStart + colonIndex, attrNameStart + colonIndex + 1, ":"))
            tokens.add(Token(TokenType.ATTRIBUTE, attrNameStart + colonIndex + 1, attrNameEnd, attrName.substring(colonIndex + 1)))
        } else {
            // 检查是否是特殊属性（如 xmlns）
            val tokenType = if (attrName == "xmlns" || attrName.startsWith("xmlns:")) {
                TokenType.KEYWORD
            } else {
                TokenType.ATTRIBUTE
            }
            tokens.add(Token(tokenType, attrNameStart, attrNameEnd, attrName))
        }
        
        for (j in attrNameStart until attrNameEnd) processed[j] = true
        
        // 跳过空白
        while (i < maxEnd && code[i].isWhitespace()) {
            processed[i] = true
            i++
        }
        
        // 检查是否有 = 和值
        if (i < maxEnd && code[i] == '=') {
            tokens.add(Token(TokenType.OPERATOR, i, i + 1, "="))
            processed[i] = true
            i++
            
            // 跳过空白
            while (i < maxEnd && code[i].isWhitespace()) {
                processed[i] = true
                i++
            }
            
            // 解析属性值
            if (i < maxEnd) {
                i = parseAttributeValue(code, i, tokens, processed, maxEnd)
            }
        }
        
        return i
    }
    
    /**
     * 解析属性值
     */
    private fun parseAttributeValue(
        code: String,
        startPos: Int,
        tokens: MutableList<Token>,
        processed: BooleanArray,
        maxEnd: Int = code.length
    ): Int {
        val firstChar = code[startPos]
        var valEnd: Int
        
        if (firstChar == '"' || firstChar == '\'') {
            // 引号包裹的值
            valEnd = startPos + 1
            while (valEnd < maxEnd && code[valEnd] != firstChar) {
                valEnd++
            }
            if (valEnd < maxEnd) {
                valEnd++ // 包含结束引号
            }
            
            val value = code.substring(startPos, valEnd)
            
            // 检查是否包含特殊内容（如颜色值、URL）
            val innerValue = value.substring(1, value.length - 1)
            
            when {
                // 颜色值
                innerValue.startsWith("#") && innerValue.length in 4..9 -> {
                    tokens.add(Token(TokenType.PUNCTUATION, startPos, startPos + 1, firstChar.toString()))
                    tokens.add(Token(TokenType.CSS_COLOR, startPos + 1, valEnd - 1, innerValue))
                    tokens.add(Token(TokenType.PUNCTUATION, valEnd - 1, valEnd, firstChar.toString()))
                }
                // URL 或 url()
                innerValue.startsWith("url(") || innerValue.startsWith("http") -> {
                    tokens.add(Token(TokenType.PUNCTUATION, startPos, startPos + 1, firstChar.toString()))
                    tokens.add(Token(TokenType.FUNCTION, startPos + 1, valEnd - 1, innerValue))
                    tokens.add(Token(TokenType.PUNCTUATION, valEnd - 1, valEnd, firstChar.toString()))
                }
                // 数字值（可能带单位）
                innerValue.matches(Regex("^-?[0-9]+\\.?[0-9]*(%|px|em|rem|pt|cm|mm|in|vh|vw)?$")) -> {
                    tokens.add(Token(TokenType.PUNCTUATION, startPos, startPos + 1, firstChar.toString()))
                    tokens.add(Token(TokenType.NUMBER, startPos + 1, valEnd - 1, innerValue))
                    tokens.add(Token(TokenType.PUNCTUATION, valEnd - 1, valEnd, firstChar.toString()))
                }
                else -> {
                    // 普通属性值
                    tokens.add(Token(TokenType.ATTRIBUTE_VALUE, startPos, valEnd, value))
                }
            }
            
            for (j in startPos until valEnd) processed[j] = true
            return valEnd
        } else {
            // 无引号的值（XML 中不推荐，但需要处理）
            valEnd = startPos
            while (valEnd < maxEnd && !code[valEnd].isWhitespace() && code[valEnd] != '>' && code[valEnd] != '/') {
                valEnd++
            }
            
            if (valEnd > startPos) {
                tokens.add(Token(TokenType.ATTRIBUTE_VALUE, startPos, valEnd, code.substring(startPos, valEnd)))
                for (j in startPos until valEnd) processed[j] = true
            }
            
            return valEnd
        }
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